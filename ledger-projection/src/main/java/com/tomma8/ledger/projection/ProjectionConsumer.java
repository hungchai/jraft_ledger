package com.tomma8.ledger.projection;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.util.JsonRecyclerPools;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
     * <p>Backed by an access-order {@link LinkedHashMap} wrapped with
     * {@link Collections#synchronizedMap}; the cache is only ever touched from
     * the four batch-listener consumer threads, so contention is light. The
     * size is bounded — the eldest entry is evicted on insert past the cap.
     */
    private static final int DATE_CACHE_SIZE = 1024;
    private static final Map<String, LocalDate> DATE_CACHE =
            Collections.synchronizedMap(new LinkedHashMap<>(DATE_CACHE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, LocalDate> eldest) {
                    return size() > DATE_CACHE_SIZE;
                }
            });

    private final ProjectionWriter writer;

    public ProjectionConsumer(ProjectionWriter writer) {
        this.writer = writer;
    }

    @KafkaListener(topics = "ledger.account.v1", groupId = "ledger-projection",
            containerFactory = "singleFactory")
    public void onAccountCreated(String message, Acknowledgment ack) {
        try (JsonParser p = JSON_FACTORY.createParser(message)) {
            String accountId = null, accountType = null, displayName = "",
                    ownerId = null, status = null, createdAtRaw = null;
            if (p.nextToken() != JsonToken.START_OBJECT) {
                ack.acknowledge();
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
                ack.acknowledge();
                return;
            }
            writer.writeAccount(accountId, accountType, displayName, ownerId, status,
                    toLocalDateTime(createdAtRaw));
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Failed to project account event — not acking, will redeliver", e);
        }
    }

    /**
     * Batch listener: one whole poll batch per invocation (see {@code batchFactory}
     * in ProjectionConfig). Parses each message with a streaming {@link JsonParser}
     * — no {@link JsonNode} tree, no per-field intermediate objects beyond the
     * final {@link ProjectionWriter.BalanceEvent} record. Hands the batch to the
     * writer, which persists it, then blocks on
     * {@link ProjectionWriter#flushJournalPending()} so that a successful ack
     * corresponds to "rows are in MySQL".
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
            ack.acknowledge();
            return;
        }
        try {
            var parsed = new ArrayList<ProjectionWriter.BalanceEvent>(messages.size());
            for (String m : messages) {
                ProjectionWriter.BalanceEvent pe = parseBalanceEvent(m);
                if (pe != null) parsed.add(pe);
            }
            writer.writeBalanceBatch(parsed);
            writer.flushJournalPending();
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Balance batch projection failed (size={}) — not acking, will redeliver",
                    messages.size(), e);
        }
    }

    private ProjectionWriter.BalanceEvent parseBalanceEvent(String message) {
        try (JsonParser p = JSON_FACTORY.createParser(message)) {
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

            if (p.nextToken() != JsonToken.START_OBJECT) return null;
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
                    nullToEmpty(businessEventRef),
                    toLocalDate(valueDateRaw), configVersion, nz(preBalance));
        } catch (Exception e) {
            log.error("Failed to parse balance event", e);
            return null;
        }
    }

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
        LocalDate cached = DATE_CACHE.get(raw);
        if (cached != null) return cached;
        LocalDate parsed = LocalDate.parse(raw);
        // putIfAbsent: another thread may have raced and stored a value; either
        // is correct, no need to overwrite.
        DATE_CACHE.putIfAbsent(raw, parsed);
        return parsed;
    }

    /**
     * Drop the date cache on container shutdown. The bounded
     * {@code JsonRecyclerPools.BoundedPool} releases its deque of
     * {@code BufferRecycler}s when the factory is GC'd; we just clear our
     * own cache references so they don't outlive the consumer.
     */
    @PreDestroy
    void onShutdown() {
        DATE_CACHE.clear();
        log.info("ProjectionConsumer shutdown: date cache cleared, bounded recycler pool will be released by GC");
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
