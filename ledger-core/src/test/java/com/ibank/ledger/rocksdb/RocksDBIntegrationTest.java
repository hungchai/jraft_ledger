package com.ibank.ledger.rocksdb;

import com.ibank.ledger.domain.model.*;
import com.ibank.ledger.store.AccountMetaStore;
import com.ibank.ledger.store.BalanceStore;
import com.ibank.ledger.store.BalanceTypeConfigStore;
import com.ibank.ledger.statemachine.LedgerStateMachine;
import com.ibank.ledger.domain.command.*;
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
                "CLIENT_ACC_001", AccountType.CLIENT, "Client",
                "CUST-001", AccountStatus.ACTIVE, null, Instant.now()));

        balanceStore.put(new AccountBalanceKey("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD"),
                new BalanceEntry(new BigDecimal("1000.00"), 0, 1, "JNL-INIT", Instant.now()));

        // Execute postings
        for (int i = 0; i < 5; i++) {
            PostingCommand cmd = new PostingCommand(
                    "req-" + i, "TEST", "test-ref", LocalDate.now(),
                    List.of(new PostingCommand.Leg("leg-" + i, "TEST", List.of(
                            new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD",
                                    EntryType.DEBIT, new BigDecimal("100.00"), "Debit " + i)
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
        AccountBalanceKey key = new AccountBalanceKey("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD");
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
                "CLIENT_ACC_001", AccountType.CLIENT, "Client",
                "CUST-001", AccountStatus.ACTIVE, null, Instant.now()));

        // Set accountSeq to 42
        AccountBalanceKey key = new AccountBalanceKey("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD");
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
                "CLIENT_ACC_001", AccountType.CLIENT, "Client",
                "CUST-001", AccountStatus.ACTIVE, null, Instant.now()));

        AccountBalanceKey key = new AccountBalanceKey("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD");
        bs.put(key, new BalanceEntry(new BigDecimal("1000.00"), 100, 42, "JNL-042", Instant.now()));

        // Snapshot at index 100, accountSeq 42
        sm.takeSnapshot();

        // Apply 5 more postings (replay scenario)
        for (int i = 0; i < 5; i++) {
            PostingCommand cmd = new PostingCommand(
                    "replay-" + i, "TEST", "test-ref", LocalDate.now(),
                    List.of(new PostingCommand.Leg("leg-" + i, "TEST", List.of(
                            new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD",
                                    EntryType.DEBIT, new BigDecimal("100.00"), "Replay " + i)
                    )))
            );
            sm.applyPosting(cmd);
        }

        // accountSeq should be 42 + 5 = 47
        assertThat(bs.getOrThrow(key).accountSeq()).isEqualTo(47);
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
                "CLIENT_ACC_001", AccountType.CLIENT, "Client",
                "CUST-001", AccountStatus.ACTIVE, null, Instant.now()));

        AccountBalanceKey key = new AccountBalanceKey("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD");
        bs.put(key, new BalanceEntry(new BigDecimal("1000.00"), 99, 99, "JNL-099", Instant.now()));

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
                List.of(new PostingCommand.Leg("leg-1", "TEST", List.of(
                        new PostingCommand.Line("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD",
                                EntryType.DEBIT, new BigDecimal("100.00"), "After restart")
                )))
        );
        sm2.applyPosting(cmd);

        assertThat(bs2.getOrThrow(key).accountSeq()).isEqualTo(100);
    }
}
