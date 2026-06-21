package com.tomma8.ledger.raft;

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
 * ahead of the JSON body. The header replicates inside the Raft log entry so every
 * node applies with the same timestamp (root-cause fix for cross-node divergence).
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
        PostingCommand cmd = samplePosting();

        byte[] bytes = CommandSerializer.serialize(cmd, applyTime);

        assertThat(CommandSerializer.extractApplyTimeMillis(bytes, bytes.length))
                .isEqualTo(applyTime);

        RaftCommand decoded = CommandSerializer.deserialize(bytes);
        assertThat(decoded).isInstanceOf(PostingCommand.class);
        assertThat(decoded.requestId()).isEqualTo("req-1");
        assertThat(((PostingCommand) decoded).legs()).hasSize(1);
    }

    @Test
    void header_isEightBytesAheadOfJsonBody() {
        byte[] bytes = CommandSerializer.serialize(samplePosting(), 0L);
        // First 8 bytes are the big-endian apply-time header; JSON body starts at offset 8.
        String body = new String(bytes, 8, bytes.length - 8, java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).startsWith("{").contains("\"legs\"");
    }

    @Test
    void differentCommandTypes_deserializeCorrectly() {
        var reversal = new ReversalCommand("req-2", "JNL-0000000000000001",
                "wrong amount", "ERR-01", LocalDate.of(2026, 6, 21));
        byte[] bytes = CommandSerializer.serialize(reversal, 42L);

        assertThat(CommandSerializer.extractApplyTimeMillis(bytes, bytes.length)).isEqualTo(42L);
        RaftCommand decoded = CommandSerializer.deserialize(bytes);
        assertThat(decoded).isInstanceOf(ReversalCommand.class);
        assertThat(((ReversalCommand) decoded).originalJournalId()).isEqualTo("JNL-0000000000000001");
    }
}
