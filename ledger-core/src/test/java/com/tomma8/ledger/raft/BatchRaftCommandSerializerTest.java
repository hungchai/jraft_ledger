package com.tomma8.ledger.raft;

import com.tomma8.ledger.domain.command.BatchRaftCommand;
import com.tomma8.ledger.domain.command.PostingCommand;
import com.tomma8.ledger.domain.command.RaftApplyContext;
import com.tomma8.ledger.domain.model.EntryType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Command serializer batch support")
class BatchRaftCommandSerializerTest {

    @Test
    @DisplayName("BatchRaftCommand round-trips through JSON serializer")
    void batchRoundTrip() {
        PostingCommand p1 = posting("req-1");
        PostingCommand p2 = posting("req-2");
        BatchRaftCommand batch = BatchRaftCommand.of(List.of(p1, p2));

        byte[] bytes = CommandSerializer.serialize(batch);
        var restored = CommandSerializer.deserialize(bytes, bytes.length);

        assertThat(restored).isInstanceOf(BatchRaftCommand.class);
        BatchRaftCommand b = (BatchRaftCommand) restored;
        assertThat(b.commands()).hasSize(2);
        assertThat(b.commands().get(0).requestId()).isEqualTo("req-1");
        assertThat(b.commands().get(1).requestId()).isEqualTo("req-2");
    }

    @Test
    @DisplayName("RaftApplyContext batch journal IDs are unique within one log index")
    void batchJournalIds_unique() {
        var a = RaftApplyContext.batchEntry(42, 0);
        var b = RaftApplyContext.batchEntry(42, 1);
        assertThat(a.journalId(0)).isEqualTo("JNL-0000000000000042-0000");
        assertThat(b.journalId(0)).isEqualTo("JNL-0000000000000042-0001");
        assertThat(a.logicalIndex()).isLessThan(b.logicalIndex());
    }

    private static PostingCommand posting(String requestId) {
        return new PostingCommand(
                requestId, "TEST", "test", LocalDate.now(),
                List.of(new PostingCommand.Leg("leg", "TEST", BigDecimal.ONE, "USD", List.of(
                        new PostingCommand.Line("ACC", "AVAILABLE_BALANCE", "CURRENT", EntryType.DEBIT, "")
                )))
        );
    }
}
