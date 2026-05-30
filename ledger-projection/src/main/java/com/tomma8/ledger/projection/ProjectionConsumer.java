package com.tomma8.ledger.projection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tomma8.ledger.dao.mapper.AccountBalanceMapper;
import com.tomma8.ledger.dao.mapper.AccountMapper;
import com.tomma8.ledger.dao.mapper.JournalMapper;
import com.tomma8.ledger.dao.mapper.ProjectionEventLogMapper;
import com.tomma8.ledger.utils.SnowflakeIdGenerator;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class ProjectionConsumer {

    private static final Logger log = LoggerFactory.getLogger(ProjectionConsumer.class);
    private static final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private final JournalMapper journalMapper;
    private final AccountMapper accountMapper;
    private final AccountBalanceMapper accountBalanceMapper;
    private final ProjectionEventLogMapper eventLogMapper;
    private final SnowflakeIdGenerator idGenerator;

    // Projection lag tracking: last event timestamp (epoch millis)
    private final AtomicLong lastEventTimestamp = new AtomicLong(System.currentTimeMillis());
    // Counter: total events processed
    private final AtomicLong eventsProcessed = new AtomicLong(0);

    public ProjectionConsumer(JournalMapper journalMapper,
                              AccountMapper accountMapper,
                              AccountBalanceMapper accountBalanceMapper,
                              ProjectionEventLogMapper eventLogMapper,
                              MeterRegistry meterRegistry) {
        this.journalMapper = journalMapper;
        this.accountMapper = accountMapper;
        this.accountBalanceMapper = accountBalanceMapper;
        this.eventLogMapper = eventLogMapper;
        this.idGenerator = SnowflakeIdGenerator.forWorker(SnowflakeIdGenerator.deriveWorkerId());

        // Expose projection lag: seconds since last processed event
        Gauge.builder("ledger.projection.seconds.since.last.event", this,
                pc -> (System.currentTimeMillis() - pc.lastEventTimestamp.get()) / 1000.0)
                .description("Seconds since last projection event was processed. High values indicate consumer lag or stall.")
                .baseUnit("seconds")
                .register(meterRegistry);

        // Expose total events processed
        Gauge.builder("ledger.projection.events.processed", eventsProcessed, AtomicLong::doubleValue)
                .description("Total number of projection events processed since startup.")
                .register(meterRegistry);
    }

    // ============================================================
    // Account events
    // ============================================================

    @KafkaListener(topics = "ledger.account.v1", groupId = "ledger-projection")
    public void onAccountCreated(String message) {
        try {
            JsonNode event = mapper.readTree(message);

            String accountId   = event.get("accountId").asText();
            String accountType = event.get("accountType").asText();
            String displayName = event.has("displayName") ? event.get("displayName").asText() : "";
            String ownerId     = event.has("ownerId") && !event.get("ownerId").isNull() ? event.get("ownerId").asText() : null;
            String status      = event.get("status").asText();
            LocalDateTime createdAt = toLocalDateTime(event.get("createdAt"));

            accountMapper.upsertAccount(accountId, accountType, displayName, ownerId, status, createdAt);
            lastEventTimestamp.set(System.currentTimeMillis());
            eventsProcessed.incrementAndGet();
            log.info("Projected account: {} type={} status={}", accountId, accountType, status);

        } catch (Exception e) {
            log.error("Failed to project account event: {}", message, e);
        }
    }

    // ============================================================
    // Balance change events
    // ============================================================

    @KafkaListener(topics = "ledger.balance.change.v1", groupId = "ledger-projection")
    public void onBalanceChange(String message) {
        try {
            JsonNode event = mapper.readTree(message);

            // --- Parse event fields ---
            String eventId         = event.has("eventId") ? event.get("eventId").asText() : "";
            String journalId       = event.get("journalId").asText();
            String journalLineId   = event.get("journalLineId").asText();
            String requestId       = event.get("requestId").asText();
            String commandType     = event.get("commandType").asText();
            String accountId       = event.get("accountId").asText();
            String balanceType     = event.get("balanceType").asText();
            String position        = event.has("position") ? event.get("position").asText() : "CURRENT";
            String currency        = event.get("currency").asText();
            String entryType       = event.get("entryType").asText();
            BigDecimal amount      = toBigDecimal(event.get("amount"));
            BigDecimal postBalance = toBigDecimal(event.get("postBalance"));
            long accountSeq        = event.get("accountSeq").asLong();
            String businessRef     = event.has("businessEventRef") ? event.get("businessEventRef").asText() : "";
            LocalDate valueDate    = toLocalDate(event.get("valueDate"));
            int configVersion      = event.has("configVersion") ? event.get("configVersion").asInt() : 1;
            BigDecimal preBalance  = event.has("preBalance") ? toBigDecimal(event.get("preBalance")) : BigDecimal.ZERO;

            // --- Step 1: Ensure account exists (do NOT overwrite accountType) ---
            // Only upsert if account doesn't exist. The onAccountCreated listener
            // sets the correct accountType; we shouldn't overwrite it with "CLIENT".
            Long accountPk = accountMapper.findIdByAccountId(accountId);
            if (accountPk == null) {
                // Account not yet projected from ledger.account.v1 — insert placeholder
                // This is a fallback; correct type will be set by onAccountCreated listener
                accountMapper.upsertAccount(accountId, "CLIENT", "", null, "ACTIVE", LocalDateTime.now());
                accountPk = accountMapper.findIdByAccountId(accountId);
            }
            if (accountPk == null) {
                log.error("Failed to get account surrogate id for {} after upsert attempt", accountId);
                return;
            }

            // --- Step 2: Insert journal header (idempotent by journal_id UNIQUE) ---
            long journalPk = idGenerator.nextId();
            boolean journalInserted = false;
            try {
                journalMapper.insertJournal(
                        journalPk,
                        journalId,
                        "REVERSAL".equals(commandType) ? "REVERSAL" : "NORMAL",
                        requestId, commandType, businessRef,
                        valueDate, "CONFIRMED", false, LocalDateTime.now());
                journalInserted = true;
            } catch (DuplicateKeyException e) {
                // Journal already exists from a previous event replay
                Long existingPk = journalMapper.findIdByJournalId(journalId);
                if (existingPk != null) {
                    journalPk = existingPk;
                }
                log.debug("Journal {} already exists, using id={}", journalId, journalPk);
            }

            // --- Step 3: Upsert account_balance with accountSeq guard ---
            accountBalanceMapper.upsertBalance(
                    accountPk, accountId, balanceType, currency,
                    postBalance, position, accountSeq, journalId);

            Long balancePk = accountBalanceMapper.findIdByKey(accountId, balanceType, currency);
            if (balancePk == null) {
                log.error("Failed to get account_balance id for {} {} {}", accountId, balanceType, currency);
                return;
            }

            // --- Step 4: Verify balance change was applied (seq guard) ---
            Long storedSeq = accountBalanceMapper.getAccountSeq(balancePk);
            if (storedSeq != null && storedSeq > accountSeq) {
                // Stale event — seq guard rejected the update. Log and skip journal_line.
                log.debug("Stale event: incoming seq={} < stored seq={} for {} {} {}",
                        accountSeq, storedSeq, accountId, balanceType, currency);
                try {
                    eventLogMapper.insertEvent(accountId, balanceType, currency, accountSeq,
                            journalLineId, journalId, eventId, "SKIPPED_STALE");
                } catch (DuplicateKeyException ignored) {
                    // already logged
                }
                return;
            }

            // --- Step 5: Insert journal_line (idempotent by journal_line_id UNIQUE) ---
            // Only insert if journal was newly created (avoid re-insert on replay)
            long linePk = idGenerator.nextId();
            try {
                journalMapper.insertJournalLine(
                        linePk,
                        journalPk,             // journal_id (surrogate → journal.id)
                        accountPk,             // account_id (surrogate → account.id)
                        balancePk,             // account_balance_id (surrogate → account_balance.id)
                        journalLineId,         // journal_line_id (business key)
                        journalId,             // journal_journal_id (denormalized business key)
                        accountId,             // account_account_id (denormalized business key)
                        "",                    // leg_id (not available per-event; set empty)
                        balanceType,
                        position,
                        currency,
                        entryType,
                        amount,
                        preBalance,
                        postBalance,
                        configVersion,
                        LocalDateTime.now());
            } catch (DuplicateKeyException e) {
                log.debug("JournalLine {} already exists", journalLineId);
            }

            // --- Step 6: Record in projection_event_log (last, after all mutations) ---
            try {
                eventLogMapper.insertEvent(accountId, balanceType, currency, accountSeq,
                        journalLineId, journalId, eventId, "APPLIED");
            } catch (DuplicateKeyException e) {
                log.debug("Event already logged: {} seq={}", journalLineId, accountSeq);
            }

            lastEventTimestamp.set(System.currentTimeMillis());
            eventsProcessed.incrementAndGet();
            log.info("Projected: {} {} {} {} seq={} balance={}", journalId, accountId, entryType, amount, accountSeq, postBalance);

        } catch (Exception e) {
            log.error("Failed to project event: {}", message, e);
        }
    }

    // ============================================================
    // Helpers
    // ============================================================

    private static BigDecimal toBigDecimal(JsonNode node) {
        if (node == null) return BigDecimal.ZERO;
        if (node.isNumber()) return node.decimalValue();
        return new BigDecimal(node.asText());
    }

    private static LocalDate toLocalDate(JsonNode node) {
        if (node == null) return LocalDate.now();
        if (node.isArray() && node.size() >= 3) {
            return LocalDate.of(node.get(0).asInt(), node.get(1).asInt(), node.get(2).asInt());
        }
        return LocalDate.parse(node.asText());
    }

    private static LocalDateTime toLocalDateTime(JsonNode node) {
        if (node == null) return LocalDateTime.now();
        if (node.isArray() && node.size() == 2) {
            return java.time.Instant.ofEpochSecond(node.get(0).asLong(), node.get(1).asLong())
                    .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();
        }
        String text = node.asText();
        if (text.endsWith("Z")) {
            return java.time.Instant.parse(text).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();
        }
        if (text.contains("T")) {
            return LocalDateTime.parse(text);
        }
        return LocalDateTime.of(toLocalDate(node), java.time.LocalTime.MIN);
    }
}
