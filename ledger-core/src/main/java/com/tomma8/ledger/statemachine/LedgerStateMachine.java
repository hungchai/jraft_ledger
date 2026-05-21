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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
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

    private void persistIfNeeded() {
        if (persistAfterApply && rocksDB != null) {
            try { takeSnapshot(); } catch (Exception e) { log.error("Snapshot failed", e); }
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
        raftLogIndex.set(data.raftLogIndex);
        journalSequence.set(data.journalSequence);
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
            return k.accountId() + "#" + k.balanceType() + "#" + k.currency();
        }
        private static AccountBalanceKey parseKey(String s) {
            String[] parts = s.split("#", 3);
            return new AccountBalanceKey(parts[0], parts[1], parts[2]);
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

    private record LineWithLeg(String legId, PostingCommand.Line line) {}

    // ── Posting ────────────────────────────────────────────────

    public synchronized CommandResult applyPosting(PostingCommand cmd) {
        // 1. Idempotency check
        var existing = idempotencyStore.get(cmd.requestId());
        if (existing.isPresent()) {
            var entry = existing.get();
            if ("COMPLETED".equals(entry.status())) {
                return CommandResult.completed(entry.journalId());
            }
            return CommandResult.rejected(entry.errors());
        }

        // 2. Extract all lines with their legId
        List<LineWithLeg> allLines = new ArrayList<>();
        for (var leg : cmd.legs()) {
            for (var line : leg.lines()) {
                allLines.add(new LineWithLeg(leg.legId(), line));
            }
        }

        // Collect unique accounts
        Set<String> uniqueAccounts = new HashSet<>();
        for (var lwl : allLines) {
            uniqueAccounts.add(lwl.line().accountId());
        }

        // 3. Account status check (before balance checks)
        for (String accountId : uniqueAccounts) {
            var account = accountMetaStore.get(accountId);
            if (account.isEmpty()) {
                var result = CommandResult.rejected("ACCOUNT_NOT_FOUND");
                idempotencyStore.put(cmd.requestId(),
                        IdempotencyEntry.rejected(cmd.requestId(), result.errorCodes(), Instant.now()));
                return result;
            }
            if (account.get().status() == AccountStatus.FROZEN) {
                var result = CommandResult.rejected("ACCOUNT_FROZEN");
                idempotencyStore.put(cmd.requestId(),
                        IdempotencyEntry.rejected(cmd.requestId(), result.errorCodes(), Instant.now()));
                return result;
            }
        }

        // 4. Check per-leg journal balance + seed account restriction
        for (var leg : cmd.legs()) {
            if (leg.lines().size() < 2) {
                // Single-line leg = seed/capital injection. Only COMPANY/NOSTRO/SUSPENSE allowed.
                for (var line : leg.lines()) {
                    var account = accountMetaStore.get(line.accountId());
                    if (account.isPresent()) {
                        var type = account.get().accountType();
                        if (type == AccountType.CLIENT || type == AccountType.CONTROL) {
                            var result = CommandResult.rejected("SEED_NOT_ALLOWED_FOR_" + type);
                            idempotencyStore.put(cmd.requestId(),
                                    IdempotencyEntry.rejected(cmd.requestId(), result.errorCodes(), Instant.now()));
                            return result;
                        }
                    }
                }
                continue;
            }
            BigDecimal debitTotal = BigDecimal.ZERO;
            BigDecimal creditTotal = BigDecimal.ZERO;
            for (var line : leg.lines()) {
                if (line.entryType() == EntryType.DEBIT) {
                    debitTotal = debitTotal.add(line.amount());
                } else {
                    creditTotal = creditTotal.add(line.amount());
                }
            }
            if (debitTotal.compareTo(creditTotal) != 0) {
                var result = CommandResult.rejected("JOURNAL_UNBALANCED");
                idempotencyStore.put(cmd.requestId(),
                        IdempotencyEntry.rejected(cmd.requestId(), result.errorCodes(), Instant.now()));
                return result;
            }
        }

        // 5. Balance validation (compute after values, check rules)
        Map<AccountBalanceKey, BigDecimal> afterBalances = new HashMap<>();
        for (var lwl : allLines) {
            var line = lwl.line();
            BalanceTypeConfig config = balanceTypeConfigStore.get(line.balanceType())
                    .orElseThrow(() -> new BalanceTypeNotFoundException(line.balanceType()));

            AccountBalanceKey key = new AccountBalanceKey(line.accountId(), line.balanceType(), line.currency());
            BalanceEntry current = balanceStore.get(key).orElse(BalanceEntry.zero());
            BigDecimal after = computeAfterBalance(current.amount(), line.entryType(), line.amount());

            if (!config.allowNegative() && after.compareTo(BigDecimal.ZERO) < 0) {
                // Auto top-up for institutional accounts (COMPANY/NOSTRO/SUSPENSE)
                // Only CLIENT/CONTROL accounts have strict balance enforcement
                var account = accountMetaStore.get(line.accountId());
                boolean isInstitutional = account.isPresent() && (
                        account.get().accountType() == AccountType.COMPANY ||
                        account.get().accountType() == AccountType.NOSTRO ||
                        account.get().accountType() == AccountType.SUSPENSE);
                if (!isInstitutional) {
                    var result = CommandResult.rejected("INSUFFICIENT_BALANCE");
                    idempotencyStore.put(cmd.requestId(),
                            IdempotencyEntry.rejected(cmd.requestId(), result.errorCodes(), Instant.now()));
                    return result;
                }
            }
            if (config.allowNegative() && after.compareTo(BigDecimal.ZERO) > 0) {
                var result = CommandResult.rejected("CREDIT_EXCEEDS_LIMIT");
                idempotencyStore.put(cmd.requestId(),
                        IdempotencyEntry.rejected(cmd.requestId(), result.errorCodes(), Instant.now()));
                return result;
            }
            afterBalances.put(key, after);
        }

        // 6. Generate journal
        long index = raftLogIndex.incrementAndGet();
        long seq = journalSequence.incrementAndGet();
        String journalId = String.format("JNL-%04d", seq);
        Instant now = Instant.now();

        List<JournalLine> journalLines = new ArrayList<>();
        for (var lwl : allLines) {
            var line = lwl.line();
            AccountBalanceKey key = new AccountBalanceKey(line.accountId(), line.balanceType(), line.currency());
            BalanceEntry current = balanceStore.get(key).orElse(BalanceEntry.zero());
            BigDecimal after = afterBalances.get(key);
            BalanceTypeConfig config = balanceTypeConfigStore.getOrThrow(line.balanceType());

            String journalLineId = journalId + "-" + String.format("%02d", journalLines.size() + 1);
            JournalLine jl = new JournalLine(
                    journalLineId, journalId, lwl.legId(),
                    line.accountId(), line.balanceType(), line.currency(),
                    line.entryType(), line.amount(),
                    current.amount(), after,
                    config.configVersion(), now);
            journalLines.add(jl);
        }

        Journal journal = new Journal(
                journalId, JournalType.NORMAL, cmd.requestId(),
                cmd.businessEventType(), cmd.businessEventRef(),
                cmd.valueDate(), JournalStatus.CONFIRMED,
                List.copyOf(journalLines), false, now);

        journalStore.put(journalId, journal);

        // 7. Atomic balance update with accountSeq increment + event publishing
        for (int i = 0; i < allLines.size(); i++) {
            var lwl = allLines.get(i);
            var line = lwl.line();
            AccountBalanceKey key = new AccountBalanceKey(line.accountId(), line.balanceType(), line.currency());
            BigDecimal before = balanceStore.get(key).orElse(BalanceEntry.zero()).amount();
            BigDecimal after = afterBalances.get(key);
            BalanceEntry current = balanceStore.get(key).orElse(BalanceEntry.zero());
            long prevSeq = current.accountSeq();
            long nextSeq = prevSeq + 1;

            // NFR-15: accountSeq overflow warning (should never trigger)
            if (nextSeq >= ACCOUNT_SEQ_OVERFLOW_WARN) {
                log.error("[SEQ_OVERFLOW_WARN] accountId={} balanceType={} currency={} seq={}",
                        key.accountId(), key.balanceType(), key.currency(), nextSeq);
            }

            balanceStore.put(key, new BalanceEntry(after, index, nextSeq, journalId, now));

            // Publish BalanceChangeEvent (F-011)
            if (eventListener != null) {
                BigDecimal delta = line.entryType() == EntryType.DEBIT
                        ? line.amount().negate()
                        : line.amount();
                JournalLine jl = journalLines.get(i);
                BalanceChangeEvent event = new BalanceChangeEvent(
                        UUID.randomUUID().toString(),
                        BalanceChangeEvent.EVENT_TYPE,
                        BalanceChangeEvent.EVENT_VERSION,
                        now,
                        cmd.requestId() + ":" + line.accountId() + ":" + line.balanceType() + ":" + line.currency(),
                        "POSTING",
                        journalId,
                        jl.journalLineId(),
                        cmd.requestId(),
                        cmd.businessEventRef(),
                        null,
                        line.accountId(),
                        line.balanceType(),
                        line.currency(),
                        line.entryType(),
                        line.amount(),
                        before,
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
                if (outboxStore != null) outboxStore.enqueue(event);
            }
        }

        // 8. Idempotency
        idempotencyStore.put(cmd.requestId(),
                IdempotencyEntry.completed(cmd.requestId(), journalId, now));

        persistIfNeeded();
        return CommandResult.completed(journalId);
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
            return CommandResult.rejected("ACCOUNT_ALREADY_EXISTS");
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
            AccountBalanceKey key = new AccountBalanceKey(cmd.accountId(), init.balanceType(), init.currency());
            balanceStore.initialize(key);
        }

        // Publish account creation event
        if (eventListener != null) {
            eventListener.onAccountCreated(new AccountCreatedEvent(
                    UUID.randomUUID().toString(),
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

        AccountBalanceKey key = new AccountBalanceKey(accountId, balanceType, currency);
        balanceStore.initialize(key);

        return CommandResult.completed(null);
    }

    // ── Reversal (F-008 Section 4.2) ──────────────────────────────

    public synchronized CommandResult applyReversal(ReversalCommand cmd) {
        var existing = idempotencyStore.get(cmd.requestId());
        if (existing.isPresent()) {
            var entry = existing.get();
            if ("COMPLETED".equals(entry.status())) {
                return CommandResult.completed(entry.journalId());
            }
            return CommandResult.rejected(entry.errors());
        }

        Journal originalJournal = journalStore.get(cmd.originalJournalId());
        if (originalJournal == null) {
            var result = CommandResult.rejected("JOURNAL_NOT_FOUND");
            idempotencyStore.put(cmd.requestId(),
                    IdempotencyEntry.rejected(cmd.requestId(), result.errorCodes(), Instant.now()));
            return result;
        }

        // Cannot reverse an already-reversed journal
        if (originalJournal.status() == JournalStatus.REVERSED) {
            var result = CommandResult.rejected("JOURNAL_ALREADY_REVERSED");
            idempotencyStore.put(cmd.requestId(),
                    IdempotencyEntry.rejected(cmd.requestId(), result.errorCodes(), Instant.now()));
            return result;
        }

        // Cannot reverse a reversal journal
        if (originalJournal.journalType() == JournalType.REVERSAL) {
            var result = CommandResult.rejected("CANNOT_REVERSE_REVERSAL");
            idempotencyStore.put(cmd.requestId(),
                    IdempotencyEntry.rejected(cmd.requestId(), result.errorCodes(), Instant.now()));
            return result;
        }

        long index = raftLogIndex.incrementAndGet();
        long seq = journalSequence.incrementAndGet();
        String reversalJournalId = String.format("JNL-%04d", seq);
        Instant now = Instant.now();

        // Mirror each original line: DEBIT ↔ CREDIT, no balance check
        List<JournalLine> mirroredLines = new ArrayList<>();
        for (var origLine : originalJournal.lines()) {
            EntryType mirrored = origLine.entryType() == EntryType.DEBIT ? EntryType.CREDIT : EntryType.DEBIT;
            AccountBalanceKey key = new AccountBalanceKey(origLine.accountId(), origLine.balanceType(), origLine.currency());
            BalanceEntry current = balanceStore.get(key).orElse(BalanceEntry.zero());
            BigDecimal after = computeAfterBalance(current.amount(), mirrored, origLine.amount());
            long prevSeq = current.accountSeq();
            long nextSeq = prevSeq + 1;

            // NFR-15 overflow check
            if (nextSeq >= ACCOUNT_SEQ_OVERFLOW_WARN) {
                log.error("[SEQ_OVERFLOW_WARN] accountId={} balanceType={} currency={} seq={}",
                        key.accountId(), key.balanceType(), key.currency(), nextSeq);
            }

            balanceStore.put(key, new BalanceEntry(after, index, nextSeq, reversalJournalId, now));

            String journalLineId = reversalJournalId + "-" + String.format("%02d", mirroredLines.size() + 1);
            JournalLine jl = new JournalLine(
                    journalLineId, reversalJournalId, origLine.legId(),
                    origLine.accountId(), origLine.balanceType(), origLine.currency(),
                    mirrored, origLine.amount(),
                    current.amount(), after,
                    origLine.configVersion(), now);
            mirroredLines.add(jl);

            // Publish BalanceChangeEvent for reversal
            if (eventListener != null) {
                BigDecimal delta = mirrored == EntryType.DEBIT
                        ? jl.amount().negate()
                        : jl.amount();
                BalanceChangeEvent event = new BalanceChangeEvent(
                        UUID.randomUUID().toString(),
                        BalanceChangeEvent.EVENT_TYPE,
                        BalanceChangeEvent.EVENT_VERSION,
                        now,
                        cmd.requestId() + ":" + key.accountId() + ":" + key.balanceType() + ":" + key.currency(),
                        "REVERSAL",
                        reversalJournalId,
                        journalLineId,
                        cmd.requestId(),
                        originalJournal.businessEventRef(),
                        null,
                        key.accountId(),
                        key.balanceType(),
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

        boolean crossPeriod = cmd.valueDate().getYear() != originalJournal.valueDate().getYear()
                || cmd.valueDate().getMonth() != originalJournal.valueDate().getMonth();

        Journal reversalJournal = new Journal(
                reversalJournalId, JournalType.REVERSAL, cmd.requestId(),
                originalJournal.businessEventType(), originalJournal.businessEventRef(),
                cmd.valueDate(), JournalStatus.CONFIRMED,
                List.copyOf(mirroredLines), crossPeriod, now);

        journalStore.put(reversalJournalId, reversalJournal);

        // Mark original as reversed
        journalStore.put(cmd.originalJournalId(), originalJournal.withStatus(JournalStatus.REVERSED));

        idempotencyStore.put(cmd.requestId(),
                IdempotencyEntry.completed(cmd.requestId(), reversalJournalId, now));

        persistIfNeeded();
        return CommandResult.completed(reversalJournalId);
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
