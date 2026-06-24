package com.tomma8.ledger.raft;

import com.tomma8.ledger.domain.command.BatchRaftCommand;
import com.tomma8.ledger.domain.command.PostingCommand;
import com.tomma8.ledger.domain.command.RaftCommand;
import com.tomma8.ledger.domain.command.ReversalCommand;
import com.tomma8.ledger.domain.model.EntryType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TC-DET-03: CommandSerializer prepends an 8-byte leader-stamped apply-time header
 * ahead of the JSON body, so the apply timestamp replicates inside the Raft log entry
 * and every node applies with the same time (root-cause fix for cross-node divergence).
 */
class CommandSerializerTest {

    private static PostingCommand samplePosting() {
        var line = new PostingCommand.Line("ACC-1", "AVAILABLE_BALANCE", "CURRENT",
                EntryType.DEBIT, "test");
        var leg = new PostingCommand.Leg("LEG-1", "TRANSFER",
                new BigDecimal("100.00"), "USDT", List.of(line));
        return new PostingCommand("req-1", "TEST", "ref-1",
                LocalDate.of(2026, 6, 21), List.of(leg));
    }

    @Test
    void serialize_roundTripsCommand_andApplyTime() {
        long applyTime = 1_750_000_000_123L;
        byte[] bytes = CommandSerializer.serialize(samplePosting(), applyTime);

        assertThat(CommandSerializer.readApplyTimeMillis(bytes, bytes.length)).isEqualTo(applyTime);

        RaftCommand decoded = CommandSerializer.deserialize(
                bytes, CommandSerializer.APPLY_TIME_HEADER, bytes.length - CommandSerializer.APPLY_TIME_HEADER);
        assertThat(decoded).isInstanceOf(PostingCommand.class);
        assertThat(decoded.requestId()).isEqualTo("req-1");
        assertThat(((PostingCommand) decoded).legs()).hasSize(1);
    }

    @Test
    void header_isEightBytesAheadOfJsonBody() {
        byte[] bytes = CommandSerializer.serialize(samplePosting(), 0L);
        String body = new String(bytes, CommandSerializer.APPLY_TIME_HEADER,
                bytes.length - CommandSerializer.APPLY_TIME_HEADER, java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).startsWith("{").contains("\"legs\"");
    }

    @Test
    void reversalCommand_roundTrips() {
        var reversal = new ReversalCommand("req-2", "JNL-0000000000000001",
                "wrong amount", "ERR-01", LocalDate.of(2026, 6, 21));
        byte[] bytes = CommandSerializer.serialize(reversal, 42L);

        assertThat(CommandSerializer.readApplyTimeMillis(bytes, bytes.length)).isEqualTo(42L);
        RaftCommand decoded = CommandSerializer.deserialize(
                bytes, CommandSerializer.APPLY_TIME_HEADER, bytes.length - CommandSerializer.APPLY_TIME_HEADER);
        assertThat(decoded).isInstanceOf(ReversalCommand.class);
        assertThat(((ReversalCommand) decoded).originalJournalId()).isEqualTo("JNL-0000000000000001");
    }

    @Test
    void batchCommand_roundTrips_withHeader() {
        var batch = BatchRaftCommand.of(List.of(samplePosting(), samplePosting()));
        byte[] bytes = CommandSerializer.serialize(batch, 7L);

        assertThat(CommandSerializer.readApplyTimeMillis(bytes, bytes.length)).isEqualTo(7L);
        RaftCommand decoded = CommandSerializer.deserialize(
                bytes, CommandSerializer.APPLY_TIME_HEADER, bytes.length - CommandSerializer.APPLY_TIME_HEADER);
        assertThat(decoded).isInstanceOf(BatchRaftCommand.class);
        assertThat(((BatchRaftCommand) decoded).commands()).hasSize(2);
    }
}
