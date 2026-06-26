package com.tomma8.ledger.statemachine;

import com.tomma8.ledger.domain.command.CommandResult;
import com.tomma8.ledger.domain.command.PostingCommand;
import com.tomma8.ledger.domain.model.*;
import com.tomma8.ledger.domain.command.RaftApplyContext;
import com.tomma8.ledger.rocksdb.ColumnFamilyRegistry;
import com.tomma8.ledger.rocksdb.RocksDBManager;
import com.tomma8.ledger.store.AccountMetaStore;
import com.tomma8.ledger.store.BalanceStore;
import com.tomma8.ledger.store.BalanceTypeConfigStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hotspot batch-coalescing equivalence: applying N postings that all hit the same hotspot account
 * through ONE coalesced WriteBatch (beginBatch → applyPosting×N → commitBatch) must produce
 * byte-for-byte identical persisted state vs applying them one at a time (N separate writes).
 *
 * Proves the optimization is correctness-preserving: only the redundant intermediate balance-row
 * persists are skipped; journals, journal-lines (the before/after audit), final balance rows,
 * accountSeq, and idempotency entries are all identical.
 */
@DisplayName("Hotspot batch-coalescing — single vs batched apply equivalence")
class HotspotBatchCoalescingTest {

    @TempDir Path dirSingle;
    @TempDir Path dirBatch;
    private RocksDBManager rocksSingle;
    private RocksDBManager rocksBatch;

    private static final String HOTSPOT = "COMPANY_HOTSPOT";
    private static final String COUNTER = "COMPANY_COUNTER";
    private static final int N = 16;   // a full command-queue batch

    @AfterEach
    void tearDown() {
        if (rocksSingle != null) rocksSingle.close();
        if (rocksBatch != null) rocksBatch.close();
    }

    private LedgerStateMachine newSm(RocksDBManager rocks) {
        BalanceStore balanceStore = new BalanceStore();
        AccountMetaStore accountMetaStore = new AccountMetaStore();
        BalanceTypeConfigStore configStore = new BalanceTypeConfigStore();
        LedgerStateMachine sm = new LedgerStateMachine(balanceStore, accountMetaStore, configStore);
        sm.setRocksDB(rocks);
        configStore.put("AVAILABLE_BALANCE", new BalanceTypeConfig(
                "AVAILABLE_BALANCE", false, null, SignConvention.NORMAL_CREDIT, 1));
        accountMetaStore.put(HOTSPOT, new Account(HOTSPOT, AccountType.COMPANY, "Hotspot",
                null, AccountStatus.ACTIVE, null, Instant.now()));
        accountMetaStore.put(COUNTER, new Account(COUNTER, AccountType.COMPANY, "Counter",
                null, AccountStatus.ACTIVE, null, Instant.now()));
        balanceStore.put(new AccountBalanceKey(HOTSPOT, "AVAILABLE_BALANCE", "CURRENT", "USD"),
                new BalanceEntry(new BigDecimal("1000000.00"), 0, 1, "", Instant.now()));
        balanceStore.put(new AccountBalanceKey(COUNTER, "AVAILABLE_BALANCE", "CURRENT", "USD"),
                new BalanceEntry(new BigDecimal("1000000.00"), 0, 1, "", Instant.now()));
        return sm;
    }

    // posting i: DEBIT counter, CREDIT hotspot (both touch the hotspot every time)
    private PostingCommand posting(int i) {
        return new PostingCommand(
                "req-" + i, "RFQ_SETTLEMENT", "ref-" + i, LocalDate.of(2026, 6, 26),
                List.of(new PostingCommand.Leg("leg-" + i, "RFQ", new BigDecimal("10.00"), "USD", List.of(
                        new PostingCommand.Line(COUNTER, "AVAILABLE_BALANCE", "CURRENT", EntryType.DEBIT, "d"),
                        new PostingCommand.Line(HOTSPOT, "AVAILABLE_BALANCE", "CURRENT", EntryType.CREDIT, "c")
                )))
        );
    }

    @Test
    @DisplayName("batched apply produces identical persisted state as single applies")
    void batchEquivalentToSingle() throws Exception {
        rocksSingle = new RocksDBManager(dirSingle.toString()); rocksSingle.open();
        rocksBatch = new RocksDBManager(dirBatch.toString()); rocksBatch.open();
        LedgerStateMachine smSingle = newSm(rocksSingle);
        LedgerStateMachine smBatch = newSm(rocksBatch);

        long raftIndex = 100L;
        long applyTime = Instant.parse("2026-06-26T00:00:00Z").toEpochMilli();

        // SINGLE: N separate applies (each its own WriteBatch + write)
        List<CommandResult> single = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            single.add(smSingle.applyPosting(posting(i), RaftApplyContext.batchEntry(raftIndex, i, applyTime)));
        }

        // BATCH: same N applies, coalesced into one WriteBatch
        smBatch.beginBatch();
        List<CommandResult> batched = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            batched.add(smBatch.applyPosting(posting(i), RaftApplyContext.batchEntry(raftIndex, i, applyTime)));
        }
        smBatch.commitBatch();

        // 1. all commands completed, same journalIds (same ctx → deterministic ids)
        for (int i = 0; i < N; i++) {
            assertThat(single.get(i).isCompleted()).isTrue();
            assertThat(batched.get(i).isCompleted()).isTrue();
            assertThat(batched.get(i).journalId()).isEqualTo(single.get(i).journalId());
        }

        // 2. final persisted balance rows byte-identical (hotspot + counter)
        for (String acc : List.of(HOTSPOT, COUNTER)) {
            byte[] keyB = (acc + "#AVAILABLE_BALANCE#CURRENT#USD").getBytes(StandardCharsets.UTF_8);
            byte[] s = rocksSingle.get(ColumnFamilyRegistry.CF_BALANCE, keyB);
            byte[] b = rocksBatch.get(ColumnFamilyRegistry.CF_BALANCE, keyB);
            assertThat(b).as("balance row %s", acc).isEqualTo(s).isNotNull();
        }

        // 3. every journal + journal_line + idempotency byte-identical across the two DBs
        for (int i = 0; i < N; i++) {
            String jid = single.get(i).journalId();
            byte[] jKey = jid.getBytes(StandardCharsets.UTF_8);
            assertThat(rocksBatch.get(ColumnFamilyRegistry.CF_JOURNAL, jKey))
                    .as("journal %s", jid)
                    .isEqualTo(rocksSingle.get(ColumnFamilyRegistry.CF_JOURNAL, jKey)).isNotNull();
            for (int ln = 1; ln <= 2; ln++) {
                byte[] lKey = (jid + "#" + jid + "-" + String.format("%02d", ln)).getBytes(StandardCharsets.UTF_8);
                assertThat(rocksBatch.get(ColumnFamilyRegistry.CF_JOURNAL_LINE, lKey))
                        .as("journal_line %s-%02d (before/after audit)", jid, ln)
                        .isEqualTo(rocksSingle.get(ColumnFamilyRegistry.CF_JOURNAL_LINE, lKey)).isNotNull();
            }
            byte[] idKey = ("req-" + i).getBytes(StandardCharsets.UTF_8);
            assertThat(rocksBatch.get(ColumnFamilyRegistry.CF_IDEMPOTENCY, idKey))
                    .as("idempotency req-%d", i)
                    .isEqualTo(rocksSingle.get(ColumnFamilyRegistry.CF_IDEMPOTENCY, idKey)).isNotNull();
        }

        // 4. in-memory final balances match (hotspot credited N×10 from seed)
        AccountBalanceKey hk = new AccountBalanceKey(HOTSPOT, "AVAILABLE_BALANCE", "CURRENT", "USD");
        BigDecimal expectHotspot = new BigDecimal("1000000.00").add(new BigDecimal(N * 10));
        assertThat(smSingle.getBalanceStore().getOrThrow(hk).amount()).isEqualByComparingTo(expectHotspot);
        assertThat(smBatch.getBalanceStore().getOrThrow(hk).amount()).isEqualByComparingTo(expectHotspot);
        // accountSeq advanced once per command in both modes
        assertThat(smBatch.getBalanceStore().getOrThrow(hk).accountSeq())
                .isEqualTo(smSingle.getBalanceStore().getOrThrow(hk).accountSeq());
    }
}
