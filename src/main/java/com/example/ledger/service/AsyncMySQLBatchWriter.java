package com.example.ledger.service;

import com.example.ledger.mapper.AccountMapper;
import com.example.ledger.mapper.ProcessedTransactionMapper;
import com.example.ledger.model.Account;
import com.example.ledger.model.ProcessedTransaction;
import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.EventFactory;
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class AsyncMySQLBatchWriter {
    // Configurable batch size and flush interval
    @Value("${mysql.batch.size:16384}")
    private int batchSize;
    
    @Value("${mysql.batch.interval.ms:100}")
    private long batchIntervalMs;
    
    @Value("${mysql.writer.thread.count:4}")
    private int writerThreadCount;
    
    @Value("${mysql.ring.buffer.size:16384}")
    private int ringBufferSize;
    
    @Value("${mysql.use.ring.buffer:true}")
    private boolean useRingBuffer;

    // Traditional queue-based approach (used if useRingBuffer=false)
    private final LinkedBlockingQueue<WriteEvent> queue = new LinkedBlockingQueue<>(100_000);
    
    // Thread pool for batch processing
    private ExecutorService executorService;
    
    // Disruptor for high-performance event processing
    private Disruptor<WriteEvent> disruptor;
    private RingBuffer<WriteEvent> ringBuffer;
    
    // Metrics
    private final AtomicLong totalEventsProcessed = new AtomicLong(0);
    private final AtomicLong totalBatchesProcessed = new AtomicLong(0);
    private volatile boolean running = true;

    @Autowired
    private AccountMapper accountMapper;
    
    @Autowired
    private ProcessedTransactionMapper transactionMapper;

    @PostConstruct
    public void start() {
        // Create named thread factory for better monitoring
        ThreadFactory threadFactory = new ThreadFactory() {
            private final AtomicInteger threadCount = new AtomicInteger(1);
            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r, "mysql-batch-writer-" + threadCount.getAndIncrement());
                thread.setDaemon(true);
                return thread;
            }
        };
        
        // Initialize thread pool with fixed size and custom thread factory
        executorService = Executors.newFixedThreadPool(writerThreadCount, threadFactory);
        
        if (useRingBuffer) {
            initializeDisruptor(threadFactory);
        } else {
            initializeTraditionalQueue();
        }
        
        log.info("AsyncMySQLBatchWriter started: mode={}, batchSize={}, intervalMs={}, threads={}",
                useRingBuffer ? "RingBuffer" : "Queue", batchSize, batchIntervalMs, writerThreadCount);
    }
    
    private void initializeDisruptor(ThreadFactory threadFactory) {
        // Power of 2 size is required for RingBuffer
        int bufferSize = Integer.highestOneBit(ringBufferSize) << 1;
        
        // Create the disruptor
        disruptor = new Disruptor<>(
                WriteEvent::new,
                bufferSize,
                threadFactory,
                ProducerType.MULTI, // Multiple producers can publish
                new BlockingWaitStrategy() // Good balance between CPU usage and latency
        );
        
        // Set up batch event handler
        disruptor.handleEventsWith(new WriteEventBatchHandler());
        
        // Start the disruptor
        ringBuffer = disruptor.start();
        
        log.info("Disruptor RingBuffer initialized with size: {}", bufferSize);
    }
    
    private void initializeTraditionalQueue() {
        // Start worker threads for traditional queue approach
        for (int i = 0; i < writerThreadCount; i++) {
            executorService.submit(this::runWorker);
        }
        log.info("Traditional queue-based workers initialized: {}", writerThreadCount);
    }

    @PreDestroy
    public void stop() {
        running = false;
        
        if (disruptor != null) {
            disruptor.shutdown();
            log.info("Disruptor shutdown completed");
        }
        
        if (executorService != null) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
            log.info("Thread pool shutdown completed");
        }
        
        log.info("AsyncMySQLBatchWriter stopped - Events processed: {}, Batches: {}", 
                totalEventsProcessed.get(), totalBatchesProcessed.get());
    }

    /**
     * Enqueue a write event for processing
     */
    public void enqueue(WriteEvent event) {
        if (!running) {
            log.warn("Attempted to enqueue event while writer is shutting down");
            return;
        }
        
        if (useRingBuffer) {
            // Publish to the ring buffer
            long sequence = ringBuffer.next();
            try {
                WriteEvent bufferedEvent = ringBuffer.get(sequence);
                // Copy event data to the pre-allocated event in the ring
                bufferedEvent.setType(event.getType());
                bufferedEvent.setAccountId(event.getAccountId());
                bufferedEvent.setBalance(event.getBalance());
                bufferedEvent.setTransaction(event.getTransaction());
                bufferedEvent.setEventTime(event.getEventTime());
            } finally {
                ringBuffer.publish(sequence);
            }
        } else {
            // Use traditional queue
            boolean offered = queue.offer(event);
            if (!offered) {
                log.error("MySQL write queue is full! Dropping event: {}", event);
            }
        }
    }

    /**
     * Worker method for traditional queue-based approach
     */
    private void runWorker() {
        List<WriteEvent> batch = new ArrayList<>(batchSize);
        while (running) {
            try {
                // Block for up to batchIntervalMs for the first event
                WriteEvent first = queue.poll(batchIntervalMs, TimeUnit.MILLISECONDS);
                if (first == null) continue;
                
                batch.clear();
                batch.add(first);
                queue.drainTo(batch, batchSize - 1);
                
                processBatch(batch);
                totalBatchesProcessed.incrementAndGet();
                totalEventsProcessed.addAndGet(batch.size());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error in MySQL batch writer", e);
            }
        }
    }

    /**
     * Process a batch of write events
     */
    private void processBatch(List<WriteEvent> batch) {
        if (batch.isEmpty()) return;
        
        List<Account> balanceUpdates = new ArrayList<>();
        List<ProcessedTransaction> transactions = new ArrayList<>();
        
        // Categorize events
        for (WriteEvent event : batch) {
            if (event.getType() == WriteEvent.Type.BALANCE) {
                // Only update balance field
                Account acc = new Account();
                acc.setAccountId(event.getAccountId());
                acc.setBalance(event.getBalance());
                balanceUpdates.add(acc);
            } else if (event.getType() == WriteEvent.Type.TRANSACTION) {
                transactions.add(event.getTransaction());
            }
        }
        
        // Process balance updates
        if (!balanceUpdates.isEmpty()) {
            try {
                for (Account acc : balanceUpdates) {
                    int updated = accountMapper.updateBalanceByAccountId(acc.getAccountId(), acc.getBalance());
                    if (updated == 0) {
                        // Insert new account if not exists
                        try {
                            String[] parts = acc.getAccountId().split(":");
                            if (parts.length == 2) {
                                String userId = parts[0];
                                String typeStr = parts[1];
                                Account.AccountType type = Account.AccountType.fromValue(typeStr);
                                Account newAcc = new Account();
                                newAcc.setAccountId(acc.getAccountId());
                                newAcc.setUserId(userId);
                                newAcc.setAccountType(type);
                                newAcc.setBalance(acc.getBalance());
                                newAcc.setCreatedAt(java.time.LocalDateTime.now());
                                newAcc.setUpdatedAt(java.time.LocalDateTime.now());
                                accountMapper.insert(newAcc);
                                if (log.isDebugEnabled()) {
                                    log.debug("Inserted new account {} to MySQL", newAcc.getAccountId());
                                }
                            } else {
                                log.error("Invalid accountId format: {}", acc.getAccountId());
                            }
                        } catch (Exception ex) {
                            log.error("Failed to insert new account {}: {}", acc.getAccountId(), ex.getMessage(), ex);
                        }
                    }
                }
                if (log.isDebugEnabled()) {
                    log.debug("Batch updated {} balances to MySQL", balanceUpdates.size());
                }
            } catch (Exception e) {
                log.error("Failed to batch update balances to MySQL: {}", e.getMessage(), e);
            }
        }
        
        // Process transactions
        if (!transactions.isEmpty()) {
            try {
                for (ProcessedTransaction tx : transactions) {
                    transactionMapper.insert(tx);
                }
                if (log.isDebugEnabled()) {
                    log.debug("Batch inserted {} transactions to MySQL", transactions.size());
                }
            } catch (Exception e) {
                log.error("Failed to batch insert transactions to MySQL: {}", e.getMessage(), e);
            }
        }
    }
    
    /**
     * Disruptor event handler for batch processing
     */
    private class WriteEventBatchHandler implements EventHandler<WriteEvent> {
        private final List<WriteEvent> batch = new ArrayList<>(batchSize);
        private long lastFlushTime = System.currentTimeMillis();
        
        @Override
        public void onEvent(WriteEvent event, long sequence, boolean endOfBatch) {
            batch.add(copyEvent(event));
            
            boolean timeToFlush = batch.size() >= batchSize || 
                                 (System.currentTimeMillis() - lastFlushTime) >= batchIntervalMs ||
                                 endOfBatch;
                                 
            if (timeToFlush && !batch.isEmpty()) {
                processBatch(batch);
                totalBatchesProcessed.incrementAndGet();
                totalEventsProcessed.addAndGet(batch.size());
                batch.clear();
                lastFlushTime = System.currentTimeMillis();
            }
        }
        
        private WriteEvent copyEvent(WriteEvent event) {
            // Create a copy to avoid issues with reused ring buffer slots
            WriteEvent copy = new WriteEvent();
            copy.setType(event.getType());
            copy.setAccountId(event.getAccountId());
            copy.setBalance(event.getBalance());
            copy.setTransaction(event.getTransaction());
            copy.setEventTime(event.getEventTime());
            return copy;
        }
    }
    
    /**
     * Get metrics about the writer's performance
     */
    public String getMetrics() {
        return String.format("Events: %d, Batches: %d, Avg batch size: %.2f",
                totalEventsProcessed.get(),
                totalBatchesProcessed.get(),
                totalBatchesProcessed.get() > 0 ? 
                    (double) totalEventsProcessed.get() / totalBatchesProcessed.get() : 0);
    }
} 