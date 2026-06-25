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

    /** Default cap on commands enqueued-to-Raft-but-not-yet-applied (pipelining backpressure). */
    static final int DEFAULT_MAX_INFLIGHT = 1024;

    private final BlockingQueue<QueuedCommand> queue;
    private final ConsensusEngine consensusEngine;
    private final int batchSize;
    private final long batchWaitMs;
    private final ExecutorService worker;
    // Bounds in-flight (submitted-to-Raft, not-yet-completed) commands. With async pipelined
    // dispatch the worker no longer blocks per batch, so without this it could flood node.apply
    // faster than commit and overrun the Raft disruptor / grow pendingCommands unbounded. The
    // controller's admission semaphore also bounds this in practice, but this is defense-in-depth
    // so the queue stays safe even if that upstream guard changes.
    private final Semaphore inflight;
    private volatile boolean running = true;

    public CommandQueueManager(ConsensusEngine consensusEngine, int maxQueueSize, int batchSize, long batchWaitMs) {
        this(consensusEngine, maxQueueSize, batchSize, batchWaitMs, DEFAULT_MAX_INFLIGHT, true);
    }

    public CommandQueueManager(ConsensusEngine consensusEngine, int maxQueueSize, int batchSize, long batchWaitMs,
                               int maxInFlight) {
        this(consensusEngine, maxQueueSize, batchSize, batchWaitMs, maxInFlight, true);
    }

    CommandQueueManager(ConsensusEngine consensusEngine, int maxQueueSize, int batchSize, long batchWaitMs,
                        boolean startWorker) {
        this(consensusEngine, maxQueueSize, batchSize, batchWaitMs, DEFAULT_MAX_INFLIGHT, startWorker);
    }

    CommandQueueManager(ConsensusEngine consensusEngine, int maxQueueSize, int batchSize, long batchWaitMs,
                        int maxInFlight, boolean startWorker) {
        this.consensusEngine = consensusEngine;
        this.queue = new LinkedBlockingQueue<>(maxQueueSize);
        this.batchSize = Math.max(1, batchSize);
        this.batchWaitMs = Math.max(0, batchWaitMs);
        // never smaller than a single batch, else a full batch could never acquire and would deadlock
        this.inflight = new Semaphore(Math.max(this.batchSize, maxInFlight));
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
        boolean accepted = queue.offer(new QueuedCommand(command, null));
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
        QueuedCommand task = new QueuedCommand(command, future);
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
                // backpressure: blocks the worker ONLY when the in-flight cap is reached (not per
                // batch) — this is what keeps pipelined dispatch bounded.
                inflight.acquire(batch.size());
                dispatchBatch(batch);
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
        List<RaftCommand> commands = batch.stream().map(QueuedCommand::command).toList();
        try {
            // Pipelined: enqueue to Raft and get per-command futures WITHOUT blocking. The worker
            // returns to drain the next batch immediately, keeping the Raft pipeline full instead
            // of one batch at a time. Each Raft future is chained to the caller's resultFuture and
            // completes asynchronously on the state-machine apply thread.
            List<CompletableFuture<CommandResult>> raftFutures = consensusEngine.submitBatchAsync(commands);
            for (int i = 0; i < batch.size(); i++) {
                CompletableFuture<CommandResult> caller = batch.get(i).resultFuture();
                // release one in-flight permit per command as it completes (success OR failure),
                // unconditionally — even null-caller commands must release or the permit leaks.
                raftFutures.get(i).whenComplete((result, ex) -> {
                    inflight.release(1);
                    if (caller == null) {
                        return;
                    }
                    if (ex != null) {
                        caller.completeExceptionally(ex);
                    } else {
                        caller.complete(result);
                    }
                });
            }
        } catch (Exception e) {
            // submitBatchAsync only throws on enqueue failure (e.g. node.apply rejected): no futures
            // were returned, so release all permits acquired for this batch and fail it.
            inflight.release(batch.size());
            for (QueuedCommand task : batch) {
                if (task.resultFuture() != null) {
                    task.resultFuture().completeExceptionally(e);
                }
            }
        }
    }

    @Override
    public void close() {
        running = false;
        worker.shutdownNow();
    }

    private record QueuedCommand(RaftCommand command, CompletableFuture<CommandResult> resultFuture) {}
}
