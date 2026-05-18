package com.ibank.ledger.raft;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ibank.ledger.domain.command.PostingCommand;
import com.ibank.ledger.domain.command.RaftCommand;
import com.ibank.ledger.domain.command.ReversalCommand;

import java.nio.charset.StandardCharsets;

public final class CommandSerializer {

    private static final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    public static byte[] serialize(RaftCommand command) {
        try {
            return mapper.writeValueAsBytes(command);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize command", e);
        }
    }

    public static RaftCommand deserialize(byte[] data) {
        try {
            String json = new String(data, StandardCharsets.UTF_8);
            if (json.contains("\"legs\"")) {
                return mapper.readValue(data, PostingCommand.class);
            }
            if (json.contains("\"originalJournalId\"")) {
                return mapper.readValue(data, ReversalCommand.class);
            }
            throw new IllegalArgumentException("Unknown command type: " + json.substring(0, Math.min(100, json.length())));
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize command", e);
        }
    }
}
