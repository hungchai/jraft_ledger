package com.tomma8.ledger.queue;

import com.tomma8.ledger.domain.command.RaftCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Per-account serialization via LinkedBlockingQueue + Virtual Thread workers (ADR-001 v0.2).
 *
 * Each account gets a single Virtual Thread worker that processes requests
 * sequentially from its queue. Multi-account requests are coordinated via
 * MultiAccountTask with sorted account locking.
 */
public class AccountQueueManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AccountQueueManager.class);

    private static final int MAX_QUEUE_SIZE = 1000;
    private static final int WORKER_SHUTDOWN_TIMEOUT_MS = 5000;

    private final ConcurrentHashMap<String, BlockingQueue<QueueTask>> queues = new ConcurrentHashMap<>();
    private final Consumer<RaftCommand> commandHandler;
    private final ExecutorService workerPool;
    private volatile boolean running = true;

    public AccountQueueManager(Consumer<RaftCommand> commandHandler) {
        this.commandHandler = commandHandler;
        this.workerPool = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Submit a command for a single account. Returns false if queue is full (backpressure).
     */
    public boolean submit(String accountId, RaftCommand command) {
        BlockingQueue<QueueTask> queue = queues.computeIfAbsent(accountId, k -> {
            BlockingQueue<QueueTask> q = new LinkedBlockingQueue<>(MAX_QUEUE_SIZE);
            startWorker(accountId, q);
            return q;
        });

        QueueTask task = new QueueTask(accountId, command, null);
        boolean offered = queue.offer(task);
        if (!offered) {
            log.warn("Queue full for account {} — backpressure triggered", accountId);
        }
        return offered;
    }

    /**
     * Submit a multi-account task. All involved account queues must have room.
     */
    public boolean submitMultiAccount(java.util.List<String> sortedAccountIds,
                                       RaftCommand command) {
        // Check all queues have capacity first
        for (String accountId : sortedAccountIds) {
            BlockingQueue<QueueTask> queue = queues.get(accountId);
            if (queue != null && queue.remainingCapacity() == 0) {
                return false;
            }
        }

        CompletableFuture<Void> ready = new CompletableFuture<>();
        AtomicInteger readyCount = new AtomicInteger(0);
        int total = sortedAccountIds.size();

        for (String accountId : sortedAccountIds) {
            BlockingQueue<QueueTask> queue = queues.computeIfAbsent(accountId, k -> {
                BlockingQueue<QueueTask> q = new LinkedBlockingQueue<>(MAX_QUEUE_SIZE);
                startWorker(accountId, q);
                return q;
            });

            QueueTask task = new QueueTask(accountId, command, () -> {
                if (readyCount.incrementAndGet() == total) {
                    ready.complete(null);
                }
            });
            if (!queue.offer(task)) {
                return false;
            }
        }

        try {
            ready.get(30, TimeUnit.MILLISECONDS);
            return true;
        } catch (Exception e) {
            log.warn("Multi-account ready timeout");
            return false;
        }
    }

    private void startWorker(String accountId, BlockingQueue<QueueTask> queue) {
        workerPool.submit(() -> {
            while (running) {
                try {
                    QueueTask task = queue.poll(1, TimeUnit.SECONDS);
                    if (task == null) continue;

                    // Notify multi-account coordinator
                    if (task.readyCallback != null) {
                        task.readyCallback.run();
                        // Non-leader accounts wait for result via the RaftNodeManager
                        // The leader submits the actual RaftCommand
                        continue;
                    }

                    try {
                        commandHandler.accept(task.command);
                    } catch (Exception e) {
                        log.error("Error processing command for account {}", accountId, e);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            log.info("Worker for account {} stopped", accountId);
        });
    }

    public int getQueueDepth(String accountId) {
        BlockingQueue<QueueTask> queue = queues.get(accountId);
        return queue != null ? queue.size() : 0;
    }

    @Override
    public void close() {
        running = false;
        workerPool.shutdown();
        try {
            workerPool.awaitTermination(WORKER_SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    record QueueTask(String accountId, RaftCommand command, Runnable readyCallback) {}
}
