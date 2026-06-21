package com.tomma8.ledger.raft;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tomma8.ledger.domain.command.*;
import com.tomma8.ledger.util.LedgerMappers;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public final class CommandSerializer {

    private static final ObjectMapper mapper = LedgerMappers.get();

    /** Leader-stamped wall-clock time (epoch millis) is prepended as an 8-byte
     *  big-endian header ahead of the JSON body. Replicating it inside the log
     *  entry makes the apply timestamp identical on every node — without this,
     *  each replica would call Instant.now() during apply and diverge. */
    private static final int APPLY_TIME_HEADER = Long.BYTES;

    public static byte[] serialize(RaftCommand command, long applyTimeMillis) {
        try {
            byte[] body = mapper.writeValueAsBytes(command);
            return ByteBuffer.allocate(APPLY_TIME_HEADER + body.length)
                    .putLong(applyTimeMillis)
                    .put(body)
                    .array();
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize command", e);
        }
    }

    /** Extracts the leader-stamped apply time (epoch millis) from the header. */
    public static long extractApplyTimeMillis(byte[] data, int length) {
        if (length < APPLY_TIME_HEADER) {
            throw new IllegalArgumentException("Command payload too short for apply-time header");
        }
        return ByteBuffer.wrap(data, 0, APPLY_TIME_HEADER).getLong();
    }

    public static RaftCommand deserialize(byte[] data) {
        return deserialize(data, data.length);
    }

    public static RaftCommand deserialize(byte[] data, int length) {
        try {
            int off = APPLY_TIME_HEADER;
            int bodyLen = length - off;
            String json = new String(data, off, bodyLen, StandardCharsets.UTF_8);
            // Heuristic detection based on presence of distinctive fields
            if (json.contains("\"adjustmentType\"")) {
                return mapper.readValue(data, off, bodyLen,AdjustmentCommand.class);
            }
            if (json.contains("\"legs\"")) {
                return mapper.readValue(data, off, bodyLen,PostingCommand.class);
            }
            if (json.contains("\"originalJournalId\"")) {
                return mapper.readValue(data, off, bodyLen,ReversalCommand.class);
            }
            if (json.contains("\"accountType\"") && json.contains("\"balanceInitializations\"")) {
                return mapper.readValue(data, off, bodyLen,AccountCreateCommand.class);
            }
            if (json.contains("\"freeze\"")) {
                return mapper.readValue(data, off, bodyLen,AccountFreezeCommand.class);
            }
            if (json.contains("\"businessEventRef\"") && !json.contains("\"legs\"")) {
                // ReversalCommand has originalJournalId; this catches other cases
            }
            if (json.contains("\"balanceType\"") && json.contains("\"currency\"") && json.contains("\"accountId\"")) {
                // Could be AccountAddBalanceTypeCommand or other - check requestId presence
                if (json.contains("\"requestId\"")) {
                    return mapper.readValue(data, off, bodyLen,AccountAddBalanceTypeCommand.class);
                }
            }
            if (json.contains("\"requestId\"") && json.contains("\"accountId\"") && !json.contains("\"balanceType\"")) {
                return mapper.readValue(data, off, bodyLen,AccountCloseCommand.class);
            }
            throw new IllegalArgumentException("Unknown command type: " + json.substring(0, Math.min(100, json.length())));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize command", e);
        }
    }
}
