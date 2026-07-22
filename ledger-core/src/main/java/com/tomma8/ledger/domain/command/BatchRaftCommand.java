package com.tomma8.ledger.domain.command;

import java.util.List;
import java.util.Objects;

/**
 * Envelope for multiple independent commands committed in a single Raft log entry.
 * Amortizes one quorum round-trip and one RocksDB fsync across N commands.
 */
public record BatchRaftCommand(String requestId, List<RaftCommand> commands) implements RaftCommand {

    public BatchRaftCommand {
        Objects.requireNonNull(commands, "commands");
        if (commands.isEmpty()) {
            throw new IllegalArgumentException("commands must not be empty");
        }
        if (requestId == null || requestId.isBlank()) {
            requestId = "batch-" + commands.get(0).requestId() + "-" + commands.size();
        }
        commands = List.copyOf(commands);
    }

    public static BatchRaftCommand of(List<RaftCommand> commands) {
        return new BatchRaftCommand(null, commands);
    }
}
