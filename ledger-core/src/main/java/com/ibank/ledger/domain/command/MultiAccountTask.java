package com.ibank.ledger.domain.command;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Multi-account coordination task (ADR-001 v0.2 Section 3.3).
 *
 * Coordinates execution of a single RaftCommand across multiple account queues.
 * Uses Queue-as-Lock model — no explicit locks, serialization via LinkedBlockingQueue.
 * Deadlock prevention: accounts sorted by accountId ascending (Resource Ordering).
 * Leader election: the LAST account in the sorted list submits the RaftCommand.
 */
public class MultiAccountTask {

    private final String requestId;
    private final RaftCommand command;
    private final String leaderAccountId;
    private final int totalAccounts;
    private final CountDownLatch readyLatch;
    private final CountDownLatch resultLatch;
    private final AtomicInteger readyCount;
    private volatile boolean cancelled;
    private volatile CommandResult result;

    public MultiAccountTask(String requestId, RaftCommand command, List<String> sortedAccountIds) {
        if (sortedAccountIds == null || sortedAccountIds.isEmpty()) {
            throw new IllegalArgumentException("sortedAccountIds must not be empty");
        }
        this.requestId = requestId;
        this.command = command;
        this.leaderAccountId = sortedAccountIds.get(sortedAccountIds.size() - 1);
        this.totalAccounts = sortedAccountIds.size();
        this.readyLatch = new CountDownLatch(totalAccounts);
        this.resultLatch = new CountDownLatch(1);
        this.readyCount = new AtomicInteger(0);
    }

    public boolean markReadyAndCheckLeader(String myAccountId) {
        readyCount.incrementAndGet();
        readyLatch.countDown();
        return myAccountId.equals(leaderAccountId);
    }

    public void awaitReady(long timeoutMs) throws InterruptedException, TimeoutException {
        if (!readyLatch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw new TimeoutException("Timeout waiting for all accounts to be ready: " + requestId);
        }
    }

    public void setResult(CommandResult r) {
        this.result = r;
        resultLatch.countDown();
    }

    public CommandResult getResult(long timeoutMs) throws InterruptedException, TimeoutException {
        if (!resultLatch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw new TimeoutException("Timeout waiting for Raft result: " + requestId);
        }
        return result;
    }

    public void cancel() {
        this.cancelled = true;
        while (readyLatch.getCount() > 0) {
            readyLatch.countDown();
        }
        while (resultLatch.getCount() > 0) {
            resultLatch.countDown();
        }
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public String requestId() { return requestId; }
    public RaftCommand command() { return command; }
    public String leaderAccountId() { return leaderAccountId; }
    public int totalAccounts() { return totalAccounts; }
    public int readyCount() { return readyCount.get(); }
}
