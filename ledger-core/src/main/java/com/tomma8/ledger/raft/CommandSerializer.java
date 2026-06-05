package com.tomma8.ledger.raft;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tomma8.ledger.domain.command.*;
import com.tomma8.ledger.util.LedgerMappers;

import java.nio.charset.StandardCharsets;

public final class CommandSerializer {

    private static final ObjectMapper mapper = LedgerMappers.get();

    public static byte[] serialize(RaftCommand command) {
        try {
            return mapper.writeValueAsBytes(command);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize command", e);
        }
    }

    public static RaftCommand deserialize(byte[] data) {
        return deserialize(data, data.length);
    }

    public static RaftCommand deserialize(byte[] data, int length) {
        try {
            String json = new String(data, 0, length, StandardCharsets.UTF_8);
            // Heuristic detection based on presence of distinctive fields
            if (json.contains("\"adjustmentType\"")) {
                return mapper.readValue(data, AdjustmentCommand.class);
            }
            if (json.contains("\"legs\"")) {
                return mapper.readValue(data, PostingCommand.class);
            }
            if (json.contains("\"originalJournalId\"")) {
                return mapper.readValue(data, ReversalCommand.class);
            }
            if (json.contains("\"accountType\"") && json.contains("\"balanceInitializations\"")) {
                return mapper.readValue(data, AccountCreateCommand.class);
            }
            if (json.contains("\"freeze\"")) {
                return mapper.readValue(data, AccountFreezeCommand.class);
            }
            if (json.contains("\"businessEventRef\"") && !json.contains("\"legs\"")) {
                // ReversalCommand has originalJournalId; this catches other cases
            }
            if (json.contains("\"balanceType\"") && json.contains("\"currency\"") && json.contains("\"accountId\"")) {
                // Could be AccountAddBalanceTypeCommand or other - check requestId presence
                if (json.contains("\"requestId\"")) {
                    return mapper.readValue(data, AccountAddBalanceTypeCommand.class);
                }
            }
            if (json.contains("\"requestId\"") && json.contains("\"accountId\"") && !json.contains("\"balanceType\"")) {
                return mapper.readValue(data, AccountCloseCommand.class);
            }
            throw new IllegalArgumentException("Unknown command type: " + json.substring(0, Math.min(100, json.length())));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize command", e);
        }
    }
}
