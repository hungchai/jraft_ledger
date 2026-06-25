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
    // When true, dispatch is pipelined (worker submits to Raft and returns without blocking on
    // commit → many batches in-flight). Measured WORSE below saturation (the single FSM apply
    // thread becomes the queue; pipelining just deepens it and adds latency with no throughput
    // gain when the offered load is under capacity), so default OFF. Kept behind a flag for
    // saturation scenarios where one-batch-at-a-time genuinely stalls the worker.
    private final boolean pipelined;
    private volatile boolean running = true;

    public CommandQueueManager(ConsensusEngine consensusEngine, int maxQueueSize, int batchSize, long batchWaitMs) {
        this(consensusEngine, maxQueueSize, batchSize, batchWaitMs, DEFAULT_MAX_INFLIGHT, false, true);
    }

    public CommandQueueManager(ConsensusEngine consensusEngine, int maxQueueSize, int batchSize, long batchWaitMs,
                               int maxInFlight, boolean pipelined) {
        this(consensusEngine, maxQueueSize, batchSize, batchWaitMs, maxInFlight, pipelined, true);
    }

    CommandQueueManager(ConsensusEngine consensusEngine, int maxQueueSize, int batchSize, long batchWaitMs,
                        boolean startWorker) {
        this(consensusEngine, maxQueueSize, batchSize, batchWaitMs, DEFAULT_MAX_INFLIGHT, false, startWorker);
    }

    CommandQueueManager(ConsensusEngine consensusEngine, int maxQueueSize, int batchSize, long batchWaitMs,
                        int maxInFlight, boolean pipelined, boolean startWorker) {
        this.consensusEngine = consensusEngine;
        this.queue = new LinkedBlockingQueue<>(maxQueueSize);
        this.batchSize = Math.max(1, batchSize);
        this.batchWaitMs = Math.max(0, batchWaitMs);
        // never smaller than a single batch, else a full batch could never acquire and would deadlock
        this.inflight = new Semaphore(Math.max(this.batchSize, maxInFlight));
        this.pipelined = pipelined;
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
        if (pipelined) {
            dispatchPipelined(batch, commands);
        } else {
            dispatchBlocking(batch, commands);
        }
    }

    /**
     * Blocking dispatch (default): submit the batch and wait for commit before returning, so only
     * one batch is in-flight. Lower latency below saturation — the FSM apply thread never builds a
     * backlog and the worker paces submissions to the apply rate.
     */
    private void dispatchBlocking(List<QueuedCommand> batch, List<RaftCommand> commands) {
        try {
            List<CommandResult> results = consensusEngine.submitBatch(commands);
            for (int i = 0; i < batch.size(); i++) {
                CompletableFuture<CommandResult> caller = batch.get(i).resultFuture();
                if (caller != null) {
                    caller.complete(results.get(i));
                }
            }
        } catch (Exception e) {
            for (QueuedCommand task : batch) {
                if (task.resultFuture() != null) {
                    task.resultFuture().completeExceptionally(e);
                }
            }
        } finally {
            inflight.release(batch.size());
        }
    }

    /**
     * Pipelined dispatch: enqueue to Raft and get per-command futures WITHOUT blocking. The worker
     * returns to drain the next batch immediately, keeping the Raft pipeline full. Each Raft future
     * is chained to the caller's resultFuture and completes on the state-machine apply thread.
     * Helps throughput only at saturation; adds latency below it (see the {@code pipelined} field).
     */
    private void dispatchPipelined(List<QueuedCommand> batch, List<RaftCommand> commands) {
        try {
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
