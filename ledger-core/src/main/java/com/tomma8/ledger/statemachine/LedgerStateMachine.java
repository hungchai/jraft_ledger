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
    private final ConcurrentHashMap<String, Journal> journalStore;

    private final AtomicLong raftLogIndex;
    private final AtomicLong journalSequence;
    private final ConcurrentHashMap<String, ReentrantLock> accountLocks = new ConcurrentHashMap<>();

    private LedgerEventListener eventListener;
    private RocksDBManager rocksDB;
    private com.tomma8.ledger.rocksdb.OutboxStore outboxStore;
    private boolean persistAfterApply;

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

    private void persistApply(Journal journal, Map<AccountBalanceKey, BalanceEntry> balanceUpdates, IdempotencyEntry idempotencyEntry) {
        if (rocksDB == null) return;
        // Synchronous RocksDB write — ensures snapshot consistency on restart.
        // Previously async (persistExecutor) which caused RocksDB to lag behind
        // in-memory state. On restart, restoreFromSnapshot() loaded stale data,
        // causing Raft log replay to double-apply or skip entries.
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
            byte[] idempotencyBytes = objectMapper.writeValueAsBytes(idempotencyEntry);
            batch.put(rocksDB.getHandle("idempotency"),
                    idempotencyEntry.requestId().getBytes(StandardCharsets.UTF_8), idempotencyBytes);
            rocksDB.write(batch);
            if (outboxStore != null) outboxStore.flush();
        } catch (Exception e) {
            log.error("RocksDB write failed for journalId={}", journal.journalId(), e);
        }
    }

    private static final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public void setEventListener(LedgerEventListener listener) {
        this.eventListener = listener;
    }

    public void setRocksDB(RocksDBManager rocksDB) {
        this.rocksDB = rocksDB;
    }

    // ── Snapshot / Restore ─────────────────────────────────────

    public void takeSnapshot() throws Exception {
        if (rocksDB == null) return;
        SnapshotData data = SnapshotData.from(
                balanceStore, accountMetaStore, balanceTypeConfigStore,
                journalStore, idempotencyStore,
                raftLogIndex.get(), journalSequence.get());
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
        SnapshotData data = SnapshotData.from(
                balanceStore, accountMetaStore, balanceTypeConfigStore,
                journalStore, idempotencyStore,
                raftLogIndex.get(), journalSequence.get());
        return objectMapper.writeValueAsBytes(data);
    }

    public void restoreFromBytes(byte[] bytes) throws Exception {
        SnapshotData data = objectMapper.readValue(bytes, SnapshotData.class);
        data.restoreTo(balanceStore, accountMetaStore, balanceTypeConfigStore,
                journalStore, idempotencyStore);
        // Derive counters from actual journal count, not stored snapshot values.
        // Stored counters can diverge when snapshot is taken on a different node
        // that had a different application history (e.g., after leader change).
        long journalCount = journalStore.size();
        raftLogIndex.set(journalCount);
        journalSequence.set(journalCount);
    }

    public long getRaftLogIndex() { return raftLogIndex.get(); }
    public long getJournalSequence() { return journalSequence.get(); }

    public BalanceStore getBalanceStore() { return balanceStore; }
    public AccountMetaStore getAccountMetaStore() { return accountMetaStore; }
    public BalanceTypeConfigStore getBalanceTypeConfigStore() { return balanceTypeConfigStore; }
    public Map<String, Journal> getAllJournals() { return new HashMap<>(journalStore); }

    // ── Snapshot data record ───────────────────────────────────

    record SnapshotData(
            Map<String, BalanceEntry> balances,
            Map<String, Account> accounts,
            Map<String, BalanceTypeConfig> configs,
            Map<String, Journal> journals,
            Map<String, IdempotencyEntry> idempotency,
            long raftLogIndex,
            long journalSequence) {

        static SnapshotData from(BalanceStore bs, AccountMetaStore ams,
                                 BalanceTypeConfigStore bcs,
                                 ConcurrentHashMap<String, Journal> js,
                                 IdempotencyStore is,
                                 long raftLogIndex, long journalSeq) {
            Map<String, BalanceEntry> balMap = new HashMap<>();
            bs.getAll().forEach((k, v) -> balMap.put(keyStr(k), v));
            Map<String, Account> accMap = new HashMap<>();
            ams.getAll().forEach(accMap::put);
            return new SnapshotData(balMap, accMap, bcs.getAll(), Map.copyOf(js),
                    Map.copyOf(is.getAll()), raftLogIndex, journalSeq);
        }

        void restoreTo(BalanceStore bs, AccountMetaStore ams,
                       BalanceTypeConfigStore bcs,
                       ConcurrentHashMap<String, Journal> js,
                       IdempotencyStore is) {
            bs.clear();
            balances.forEach((k, v) -> bs.put(parseKey(k), v));
            ams.clear();
            accounts.forEach(ams::put);
            bcs.clear();
            configs.forEach(bcs::put);
            js.clear();
            js.putAll(journals);
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
        this.journalStore = new ConcurrentHashMap<>();
        this.raftLogIndex = new AtomicLong(0);
        this.journalSequence = new AtomicLong(0);
    }

    private record LineWithLeg(String legId, BigDecimal amount, String currency, PostingCommand.Line line) {}

    // ── Posting ────────────────────────────────────────────────

    public CommandResult applyPosting(PostingCommand cmd) {
        return applyJournalCommand(cmd.requestId(), cmd.businessEventType(), cmd.businessEventRef(),
                cmd.valueDate(), cmd.legs(), JournalType.NORMAL, "POSTING");
    }

    public CommandResult applyAdjustment(AdjustmentCommand cmd) {
        return applyJournalCommand(cmd.requestId(), cmd.businessEventType(), cmd.businessEventRef(),
                cmd.valueDate(), cmd.legs(), JournalType.MANUAL_ADJUSTMENT, "ADJUSTMENT");
    }

    private CommandResult applyJournalCommand(String requestId, String businessEventType,
                                              String businessEventRef, LocalDate valueDate,
                                              List<PostingCommand.Leg> legs,
                                              JournalType journalType, String commandLabel) {
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
            // 1. Idempotency check
            var existing = idempotencyStore.get(requestId);
            if (existing.isPresent()) {
                var entry = existing.get();
                if (CommandResult.COMPLETED.equals(entry.status())) {
                    return CommandResult.completed(entry.journalId());
                }
                return CommandResult.rejected(entry.errors(), entry.errorDetails());
            }

            // 2. Account status check (before balance checks)
            Map<String, Account> accountCache = new HashMap<>(reusableAccountSet.size() * 2);
            for (String accountId : reusableAccountSet) {
                var account = accountMetaStore.get(accountId);
                if (account.isEmpty()) {
                    var result = CommandResult.rejected(LedgerErrorCode.ACCOUNT_NOT_FOUND,
                            Map.of("accountId", accountId));
                    idempotencyStore.put(requestId,
                            IdempotencyEntry.rejected(requestId, result.errorCodes(), result.errorDetails(), Instant.now()));
                    return result;
                }
                accountCache.put(accountId, account.get());
                if (account.get().status() == AccountStatus.FROZEN) {
                    var result = CommandResult.rejected(LedgerErrorCode.ACCOUNT_FROZEN,
                            Map.of("accountId", accountId));
                    idempotencyStore.put(requestId,
                            IdempotencyEntry.rejected(requestId, result.errorCodes(), result.errorDetails(), Instant.now()));
                    return result;
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
                                var result = CommandResult.rejected(LedgerErrorCode.SEED_NOT_ALLOWED_FOR_CLIENT,
                                        Map.of("accountId", line.accountId()));
                                idempotencyStore.put(requestId,
                                        IdempotencyEntry.rejected(requestId, result.errorCodes(), result.errorDetails(), Instant.now()));
                                return result;
                            }
                            if (type == AccountType.CONTROL) {
                                var result = CommandResult.rejected(LedgerErrorCode.SEED_NOT_ALLOWED_FOR_CONTROL,
                                        Map.of("accountId", line.accountId()));
                                idempotencyStore.put(requestId,
                                        IdempotencyEntry.rejected(requestId, result.errorCodes(), result.errorDetails(), Instant.now()));
                                return result;
                            }
                        }
                    }
                    continue;
                }
                // For multi-line legs, validate that the leg's amount is positive
                // (debits and credits will use the same leg amount)
                if (leg.amount().compareTo(BigDecimal.ZERO) <= 0) {
                    var result = CommandResult.rejected(LedgerErrorCode.INVALID_LEG_AMOUNT,
                            Map.of("legId", leg.legId(), "amount", leg.amount().toPlainString()));
                    idempotencyStore.put(requestId,
                            IdempotencyEntry.rejected(requestId, result.errorCodes(), result.errorDetails(), Instant.now()));
                    return result;
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
                        var result = CommandResult.rejected(LedgerErrorCode.JOURNAL_UNBALANCED,
                                Map.of("currency", cc, "debitTotal", debit.toPlainString(), "creditTotal", credit.toPlainString()));
                        idempotencyStore.put(requestId,
                                IdempotencyEntry.rejected(requestId, result.errorCodes(), result.errorDetails(), Instant.now()));
                        return result;
                    }
                }
                for (String cc : creditByCurrency.keySet()) {
                    if (!debitByCurrency.containsKey(cc)) {
                        BigDecimal credit = creditByCurrency.get(cc);
                        var result = CommandResult.rejected(LedgerErrorCode.JOURNAL_UNBALANCED,
                                Map.of("currency", cc, "debitTotal", "0", "creditTotal", credit.toPlainString()));
                        idempotencyStore.put(requestId,
                                IdempotencyEntry.rejected(requestId, result.errorCodes(), result.errorDetails(), Instant.now()));
                        return result;
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

                AccountBalanceKey key = new AccountBalanceKey(line.accountId(), line.balanceType(), line.position(), lwl.currency());
                BalanceEntry current = balanceStore.get(key).orElse(BalanceEntry.zero());
                BigDecimal after = computeAfterBalance(current.amount(), line.entryType(), lwl.amount());

                // V-13: LOCKED/FROZEN positions cannot go negative
                if (("LOCKED".equals(line.position()) || "FROZEN".equals(line.position()))
                        && after.compareTo(BigDecimal.ZERO) < 0) {
                    var result = CommandResult.rejected(LedgerErrorCode.POSITION_BALANCE_FLOOR_BREACH,
                            Map.of("accountId", line.accountId(), "balanceType", line.balanceType(),
                                    "currency", lwl.currency(), "position", line.position()));
                    idempotencyStore.put(requestId,
                            IdempotencyEntry.rejected(requestId, result.errorCodes(), result.errorDetails(), Instant.now()));
                    return result;
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
                        var result = CommandResult.rejected(LedgerErrorCode.INSUFFICIENT_BALANCE,
                                Map.of("accountId", line.accountId(), "balanceType", line.balanceType(),
                                        "currency", lwl.currency(), "position", line.position(),
                                        "required", lwl.amount().toPlainString(),
                                        "available", current.amount().toPlainString()));
                        idempotencyStore.put(requestId,
                                IdempotencyEntry.rejected(requestId, result.errorCodes(), result.errorDetails(), Instant.now()));
                        return result;
                    }
                }
                if (config.allowNegative() && after.compareTo(BigDecimal.ZERO) > 0) {
                    var result = CommandResult.rejected(LedgerErrorCode.CREDIT_EXCEEDS_LIMIT,
                            Map.of("accountId", line.accountId(), "balanceType", line.balanceType(),
                                    "currency", lwl.currency(), "position", line.position()));
                    idempotencyStore.put(requestId,
                            IdempotencyEntry.rejected(requestId, result.errorCodes(), result.errorDetails(), Instant.now()));
                    return result;
                }
                reusableAfterBalances.put(key, after);
            }
            long tBalanceEnd = System.nanoTime();

            // 6. Generate journal
            long index = raftLogIndex.incrementAndGet();
            long seq = journalSequence.incrementAndGet();
            String journalId = String.format("JNL-%04d", seq);
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

            journalStore.put(journalId, journal);

            // 7. Atomic balance update with accountSeq increment + event publishing
            Map<AccountBalanceKey, BalanceEntry> balanceUpdates = new HashMap<>();
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

                // Publish BalanceChangeEvent (F-011)
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
                    eventListener.onEvent(event);
                    if (outboxStore != null) outboxStore.enqueue(event);
                }
            }

            // 8. Idempotency
            IdempotencyEntry idempotencyEntry = IdempotencyEntry.completed(requestId, journalId, now);
            idempotencyStore.put(requestId, idempotencyEntry);
            long tEventsEnd = System.nanoTime();

            // 9. Atomic RocksDB persistence
            persistApply(journal, balanceUpdates, idempotencyEntry);
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

        // Initialize balances
        for (var init : cmd.balanceInitializations()) {
            AccountBalanceKey key = new AccountBalanceKey(cmd.accountId(), init.balanceType(), "CURRENT", init.currency());
            balanceStore.initialize(key);
        }

        // Publish account creation event
        if (eventListener != null) {
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

    public CommandResult applyFreeze(AccountFreezeCommand cmd) {
        Account account = accountMetaStore.getOrThrow(cmd.accountId());
        accountMetaStore.put(cmd.accountId(), account.withStatus(AccountStatus.FROZEN));
        return CommandResult.completed(null);
    }

    public CommandResult applyUnfreeze(AccountFreezeCommand cmd) {
        Account account = accountMetaStore.getOrThrow(cmd.accountId());
        if (account.status() == AccountStatus.CLOSED) {
            throw new AccountClosedException(cmd.accountId());
        }
        accountMetaStore.put(cmd.accountId(), account.withStatus(AccountStatus.ACTIVE));
        return CommandResult.completed(null);
    }

    public CommandResult applyCloseAccount(String accountId, String requestId) {
        if (balanceStore.hasNonZeroBalance(accountId)) {
            throw new AccountHasNonZeroBalanceException(accountId);
        }
        Account account = accountMetaStore.getOrThrow(accountId);
        accountMetaStore.put(accountId, account.withStatus(AccountStatus.CLOSED));
        return CommandResult.completed(null);
    }

    public CommandResult applyAddBalanceType(String accountId, String balanceType, String currency, String requestId) {
        Account account = accountMetaStore.getOrThrow(accountId);
        Account updated = account.withAdditionalBalanceType(balanceType);
        accountMetaStore.put(accountId, updated);

        AccountBalanceKey key = new AccountBalanceKey(accountId, balanceType, "CURRENT", currency);
        balanceStore.initialize(key);

        return CommandResult.completed(null);
    }

    // ── Reversal (F-008 Section 4.2) ──────────────────────────────

    public CommandResult applyReversal(ReversalCommand cmd) {
        List<JournalLine> reusableMirroredLines = new ArrayList<>(64);
        var existing = idempotencyStore.get(cmd.requestId());
        if (existing.isPresent()) {
            var entry = existing.get();
            if (CommandResult.COMPLETED.equals(entry.status())) {
                return CommandResult.completed(entry.journalId());
            }
            return CommandResult.rejected(entry.errors(), entry.errorDetails());
        }

        Journal originalJournal = journalStore.get(cmd.originalJournalId());
        if (originalJournal == null) {
            var result = CommandResult.rejected(LedgerErrorCode.JOURNAL_NOT_FOUND,
                    Map.of("journalId", cmd.originalJournalId()));
            idempotencyStore.put(cmd.requestId(),
                    IdempotencyEntry.rejected(cmd.requestId(), result.errorCodes(), result.errorDetails(), Instant.now()));
            return result;
        }

        Set<String> accountIds = new HashSet<>();
        for (var origLine : originalJournal.lines()) {
            accountIds.add(origLine.accountId());
        }

        // Belt-and-suspenders: AQM serializes before Raft, lock serializes during apply
        return withAccountLocks(accountIds, () -> {
            // Re-check idempotency
            var existing2 = idempotencyStore.get(cmd.requestId());
            if (existing2.isPresent()) {
                var entry = existing2.get();
                if (CommandResult.COMPLETED.equals(entry.status())) {
                    return CommandResult.completed(entry.journalId());
            }
            return CommandResult.rejected(entry.errors(), entry.errorDetails());
        }

        // Re-fetch original journal
            Journal orig = journalStore.get(cmd.originalJournalId());
            if (orig == null) {
                var result = CommandResult.rejected(LedgerErrorCode.JOURNAL_NOT_FOUND,
                        Map.of("journalId", cmd.originalJournalId()));
                idempotencyStore.put(cmd.requestId(),
                        IdempotencyEntry.rejected(cmd.requestId(), result.errorCodes(), result.errorDetails(), Instant.now()));
                return result;
            }
            if (orig.status() == JournalStatus.REVERSED) {
                var result = CommandResult.rejected(LedgerErrorCode.JOURNAL_ALREADY_REVERSED,
                        Map.of("journalId", cmd.originalJournalId()));
                idempotencyStore.put(cmd.requestId(),
                        IdempotencyEntry.rejected(cmd.requestId(), result.errorCodes(), result.errorDetails(), Instant.now()));
                return result;
            }
            if (orig.journalType() == JournalType.REVERSAL) {
                var result = CommandResult.rejected(LedgerErrorCode.CANNOT_REVERSE_REVERSAL,
                        Map.of("journalId", cmd.originalJournalId()));
                idempotencyStore.put(cmd.requestId(),
                        IdempotencyEntry.rejected(cmd.requestId(), result.errorCodes(), result.errorDetails(), Instant.now()));
                return result;
            }

            long index = raftLogIndex.incrementAndGet();
            long seq = journalSequence.incrementAndGet();
            String reversalJournalId = String.format("JNL-%04d", seq);
            Instant now = Instant.now();

            Map<AccountBalanceKey, BalanceEntry> balanceUpdates = new HashMap<>();

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

                // Publish BalanceChangeEvent for reversal
                if (eventListener != null) {
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
                    eventListener.onEvent(event);
                }
            }

            boolean crossPeriod = cmd.valueDate().getYear() != orig.valueDate().getYear()
                    || cmd.valueDate().getMonth() != orig.valueDate().getMonth();

            Journal reversalJournal = new Journal(
                    reversalJournalId, JournalType.REVERSAL, cmd.requestId(),
                    orig.businessEventType(), orig.businessEventRef(),
                    cmd.valueDate(), JournalStatus.CONFIRMED,
                    List.copyOf(reusableMirroredLines), crossPeriod, now);

            journalStore.put(reversalJournalId, reversalJournal);

            // Mark original as reversed
            journalStore.put(cmd.originalJournalId(), orig.withStatus(JournalStatus.REVERSED));

            IdempotencyEntry idempotencyEntry = IdempotencyEntry.completed(cmd.requestId(), reversalJournalId, now);
            idempotencyStore.put(cmd.requestId(), idempotencyEntry);

            // Atomic RocksDB persistence
            persistApply(reversalJournal, balanceUpdates, idempotencyEntry);
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
        return journalStore.get(journalId);
    }

    public List<Journal> getJournalsByAccount(String accountId, int page, int size) {
        return journalStore.values().stream()
                .filter(j -> j.lines().stream().anyMatch(l -> l.accountId().equals(accountId)))
                .skip((long) page * size)
                .limit(size)
                .toList();
    }

    public long countJournalsByAccount(String accountId) {
        return journalStore.values().stream()
                .filter(j -> j.lines().stream().anyMatch(l -> l.accountId().equals(accountId)))
                .count();
    }

    public List<Journal> getJournalsByBusinessEventRef(String businessEventRef) {
        return journalStore.values().stream()
                .filter(j -> businessEventRef.equals(j.businessEventRef()))
                .toList();
    }

    public List<Journal> getJournalChain(String originalJournalId) {
        Journal original = journalStore.get(originalJournalId);
        if (original == null) return List.of();
        // All journals sharing the same businessEventRef form the chain
        String ref = original.businessEventRef();
        return journalStore.values().stream()
                .filter(j -> ref != null && ref.equals(j.businessEventRef()))
                .sorted(java.util.Comparator.comparing(Journal::createdAt))
                .toList();
    }

    public Journal getJournalByRequestId(String requestId) {
        return journalStore.values().stream()
                .filter(j -> requestId.equals(j.requestId()))
                .findFirst()
                .orElse(null);
    }

    // ── Balance computation ────────────────────────────────────

    private BigDecimal computeAfterBalance(BigDecimal current, EntryType entryType, BigDecimal amount) {
        return entryType == EntryType.CREDIT
                ? current.add(amount)
                : current.subtract(amount);
    }
}
