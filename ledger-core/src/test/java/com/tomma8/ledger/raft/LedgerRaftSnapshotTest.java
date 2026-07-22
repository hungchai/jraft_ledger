package com.tomma8.ledger.raft;

import com.alipay.sofa.jraft.Closure;
import com.alipay.sofa.jraft.Status;
import com.alipay.sofa.jraft.entity.RaftOutter;
import com.alipay.sofa.jraft.storage.snapshot.SnapshotWriter;
import com.tomma8.ledger.domain.command.PostingCommand;
import com.tomma8.ledger.domain.model.*;
import com.tomma8.ledger.rocksdb.RocksDBManager;
import com.tomma8.ledger.statemachine.LedgerStateMachine;
import com.tomma8.ledger.store.AccountMetaStore;
import com.tomma8.ledger.store.BalanceStore;
import com.tomma8.ledger.store.BalanceTypeConfigStore;
import org.junit.jupiter.api.*;

import java.io.File;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

/**
 * TC-RAFT-30/31 — snapshot save must not freeze apply.
 *
 * <p>Regression guard for the snapshot-stall fix: {@code onSnapshotSave} previously held the
 * snapshot write lock while streaming the entire (O(journal-count)) journal CF to disk, blocking
 * every {@code onApply} for seconds each snapshot interval (240m soak: p99→10s, TPS −75%, ~500
 * client {@code .get()} timeouts, all aligned to the 600s snapshot cadence). The fix scopes the
 * write lock to the in-memory state capture only; journal streaming runs lock-free.
 */
@DisplayName("LedgerRaftStateMachine snapshot save")
class LedgerRaftSnapshotTest {

    private Path tempDir;
    private RocksDBManager rocks;
    private BalanceStore balanceStore;
    private AccountMetaStore accountMetaStore;
    private BalanceTypeConfigStore configStore;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("raft-snap-test-");
        rocks = new RocksDBManager(tempDir.resolve("db").toString());
        rocks.open();
        balanceStore = new BalanceStore();
        accountMetaStore = new AccountMetaStore();
        configStore = new BalanceTypeConfigStore();
    }

    @AfterEach
    void tearDown() {
        rocks.close();
        try {
            Files.walk(tempDir).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        } catch (Exception ignored) {}
    }

    private LedgerStateMachine newSm(RocksDBManager db) {
        LedgerStateMachine sm = new LedgerStateMachine(balanceStore, accountMetaStore, configStore);
        sm.setRocksDB(db);
        return sm;
    }

    private void seed(LedgerStateMachine sm, int postings) {
        configStore.put("AVAILABLE_BALANCE", new BalanceTypeConfig(
                "AVAILABLE_BALANCE", false, null, SignConvention.NORMAL_CREDIT, 1));
        accountMetaStore.put("CLIENT", new Account(
                "CLIENT", AccountType.COMPANY, "Client", "CUST", AccountStatus.ACTIVE, null, Instant.now()));
        accountMetaStore.put("SUSPENSE", new Account(
                "SUSPENSE", AccountType.SUSPENSE, "Suspense", "INTERNAL", AccountStatus.ACTIVE, null, Instant.now()));
        balanceStore.put(new AccountBalanceKey("CLIENT", "AVAILABLE_BALANCE", "CURRENT", "USD"),
                new BalanceEntry(new BigDecimal("1000000.00"), 0, 1, "JNL-INIT", Instant.now()));
        for (int i = 0; i < postings; i++) {
            sm.applyPosting(new PostingCommand("req-" + i, "TEST", "ref", LocalDate.now(),
                    List.of(new PostingCommand.Leg("leg-" + i, "TEST", new BigDecimal("1.00"), "USD", List.of(
                            new PostingCommand.Line("CLIENT", "AVAILABLE_BALANCE", "CURRENT", EntryType.DEBIT, "d"),
                            new PostingCommand.Line("SUSPENSE", "AVAILABLE_BALANCE", "CURRENT", EntryType.CREDIT, "c"))))));
        }
    }

    @Test
    @DisplayName("TC-RAFT-30 onSnapshotSave produces restorable state + journals")
    void onSnapshotSave_producesRestorableState() throws Exception {
        LedgerStateMachine sm = newSm(rocks);
        seed(sm, 20);
        long journalsBefore = sm.getJournalSequence();
        BigDecimal clientBefore = balanceStore.get(
                new AccountBalanceKey("CLIENT", "AVAILABLE_BALANCE", "CURRENT", "USD")).orElseThrow().amount();

        LedgerRaftStateMachine raftSm = new LedgerRaftStateMachine(sm);
        Path snapDir = Files.createDirectory(tempDir.resolve("snap1"));
        StubSnapshotWriter writer = new StubSnapshotWriter(snapDir.toString());
        AtomicReference<Status> status = new AtomicReference<>();
        CountDownLatch saved = new CountDownLatch(1);
        // Save is async (offloaded off the FSM thread) — wait for the done callback.
        raftSm.onSnapshotSave(writer, s -> { status.set(s); saved.countDown(); });
        assertThat(saved.await(10, TimeUnit.SECONDS)).as("save completed").isTrue();

        assertThat(status.get().isOk()).isTrue();
        // Checkpoint format: the state blob + cp_-prefixed hardlinked RocksDB files
        assertThat(writer.files).contains("state_machine_snapshot");
        assertThat(writer.files.stream().anyMatch(f -> f.startsWith("cp_"))).as("checkpoint files registered").isTrue();
        assertThat(Files.exists(snapDir.resolve("state_machine_snapshot"))).isTrue();
        assertThat(Files.exists(snapDir.resolve("journals.dat"))).as("journal stream replaced by checkpoint").isFalse();

        // Restore into a fresh state machine (fresh stores + fresh RocksDB) and verify —
        // same steps as LedgerRaftStateMachine.onSnapshotLoad's checkpoint branch.
        Path restoreDir = Files.createDirectory(tempDir.resolve("cp_restore"));
        try (var files = Files.list(snapDir)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                String name = p.getFileName().toString();
                if (name.startsWith("cp_")) Files.createLink(restoreDir.resolve(name.substring(3)), p);
            }
        }
        RocksDBManager rocks2 = new RocksDBManager(tempDir.resolve("db2").toString());
        rocks2.open();
        try {
            rocks2.restoreFromCheckpoint(restoreDir);
            balanceStore = new BalanceStore();
            accountMetaStore = new AccountMetaStore();
            configStore = new BalanceTypeConfigStore();
            LedgerStateMachine sm2 = newSm(rocks2);
            sm2.restoreFromBytes(Files.readAllBytes(snapDir.resolve("state_machine_snapshot")));
            assertThat(sm2.getJournalSequence()).isEqualTo(journalsBefore);
            assertThat(balanceStore.get(new AccountBalanceKey("CLIENT", "AVAILABLE_BALANCE", "CURRENT", "USD"))
                    .orElseThrow().amount()).isEqualByComparingTo(clientBefore);
            // the checkpoint carried all 20 journals into the restored journal CF
            int[] count = {0};
            rocks2.forEach("journal", (k, v) -> count[0]++);
            assertThat(count[0]).isEqualTo(20);
        } finally {
            rocks2.close();
        }
    }

    @Test
    @DisplayName("TC-RAFT-31 onSnapshotSave returns before the journal stream finishes (FSM thread not blocked)")
    void onSnapshotSave_offloadsHeavyWorkOffCallingThread() throws Exception {
        CountDownLatch streaming = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // SM whose checkpoint blocks, simulating a slow flush+checkpoint.
        LedgerStateMachine sm = new LedgerStateMachine(balanceStore, accountMetaStore, configStore) {
            @Override
            public void checkpointTo(java.nio.file.Path dir) throws Exception {
                streaming.countDown();          // signal: background save reached the heavy step
                release.await(5, TimeUnit.SECONDS);
                super.checkpointTo(dir);
            }
        };
        sm.setRocksDB(rocks);
        seed(sm, 5);

        LedgerRaftStateMachine raftSm = new LedgerRaftStateMachine(sm);
        Path snapDir = Files.createDirectory(tempDir.resolve("snap2"));
        AtomicReference<Status> status = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        // onSnapshotSave runs on the FSM apply thread in production. It MUST return before
        // the heavy journal stream completes, otherwise apply is frozen for the stream's
        // duration (the 240m-soak stall). Calling it directly here models the FSM thread.
        raftSm.onSnapshotSave(new StubSnapshotWriter(snapDir.toString()),
                s -> { status.set(s); done.countDown(); });

        // Heavy stream is now running (on a background thread) and still blocked...
        assertThat(streaming.await(5, TimeUnit.SECONDS)).as("stream started off-thread").isTrue();
        // ...yet onSnapshotSave already returned and done has NOT fired → caller not blocked.
        assertThat(done.getCount()).as("save not yet complete while stream blocked").isEqualTo(1);

        release.countDown();
        assertThat(done.await(5, TimeUnit.SECONDS)).as("save completes after stream").isTrue();
        assertThat(status.get().isOk()).isTrue();
    }

    /** Minimal in-memory SnapshotWriter over a directory — records added file names. */
    private static final class StubSnapshotWriter extends SnapshotWriter {
        private final String path;
        final Set<String> files = new HashSet<>();
        StubSnapshotWriter(String path) { this.path = path; }
        @Override public boolean init(Void opts) { return true; }
        @Override public void shutdown() {}
        @Override public String getPath() { return path; }
        @Override public Set<String> listFiles() { return files; }
        @Override public com.google.protobuf.Message getFileMeta(String fileName) { return null; }
        @Override public boolean saveMeta(RaftOutter.SnapshotMeta meta) { return true; }
        @Override public boolean addFile(String fileName, com.google.protobuf.Message fileMeta) { return files.add(fileName); }
        @Override public boolean removeFile(String fileName) { return files.remove(fileName); }
        @Override public void close() {}
        @Override public void close(boolean keepDataOnError) {}
    }
}
