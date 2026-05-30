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
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

@Service
public class ProjectionConsumer {

    private static final Logger log = LoggerFactory.getLogger(ProjectionConsumer.class);
    private static final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private final SqlSessionFactory sqlSessionFactory;
    private final SnowflakeIdGenerator idGenerator;

    // In-memory cache: accountId → surrogate PK
    private final ConcurrentHashMap<String, Long> accountIdCache = new ConcurrentHashMap<>();
    // Journal PK cache: journalId → surrogate PK (INSERT IGNORE makes this optional but saves SELECT)
    private final ConcurrentHashMap<String, Long> journalPkCache = new ConcurrentHashMap<>();

    // Async balance processing
    private final ConflationQueue balanceQueue = new ConflationQueue();
    private final Thread balanceWorker;

    // Metrics
    private final AtomicLong lastEventTimestamp = new AtomicLong(System.currentTimeMillis());
    private final LongAdder eventsProcessed = new LongAdder();
    private final LongAdder balanceWrites = new LongAdder();

    public ProjectionConsumer(SqlSessionFactory sqlSessionFactory,
                              MeterRegistry meterRegistry) {
        this.sqlSessionFactory = sqlSessionFactory;
        this.idGenerator = SnowflakeIdGenerator.forWorker(SnowflakeIdGenerator.deriveWorkerId());
        this.balanceWorker = Thread.ofPlatform().daemon()
                .name("projection-balance-worker")
                .start(this::balanceWorkerLoop);

        Gauge.builder("ledger.projection.seconds.since.last.event", this,
                pc -> (System.currentTimeMillis() - pc.lastEventTimestamp.get()) / 1000.0)
                .description("Seconds since last projection event was processed.")
                .baseUnit("seconds").register(meterRegistry);

        Gauge.builder("ledger.projection.events.processed", eventsProcessed, LongAdder::doubleValue)
                .description("Total projection events processed.").register(meterRegistry);

        Gauge.builder("ledger.projection.balance.queue.depth", balanceQueue, ConflationQueue::size)
                .description("Pending conflated balance updates.").register(meterRegistry);

        Gauge.builder("ledger.projection.balance.writes", balanceWrites, LongAdder::doubleValue)
                .description("Total balance SQL writes after conflation.").register(meterRegistry);
    }

    // ============================================================
    // Balance worker — drains conflation queue, batch upserts MySQL
    // ============================================================

    private void balanceWorkerLoop() {
        log.info("Balance worker started");
        var batch = new ArrayList<BalanceUpdate>(200);
        while (!Thread.currentThread().isInterrupted()) {
            try {
                if (balanceQueue.isEmpty()) {
                    Thread.sleep(5);
                    continue;
                }
                batch.clear();
                int drained = balanceQueue.drainTo(batch, 200);
                if (drained == 0) continue;

                try (SqlSession session = sqlSessionFactory.openSession(ExecutorType.BATCH)) {
                    AccountBalanceMapper bm = session.getMapper(AccountBalanceMapper.class);
                    for (var bu : batch) {
                        bm.upsertBalance(bu.accountPk(), bu.accountId(), bu.balanceType(), bu.currency(),
                                bu.amount(), bu.position(), bu.accountSeq(), bu.lastJournalId());
                    }
                    session.flushStatements();
                    session.commit();
                }
                balanceWrites.add(drained);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Balance worker error", e);
            }
        }
        log.info("Balance worker stopped");
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

            try (SqlSession session = sqlSessionFactory.openSession()) {
                AccountMapper am = session.getMapper(AccountMapper.class);
                am.upsertAccount(accountId, accountType, displayName, ownerId, status, createdAt);
                session.commit();
                Long pk = am.findIdByAccountId(accountId);
                if (pk != null) accountIdCache.put(accountId, pk);
            }
            lastEventTimestamp.set(System.currentTimeMillis());
            eventsProcessed.increment();

        } catch (Exception e) {
            log.error("Failed to project account event", e);
        }
    }

    // ============================================================
    // Balance change events
    // ============================================================

    @KafkaListener(topics = "ledger.balance.change.v1", groupId = "ledger-projection")
    public void onBalanceChange(String message) {
        processBalanceEvent(message);
    }

    private void processBalanceEvent(String message) {
        try {
            JsonNode event = mapper.readTree(message);

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

            // Resolve account PK (cache-first)
            Long accountPk = accountIdCache.get(accountId);
            if (accountPk == null) {
                try (SqlSession session = sqlSessionFactory.openSession()) {
                    AccountMapper am = session.getMapper(AccountMapper.class);
                    accountPk = am.findIdByAccountId(accountId);
                    if (accountPk == null) {
                        am.upsertAccount(accountId, "CLIENT", "", null, "ACTIVE", LocalDateTime.now());
                        session.commit();
                        accountPk = am.findIdByAccountId(accountId);
                    }
                    if (accountPk == null) {
                        log.error("Failed to get account PK for {}", accountId);
                        return;
                    }
                    accountIdCache.put(accountId, accountPk);
                }
            }

            // Journal PK cache: dedup journal inserts (4 events share same journal).
            // Insert journal in non-batch session so DuplicateKeyException is caught immediately.
            long journalPk;
            Long cachedJournalPk = journalPkCache.get(journalId);
            if (cachedJournalPk != null) {
                journalPk = cachedJournalPk;
            } else {
                journalPk = idGenerator.nextId();
                try (SqlSession session = sqlSessionFactory.openSession()) {
                    JournalMapper jm = session.getMapper(JournalMapper.class);
                    try {
                        jm.insertJournal(journalPk, journalId,
                                "REVERSAL".equals(commandType) ? "REVERSAL" : "NORMAL",
                                requestId, commandType, businessRef,
                                valueDate, "CONFIRMED", false, LocalDateTime.now());
                        session.commit();
                    } catch (org.springframework.dao.DuplicateKeyException e) {
                        Long pk = jm.findIdByJournalId(journalId);
                        if (pk != null) journalPk = pk;
                    }
                }
                journalPkCache.put(journalId, journalPk);
            }

            // Insert journal_line + event_log (INSERT IGNORE handles duplicates silently)
            long linePk = idGenerator.nextId();
            try (SqlSession session = sqlSessionFactory.openSession()) {
                JournalMapper jm = session.getMapper(JournalMapper.class);
                ProjectionEventLogMapper em = session.getMapper(ProjectionEventLogMapper.class);
                jm.insertJournalLine(linePk, journalPk, accountPk, 0L,
                        journalLineId, journalId, accountId, "",
                        balanceType, position, currency, entryType,
                        amount, preBalance, postBalance, configVersion, LocalDateTime.now());
                em.insertEvent(accountId, balanceType, currency, accountSeq,
                        journalLineId, journalId, eventId, "APPLIED");
                session.commit();
            }

            // Async balance via conflation queue
            balanceQueue.offer(new BalanceUpdate(accountPk, accountId, balanceType, currency,
                    postBalance, position, accountSeq, journalId));

            lastEventTimestamp.set(System.currentTimeMillis());
            eventsProcessed.increment();

        } catch (Exception e) {
            log.error("Failed to project event", e);
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
