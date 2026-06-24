package com.tomma8.ledger.domain.command;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Reply payload when a {@link BatchRaftCommand} is applied (Ratis path). */
public record BatchApplyResult(List<CommandResult> results) {

    public BatchApplyResult {
        Objects.requireNonNull(results, "results");
        results = List.copyOf(results);
    }

    public static BatchApplyResult of(List<CommandResult> results) {
        return new BatchApplyResult(results);
    }

    public Map<String, CommandResult> byRequestId(List<RaftCommand> commands) {
        if (commands.size() != results.size()) {
            throw new IllegalStateException("command/result size mismatch");
        }
        Map<String, CommandResult> map = new LinkedHashMap<>();
        for (int i = 0; i < commands.size(); i++) {
            map.put(commands.get(i).requestId(), results.get(i));
        }
        return map;
    }
}
