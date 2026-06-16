package com.tomma8.ledger.rocksdb;

import com.tomma8.ledger.domain.model.*;
import com.tomma8.ledger.store.AccountMetaStore;
import com.tomma8.ledger.store.BalanceStore;
import com.tomma8.ledger.store.BalanceTypeConfigStore;
import com.tomma8.ledger.statemachine.LedgerStateMachine;
import com.tomma8.ledger.domain.command.*;
import org.junit.jupiter.api.*;
import org.rocksdb.RocksDB;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;

import java.io.File;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@DisplayName("RocksDB Integration Tests")
class RocksDBIntegrationTest {

    private Path tempDir;
    private RocksDBManager rocksDBManager;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("rocksdb-test-");
        rocksDBManager = new RocksDBManager(tempDir.toString());
        rocksDBManager.open();
    }

    @AfterEach
    void tearDown() {
        rocksDBManager.close();
        try {
            Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        } catch (Exception ignored) {}
    }

    @Test
    @DisplayName("TC-ROCKS-01 writeBatch atomic journal and balance consistent after crash")
    void writeBatch_atomic_journalAndBalanceConsistentAfterCrash() throws Exception {
        // Write journal + balance in a single atomic WriteBatch
        byte[] journalKey = RocksDBKeySerializer.journalKey("JNL-001");
        byte[] journalVal = "journal-data".getBytes(StandardCharsets.UTF_8);
        byte[] balanceKey = RocksDBKeySerializer.balanceKey("ACC-001", "AVAILABLE_BALANCE", "USD");
        byte[] balanceVal = "balance-data".getBytes(StandardCharsets.UTF_8);

        WriteBatch batch = new WriteBatch();
        batch.put(rocksDBManager.getHandle(ColumnFamilyRegistry.CF_JOURNAL), journalKey, journalVal);
        batch.put(rocksDBManager.getHandle(ColumnFamilyRegistry.CF_BALANCE), balanceKey, balanceVal);
        rocksDBManager.write(batch);
        batch.close();

        // Both should be present
        assertThat(rocksDBManager.get(ColumnFamilyRegistry.CF_JOURNAL, journalKey)).isNotNull();
        assertThat(rocksDBManager.get(ColumnFamilyRegistry.CF_BALANCE, balanceKey)).isNotNull();

        // Simulate crash: close without flushing
        rocksDBManager.close();

        // Reopen — WAL replay should restore both
        rocksDBManager = new RocksDBManager(tempDir.toString());
        rocksDBManager.open();

        assertThat(rocksDBManager.get(ColumnFamilyRegistry.CF_JOURNAL, journalKey)).isNotNull();
        assertThat(rocksDBManager.get(ColumnFamilyRegistry.CF_BALANCE, balanceKey)).isNotNull();
    }

    @Test
    @DisplayName("TC-ROCKS-02 walReplay after clean restart balance recovered")
    void walReplay_afterCleanRestart_balanceRecovered() throws Exception {
        // Set up state machine with RocksDB
        BalanceStore balanceStore = new BalanceStore();
        AccountMetaStore accountMetaStore = new AccountMetaStore();
        BalanceTypeConfigStore configStore = new BalanceTypeConfigStore();
        LedgerStateMachine sm = new LedgerStateMachine(balanceStore, accountMetaStore, configStore);
        sm.setRocksDB(rocksDBManager);

        configStore.put("AVAILABLE_BALANCE", new BalanceTypeConfig(
                "AVAILABLE_BALANCE", false, null, SignConvention.NORMAL_CREDIT, 1));
        accountMetaStore.put("CLIENT_ACC_001", new Account(
                "CLIENT_ACC_001", AccountType.COMPANY, "Client",
                "CUST-001", AccountStatus.ACTIVE, null, Instant.now()));

        balanceStore.put(new AccountBalanceKey("CLIENT_ACC_001", "AVAILABLE_BALANCE", "CURRENT", "USD"),
                new BalanceEntry(new BigDecimal("1000.00"), 0, 1, "JNL-INIT", Instant.now()));

        // Set up suspense account for balanced postings
        accountMetaStore.put("SUSPENSE_ACC", new Account(
                "SUSPENSE_ACC", AccountType.SUSPENSE, "Suspense",
                "INTERNAL", AccountStatus.ACTIVE, null, Instant.now()));

        // Execute postings
        for (int i = 0; i < 5; i++) {
            PostingCommand cmd = new PostingCommand(
                    "req-" + i, "TEST", "test-ref", LocalDate.now(),
                    List.of(new PostingCommand.Leg("leg-" + i, "TEST", new BigDecimal("100.00"), "USD", List.of(
                            new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "CURRENT",
                                    EntryType.DEBIT, "Debit " + i),
                            new PostingCommand.Line("SUSPENSE_ACC", "AVAILABLE_BALANCE", "CURRENT",
                                    EntryType.CREDIT, "Credit " + i)
                    )))
            );
            sm.applyPosting(cmd);
        }

        // Take snapshot
        sm.takeSnapshot();

        // Close and reopen
        rocksDBManager.close();
        rocksDBManager = new RocksDBManager(tempDir.toString());
        rocksDBManager.open();

        // Fresh state machine, restore from snapshot
        BalanceStore bs2 = new BalanceStore();
        AccountMetaStore ams2 = new AccountMetaStore();
        BalanceTypeConfigStore cs2 = new BalanceTypeConfigStore();
        LedgerStateMachine sm2 = new LedgerStateMachine(bs2, ams2, cs2);
        sm2.setRocksDB(rocksDBManager);
        sm2.restoreFromSnapshot();

        // Balance should be restored
        AccountBalanceKey key = new AccountBalanceKey("CLIENT_ACC_001", "AVAILABLE_BALANCE", "CURRENT", "USD");
        BalanceEntry restored = bs2.get(key).orElseThrow();
        assertThat(restored.amount()).isEqualByComparingTo(new BigDecimal("500.00")); // 1000 - 5*100
        assertThat(restored.accountSeq()).isEqualTo(6); // 1 + 5
    }

    @Test
    @DisplayName("TC-ROCKS-03 columnFamilyIsolation write to one CF not affect others")
    void columnFamilyIsolation_writeToOneCF_notAffectOthers() throws Exception {
        byte[] journalKey = RocksDBKeySerializer.journalKey("JNL-ISO");
        byte[] journalVal = "journal-data".getBytes(StandardCharsets.UTF_8);
        byte[] balanceKey = RocksDBKeySerializer.balanceKey("ACC-ISO", "AVAILABLE_BALANCE", "USD");

        // Write to CF_JOURNAL only
        rocksDBManager.put(ColumnFamilyRegistry.CF_JOURNAL, journalKey, journalVal);

        // CF_BALANCE should not have the journal key
        assertThat(rocksDBManager.get(ColumnFamilyRegistry.CF_BALANCE, journalKey)).isNull();
        // And the balance key shouldn't exist in either CF
        assertThat(rocksDBManager.get(ColumnFamilyRegistry.CF_BALANCE, balanceKey)).isNull();
        assertThat(rocksDBManager.get(ColumnFamilyRegistry.CF_JOURNAL, balanceKey)).isNull();
    }

    // ── Snapshot accountSeq tests (TC-F008-24~26) ──────────────

    @Test
    @DisplayName("TC-F008-24 takeSnapshot accountSeq serialized and restored")
    void takeSnapshot_accountSeqSerializedAndRestored() throws Exception {
        BalanceStore bs = new BalanceStore();
        AccountMetaStore ams = new AccountMetaStore();
        BalanceTypeConfigStore cs = new BalanceTypeConfigStore();
        LedgerStateMachine sm = new LedgerStateMachine(bs, ams, cs);
        sm.setRocksDB(rocksDBManager);

        cs.put("AVAILABLE_BALANCE", new BalanceTypeConfig(
                "AVAILABLE_BALANCE", false, null, SignConvention.NORMAL_CREDIT, 1));
        ams.put("CLIENT_ACC_001", new Account(
                "CLIENT_ACC_001", AccountType.COMPANY, "Client",
                "CUST-001", AccountStatus.ACTIVE, null, Instant.now()));

        // Set accountSeq to 42
        AccountBalanceKey key = new AccountBalanceKey("CLIENT_ACC_001", "AVAILABLE_BALANCE", "CURRENT", "USD");
        bs.put(key, new BalanceEntry(new BigDecimal("500.00"), 42, 42, "JNL-042", Instant.now()));

        // Snapshot
        sm.takeSnapshot();

        // Restore into fresh StateMachine
        BalanceStore bs2 = new BalanceStore();
        AccountMetaStore ams2 = new AccountMetaStore();
        BalanceTypeConfigStore cs2 = new BalanceTypeConfigStore();
        LedgerStateMachine sm2 = new LedgerStateMachine(bs2, ams2, cs2);
        sm2.setRocksDB(rocksDBManager);
        sm2.restoreFromSnapshot();

        BalanceEntry restored = bs2.get(key).orElseThrow();
        assertThat(restored.amount()).isEqualByComparingTo(new BigDecimal("500.00"));
        assertThat(restored.accountSeq()).isEqualTo(42); // accountSeq preserved
    }

    @Test
    @DisplayName("TC-F008-25 replayFromLog after snapshot accountSeq continues")
    void replayFromLog_afterSnapshot_accountSeqContinues() throws Exception {
        BalanceStore bs = new BalanceStore();
        AccountMetaStore ams = new AccountMetaStore();
        BalanceTypeConfigStore cs = new BalanceTypeConfigStore();
        LedgerStateMachine sm = new LedgerStateMachine(bs, ams, cs);
        sm.setRocksDB(rocksDBManager);

        cs.put("AVAILABLE_BALANCE", new BalanceTypeConfig(
                "AVAILABLE_BALANCE", false, null, SignConvention.NORMAL_CREDIT, 1));
        ams.put("CLIENT_ACC_001", new Account(
                "CLIENT_ACC_001", AccountType.COMPANY, "Client",
                "CUST-001", AccountStatus.ACTIVE, null, Instant.now()));

        AccountBalanceKey key = new AccountBalanceKey("CLIENT_ACC_001", "AVAILABLE_BALANCE", "CURRENT", "USD");
        bs.put(key, new BalanceEntry(new BigDecimal("1000.00"), 100, 42, "JNL-042", Instant.now()));

        // Snapshot at index 100, accountSeq 42
        sm.takeSnapshot();

        // Set up suspense account for balanced postings
        ams.put("SUSPENSE_ACC", new Account(
                "SUSPENSE_ACC", AccountType.SUSPENSE, "Suspense",
                "INTERNAL", AccountStatus.ACTIVE, null, Instant.now()));

        // Apply 5 more postings (replay scenario)
        for (int i = 0; i < 5; i++) {
            PostingCommand cmd = new PostingCommand(
                    "replay-" + i, "TEST", "test-ref", LocalDate.now(),
                    List.of(new PostingCommand.Leg("leg-" + i, "TEST", new BigDecimal("100.00"), "USD", List.of(
                            new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "CURRENT",
                                    EntryType.DEBIT, "Replay " + i),
                            new PostingCommand.Line("SUSPENSE_ACC", "AVAILABLE_BALANCE", "CURRENT",
                                    EntryType.CREDIT, "Replay credit " + i)
                    )))
            );
            sm.applyPosting(cmd);
        }

        // accountSeq should be 42 + 5 = 47
        assertThat(bs.getOrThrow(key).accountSeq()).isEqualTo(47);
    }

    // ── F-008 Snapshot & Replay Tests ───────────────────────────

    @Test
    @DisplayName("TC-F008-16 takeSnapshot all balances serialized and restored")
    void takeSnapshot_allBalancesSerializedAndRestored() throws Exception {
        BalanceStore bs = new BalanceStore();
        AccountMetaStore ams = new AccountMetaStore();
        BalanceTypeConfigStore cs = new BalanceTypeConfigStore();
        LedgerStateMachine sm = new LedgerStateMachine(bs, ams, cs);
        sm.setRocksDB(rocksDBManager);

        cs.put("AVAILABLE_BALANCE", new BalanceTypeConfig(
                "AVAILABLE_BALANCE", false, null, SignConvention.NORMAL_CREDIT, 1));
        cs.put("TRADE_AHEAD_BALANCE", new BalanceTypeConfig(
                "TRADE_AHEAD_BALANCE", true, NegativeSemantics.PRE_AUTHORIZED,
                SignConvention.NORMAL_DEBIT, 1));

        // Create 5 accounts with different balances
        String[][] accounts = {
                {"CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD", "1000.00", "1"},
                {"CLIENT_ACC_002", "AVAILABLE_BALANCE", "USD", "2000.00", "3"},
                {"CLIENT_ACC_003", "AVAILABLE_BALANCE", "HKD", "50000.00", "7"},
                {"COMPANY_FX_ACC", "AVAILABLE_BALANCE", "USD", "100000.00", "15"},
                {"CLIENT_ACC_001", "TRADE_AHEAD_BALANCE", "USD", "-5000.00", "42"},
        };

        for (String[] acct : accounts) {
            AccountBalanceKey key = new AccountBalanceKey(acct[0], acct[1], "CURRENT", acct[2]);
            if (!ams.contains(acct[0])) {
                ams.put(acct[0], new Account(acct[0], AccountType.COMPANY, acct[0],
                        "OWNER-" + acct[0], AccountStatus.ACTIVE, null, Instant.now()));
            }
            bs.put(key, new BalanceEntry(
                    new BigDecimal(acct[3]), Long.parseLong(acct[4]), Long.parseLong(acct[4]),
                    "JNL-" + acct[4], Instant.now()));
        }

        sm.takeSnapshot();

        // Restore into fresh state machine
        BalanceStore bs2 = new BalanceStore();
        AccountMetaStore ams2 = new AccountMetaStore();
        BalanceTypeConfigStore cs2 = new BalanceTypeConfigStore();
        LedgerStateMachine sm2 = new LedgerStateMachine(bs2, ams2, cs2);
        sm2.setRocksDB(rocksDBManager);
        sm2.restoreFromSnapshot();

        // Verify all 5 balances restored correctly
        for (String[] acct : accounts) {
            AccountBalanceKey key = new AccountBalanceKey(acct[0], acct[1], "CURRENT", acct[2]);
            BalanceEntry restored = bs2.get(key).orElseThrow();
            assertThat(restored.amount()).isEqualByComparingTo(new BigDecimal(acct[3]));
            assertThat(restored.accountSeq()).isEqualTo(Long.parseLong(acct[4]));
            assertThat(restored.stateVersion()).isEqualTo(Long.parseLong(acct[4]));
        }

        assertThat(bs2.size()).isEqualTo(5);
        // Verify raftLogIndex and journalSequence restored
        assertThat(sm2.getRaftLogIndex()).isEqualTo(sm.getRaftLogIndex());
        assertThat(sm2.getJournalSequence()).isEqualTo(sm.getJournalSequence());
    }

    @Test
    @DisplayName("TC-F008-17 replayFromLog after snapshot balance correct")
    void replayFromLog_afterSnapshot_balanceCorrect() throws Exception {
        BalanceStore bs = new BalanceStore();
        AccountMetaStore ams = new AccountMetaStore();
        BalanceTypeConfigStore cs = new BalanceTypeConfigStore();
        LedgerStateMachine sm = new LedgerStateMachine(bs, ams, cs);
        sm.setRocksDB(rocksDBManager);

        cs.put("AVAILABLE_BALANCE", new BalanceTypeConfig(
                "AVAILABLE_BALANCE", false, null, SignConvention.NORMAL_CREDIT, 1));
        ams.put("CLIENT_ACC_001", new Account(
                "CLIENT_ACC_001", AccountType.COMPANY, "Client",
                "CUST-001", AccountStatus.ACTIVE, null, Instant.now()));

        // Initial balance
        AccountBalanceKey key = new AccountBalanceKey("CLIENT_ACC_001", "AVAILABLE_BALANCE", "CURRENT", "USD");
        bs.put(key, new BalanceEntry(new BigDecimal("1000.00"), 0, 0, "", Instant.EPOCH));

        // Set up suspense account for balanced postings
        ams.put("SUSPENSE_ACC", new Account(
                "SUSPENSE_ACC", AccountType.SUSPENSE, "Suspense",
                "INTERNAL", AccountStatus.ACTIVE, null, Instant.now()));

        // Apply 10 postings → balance = 1000 - 10*100 = 0, accountSeq = 10
        for (int i = 0; i < 10; i++) {
            PostingCommand cmd = new PostingCommand(
                    "req-snap-" + i, "TEST", "test-ref", LocalDate.now(),
                    List.of(new PostingCommand.Leg("leg-" + i, "TEST", new BigDecimal("100.00"), "USD", List.of(
                            new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "CURRENT",
                                    EntryType.DEBIT, "Debit " + i),
                            new PostingCommand.Line("SUSPENSE_ACC", "AVAILABLE_BALANCE", "CURRENT",
                                    EntryType.CREDIT, "Credit " + i)
                    )))
            );
            sm.applyPosting(cmd);
        }

        assertThat(bs.getOrThrow(key).amount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(bs.getOrThrow(key).accountSeq()).isEqualTo(10);
        long snapshotIndex = sm.getRaftLogIndex();

        // Take snapshot at this point (covers log entries 0..10)
        sm.takeSnapshot();

        // Apply 5 more postings (log entries 11..15, simulating replay)
        for (int i = 0; i < 5; i++) {
            PostingCommand cmd = new PostingCommand(
                    "replay-" + i, "TEST", "test-ref", LocalDate.now(),
                    List.of(new PostingCommand.Leg("leg-" + i, "TEST", new BigDecimal("50.00"), "USD", List.of(
                            new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "CURRENT",
                                    EntryType.CREDIT, "Credit " + i),
                            new PostingCommand.Line("SUSPENSE_ACC", "AVAILABLE_BALANCE", "CURRENT",
                                    EntryType.DEBIT, "Replay debit " + i)
                    )))
            );
            sm.applyPosting(cmd);
        }

        // After total of 15 operations: 1000 - 1000 + 250 = 250
        // accountSeq = 10 + 5 = 15
        assertThat(bs.getOrThrow(key).amount()).isEqualByComparingTo(new BigDecimal("250.00"));
        assertThat(bs.getOrThrow(key).accountSeq()).isEqualTo(15);

        // Simulate crash and restore from snapshot
        rocksDBManager.close();
        rocksDBManager = new RocksDBManager(tempDir.toString());
        rocksDBManager.open();

        BalanceStore bs2 = new BalanceStore();
        AccountMetaStore ams2 = new AccountMetaStore();
        BalanceTypeConfigStore cs2 = new BalanceTypeConfigStore();
        LedgerStateMachine sm2 = new LedgerStateMachine(bs2, ams2, cs2);
        sm2.setRocksDB(rocksDBManager);
        sm2.restoreFromSnapshot();

        // After restore, should be at snapshot state (10 debits, 5 credits not in snapshot)
        BalanceEntry restored = bs2.get(key).orElseThrow();
        assertThat(restored.amount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(restored.accountSeq()).isEqualTo(10);

        // Replay the 5 postings that were after the snapshot
        for (int i = 0; i < 5; i++) {
            PostingCommand cmd = new PostingCommand(
                    "replay-" + i, "TEST", "test-ref", LocalDate.now(),
                    List.of(new PostingCommand.Leg("leg-" + i, "TEST", new BigDecimal("50.00"), "USD", List.of(
                            new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "CURRENT",
                                    EntryType.CREDIT, "Credit " + i),
                            new PostingCommand.Line("SUSPENSE_ACC", "AVAILABLE_BALANCE", "CURRENT",
                                    EntryType.DEBIT, "Replay debit " + i)
                    )))
            );
            sm2.applyPosting(cmd);
        }

        // Final balance should match: 250.00, accountSeq = 15
        assertThat(bs2.getOrThrow(key).amount()).isEqualByComparingTo(new BigDecimal("250.00"));
        assertThat(bs2.getOrThrow(key).accountSeq()).isEqualTo(15);
    }

    @Test
    @DisplayName("TC-F008-18 inactiveAccount evicted from memory reloaded from RocksDB")
    void inactiveAccount_evictedFromMemory_reloadedFromRocksDB() throws Exception {
        BalanceStore bs = new BalanceStore();
        AccountMetaStore ams = new AccountMetaStore();
        BalanceTypeConfigStore cs = new BalanceTypeConfigStore();
        LedgerStateMachine sm = new LedgerStateMachine(bs, ams, cs);
        sm.setRocksDB(rocksDBManager);

        cs.put("AVAILABLE_BALANCE", new BalanceTypeConfig(
                "AVAILABLE_BALANCE", false, null, SignConvention.NORMAL_CREDIT, 1));
        ams.put("CLIENT_ACC_001", new Account(
                "CLIENT_ACC_001", AccountType.COMPANY, "Client",
                "CUST-001", AccountStatus.ACTIVE, null, Instant.now()));

        // Create account with existing balance and transactions
        AccountBalanceKey key = new AccountBalanceKey("CLIENT_ACC_001", "AVAILABLE_BALANCE", "CURRENT", "USD");
        bs.put(key, new BalanceEntry(
                new BigDecimal("1000.00"), 50, 50, "JNL-050", Instant.now()));

        // Set up suspense account for balanced postings
        ams.put("SUSPENSE_ACC", new Account(
                "SUSPENSE_ACC", AccountType.SUSPENSE, "Suspense",
                "INTERNAL", AccountStatus.ACTIVE, null, Instant.now()));

        // Take snapshot to persist current state
        sm.takeSnapshot();

        // Simulate inactive account eviction — remove from in-memory store
        bs.clear();

        // Snapshot restore to reload from RocksDB (simulating cold account warm-up)
        sm.restoreFromSnapshot();

        // Account should be back with correct balance and accountSeq
        BalanceEntry warmed = bs.get(key).orElseThrow();
        assertThat(warmed.amount()).isEqualByComparingTo(new BigDecimal("1000.00"));
        assertThat(warmed.accountSeq()).isEqualTo(50);
        assertThat(warmed.lastJournalId()).isEqualTo("JNL-050");

        // Now apply a new posting — accountSeq must continue from 50, not 0 or 1
        PostingCommand cmd = new PostingCommand(
                "warmup-001", "TEST", "test-ref", LocalDate.now(),
                List.of(new PostingCommand.Leg("leg-1", "TEST", new BigDecimal("200.00"), "USD", List.of(
                        new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "CURRENT", EntryType.CREDIT, "After warm-up"),
                        new PostingCommand.Line("SUSPENSE_ACC", "AVAILABLE_BALANCE", "CURRENT", EntryType.DEBIT, "Warm-up debit")
                )))
        );
        sm.applyPosting(cmd);

        assertThat(bs.getOrThrow(key).amount()).isEqualByComparingTo(new BigDecimal("1200.00"));
        assertThat(bs.getOrThrow(key).accountSeq()).isEqualTo(51);
    }

    @Test
    @DisplayName("TC-F008-26 restartNode accountSeq resumes from RocksDB")
    void restartNode_accountSeqResumesFromRocksDB() throws Exception {
        BalanceStore bs = new BalanceStore();
        AccountMetaStore ams = new AccountMetaStore();
        BalanceTypeConfigStore cs = new BalanceTypeConfigStore();
        LedgerStateMachine sm = new LedgerStateMachine(bs, ams, cs);
        sm.setRocksDB(rocksDBManager);

        cs.put("AVAILABLE_BALANCE", new BalanceTypeConfig(
                "AVAILABLE_BALANCE", false, null, SignConvention.NORMAL_CREDIT, 1));
        ams.put("CLIENT_ACC_001", new Account(
                "CLIENT_ACC_001", AccountType.COMPANY, "Client",
                "CUST-001", AccountStatus.ACTIVE, null, Instant.now()));

        AccountBalanceKey key = new AccountBalanceKey("CLIENT_ACC_001", "AVAILABLE_BALANCE", "CURRENT", "USD");
        bs.put(key, new BalanceEntry(new BigDecimal("1000.00"), 99, 99, "JNL-099", Instant.now()));

        // Set up suspense account for balanced postings
        ams.put("SUSPENSE_ACC", new Account(
                "SUSPENSE_ACC", AccountType.SUSPENSE, "Suspense",
                "INTERNAL", AccountStatus.ACTIVE, null, Instant.now()));

        // Snapshot with accountSeq=99
        sm.takeSnapshot();

        // Simulate crash and restart
        rocksDBManager.close();
        rocksDBManager = new RocksDBManager(tempDir.toString());
        rocksDBManager.open();

        BalanceStore bs2 = new BalanceStore();
        AccountMetaStore ams2 = new AccountMetaStore();
        BalanceTypeConfigStore cs2 = new BalanceTypeConfigStore();
        LedgerStateMachine sm2 = new LedgerStateMachine(bs2, ams2, cs2);
        sm2.setRocksDB(rocksDBManager);
        sm2.restoreFromSnapshot();

        // accountSeq should be 99, not 0 or 1
        BalanceEntry restored = bs2.get(key).orElseThrow();
        assertThat(restored.accountSeq()).isEqualTo(99);

        // Next posting should get accountSeq=100
        // Snapshot restore already restored accounts and configs
        PostingCommand cmd = new PostingCommand(
                "restart-001", "TEST", "test-ref", LocalDate.now(),
                List.of(new PostingCommand.Leg("leg-1", "TEST", new BigDecimal("100.00"), "USD", List.of(
                        new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "CURRENT", EntryType.DEBIT, "After restart"),
                        new PostingCommand.Line("SUSPENSE_ACC", "AVAILABLE_BALANCE", "CURRENT", EntryType.CREDIT, "Restart credit")
                )))
        );
        sm2.applyPosting(cmd);

        assertThat(bs2.getOrThrow(key).accountSeq()).isEqualTo(100);
    }

    @Test
    @DisplayName("TC-ROCKS-RETENTION-01 prune deletes old journals, keeps recent + reversible")
    void pruneJournals_deletesOld_keepsRecentAndReversible() throws Exception {
        BalanceStore bs = new BalanceStore();
        AccountMetaStore ams = new AccountMetaStore();
        BalanceTypeConfigStore cs = new BalanceTypeConfigStore();
        LedgerStateMachine sm = new LedgerStateMachine(bs, ams, cs);
        sm.setRocksDB(rocksDBManager);
        cs.put("AVAILABLE_BALANCE", new BalanceTypeConfig(
                "AVAILABLE_BALANCE", false, null, SignConvention.NORMAL_CREDIT, 1));
        ams.put("A", new Account("A", AccountType.COMPANY, "A", null, AccountStatus.ACTIVE, null, Instant.now()));
        ams.put("B", new Account("B", AccountType.COMPANY, "B", null, AccountStatus.ACTIVE, null, Instant.now()));
        // A funded so 20 debits of 1.00 stay non-negative.
        bs.put(new AccountBalanceKey("A", "AVAILABLE_BALANCE", "CURRENT", "USD"),
                new BalanceEntry(new BigDecimal("1000.00"), 0, 1, "JNL-INIT", Instant.now()));

        // Apply 20 postings at raftIndex 1..20 → journals JNL-...0001 .. JNL-...0020
        for (int i = 1; i <= 20; i++) {
            CommandResult r = sm.applyPosting(new PostingCommand(
                    "req-" + i, "TEST", "ref-" + i, LocalDate.now(),
                    List.of(new PostingCommand.Leg("leg", "TEST", new BigDecimal("1.00"), "USD", List.of(
                            new PostingCommand.Line("A", "AVAILABLE_BALANCE", "CURRENT", EntryType.DEBIT, "d"),
                            new PostingCommand.Line("B", "AVAILABLE_BALANCE", "CURRENT", EntryType.CREDIT, "c"))))), i);
            assertThat(r.status()).as("posting %s: %s", i, r.errorCodes()).isEqualTo(CommandResult.COMPLETED);
        }
        String oldId = String.format("JNL-%016d", 3);
        String recentId = String.format("JNL-%016d", 20);
        assertThat(sm.getJournal(oldId)).isNotNull();
        assertThat(sm.getJournal(recentId)).isNotNull();

        // applied=20, retention=5 → pruneBelow=15 → JNL 1..14 deleted, 15..20 kept
        sm.setLastAppliedIndexSource(() -> 20L);
        sm.pruneJournals(5);

        // Clearly-old pruned; clearly-recent (within retention) kept. The exact
        // boundary key isn't pinned — retention is approximate at the edge.
        assertThat(sm.getJournal(oldId)).as("old journal (3) pruned").isNull();
        assertThat(sm.getJournal(String.format("JNL-%016d", 10))).as("old journal (10) pruned").isNull();
        assertThat(sm.getJournal(String.format("JNL-%016d", 18))).as("recent (18) kept").isNotNull();
        assertThat(sm.getJournal(recentId)).as("recent (20) kept").isNotNull();

        // A retained (recent) journal is still reversible — reads original from RocksDB.
        CommandResult rev = sm.applyReversal(new ReversalCommand("rev-1", recentId, "reversal", "ADJ", LocalDate.now()), 21);
        assertThat(rev.status()).isEqualTo(CommandResult.COMPLETED);
        assertThat(sm.getJournal(recentId).status()).isEqualTo(JournalStatus.REVERSED);
    }
}
