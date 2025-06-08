package com.example.ledger.raft;

import com.alipay.sofa.jraft.Closure;
import com.alipay.sofa.jraft.Status;
import lombok.Data;

import java.util.concurrent.CompletableFuture;

@Data
public class LedgerClosure implements Closure {
    
    private final LedgerOperation operation;
    private final CompletableFuture<Boolean> future;
    private boolean result;
    
    public LedgerClosure(LedgerOperation operation, CompletableFuture<Boolean> future) {
        this.operation = operation;
        this.future = future;
    }
    
    @Override
    public void run(Status status) {
        if (status.isOk()) {
            future.complete(result);
        } else {
            future.completeExceptionally(new RuntimeException("Raft operation failed: " + status.getErrorMsg()));
        }
    }
} 