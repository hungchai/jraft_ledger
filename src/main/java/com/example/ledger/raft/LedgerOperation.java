package com.example.ledger.raft;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class LedgerOperation {
    
    private final OperationType type;
    private final Object data;
    private final long timestamp;
    
    @JsonCreator
    public LedgerOperation(@JsonProperty("type") OperationType type,
                          @JsonProperty("data") Object data,
                          @JsonProperty("timestamp") long timestamp) {
        this.type = type;
        this.data = data;
        this.timestamp = timestamp;
    }
    
    public enum OperationType {
        TRANSFER,
        BATCH_TRANSFER,
        CREATE_ACCOUNT,
        UPDATE_ACCOUNT
    }
} 