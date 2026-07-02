package com.tomma8.ledger.queue;

import com.tomma8.ledger.domain.command.CommandResult;
import com.tomma8.ledger.domain.command.RaftCommand;
import com.tomma8.ledger.raft.ConsensusEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * Global command ingress queue with micro-batching before Raft submit.
 *
 * <p>Many parallel API threads enqueue commands; a single worker drains up to
 * {@code batchSize} commands (or waits {@code batchWaitMs}) and submits them as
 * one {@link com.tomma8.ledger.domain.command.BatchRaftCommand} when possible.
 */
public class CommandQueueManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CommandQueueManager.class);

    private final BlockingQueue<QueuedCommand> queue;
    private final ConsensusEngine consensusEngine;
    private final int batchSize;
    private final long batchWaitMs;
    // Pipelining: cap batches in flight to Raft. Naturally load-adaptive — at low load a batch
    // commits before the next arrives (depth 1, no added latency); under backlog up to N batches
    // pipeline so the worker no longer blocks a full commit cycle per batch (kills queue-wait).
    private final int pipelineDepth;
    private final Semaphore inflight;
    private final ExecutorService worker;
    private volatile boolean running = true;

    public CommandQueueManager(ConsensusEngine consensusEngine, int maxQueueSize, int batchSize, long batchWaitMs) {
        this(consensusEngine, maxQueueSize, batchSize, batchWaitMs, 1, true);
    }

    public CommandQueueManager(ConsensusEngine consensusEngine, int maxQueueSize, int batchSize, long batchWaitMs,
                               int pipelineDepth) {
        this(consensusEngine, maxQueueSize, batchSize, batchWaitMs, pipelineDepth, true);
    }

    // retained for existing tests (pipelineDepth=1)
    CommandQueueManager(ConsensusEngine consensusEngine, int maxQueueSize, int batchSize, long batchWaitMs,
                        boolean startWorker) {
        this(consensusEngine, maxQueueSize, batchSize, batchWaitMs, 1, startWorker);
    }

    CommandQueueManager(ConsensusEngine consensusEngine, int maxQueueSize, int batchSize, long batchWaitMs,
                        int pipelineDepth, boolean startWorker) {
        this.consensusEngine = consensusEngine;
        this.queue = new LinkedBlockingQueue<>(maxQueueSize);
        this.batchSize = Math.max(1, batchSize);
        this.batchWaitMs = Math.max(0, batchWaitMs);
        this.pipelineDepth = Math.max(1, pipelineDepth);
        this.inflight = new Semaphore(this.pipelineDepth);
        this.worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "command-queue-worker");
            t.setDaemon(true);
            return t;
        });
        if (startWorker) {
            this.worker.submit(this::runWorker);
        }
    }

    /**
     * Non-blocking enqueue. Returns false when the queue is full (caller should 429).
     */
    public boolean offer(RaftCommand command) {
        boolean accepted = queue.offer(new QueuedCommand(command, null, System.nanoTime()));
        if (!accepted) {
            log.warn("Command queue full — backpressure triggered for requestId={}", command.requestId());
        }
        return accepted;
    }

    /**
     * Async enqueue — completes when the command is applied through Raft.
     */
    public CompletableFuture<CommandResult> submitAsync(RaftCommand command) {
        CompletableFuture<CommandResult> future = new CompletableFuture<>();
        QueuedCommand task = new QueuedCommand(command, future, System.nanoTime());
        boolean accepted = queue.offer(task);
        if (!accepted) {
            future.completeExceptionally(new RejectedExecutionException(
                    "Command queue full for requestId: " + command.requestId()));
        }
        return future;
    }

    public int getQueueDepth() {
        return queue.size();
    }

    private void runWorker() {
        while (running) {
            try {
                List<QueuedCommand> batch = drainBatch();
                if (batch.isEmpty()) {
                    continue;
                }
                inflight.acquire();          // cap batches in flight (pipelineDepth); backpressure
                try {
                    dispatchBatch(batch);    // non-blocking; releases the permit on batch completion
                } catch (RuntimeException e) {
                    inflight.release();      // synchronous dispatch failure → release now
                    throw e;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Command queue worker error", e);
            }
        }
        log.info("Command queue worker stopped");
    }

    private List<QueuedCommand> drainBatch() throws InterruptedException {
        List<QueuedCommand> batch = new ArrayList<>(batchSize);
        QueuedCommand first = queue.poll(1, TimeUnit.SECONDS);
        if (first == null) {
            return batch;
        }
        batch.add(first);

        if (batchSize > 1) {
            if (batchWaitMs > 0) {
                QueuedCommand extra = queue.poll(batchWaitMs, TimeUnit.MILLISECONDS);
                if (extra != null) {
                    batch.add(extra);
                }
            }
            queue.drainTo(batch, batchSize - batch.size());
        }
        return batch;
    }

    private void dispatchBatch(List<QueuedCommand> batch) {
        // lifecycle: queue-wait phase = time each command sat in the ingress queue before the
        // single worker picked it up (grows under load when the dispatcher lags).
        long picked = System.nanoTime();
        for (QueuedCommand q : batch) {
            com.tomma8.ledger.metrics.LedgerMetrics.recordQueueWait(picked - q.enqueueNanos());
        }
        List<RaftCommand> commands = batch.stream().map(QueuedCommand::command).toList();
        List<CompletableFuture<CommandResult>> results;
        try {
            // non-blocking: append the Raft entry, get per-command futures back immediately.
            results = consensusEngine.submitBatchAsync(commands);
        } catch (Exception e) {
            for (QueuedCommand task : batch) {
                if (task.resultFuture() != null) task.resultFuture().completeExceptionally(e);
            }
            inflight.release();
            return;
        }
        for (int i = 0; i < batch.size(); i++) {
            CompletableFuture<CommandResult> caller = batch.get(i).resultFuture();
            // 10s cap mirrors the old blocking get(); prevents a stuck future from leaking a permit.
            CompletableFuture<CommandResult> res = results.get(i).orTimeout(10, TimeUnit.SECONDS);
            if (caller != null) {
                res.whenComplete((r, e) -> {
                    if (e != null) caller.completeExceptionally(e);
                    else caller.complete(r);
                });
            }
        }
        // orTimeout above returns the SAME futures (it arms a timeout on `this`), so every element of
        // `results` is guaranteed to complete within 10s — allOf can't hang and the permit can't leak.
        CompletableFuture.allOf(results.toArray(new CompletableFuture[0]))
                .whenComplete((r, e) -> inflight.release());
    }

    @Override
    public void close() {
        running = false;
        worker.shutdownNow();
    }

    private record QueuedCommand(RaftCommand command, CompletableFuture<CommandResult> resultFuture, long enqueueNanos) {}
}
