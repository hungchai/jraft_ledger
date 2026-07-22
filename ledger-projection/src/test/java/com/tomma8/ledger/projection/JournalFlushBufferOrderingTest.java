package com.tomma8.ledger.projection;

import com.tomma8.ledger.dao.mapper.JournalMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TDD test for deadlock-safe journal_line batch ordering.
 *
 * Verifies rows are sorted by (accountAccountId, journalLineId) before insert
 * so concurrent transactions acquire InnoDB gap locks in a consistent order
 * across all projection instances.
 */
class JournalFlushBufferOrderingTest {

    private static ProjectionWriter.BalanceEvent event(String accountId, String journalLineId) {
        return new ProjectionWriter.BalanceEvent(
                "evt-" + journalLineId, "JNL-001", journalLineId, "req-001",
                "POSTING", accountId, "AVAILABLE_BALANCE", "CURRENT", "USD",
                "CREDIT", BigDecimal.TEN, BigDecimal.TEN, 1L, "ref-001",
                java.time.LocalDate.now(), 1, BigDecimal.ZERO);
    }

    @Test
    @DisplayName("TC-PROJ-DEADLOCK-01: journal_line batch rows sorted by account then line id")
    void journalLineRowsAreSortedBeforeInsert() {
        var now = LocalDateTime.now();
        List<JournalFlushBuffer.PendingRow> rows = List.of(
                new JournalFlushBuffer.PendingRow(0, 1L, 10L, 100L,
                        event("ACC-Z", "JL-002"), now),
                new JournalFlushBuffer.PendingRow(0, 2L, 10L, 100L,
                        event("ACC-A", "JL-003"), now),
                new JournalFlushBuffer.PendingRow(0, 3L, 10L, 100L,
                        event("ACC-A", "JL-001"), now),
                new JournalFlushBuffer.PendingRow(0, 4L, 10L, 100L,
                        event("ACC-Z", "JL-001"), now));

        List<JournalMapper.JournalLineBatchRow> sorted = JournalFlushBuffer.toJournalLineRows(rows);

        assertThat(sorted)
                .map(r -> r.accountAccountId() + "|" + r.journalLineId())
                .containsExactly(
                        "ACC-A|JL-001",
                        "ACC-A|JL-003",
                        "ACC-Z|JL-001",
                        "ACC-Z|JL-002");
    }
}
