# AsyncMySQLBatchWriter Performance Optimization

## 🚀 Overview

This document explains the performance optimizations made to the `AsyncMySQLBatchWriter` component, which is responsible for asynchronously writing ledger data to MySQL in batches.

## 🔄 Key Improvements

### 1. Thread Pool Implementation
- Replaced single worker thread with configurable thread pool
- Multiple threads can now process batches in parallel
- Configurable via `mysql.writer.thread.count` property (default: 4)

### 2. LMAX Disruptor RingBuffer
- Added high-performance lock-free RingBuffer option
- Significantly reduces contention compared to LinkedBlockingQueue
- Pre-allocates memory for better GC behavior
- Configurable via `mysql.use.ring.buffer` property (default: true)

### 3. Performance Metrics
- Added metrics tracking for events and batches processed
- Exposed via REST API at `/api/admin/metrics/mysql-writer`
- Helps with monitoring and tuning

### 4. Configurable Parameters
- `mysql.batch.size`: Number of events per batch (default: 200)
- `mysql.batch.interval.ms`: Maximum wait time for batch (default: 100ms)
- `mysql.ring.buffer.size`: Size of the ring buffer (default: 16384)
- `mysql.writer.thread.count`: Number of worker threads (default: 4)
- `mysql.use.ring.buffer`: Whether to use RingBuffer vs Queue (default: true)

## 📊 Architecture Comparison

### Original Implementation:
```
Client → LinkedBlockingQueue → Single Worker Thread → MySQL
```

### New Implementation:
```
                        ┌─→ Worker Thread 1 ─┐
                        │                    │
Client → RingBuffer ────┼─→ Worker Thread 2 ─┼─→ MySQL
                        │                    │
                        └─→ Worker Thread N ─┘
```

## 💡 How It Works

### RingBuffer Mode (Default)
1. Events are published to the RingBuffer
2. Pre-allocated slots reduce GC pressure
3. Multiple consumers can process batches in parallel
4. Lock-free design eliminates contention

### Traditional Queue Mode
1. Events are offered to a LinkedBlockingQueue
2. Multiple worker threads pull from the queue
3. Each worker processes batches independently
4. Good for simpler deployments

## 🔧 Performance Tuning

### For Higher Throughput:
- Increase `mysql.writer.thread.count` (4-8 recommended)
- Increase `mysql.ring.buffer.size` (power of 2, e.g., 65536)
- Increase `mysql.batch.size` (200-500 recommended)

### For Lower Latency:
- Decrease `mysql.batch.interval.ms` (20-50ms)
- Decrease `mysql.batch.size` (50-100)
- Ensure `mysql.use.ring.buffer=true`

## 🧪 Testing Performance

A test script is provided to benchmark different configurations:

```bash
./test-mysql-writer-performance.sh
```

This script runs several tests with different configurations:
1. Traditional Queue with Single Thread
2. Traditional Queue with Multiple Threads
3. RingBuffer with Default Settings
4. RingBuffer with Larger Size
5. RingBuffer with Small Batches

## 📈 Expected Performance Improvements

| Metric | Original | Optimized | Improvement |
|--------|----------|-----------|-------------|
| Throughput | ~1,000/sec | ~5,000+/sec | 5x+ |
| Latency | Variable | More consistent | Lower variance |
| CPU Usage | Higher | Lower | More efficient |
| GC Pressure | Higher | Lower | Better memory usage |

## 🛠️ Implementation Details

### LMAX Disruptor
- Uses a pre-allocated ring buffer of events
- Producers and consumers don't contend for locks
- Batching is handled by event handlers
- Uses memory barriers instead of locks

### Thread Pool
- Fixed size thread pool for predictable resource usage
- Named threads for better monitoring
- Daemon threads to avoid blocking application shutdown

## 📝 Recommendations

1. **Default Configuration**: The default settings (RingBuffer with 4 threads) should work well for most deployments
2. **High-Volume Systems**: Increase thread count and ring buffer size
3. **Resource-Constrained Systems**: Use traditional queue with fewer threads
4. **Monitoring**: Check metrics regularly to ensure optimal performance

## 🔍 Troubleshooting

### Queue Full Errors
- Increase queue capacity or ring buffer size
- Check if MySQL is bottlenecked
- Consider increasing batch size

### High Latency
- Decrease batch interval
- Ensure enough worker threads
- Check MySQL connection pool settings

### High CPU Usage
- Reduce thread count
- Increase batch size
- Use RingBuffer mode

## Transfer Endpoint Updates

The transfer endpoints (`/api/transfer/single` and `/api/transfer/batch`) now include account existence validation. If either the source or destination account does not exist, the API will return a `404 Not Found` error with a descriptive message, ensuring a better user experience by failing fast before any database operations are attempted.

## Account Creation Behavior

The account creation API (`/api/balance/create`) has been enhanced to:

- Return `200 OK` with message "Account already exists" for existing accounts
- Maintain existing success/failure responses for new account creation

This change improves API clarity by distinguishing between successful operations and existing accounts.

## Idempotency Features

### Transfer Idempotency
The single transfer endpoint (`/api/transfer/single`) includes built-in idempotency support:

- **Prevents Duplicate Transfers**: Same request won't be processed twice
- **Flexible Key Management**: Use custom keys or auto-generated content-based keys
- **Memory-Efficient**: In-memory cache with automatic cleanup
- **Configurable TTL**: 60-minute cache duration by default

### Benefits
- **Reliability**: Network retries won't cause duplicate transactions
- **Performance**: Cached responses for repeated requests
- **Monitoring**: Built-in statistics and cleanup mechanisms
- **Simplicity**: Works automatically without client changes

### Implementation Details
- Uses SHA-256 hashing for auto-generated keys
- ConcurrentHashMap for thread-safe caching
- Scheduled cleanup every 30 minutes
- Comprehensive logging and monitoring 