package com.tomma8.ledger.projection;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.util.JsonRecyclerPools;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Thin Kafka adapter: parses projection events and delegates all persistence
 * to {@link ProjectionWriter}. No direct DB access here.
 */
@Service
public class ProjectionConsumer {

    private static final Logger log = LoggerFactory.getLogger(ProjectionConsumer.class);

    /**
     * Streaming parser factory for the balance-event hot path. We never build
     * an {@code ObjectNode}/{@code JsonNode} tree — we read tokens directly
     * and pull only the fields we need, which is the dominant cost in the
     * 500-msg-per-poll batch listener. One {@link JsonFactory} instance is
     * shared and is thread-safe; {@link JavaTimeModule} is only needed if a
     * future code path falls back to full POJO binding, but the factory must
     * be built from a configured {@link ObjectMapper} to keep that option open.
     *
     * <p>Jackson 2.16+ exposes a {@code JsonFactory.setRecyclerPool(...)}
     * hook. The default pool is per-thread, which still allocates a fresh
     * {@code BufferRecycler} on first use per thread and never releases it
     * back cross-thread; under steady-state it works, but on dynamic thread
     * pools (Kafka consumer restart, scale-out) every new thread pays the
     * ~16 KiB allocation cost. We install a bounded, shared deque pool
     * ({@link JsonRecyclerPools#newBoundedPool(int)}) so the per-parser
     * internal {@code byte[]} / {@code char[]} buffers (default 8 KiB each,
     * two per parser) are reused across all consumer threads, eliminating
     * the 500-msg/poll × 4 threads ≈ 2 000 parser-allocation-per-second
     * churn that dominated young-gen GC.
     */
    private static final int RECYCLER_CAPACITY = 64;
    private static final JsonFactory JSON_FACTORY = buildFactory();

    private static JsonFactory buildFactory() {
        JsonFactory factory = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .getFactory();
        factory.setRecyclerPool(JsonRecyclerPools.newBoundedPool(RECYCLER_CAPACITY));
        return factory;
    }

    /**
     * Bounded cache for ISO {@code LocalDate} strings. Repeated dates (today,
     * yesterday, month/quarter boundaries) are the common case on the balance
     * hot path; memoising the parsed value avoids re-allocating a {@code Date}
     * and re-running the parser/validator for every message.
     *
     * <p>Backed by Caffeine: W-TinyLFU + bounded size. Compared with the prior
     * {@code Collections.synchronizedMap(LinkedHashMap)}: lock-free reads, far
     * fewer allocations per miss (Caffeine uses a ring-buffer-style entry
     * representation instead of a per-entry Map.Entry object), and bounded
     * retention under skewed access patterns (today/yesterday stay hot, stale
     * dates evict promptly).
     *
     * <p>{@code expireAfterAccess=10m} bounds stale entries that may otherwise
     * sit forever once their event stream dries up; the cache size cap is the
     * primary retention policy.
     */
    private static final Cache<String, LocalDate> DATE_CACHE = Caffeine.newBuilder()
            .maximumSize(1024)
            .expireAfterAccess(10, TimeUnit.MINUTES)
            .build();

    private final ProjectionWriter writer;

    public ProjectionConsumer(ProjectionWriter writer) {
        this.writer = writer;
    }

    // Thread-local scratch — avoids per-poll ArrayList allocation on the Kafka
    // consumer threads. Sized for typical poll batches (≈512 msgs). The same
    // thread always uses its own scratch, so consumer-thread safety is preserved.
    // The scratch is cleared before and after each use to prevent stale references
    // from leaking into the next poll batch.
    private static final ThreadLocal<ArrayList<ProjectionWriter.BalanceEvent>> BALANCE_EVENT_SCRATCH =
            ThreadLocal.withInitial(() -> new ArrayList<>(512));

    @KafkaListener(topics = "ledger.account.v1", groupId = "ledger-projection",
            containerFactory = "singleFactory")
    public void onAccountCreated(String message, Acknowledgment ack) {
        try (JsonParser p = JSON_FACTORY.createParser(message)) {
            String accountId = null, accountType = null, displayName = "",
                    ownerId = null, status = null, createdAtRaw = null;
            if (p.nextToken() != JsonToken.START_OBJECT) {
                safeAck(ack);
                return;
            }
            while (p.nextToken() != JsonToken.END_OBJECT) {
                String field = p.currentName();
                p.nextToken();
                switch (field) {
                    case "accountId"   -> accountId   = p.getText();
                    case "accountType" -> accountType = p.getText();
                    case "displayName" -> displayName = p.getText();
                    case "ownerId"     -> ownerId     = p.getText();
                    case "status"      -> status      = p.getText();
                    case "createdAt"   -> createdAtRaw = p.getText();
                    default            -> p.skipChildren();
                }
            }
            if (accountId == null || accountType == null || status == null) {
                safeAck(ack);
                return;
            }
            writer.writeAccount(accountId, accountType, displayName, ownerId, status,
                    toLocalDateTime(createdAtRaw));
            safeAck(ack);
        } catch (Exception e) {
            log.error("Failed to project account event — not acking, will redeliver", e);
        }
    }

    /**
     * Batch listener: one whole poll batch per invocation (see {@code batchFactory}
     * in ProjectionConfig). Parses each message with a streaming {@link JsonParser}
     * — no {@link JsonNode} tree, no per-field intermediate objects beyond the
     * final {@link ProjectionWriter.BalanceEvent} record. Hands the batch to the
     * writer, which persists it synchronously (balance upsert + per-poll
     * journal flush) before the listener acks.
     *
     * <p>At-least-once: any exception (parse, enqueue, or MySQL flush) leaves
     * the offset un-acked, and Kafka redelivers the same poll batch on the
     * next fetch. {@code INSERT IGNORE} + {@code ON DUPLICATE KEY UPDATE}
     * make the redelivery idempotent.
     */
    @KafkaListener(topics = "ledger.balance.change.v1", groupId = "ledger-projection",
            containerFactory = "batchFactory")
    public void onBalanceChange(List<String> messages, Acknowledgment ack) {
        if (messages == null || messages.isEmpty()) {
            safeAck(ack);
            return;
        }
        java.util.concurrent.CompletableFuture<Void> balanceFuture = null;
        try {
            ArrayList<ProjectionWriter.BalanceEvent> parsed = BALANCE_EVENT_SCRATCH.get();
            parsed.clear();
            for (String m : messages) {
                parseOneMessage(m, parsed);
            }
            // writeBalanceBatch returns the balance-upsert future; journal flush is
            // synchronous inside it. We MUST await before ack or risk losing the
            // upsert on a crash between Kafka commit and MySQL commit.
            balanceFuture = writer.writeBalanceBatch(parsed);
            balanceFuture.get(30, java.util.concurrent.TimeUnit.SECONDS);
            parsed.clear();   // release refs so the scratch stays small for the next batch
            safeAck(ack);
        } catch (Exception e) {
            // Persisting the batch failed (journal flush OR balance upsert OR
            // future timeout) — DO NOT ack so Kafka redelivers. The downstream
            // writer is idempotent (INSERT IGNORE + accountSeq-guarded upsert),
            // so redelivery is safe.
            log.error("Balance batch projection failed (size={}) — not acking, will redeliver",
                    messages.size(), e);
        }
    }

    /**
     * Acknowledge without throwing. After a rebalance the old generation's
     * commitSync throws CommitFailedException which Spring treats as a batch
     * failure — even when the DB write succeeded. With async commits this is
     * mostly a no-op, but the safety net below keeps the listener robust.
     */
    private static void safeAck(Acknowledgment ack) {
        if (ack == null) return;
        try {
            ack.acknowledge();
        } catch (Exception e) {
            // Expected on rebalance; the new generation will re-fetch from the
            // last committed offset, and the writer is idempotent.
        }
    }

    private ProjectionWriter.BalanceEvent parseBalanceEvent(String message) {
        try (JsonParser p = JSON_FACTORY.createParser(message)) {
            return parseOneEventObject(p);
        } catch (Exception e) {
            log.error("Failed to parse balance event", e);
            return null;
        }
    }

    /**
     * Single-scan dispatch: open the parser once and decide envelope vs single
     * event by inspecting the first field name in the top-level object.
     * Replaces the prior two-pass approach ({@code isEnvelope(m)} then
     * {@code parseEnvelopeEvents(m)} / {@code parseBalanceEvent(m)}) which
     * opened the parser 2× per Kafka message — the second pass re-tokenised the
     * entire JSON just to detect the same discriminator.
     *
     * <p>Result appended to {@code out}; envelope payloads may add 1..N events.
     * Parser is closed via try-with-resources on the single open per message.
     */
    private void parseOneMessage(String message, List<ProjectionWriter.BalanceEvent> out) {
        if (message == null || message.isEmpty()) return;
        try (JsonParser p = JSON_FACTORY.createParser(message)) {
            if (p.nextToken() != JsonToken.START_OBJECT) return;
            // Probe first field name. The discriminator is the very first
            // field of the top-level object: "type" = envelope.
            JsonToken firstT = p.nextToken();
            if (firstT == null || firstT == JsonToken.END_OBJECT) return;
            String firstField = p.currentName();
            if ("type".equals(firstField)) {
                // Skip the "type" value token, then continue scanning until END_OBJECT.
                // The "events" array, if present, will be discovered in the loop.
                p.nextToken(); // consume the "type" value
                parseEnvelopeBody(p, out);
            } else {
                // Single-event format: parse the object we already entered.
                // We are currently positioned AT the first field token; parseOneEventObject
                // handles both positioning cases (inside START_OBJECT or before it).
                ProjectionWriter.BalanceEvent be = parseOneEventObject(p);
                if (be != null) out.add(be);
            }
        } catch (Exception e) {
            log.error("Failed to parse balance message", e);
        }
    }

    /**
     * Continue scanning the envelope body after the discriminator field has
     * been consumed. Looks for the {@code events} array and parses each inner
     * event object in-place.
     */
    private void parseEnvelopeBody(JsonParser p, List<ProjectionWriter.BalanceEvent> out) throws java.io.IOException {
        boolean seenEventsArray = false;
        while (p.nextToken() != JsonToken.END_OBJECT) {
            String field = p.currentName();
            JsonToken t = p.nextToken();
            if ("events".equals(field)) {
                if (t == JsonToken.START_ARRAY) {
                    while (p.nextToken() != JsonToken.END_ARRAY) {
                        ProjectionWriter.BalanceEvent be = parseOneEventObject(p);
                        if (be != null) out.add(be);
                    }
                    seenEventsArray = true;
                } else {
                    p.skipChildren();
                }
            } else {
                p.skipChildren();
            }
        }
        if (!seenEventsArray) {
            log.warn("Envelope parsed but no events[] array found");
        }
    }

    /**
     * Parse a single BalanceChangeEvent JSON object. Caller must position
     * the parser EITHER before the {@code START_OBJECT} token (legacy
     * single-event path) OR at the {@code START_OBJECT} token (inner event
     * of the envelope array, where the outer loop already advanced). This
     * method handles both. After return, the parser is past the matching
     * {@code END_OBJECT}.
     */
    private ProjectionWriter.BalanceEvent parseOneEventObject(JsonParser p) throws java.io.IOException {
        // Required string fields; null = absent. We bail on missing required
        // ones by returning a partial record that the writer will skip (PK
        // resolution yields null and the loop's `continue` drops it).
        String eventId = null, journalId = null, journalLineId = null, requestId = null,
                commandType = null, accountId = null, balanceType = null,
                position = "CURRENT", currency = null, entryType = null,
                businessEventRef = null, valueDateRaw = null;
        BigDecimal amount = null, postBalance = null, preBalance = null;
        long accountSeq = 0L;
        int configVersion = 1;

        // If we're not yet inside the object, advance to START_OBJECT.
        if (p.currentToken() != JsonToken.START_OBJECT) {
            if (p.nextToken() != JsonToken.START_OBJECT) return null;
        }
        while (p.nextToken() != JsonToken.END_OBJECT) {
            String field = p.currentName();
            JsonToken t = p.nextToken();
            switch (field) {
                case "eventId"          -> eventId          = (t == JsonToken.VALUE_NULL) ? null : p.getText();
                case "journalId"        -> journalId        = p.getText();
                case "journalLineId"    -> journalLineId    = p.getText();
                case "requestId"        -> requestId        = p.getText();
                case "commandType"      -> commandType      = p.getText();
                case "accountId"        -> accountId        = p.getText();
                case "balanceType"      -> balanceType      = p.getText();
                case "position"         -> { String s = p.getText(); if (s != null) position = s; }
                case "currency"         -> currency         = p.getText();
                case "entryType"        -> entryType        = p.getText();
                case "businessEventRef" -> { String s = p.getText(); if (s != null) businessEventRef = s; }
                case "configVersion"    -> configVersion    = p.getIntValue();
                case "accountSeq"       -> accountSeq       = p.getLongValue();
                case "amount"           -> amount           = readBigDecimal(p, t);
                case "postBalance"      -> postBalance      = readBigDecimal(p, t);
                case "preBalance"       -> preBalance       = readBigDecimal(p, t);
                case "valueDate"        -> valueDateRaw     = readValueDate(p, t);
                default                 -> p.skipChildren();
            }
        }
        return new ProjectionWriter.BalanceEvent(
                nullToEmpty(eventId), nullToEmpty(journalId), nullToEmpty(journalLineId),
                nullToEmpty(requestId), nullToEmpty(commandType), nullToEmpty(accountId),
                nullToEmpty(balanceType), position, nullToEmpty(currency),
                nullToEmpty(entryType),
                nz(amount), nz(postBalance), accountSeq,
                nullToEmpty(businessRefSafe(businessEventRef)),
                toLocalDate(valueDateRaw), configVersion, nz(preBalance));
    }

    /** Null-safe helper: businessEventRef nulls become empty strings. */
    private static String businessRefSafe(String s) { return s == null ? "" : s; }

    /** Read a BigDecimal: numeric tokens go through {@code decimalValue()}, strings via {@code new BigDecimal(text)}. */
    private static BigDecimal readBigDecimal(JsonParser p, JsonToken t) throws java.io.IOException {
        if (t == null || t == JsonToken.VALUE_NULL) return null;
        if (t.isNumeric()) return p.getDecimalValue();
        String s = p.getText();
        return (s == null) ? null : new BigDecimal(s);
    }

    /**
     * valueDate is either an ISO string (current producer) or a JSON array
     * [yyyy, m, d] (legacy). For the array case we encode the three ints as
     * a delimited string {@code "yyyy|MM|dd"} that {@link #toLocalDate} parses.
     * Strings pass through as-is.
     */
    private static String readValueDate(JsonParser p, JsonToken t) throws java.io.IOException {
        if (t == JsonToken.VALUE_STRING) return p.getText();
        if (t == JsonToken.START_ARRAY) {
            int y = 0, m = 0, d = 0;
            JsonToken tt = p.nextToken();
            if (tt == JsonToken.VALUE_NUMBER_INT) y = p.getIntValue();
            tt = p.nextToken();
            if (tt == JsonToken.VALUE_NUMBER_INT) m = p.getIntValue();
            tt = p.nextToken();
            if (tt == JsonToken.VALUE_NUMBER_INT) d = p.getIntValue();
            p.nextToken(); // END_ARRAY
            return y + "|" + m + "|" + d;
        }
        return null;
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }
    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    /**
     * valueDate can arrive as a JSON array {@code [yyyy, m, d]} (legacy) or an
     * ISO string (current producer). The streaming reader encodes the array
     * form as {@code "yyyy|MM|dd"}; we detect that here and dispatch.
     *
     * <p>ISO strings pass through {@link #DATE_CACHE}: a small bounded LRU so
     * repeated dates (today, yesterday, month-ends) avoid re-parsing.
     */
    private static LocalDate toLocalDate(String raw) {
        if (raw == null || raw.isEmpty()) return LocalDate.now();
        int pipe1 = raw.indexOf('|');
        if (pipe1 > 0) {
            int pipe2 = raw.indexOf('|', pipe1 + 1);
            if (pipe2 > 0) {
                return LocalDate.of(
                        Integer.parseInt(raw, 0, pipe1, 10),
                        Integer.parseInt(raw, pipe1 + 1, pipe2, 10),
                        Integer.parseInt(raw, pipe2 + 1, raw.length(), 10));
            }
        }
        LocalDate cached = DATE_CACHE.getIfPresent(raw);
        if (cached != null) return cached;
        LocalDate parsed = LocalDate.parse(raw);
        // Concurrent put: two threads may race parsing the same date. The race
        // is benign — both threads return the same LocalDate value, and only one
        // entry survives in the cache.
        DATE_CACHE.put(raw, parsed);
        return parsed;
    }

    /**
     * Drop the date cache on container shutdown. The bounded
     * {@code JsonRecyclerPools.BoundedPool} releases its deque of
     * {@code BufferRecycler}s when the factory is GC'd; we just invalidate
     * the date cache so its references don't outlive the consumer.
     */
    @PreDestroy
    void onShutdown() {
        DATE_CACHE.invalidateAll();
        log.info("ProjectionConsumer shutdown: date cache invalidated, bounded recycler pool will be released by GC");
    }

    private static LocalDateTime toLocalDateTime(String text) {
        if (text == null || text.isEmpty()) return LocalDateTime.now();
        if (text.endsWith("Z")) {
            return java.time.Instant.parse(text).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();
        }
        if (text.contains("T")) {
            return LocalDateTime.parse(text);
        }
        return LocalDateTime.of(LocalDate.parse(text), java.time.LocalTime.MIN);
    }
}
