package com.tomma8.ledger.raft;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tomma8.ledger.domain.command.*;
import com.tomma8.ledger.util.LedgerMappers;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public final class CommandSerializer {

    private static final ObjectMapper mapper = LedgerMappers.get();

    /** Leader-stamped apply time (epoch millis) is prepended as an 8-byte big-endian
     *  header ahead of the JSON body. Replicating it inside the log entry makes the
     *  apply timestamp identical on every node — without this each replica would call
     *  Instant.now() during apply and diverge. The header lives only on the outer
     *  Raft-entry payload; the JSON body (incl. nested batch commands) is unchanged. */
    public static final int APPLY_TIME_HEADER = Long.BYTES;

    public static byte[] serialize(RaftCommand command) {
        try {
            return mapper.writeValueAsBytes(command);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize command", e);
        }
    }

    /** Serialize with the leader-stamped apply-time header for the Raft wire. */
    public static byte[] serialize(RaftCommand command, long applyTimeMillis) {
        byte[] body = serialize(command);
        return ByteBuffer.allocate(APPLY_TIME_HEADER + body.length)
                .putLong(applyTimeMillis)
                .put(body)
                .array();
    }

    /** Read the leader-stamped apply time (epoch millis) from the header. */
    public static long readApplyTimeMillis(byte[] data, int totalLength) {
        if (totalLength < APPLY_TIME_HEADER) {
            throw new IllegalArgumentException("Payload too short for apply-time header");
        }
        return ByteBuffer.wrap(data, 0, APPLY_TIME_HEADER).getLong();
    }

    public static RaftCommand deserialize(byte[] data) {
        return deserialize(data, 0, data.length);
    }

    public static RaftCommand deserialize(byte[] data, int length) {
        return deserialize(data, 0, length);
    }

    /** Parse a command from {@code data[offset, offset+length)}. The framed Raft path
     *  passes {@code offset = APPLY_TIME_HEADER} to skip the apply-time header; nested
     *  batch sub-commands recurse with {@code offset = 0} (no header). */
    public static RaftCommand deserialize(byte[] data, int offset, int length) {
        try {
            String json = new String(data, offset, length, StandardCharsets.UTF_8);
            if (json.contains("\"commands\"")) {
                var root = mapper.readTree(data, offset, length);
                var commandsNode = root.get("commands");
                if (commandsNode == null || !commandsNode.isArray()) {
                    throw new IllegalArgumentException("Invalid batch command: missing commands array");
                }
                java.util.List<RaftCommand> commands = new java.util.ArrayList<>(commandsNode.size());
                for (var node : commandsNode) {
                    byte[] cmdBytes = mapper.writeValueAsBytes(node);
                    commands.add(deserialize(cmdBytes, 0, cmdBytes.length));
                }
                String requestId = root.hasNonNull("requestId") ? root.get("requestId").asText() : null;
                return new BatchRaftCommand(requestId, commands);
            }
            // Heuristic detection based on presence of distinctive fields
            if (json.contains("\"adjustmentType\"")) {
                return mapper.readValue(data, offset, length, AdjustmentCommand.class);
            }
            if (json.contains("\"legs\"")) {
                return mapper.readValue(data, offset, length, PostingCommand.class);
            }
            if (json.contains("\"originalJournalId\"")) {
                return mapper.readValue(data, offset, length, ReversalCommand.class);
            }
            if (json.contains("\"accountType\"") && json.contains("\"balanceInitializations\"")) {
                return mapper.readValue(data, offset, length, AccountCreateCommand.class);
            }
            if (json.contains("\"freeze\"")) {
                return mapper.readValue(data, offset, length, AccountFreezeCommand.class);
            }
            if (json.contains("\"businessEventRef\"") && !json.contains("\"legs\"")) {
                // ReversalCommand has originalJournalId; this catches other cases
            }
            if (json.contains("\"balanceType\"") && json.contains("\"currency\"") && json.contains("\"accountId\"")) {
                // Could be AccountAddBalanceTypeCommand or other - check requestId presence
                if (json.contains("\"requestId\"")) {
                    return mapper.readValue(data, offset, length, AccountAddBalanceTypeCommand.class);
                }
            }
            if (json.contains("\"requestId\"") && json.contains("\"accountId\"") && !json.contains("\"balanceType\"")) {
                return mapper.readValue(data, offset, length, AccountCloseCommand.class);
            }
            throw new IllegalArgumentException("Unknown command type: " + json.substring(0, Math.min(100, json.length())));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize command", e);
        }
    }
}
