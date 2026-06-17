package com.tomma8.ledger.statemachine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tomma8.ledger.domain.command.*;
import com.tomma8.ledger.domain.event.AccountCreatedEvent;
import com.tomma8.ledger.domain.event.BalanceChangeEvent;
import com.tomma8.ledger.domain.event.LedgerEventListener;
import com.tomma8.ledger.domain.exception.*;
import com.tomma8.ledger.domain.model.*;
import com.tomma8.ledger.rocksdb.RocksDBManager;
import com.tomma8.ledger.store.AccountMetaStore;
import com.tomma8.ledger.store.BalanceStore;
import com.tomma8.ledger.store.BalanceTypeConfigStore;
import com.tomma8.ledger.store.IdempotencyStore;
import com.tomma8.ledger.util.FastIdGenerator;
import com.tomma8.ledger.util.LedgerMappers;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.rocksdb.WriteBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LedgerStateMachine {

    private static final Logger log = LoggerFactory.getLogger(LedgerStateMachine.class);

    // NFR-15: 80% of Long.MAX_VALUE — theoretical warning, should never trigger
    private static final long ACCOUNT_SEQ_OVERFLOW_WARN = Long.MAX_VALUE / 100 * 80;

    private final BalanceStore balanceStore;
    private final AccountMetaStore accountMetaStore;
    private final BalanceTypeConfigStore balanceTypeConfigStore;
    private final IdempotencyStore idempotencyStore;
    // In-memory journal map used ONLY in standalone/test mode (no RocksDB). In
    // production (RocksDB present) it is never written or read — journals are
    // served straight from the RocksDB `journal` CF (durable, with its own bounded
    // block cache). This removes the former unbounded heap map that caused OOM.
    private final Map<String, Journal> journalStore = new ConcurrentHashMap<>();

    private final AtomicLong raftLogIndex;
    private final AtomicLong journalSequence;
    // Authoritative count of journals applied. Incremented once per NEW journal
    // (deterministic — same Raft log => same count on every node). Replaces
    // journalStore.size() as the journalSequence source so the count stays correct
    // once journalStore becomes a bounded cache (disk-backed design, stage 3).
    private final AtomicLong journalCount = new AtomicLong(0);
    private final ConcurrentHashMap<String, ReentrantLock> accountLocks = new ConcurrentHashMap<>();

    private LedgerEventListener eventListener;
    private RocksDBManager rocksDB;
    private com.tomma8.ledger.rocksdb.OutboxStore outboxStore;
    private boolean persistAfterApply;
    private final com.tomma8.ledger.event.EmitGate emitGate = new com.tomma8.ledger.event.EmitGate();

    public com.tomma8.ledger.event.EmitGate getEmitGate() {
        return emitGate;
    }

    public void setOutboxStore(com.tomma8.ledger.rocksdb.OutboxStore outboxStore) {
        this.outboxStore = outboxStore;
    }

    public void setPersistAfterApply(boolean persist) {
        this.persistAfterApply = persist;
    }

    private CommandResult withAccountLocks(Set<String> accountIds, Supplier<CommandResult> action) {
        List<String> sorted = new ArrayList<>(accountIds);
        Collections.sort(sorted);
        List<ReentrantLock> locks = sorted.stream()
                .map(id -> accountLocks.computeIfAbsent(id, k -> new ReentrantLock()))
                .toList();
        locks.forEach(ReentrantLock::lock);
        try {
            return action.get();
        } finally {
            for (int i = locks.size() - 1; i >= 0; i--) {
                locks.get(i).unlock();
            }
        }
    }

    private void persistApply(Journal journal, Map<AccountBalanceKey, BalanceEntry> balanceUpdates,
                              String requestId, String journalId, List<BalanceChangeEvent> outboxEvents) {
        if (rocksDB == null) return;
        long t0 = System.nanoTime();
        try {
            WriteBatch batch = new WriteBatch();
            byte[] journalBytes = objectMapper.writeValueAsBytes(journal);
            batch.put(rocksDB.getHandle("journal"),
                    journal.journalId().getBytes(StandardCharsets.UTF_8), journalBytes);
            for (var line : journal.lines()) {
                byte[] lineBytes = objectMapper.writeValueAsBytes(line);
                String lineKey = line.journalId() + "#" + line.journalLineId();
                batch.put(rocksDB.getHandle("journal_line"),
                        lineKey.getBytes(StandardCharsets.UTF_8), lineBytes);
            }
            for (var entry : balanceUpdates.entrySet()) {
                AccountBalanceKey key = entry.getKey();
                String keyStr = key.accountId() + "#" + key.balanceType() + "#" + key.position() + "#" + key.currency();
                byte[] balanceBytes = objectMapper.writeValueAsBytes(entry.getValue());
                batch.put(rocksDB.getHandle("balance"),
                        keyStr.getBytes(StandardCharsets.UTF_8), balanceBytes);
            }
            IdempotencyEntry idempotencyEntry = new IdempotencyEntry(requestId, journalId);
            byte[] idempotencyBytes = objectMapper.writeValueAsBytes(idempotencyEntry);
            batch.put(rocksDB.getHandle("idempotency"),
                    requestId.getBytes(StandardCharsets.UTF_8), idempotencyBytes);
            // Write outbox envelope inside same WriteBatch for atomicity.
            // One CF_OUTBOX entry per journal (not per line), keyed by journalId.
            // Gated by emitGate: skip when this node won't publish (follower / init phase).
            // Skipping avoids unbounded CF_OUTBOX growth on followers — the journal itself
            // is the source of truth and is replicated via Raft.
            if (emitGate.isEnabled() && !outboxEvents.isEmpty()) {
                com.tomma8.ledger.domain.event.JournalEventEnvelope env =
                        new com.tomma8.ledger.domain.event.JournalEventEnvelope(
                                com.tomma8.ledger.domain.event.JournalEventEnvelope.TYPE,
                                journal.journalId(), outboxEvents);
                byte[] envValue = objectMapper.writeValueAsBytes(env);
                String envKey = "outbox:journal:" + journal.journalId();
                batch.put(rocksDB.getHandle("outbox"), envKey.getBytes(StandardCharsets.UTF_8), envValue);
            }
            rocksDB.write(batch);
        } catch (Exception e) {
            log.error("RocksDB write failed for journalId={}", journal.journalId(), e);
        } finally {
            com.tomma8.ledger.metrics.LedgerMetrics.recordApplyPersist(System.nanoTime() - t0);
        }
    }

    private void persistAccountMeta(String accountId, Account account) {
        if (rocksDB == null) return;
        try {
            byte[] key = accountId.getBytes(StandardCharsets.UTF_8);
            byte[] value = objectMapper.writeValueAsBytes(account);
            rocksDB.put("account_meta", key, value);
        } catch (Exception e) {
            log.error("RocksDB account_meta write failed for accountId={}", accountId, e);
        }
    }

    private void persistBalanceEntry(AccountBalanceKey key, BalanceEntry entry) {
        if (rocksDB == null || entry == null) return;
        try {
            String keyStr = key.accountId() + "#" + key.balanceType() + "#" + key.position() + "#" + key.currency();
            byte[] value = objectMapper.writeValueAsBytes(entry);
            rocksDB.put("balance", keyStr.getBytes(StandardCharsets.UTF_8), value);
        } catch (Exception e) {
            log.error("RocksDB balance write failed for key={}", key, e);
        }
    }

    private static final ObjectMapper objectMapper = LedgerMappers.get();

    public void setEventListener(LedgerEventListener listener) {
        this.eventListener = listener;
    }

    public void setRocksDB(RocksDBManager rocksDB) {
        this.rocksDB = rocksDB;
    }

    // ── Snapshot / Restore ─────────────────────────────────────

    public void takeSnapshot() throws Exception {
        if (rocksDB == null) return;
        // Journals are NOT in the snapshot blob — they would materialize the whole
        // history into heap and OOM at scale. They live in the RocksDB `journal` CF
        // (durable; same-dir restart keeps them) and, for cross-node InstallSnapshot,
        // are streamed as a separate snapshot file (see streamJournalsTo / adapter).
        SnapshotData data = SnapshotData.from(
                balanceStore, accountMetaStore, balanceTypeConfigStore,
                Map.of(), idempotencyStore,
                raftLogIndex.get(), journalCount.get());
        byte[] bytes = objectMapper.writeValueAsBytes(data);
        rocksDB.put("sm_snapshot", "snapshot:latest".getBytes(StandardCharsets.UTF_8), bytes);
    }

    public void restoreFromSnapshot() throws Exception {
        if (rocksDB == null) return;
        byte[] raw = rocksDB.get("sm_snapshot", "snapshot:latest".getBytes(StandardCharsets.UTF_8));
        if (raw == null) return;
        restoreFromBytes(raw);
    }

    public byte[] snapshotBytes() throws Exception {
        // With RocksDB: journals are NOT in the blob (would materialize all history
        // into heap → OOM at scale); they live in the journal CF and stream as a
        // separate snapshot file. Without RocksDB (standalone/test): the in-memory
        // map is the only store, so it must go in the blob.
        Map<String, Journal> journals = (rocksDB == null) ? journalStore : Map.of();
        SnapshotData data = SnapshotData.from(
                balanceStore, accountMetaStore, balanceTypeConfigStore,
                journals, idempotencyStore,
                raftLogIndex.get(), journalCount.get());
        return objectMapper.writeValueAsBytes(data);
    }

    public void restoreFromBytes(byte[] bytes) throws Exception {
        SnapshotData data = objectMapper.readValue(bytes, SnapshotData.class);
        data.restoreTo(balanceStore, accountMetaStore, balanceTypeConfigStore,
                journalStore, idempotencyStore);
        // Persist the snapshot's journals to the RocksDB `journal` CF so a node
        // bootstrapped purely from InstallSnapshot (empty local RocksDB) can still
        // read/reverse any historical journal. (Same-dir restart already has them;
        // the write is idempotent.) journalStore itself is only a bounded cache.
        if (rocksDB != null) {
            try {
                for (Journal j : data.journals().values()) {
                    rocksDB.put("journal", j.journalId().getBytes(StandardCharsets.UTF_8),
                            objectMapper.writeValueAsBytes(j));
                }
            } catch (Exception e) {
                log.error("Failed to persist restored journals to RocksDB", e);
            }
        }
        // Restore the authoritative journal count from the snapshot. (Previously
        // derived from journalStore.size(); that breaks once journalStore is a
        // bounded cache — the snapshot now carries the true count, which equals
        // the journal count at snapshot time and is deterministic per Raft log.)
        long restoredCount = data.journalSequence();
        this.journalCount.set(restoredCount);
        raftLogIndex.set(restoredCount);
        journalSequence.set(restoredCount);
    }

    private java.util.function.LongSupplier lastAppliedIndexSource;

    public void setLastAppliedIndexSource(java.util.function.LongSupplier source) {
        this.lastAppliedIndexSource = source;
    }

    public long getRaftLogIndex() {
        return lastAppliedIndexSource != null ? lastAppliedIndexSource.getAsLong() : raftLogIndex.get();
    }
    public long getJournalSequence() { return journalCount.get(); }

    public BalanceStore getBalanceStore() { return balanceStore; }
    public AccountMetaStore getAccountMetaStore() { return accountMetaStore; }
    public BalanceTypeConfigStore getBalanceTypeConfigStore() { return balanceTypeConfigStore; }
    public Map<String, Journal> getAllJournals() { return allJournalsForSnapshot(); }

    /** Complete journal set for snapshotting / cross-node comparison: from RocksDB
     *  (authoritative, all history) if present, else the in-heap cache (standalone/tests).
     *  Transiently materializes all journals — only invoked at snapshot time, not on the hot path. */
    private Map<String, Journal> allJournalsForSnapshot() {
        if (rocksDB == null) return new HashMap<>(journalStore);
        Map<String, Journal> out = new HashMap<>();
        forEachJournal(j -> out.put(j.journalId(), j));
        return out;
    }

    // ── Journal snapshot streaming (cross-node InstallSnapshot) ─────────────
    // Stream the RocksDB `journal` CF to/from a file without materializing all
    // journals in heap. Format: repeated [int keyLen][key bytes][int valLen][val bytes].

    public void streamJournalsTo(java.io.OutputStream os) throws Exception {
        if (rocksDB == null) return;
        try (var out = new java.io.DataOutputStream(new java.io.BufferedOutputStream(os))) {
            rocksDB.forEach("journal", (k, v) -> {
                try {
                    out.writeInt(k.length); out.write(k);
                    out.writeInt(v.length); out.write(v);
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            out.writeInt(-1); // end marker
            out.flush();
        }
    }

    public void ingestJournalsFrom(java.io.InputStream is) throws Exception {
        if (rocksDB == null) return;
        try (var in = new java.io.DataInputStream(new java.io.BufferedInputStream(is))) {
            while (true) {
                int kl = in.readInt();
                if (kl < 0) break;
                byte[] k = in.readNBytes(kl);
                int vl = in.readInt();
                byte[] v = in.readNBytes(vl);
                rocksDB.put("journal", k, v);
            }
        }
    }

    // ── Snapshot data record ───────────────────────────────────

    record SnapshotData(
            Map<String, BalanceEntry> balances,
            Map<String, Account> accounts,
            Map<String, BalanceTypeConfig> configs,
            Map<String, Journal> journals,
            Map<String, String> idempotency,       // requestId → journalId (completed only)
            long raftLogIndex,
            long journalSequence) {

        static SnapshotData from(BalanceStore bs, AccountMetaStore ams,
                                 BalanceTypeConfigStore bcs,
                                 Map<String, Journal> allJournals,
                                 IdempotencyStore is,
                                 long raftLogIndex, long journalSeq) {
            Map<String, BalanceEntry> balMap = new HashMap<>();
            bs.getAll().forEach((k, v) -> balMap.put(keyStr(k), v));
            Map<String, Account> accMap = new HashMap<>();
            ams.getAll().forEach(accMap::put);
            return new SnapshotData(balMap, accMap, bcs.getAll(), Map.copyOf(allJournals),
                    Map.copyOf(is.getAll()), raftLogIndex, journalSeq);
        }

        void restoreTo(BalanceStore bs, AccountMetaStore ams,
                       BalanceTypeConfigStore bcs,
                       Map<String, Journal> journalCache,
                       IdempotencyStore is) {
            bs.clear();
            balances.forEach((k, v) -> bs.put(parseKey(k), v));
            ams.clear();
            accounts.forEach(ams::put);
            bcs.clear();
            configs.forEach(bcs::put);
            // journalCache is a bounded LRU; warm it (eldest evicted automatically).
            journalCache.clear();
            journalCache.putAll(journals);
            is.clear();
            idempotency.forEach(is::put);
        }

        private static String keyStr(AccountBalanceKey k) {
            return k.accountId() + "#" + k.balanceType() + "#" + k.position() + "#" + k.currency();
        }
        private static AccountBalanceKey parseKey(String s) {
            String[] parts = s.split("#", 4);
            return new AccountBalanceKey(parts[0], parts[1], parts[2], parts[3]);
        }
    }

    public LedgerStateMachine(BalanceStore balanceStore,
                              AccountMetaStore accountMetaStore,
                              BalanceTypeConfigStore balanceTypeConfigStore) {
        this.balanceStore = balanceStore;
        this.accountMetaStore = accountMetaStore;
        this.balanceTypeConfigStore = balanceTypeConfigStore;
        this.idempotencyStore = new IdempotencyStore();
        this.raftLogIndex = new AtomicLong(0);
        this.journalSequence = new AtomicLong(0);
    }

    private static int parseIntEnv(String k, int def) {
        try { String v = System.getenv(k); return v == null ? def : Integer.parseInt(v.trim()); }
        catch (Exception e) { return def; }
    }

    /** Read a journal straight from RocksDB (the durable, authoritative on-node
     *  store). No heap cache — RocksDB's own bounded block cache serves hot blocks,
     *  so a second heap copy would only double-cache and risk unbounded growth.
     *  Falls back to the in-memory map only in standalone/test mode (no RocksDB). */
    private Journal readJournal(String journalId) {
        if (rocksDB == null) return journalStore.get(journalId);
        try {
            byte[] raw = rocksDB.get("journal", journalId.getBytes(StandardCharsets.UTF_8));
            if (raw == null) return null;
            return objectMapper.readValue(raw, Journal.class);
        } catch (Exception e) {
            log.error("RocksDB journal read failed for {}", journalId, e);
            return null;
        }
    }

    /** Scan all journals from RocksDB (F-006 list queries; bounded memory per-call by the caller's filter+limit). */
    private void forEachJournal(java.util.function.Consumer<Journal> consumer) {
        if (rocksDB == null) { journalStore.values().forEach(consumer); return; }
        rocksDB.forEach("journal", (k, v) -> {
            try { consumer.accept(objectMapper.readValue(v, Journal.class)); }
            catch (Exception e) { log.error("RocksDB journal scan decode failed", e); }
        });
    }

    private record LineWithLeg(String legId, BigDecimal amount, String currency, PostingCommand.Line line) {}

    // ── Posting ────────────────────────────────────────────────

    public CommandResult applyPosting(PostingCommand cmd) {
        return applyJournalCommand(cmd.requestId(), cmd.businessEventType(), cmd.businessEventRef(),
                cmd.valueDate(), cmd.legs(), JournalType.NORMAL, "POSTING", 0);
    }
    public CommandResult applyPosting(PostingCommand cmd, long raftIndex) {
        return applyJournalCommand(cmd.requestId(), cmd.businessEventType(), cmd.businessEventRef(),
                cmd.valueDate(), cmd.legs(), JournalType.NORMAL, "POSTING", raftIndex);
    }

    public CommandResult applyAdjustment(AdjustmentCommand cmd) {
        return applyJournalCommand(cmd.requestId(), cmd.businessEventType(), cmd.businessEventRef(),
                cmd.valueDate(), cmd.legs(), JournalType.MANUAL_ADJUSTMENT, "ADJUSTMENT", 0);
    }
    public CommandResult applyAdjustment(AdjustmentCommand cmd, long raftIndex) {
        return applyJournalCommand(cmd.requestId(), cmd.businessEventType(), cmd.businessEventRef(),
                cmd.valueDate(), cmd.legs(), JournalType.MANUAL_ADJUSTMENT, "ADJUSTMENT", raftIndex);
    }

    private CommandResult applyJournalCommand(String requestId, String businessEventType,
                                              String businessEventRef, LocalDate valueDate,
                                              List<PostingCommand.Leg> legs,
                                              JournalType journalType, String commandLabel,
                                              long raftIndex) {
        long t0 = System.nanoTime();
        List<LineWithLeg> reusableLines = new ArrayList<>(64);
        Set<String> reusableAccountSet = new HashSet<>(16);
        Map<AccountBalanceKey, BigDecimal> reusableAfterBalances = new HashMap<>(64);
        List<JournalLine> reusableJournalLines = new ArrayList<>(64);

        // Extract all lines with their legId, amount, and currency
        for (var leg : legs) {
            for (var line : leg.lines()) {
                reusableLines.add(new LineWithLeg(leg.legId(), leg.amount(), leg.currency(), line));
            }
        }

        // Collect unique accounts
        for (var lwl : reusableLines) {
            reusableAccountSet.add(lwl.line().accountId());
        }

        // Belt-and-suspenders: AQM serializes before Raft, lock serializes during apply
        return withAccountLocks(reusableAccountSet, () -> {
            // 1. Idempotency check — only completed entries stored; retry re-evaluates failures
            var cachedJournalId = idempotencyStore.get(requestId);
            if (cachedJournalId != null) {
                return CommandResult.completed(cachedJournalId);
            }

            // 2. Account status check (before balance checks)
            Map<String, Account> accountCache = new HashMap<>(reusableAccountSet.size() * 2);
            for (String accountId : reusableAccountSet) {
                var account = accountMetaStore.get(accountId);
                if (account.isEmpty()) {
                    return CommandResult.rejected(LedgerErrorCode.ACCOUNT_NOT_FOUND,
                            Map.of("accountId", accountId));
                }
                accountCache.put(accountId, account.get());
                if (account.get().status() == AccountStatus.FROZEN) {
                    return CommandResult.rejected(LedgerErrorCode.ACCOUNT_FROZEN,
                            Map.of("accountId", accountId));
                }
            }
            long t3 = System.nanoTime();

            // 4. Check per-leg journal balance + seed account restriction
            for (var leg : legs) {
                if (leg.lines().size() < 2) {
                    // Single-line leg = seed/capital injection. Only COMPANY/NOSTRO/SUSPENSE allowed.
                    for (var line : leg.lines()) {
                        var account = accountMetaStore.get(line.accountId());
                        if (account.isPresent()) {
                            var type = account.get().accountType();
                            if (type == AccountType.CLIENT) {
                                return CommandResult.rejected(LedgerErrorCode.SEED_NOT_ALLOWED_FOR_CLIENT,
                                        Map.of("accountId", line.accountId()));
                            }
                            if (type == AccountType.CONTROL) {
                                return CommandResult.rejected(LedgerErrorCode.SEED_NOT_ALLOWED_FOR_CONTROL,
                                        Map.of("accountId", line.accountId()));
                            }
                        }
                    }
                    continue;
                }
                // For multi-line legs, validate that the leg's amount is positive
                // (debits and credits will use the same leg amount)
                if (leg.amount().compareTo(BigDecimal.ZERO) <= 0) {
                    return CommandResult.rejected(LedgerErrorCode.INVALID_LEG_AMOUNT,
                            Map.of("legId", leg.legId(), "amount", leg.amount().toPlainString()));
                }
            }

            // 5. Journal balance validation (debits == credits per currency)
            // Seed posting exemption: single-line legs to institutional accounts
            // represent external capital injection and do not need to balance internally
            boolean allSingleLineInstitutional = true;
            for (var leg : legs) {
                if (leg.lines().size() >= 2) {
                    allSingleLineInstitutional = false;
                    break;
                }
                for (var line : leg.lines()) {
                    var account = accountMetaStore.get(line.accountId());
                    boolean isInstitutional = account.isPresent() && (
                            account.get().accountType() == AccountType.COMPANY ||
                            account.get().accountType() == AccountType.NOSTRO ||
                            account.get().accountType() == AccountType.SUSPENSE ||
                            account.get().accountType() == AccountType.BANK);
                    if (!isInstitutional) {
                        allSingleLineInstitutional = false;
                        break;
                    }
                }
                if (!allSingleLineInstitutional) break;
            }

            if (!allSingleLineInstitutional) {
                Map<String, BigDecimal> debitByCurrency = new HashMap<>();
                Map<String, BigDecimal> creditByCurrency = new HashMap<>();
                for (var lwl : reusableLines) {
                    String cc = lwl.currency();
                    if (lwl.line().entryType() == EntryType.DEBIT) {
                        debitByCurrency.merge(cc, lwl.amount(), BigDecimal::add);
                    } else {
                        creditByCurrency.merge(cc, lwl.amount(), BigDecimal::add);
                    }
                }
                for (String cc : debitByCurrency.keySet()) {
                    BigDecimal debit = debitByCurrency.get(cc);
                    BigDecimal credit = creditByCurrency.getOrDefault(cc, BigDecimal.ZERO);
                    if (debit.compareTo(credit) != 0) {
                        return CommandResult.rejected(LedgerErrorCode.JOURNAL_UNBALANCED,
                                Map.of("currency", cc, "debitTotal", debit.toPlainString(), "creditTotal", credit.toPlainString()));
                    }
                }
                for (String cc : creditByCurrency.keySet()) {
                    if (!debitByCurrency.containsKey(cc)) {
                        BigDecimal credit = creditByCurrency.get(cc);
                        return CommandResult.rejected(LedgerErrorCode.JOURNAL_UNBALANCED,
                                Map.of("currency", cc, "debitTotal", "0", "creditTotal", credit.toPlainString()));
                    }
                }
            }

            long tLegEnd = System.nanoTime();

            // 6. Balance validation (compute after values, check rules)
            long tBalanceStart = System.nanoTime();
            for (var lwl : reusableLines) {
                var line = lwl.line();
                BalanceTypeConfig config = balanceTypeConfigStore.get(line.balanceType())
                        .orElseThrow(() -> new BalanceTypeNotFoundException(line.balanceType()));

                // Perf: per-line INFO log removed (was emitting 4 INFO lines per posting under hotspot).
                // Cross-node drift diagnostics should use SLOW_APPLY_NS / DEBUG level instead.

                AccountBalanceKey key = new AccountBalanceKey(line.accountId(), line.balanceType(), line.position(), lwl.currency());
                BalanceEntry current = balanceStore.get(key).orElse(BalanceEntry.zero());
                BigDecimal after = computeAfterBalance(current.amount(), line.entryType(), lwl.amount());

                // V-13: LOCKED/FROZEN positions cannot go negative
                if (("LOCKED".equals(line.position()) || "FROZEN".equals(line.position()))
                        && after.compareTo(BigDecimal.ZERO) < 0) {
                    return CommandResult.rejected(LedgerErrorCode.POSITION_BALANCE_FLOOR_BREACH,
                            Map.of("accountId", line.accountId(), "balanceType", line.balanceType(),
                                    "currency", lwl.currency(), "position", line.position()));
                }

                if (!config.allowNegative() && after.compareTo(BigDecimal.ZERO) < 0) {
                    // Auto top-up for institutional accounts (COMPANY/NOSTRO/SUSPENSE/BANK)
                    // Only CLIENT/CONTROL accounts have strict balance enforcement
                    var account = accountMetaStore.get(line.accountId());
                    boolean isInstitutional = account.isPresent() && (
                            account.get().accountType() == AccountType.COMPANY ||
                            account.get().accountType() == AccountType.NOSTRO ||
                            account.get().accountType() == AccountType.SUSPENSE ||
                            account.get().accountType() == AccountType.BANK);
                    if (!isInstitutional) {
                        return CommandResult.rejected(LedgerErrorCode.INSUFFICIENT_BALANCE,
                                Map.of("accountId", line.accountId(), "balanceType", line.balanceType(),
                                        "currency", lwl.currency(), "position", line.position(),
                                        "required", lwl.amount().toPlainString(),
                                        "available", current.amount().toPlainString()));
                    }
                }
                if (config.allowNegative() && after.compareTo(BigDecimal.ZERO) > 0) {
                    return CommandResult.rejected(LedgerErrorCode.CREDIT_EXCEEDS_LIMIT,
                            Map.of("accountId", line.accountId(), "balanceType", line.balanceType(),
                                    "currency", lwl.currency(), "position", line.position()));
                }
                reusableAfterBalances.put(key, after);
            }
            long tBalanceEnd = System.nanoTime();

            // 6. Generate journal
            // index/seq/journalId must all derive from the Raft log index so they
            // are identical across nodes. Fall back to local counters only on the
            // non-Raft path (raftIndex == 0: standalone/tests).
            long seq = raftIndex > 0
                    ? raftIndex
                    : journalSequence.incrementAndGet();
            long index = raftIndex > 0 ? raftIndex : raftLogIndex.incrementAndGet();
            String journalId = raftIndex > 0
                    ? String.format("JNL-%016d", raftIndex)
                    : String.format("JNL-%04d", seq);
            Instant now = Instant.now();

            for (var lwl : reusableLines) {
                var line = lwl.line();
                AccountBalanceKey key = new AccountBalanceKey(line.accountId(), line.balanceType(), line.position(), lwl.currency());
                BalanceEntry current = balanceStore.get(key).orElse(BalanceEntry.zero());
                BigDecimal after = reusableAfterBalances.get(key);
                BalanceTypeConfig config = balanceTypeConfigStore.getOrThrow(line.balanceType());

                String journalLineId = journalId + "-" + String.format("%02d", reusableJournalLines.size() + 1);
                JournalLine jl = new JournalLine(
                        journalLineId, journalId, lwl.legId(),
                        line.accountId(), line.balanceType(), line.position(), lwl.currency(),
                        line.entryType(), lwl.amount(),
                        current.amount(), after,
                        config.configVersion(), now);
                reusableJournalLines.add(jl);
            }

            Journal journal = new Journal(
                    journalId, journalType, requestId,
                    businessEventType, businessEventRef,
                    valueDate, JournalStatus.CONFIRMED,
                    List.copyOf(reusableJournalLines), false, now);
            long tJournalEnd = System.nanoTime();

            if (rocksDB == null) journalStore.put(journalId, journal); // in-memory mode only
            journalCount.incrementAndGet();

            // 7. Atomic balance update with accountSeq increment + collect events
            Map<AccountBalanceKey, BalanceEntry> balanceUpdates = new HashMap<>();
            List<BalanceChangeEvent> eventsToPublish = new ArrayList<>(reusableLines.size());
            for (int i = 0; i < reusableLines.size(); i++) {
                var lwl = reusableLines.get(i);
                var line = lwl.line();
                AccountBalanceKey key = new AccountBalanceKey(line.accountId(), line.balanceType(), line.position(), lwl.currency());
                BigDecimal before = balanceStore.get(key).orElse(BalanceEntry.zero()).amount();
                BigDecimal after = reusableAfterBalances.get(key);
                BalanceEntry current = balanceStore.get(key).orElse(BalanceEntry.zero());
                long prevSeq = current.accountSeq();
                long nextSeq = prevSeq + 1;

                // NFR-15: accountSeq overflow warning (should never trigger)
                if (nextSeq >= ACCOUNT_SEQ_OVERFLOW_WARN) {
                    log.error("[SEQ_OVERFLOW_WARN] accountId={} balanceType={} currency={} seq={}",
                            key.accountId(), key.balanceType(), key.currency(), nextSeq);
                }

                BalanceEntry updated = new BalanceEntry(after, index, nextSeq, journalId, now);
                balanceStore.put(key, updated);
                balanceUpdates.put(key, updated);

                // Collect BalanceChangeEvent for atomic outbox + post-commit publish
                if (eventListener != null) {
                    BigDecimal delta = line.entryType() == EntryType.DEBIT
                            ? lwl.amount().negate()
                            : lwl.amount();
                    JournalLine jl = reusableJournalLines.get(i);
                    BalanceChangeEvent event = new BalanceChangeEvent(
                            FastIdGenerator.nextId(),
                            BalanceChangeEvent.EVENT_TYPE,
                            BalanceChangeEvent.EVENT_VERSION,
                            now,
                            requestId + ":" + line.accountId() + ":" + line.balanceType() + ":" + line.position() + ":" + lwl.currency(),
                            commandLabel,
                            journalId,
                            jl.journalLineId(),
                            requestId,
                            businessEventRef,
                            null,
                            line.accountId(),
                            line.balanceType(),
                            line.position(),
                            lwl.currency(),
                            line.entryType(),
                            lwl.amount(),
                            before,
                            after,
                            delta,
                            index,
                            index,
                            nextSeq,
                            prevSeq,
                            valueDate,
                            valueDate,
                            Map.of("sourceSystem", "LEDGER"));
                    eventsToPublish.add(event);
                }
            }

            // 8. Idempotency
            idempotencyStore.put(requestId, journalId);

            // 9. Atomic RocksDB persistence (journal + balance + idempotency + outbox in one WriteBatch)
            persistApply(journal, balanceUpdates, requestId, journalId, eventsToPublish);

            // 10. Publish to Kafka ONLY after outbox is committed to RocksDB
            // Bundled per-journal envelope: 1 Kafka record per posting/reversal
            // (4 lines → 1 record, not 4). Callback-driven deletion: publisher
            // calls markJournalSent on broker ack.
            // Gated by emitGate: closed during init/catch-up, open only on leader.
            if (emitGate.isEnabled() && eventListener != null && !eventsToPublish.isEmpty()) {
                com.tomma8.ledger.domain.event.JournalEventEnvelope envelope =
                        new com.tomma8.ledger.domain.event.JournalEventEnvelope(
                                com.tomma8.ledger.domain.event.JournalEventEnvelope.TYPE,
                                journalId, eventsToPublish);
                eventListener.onPosting(envelope);
            }
            long tEnd = System.nanoTime();
            long totalMs = (tEnd - t0) / 1_000_000;
            if (totalMs > 50) {
                log.warn("[SLOW_APPLY_NS] requestId={} total={}ms lines={}",
                        requestId, totalMs, reusableLines.size());
            }
            return CommandResult.completed(journalId);
        });
    }

    // ── Account Management ─────────────────────────────────────

    public CommandResult applyAccountCreate(AccountCreateCommand cmd, long raftIndex) {
        return applyAccountCreate(cmd);
    }
    public CommandResult applyAccountCreate(AccountCreateCommand cmd) {
        var existing = accountMetaStore.get(cmd.accountId());
        if (existing.isPresent()) {
            Account acc = existing.get();
            // Idempotent: same type and owner → accept as no-op
            if (acc.accountType() == cmd.accountType()
                    && Objects.equals(acc.ownerId(), cmd.ownerId())) {
                return CommandResult.completed(null);
            }
            return CommandResult.rejected(LedgerErrorCode.ACCOUNT_ALREADY_EXISTS,
                    Map.of("accountId", acc.accountId()));
        }

        Set<String> allowedBalanceTypes = new HashSet<>();
        for (var init : cmd.balanceInitializations()) {
            allowedBalanceTypes.add(init.balanceType());
        }

        Account account = new Account(
                cmd.accountId(), cmd.accountType(), cmd.displayName(),
                cmd.ownerId(), AccountStatus.ACTIVE,
                Set.copyOf(allowedBalanceTypes), Instant.now());

        accountMetaStore.put(cmd.accountId(), account);
        persistAccountMeta(cmd.accountId(), account);

        // Initialize balances
        for (var init : cmd.balanceInitializations()) {
            AccountBalanceKey key = new AccountBalanceKey(cmd.accountId(), init.balanceType(), "CURRENT", init.currency());
            balanceStore.initialize(key);
            persistBalanceEntry(key, balanceStore.get(key).orElse(null));
        }

        // Publish account creation event (gated: only on leader after catch-up)
        if (emitGate.isEnabled() && eventListener != null) {
            eventListener.onAccountCreated(new AccountCreatedEvent(
                    FastIdGenerator.nextId(),
                    AccountCreatedEvent.EVENT_TYPE,
                    AccountCreatedEvent.EVENT_VERSION,
                    Instant.now(),
                    cmd.accountId(),
                    cmd.accountType().name(),
                    cmd.displayName(),
                    cmd.ownerId(),
                    AccountStatus.ACTIVE.name(),
                    Set.copyOf(allowedBalanceTypes),
                    Instant.now()));
        }

        return CommandResult.completed(null);
    }

    public CommandResult applyFreeze(AccountFreezeCommand cmd, long raftIndex) { return applyFreeze(cmd); }
    public CommandResult applyFreeze(AccountFreezeCommand cmd) {
        Account account = accountMetaStore.getOrThrow(cmd.accountId());
        Account updated = account.withStatus(AccountStatus.FROZEN);
        accountMetaStore.put(cmd.accountId(), updated);
        persistAccountMeta(cmd.accountId(), updated);
        return CommandResult.completed(null);
    }

    public CommandResult applyUnfreeze(AccountFreezeCommand cmd, long raftIndex) { return applyUnfreeze(cmd); }
    public CommandResult applyUnfreeze(AccountFreezeCommand cmd) {
        Account account = accountMetaStore.getOrThrow(cmd.accountId());
        if (account.status() == AccountStatus.CLOSED) {
            throw new AccountClosedException(cmd.accountId());
        }
        Account updated = account.withStatus(AccountStatus.ACTIVE);
        accountMetaStore.put(cmd.accountId(), updated);
        persistAccountMeta(cmd.accountId(), updated);
        return CommandResult.completed(null);
    }

    public CommandResult applyCloseAccount(String accountId, String requestId, long raftIndex) { return applyCloseAccount(accountId, requestId); }
    public CommandResult applyCloseAccount(String accountId, String requestId) {
        if (balanceStore.hasNonZeroBalance(accountId)) {
            throw new AccountHasNonZeroBalanceException(accountId);
        }
        Account account = accountMetaStore.getOrThrow(accountId);
        Account updated = account.withStatus(AccountStatus.CLOSED);
        accountMetaStore.put(accountId, updated);
        persistAccountMeta(accountId, updated);
        return CommandResult.completed(null);
    }

    public CommandResult applyAddBalanceType(String accountId, String balanceType, String currency, String requestId, long raftIndex) { return applyAddBalanceType(accountId, balanceType, currency, requestId); }
    public CommandResult applyAddBalanceType(String accountId, String balanceType, String currency, String requestId) {
        Account account = accountMetaStore.getOrThrow(accountId);
        Account updated = account.withAdditionalBalanceType(balanceType);
        accountMetaStore.put(accountId, updated);
        persistAccountMeta(accountId, updated);

        AccountBalanceKey key = new AccountBalanceKey(accountId, balanceType, "CURRENT", currency);
        balanceStore.initialize(key);
        persistBalanceEntry(key, balanceStore.get(key).orElse(null));

        return CommandResult.completed(null);
    }

    // ── Reversal (F-008 Section 4.2) ──────────────────────────────

    public CommandResult applyReversal(ReversalCommand cmd) {
        return applyReversalInternal(cmd, 0);
    }
    public CommandResult applyReversal(ReversalCommand cmd, long raftIndex) {
        return applyReversalInternal(cmd, raftIndex);
    }
    private CommandResult applyReversalInternal(ReversalCommand cmd, long raftIndex) {
        List<JournalLine> reusableMirroredLines = new ArrayList<>(64);
        var cachedJournalId = idempotencyStore.get(cmd.requestId());
        if (cachedJournalId != null) {
            return CommandResult.completed(cachedJournalId);
        }

        Journal originalJournal = readJournal(cmd.originalJournalId());
        if (originalJournal == null) {
            return CommandResult.rejected(LedgerErrorCode.JOURNAL_NOT_FOUND,
                    Map.of("journalId", cmd.originalJournalId()));
        }

        Set<String> accountIds = new HashSet<>();
        for (var origLine : originalJournal.lines()) {
            accountIds.add(origLine.accountId());
        }

        // Belt-and-suspenders: AQM serializes before Raft, lock serializes during apply
        return withAccountLocks(accountIds, () -> {
            // Re-check idempotency — only completed entries stored
            var cachedJournalId2 = idempotencyStore.get(cmd.requestId());
            if (cachedJournalId2 != null) {
                return CommandResult.completed(cachedJournalId2);
            }

        // Re-fetch original journal
            Journal orig = readJournal(cmd.originalJournalId());
            if (orig == null) {
                return CommandResult.rejected(LedgerErrorCode.JOURNAL_NOT_FOUND,
                        Map.of("journalId", cmd.originalJournalId()));
            }
            if (orig.status() == JournalStatus.REVERSED) {
                return CommandResult.rejected(LedgerErrorCode.JOURNAL_ALREADY_REVERSED,
                        Map.of("journalId", cmd.originalJournalId()));
            }
            if (orig.journalType() == JournalType.REVERSAL) {
                return CommandResult.rejected(LedgerErrorCode.CANNOT_REVERSE_REVERSAL,
                        Map.of("journalId", cmd.originalJournalId()));
            }

            long seq = raftIndex > 0
                    ? raftIndex
                    : journalSequence.incrementAndGet();
            long index = raftIndex > 0 ? raftIndex : raftLogIndex.incrementAndGet();
            String reversalJournalId = raftIndex > 0
                    ? String.format("JNL-%016d", raftIndex)
                    : String.format("JNL-%04d", seq);
            Instant now = Instant.now();

            Map<AccountBalanceKey, BalanceEntry> balanceUpdates = new HashMap<>();
            List<BalanceChangeEvent> reversalEvents = new ArrayList<>();

            // Mirror each original line: DEBIT ↔ CREDIT, no balance check
            for (var origLine : orig.lines()) {
                EntryType mirrored = origLine.entryType() == EntryType.DEBIT ? EntryType.CREDIT : EntryType.DEBIT;
                AccountBalanceKey key = new AccountBalanceKey(origLine.accountId(), origLine.balanceType(), origLine.position(), origLine.currency());
                BalanceEntry current = balanceStore.get(key).orElse(BalanceEntry.zero());
                BigDecimal after = computeAfterBalance(current.amount(), mirrored, origLine.amount());
                long prevSeq = current.accountSeq();
                long nextSeq = prevSeq + 1;

                // NFR-15 overflow check
                if (nextSeq >= ACCOUNT_SEQ_OVERFLOW_WARN) {
                    log.error("[SEQ_OVERFLOW_WARN] accountId={} balanceType={} currency={} seq={}",
                            key.accountId(), key.balanceType(), key.currency(), nextSeq);
                }

                BalanceEntry updated = new BalanceEntry(after, index, nextSeq, reversalJournalId, now);
                balanceStore.put(key, updated);
                balanceUpdates.put(key, updated);

                String journalLineId = reversalJournalId + "-" + String.format("%02d", reusableMirroredLines.size() + 1);
                JournalLine jl = new JournalLine(
                        journalLineId, reversalJournalId, origLine.legId(),
                        origLine.accountId(), origLine.balanceType(), origLine.position(), origLine.currency(),
                        mirrored, origLine.amount(),
                        current.amount(), after,
                        origLine.configVersion(), now);
                reusableMirroredLines.add(jl);

                // Collect BalanceChangeEvent for reversal (gated: only on leader after catch-up)
                if (emitGate.isEnabled() && eventListener != null) {
                    BigDecimal delta = mirrored == EntryType.DEBIT
                            ? jl.amount().negate()
                            : jl.amount();
                    BalanceChangeEvent event = new BalanceChangeEvent(
                            FastIdGenerator.nextId(),
                            BalanceChangeEvent.EVENT_TYPE,
                            BalanceChangeEvent.EVENT_VERSION,
                            now,
                            cmd.requestId() + ":" + key.accountId() + ":" + key.balanceType() + ":" + key.position() + ":" + key.currency(),
                            "REVERSAL",
                            reversalJournalId,
                            journalLineId,
                            cmd.requestId(),
                            orig.businessEventRef(),
                            null,
                            key.accountId(),
                            key.balanceType(),
                            key.position(),
                            key.currency(),
                            mirrored,
                            jl.amount(),
                            current.amount(),
                            after,
                            delta,
                            index,
                            index,
                            nextSeq,
                            prevSeq,
                            cmd.valueDate(),
                            cmd.valueDate(),
                            Map.of("sourceSystem", "LEDGER"));
                    reversalEvents.add(event);
                }
            }
            // Publish reversal as one envelope (1 Kafka record per reversal, not N)
            if (!reversalEvents.isEmpty() && eventListener != null) {
                com.tomma8.ledger.domain.event.JournalEventEnvelope reversalEnv =
                        new com.tomma8.ledger.domain.event.JournalEventEnvelope(
                                com.tomma8.ledger.domain.event.JournalEventEnvelope.TYPE,
                                reversalJournalId, reversalEvents);
                eventListener.onPosting(reversalEnv);
            }

            boolean crossPeriod = cmd.valueDate().getYear() != orig.valueDate().getYear()
                    || cmd.valueDate().getMonth() != orig.valueDate().getMonth();

            Journal reversalJournal = new Journal(
                    reversalJournalId, JournalType.REVERSAL, cmd.requestId(),
                    orig.businessEventType(), orig.businessEventRef(),
                    cmd.valueDate(), JournalStatus.CONFIRMED,
                    List.copyOf(reusableMirroredLines), crossPeriod, now);

            if (rocksDB == null) journalStore.put(reversalJournalId, reversalJournal); // in-memory mode only
            journalCount.incrementAndGet();

            // Mark original as reversed. RocksDB write below is authoritative;
            // heap map only matters in standalone/test mode (no RocksDB).
            if (rocksDB == null) journalStore.put(cmd.originalJournalId(), orig.withStatus(JournalStatus.REVERSED));

            idempotencyStore.put(cmd.requestId(), reversalJournalId);

            // Atomic RocksDB persistence
            persistApply(reversalJournal, balanceUpdates, cmd.requestId(), reversalJournalId, reversalEvents);
            // Also persist updated original journal
            if (rocksDB != null) {
                try {
                    rocksDB.put("journal", cmd.originalJournalId().getBytes(StandardCharsets.UTF_8),
                            objectMapper.writeValueAsBytes(orig.withStatus(JournalStatus.REVERSED)));
                } catch (Exception e) {
                    log.error("Failed to persist original journal status update", e);
                }
            }

            return CommandResult.completed(reversalJournalId);
        });
    }

    // ── Journal access ─────────────────────────────────────────

    public Journal getJournal(String journalId) {
        return readJournal(journalId);
    }

    // F-006 list queries scan the RocksDB journal CF (authoritative, unbounded
    // history) instead of an in-heap map. Same O(n) as before; not the hot path.

    public List<Journal> getJournalsByAccount(String accountId, int page, int size) {
        List<Journal> all = new ArrayList<>();
        forEachJournal(j -> { if (j.lines().stream().anyMatch(l -> l.accountId().equals(accountId))) all.add(j); });
        all.sort(java.util.Comparator.comparing(Journal::createdAt).reversed());
        return all.stream().skip((long) page * size).limit(size).toList();
    }

    public long countJournalsByAccount(String accountId) {
        long[] n = {0};
        forEachJournal(j -> { if (j.lines().stream().anyMatch(l -> l.accountId().equals(accountId))) n[0]++; });
        return n[0];
    }

    public List<Journal> getJournalsByBusinessEventRef(String businessEventRef) {
        List<Journal> out = new ArrayList<>();
        forEachJournal(j -> { if (businessEventRef.equals(j.businessEventRef())) out.add(j); });
        return out;
    }

    public List<Journal> getJournalChain(String originalJournalId) {
        Journal original = readJournal(originalJournalId);
        if (original == null || original.businessEventRef() == null) return List.of();
        String ref = original.businessEventRef();
        List<Journal> chain = new ArrayList<>();
        forEachJournal(j -> { if (ref.equals(j.businessEventRef())) chain.add(j); });
        chain.sort(java.util.Comparator.comparing(Journal::createdAt));
        return chain;
    }

    public Journal getJournalByRequestId(String requestId) {
        Journal[] found = {null};
        forEachJournal(j -> { if (found[0] == null && requestId.equals(j.requestId())) found[0] = j; });
        return found[0];
    }

    // ── Balance computation ────────────────────────────────────

    private BigDecimal computeAfterBalance(BigDecimal current, EntryType entryType, BigDecimal amount) {
        return entryType == EntryType.CREDIT
                ? current.add(amount)
                : current.subtract(amount);
    }
}
