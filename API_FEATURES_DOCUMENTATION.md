# JRaft Ledger Application - API Features Documentation

## Overview

The JRaft Ledger Application is a distributed ledger system built with Java 21 and Spring Boot 3, featuring strong consistency through JRaft consensus algorithm, high-performance async processing, and comprehensive REST APIs for financial operations.

---

## 🚀 Key Features

### **Distributed Consensus & Replication**
- **JRaft Integration**: Alibaba JRaft for distributed consensus and leader election
- **Strong Consistency**: All ledger operations coordinated through Raft state machine
- **Cluster Management**: Automatic cluster membership and peer discovery
- **Fault Tolerance**: Handles node failures and network partitions gracefully

### **High-Performance Architecture**
- **RocksDB**: Embedded key-value store for fast local state persistence
- **LMAX Disruptor**: High-performance event processing for MySQL batch writes
- **Async Processing**: Non-blocking operations with CompletableFuture
- **Connection Pooling**: HikariCP for optimized database connections

### **Financial Ledger Operations**
- **Double-Entry Bookkeeping**: All transfers follow accounting principles
- **Multi-Account Types**: Support for AVAILABLE, BROKERAGE, and EXCHANGE accounts
- **Atomic Transactions**: Batch transfers are all-or-nothing operations
- **Balance Tracking**: Real-time balance queries with RocksDB optimization

### **Reliability & Data Integrity**
- **Idempotency Support**: Prevents duplicate transactions with automatic/custom keys
- **Transaction Logging**: Complete audit trail for all operations
- **Database Resiliency**: Fail-fast startup with automatic recovery
- **Batch Writing**: Async MySQL persistence with configurable batching

---

## 📚 API Endpoints

### **Transfer Operations** (`/api/transfer`)

#### `POST /api/transfer/single`
**Single Transfer with Idempotency Support**

Execute a single double-entry bookkeeping transfer between accounts.

**Headers:**
- `Idempotency-Key` (optional): Custom key to prevent duplicate processing
- `Content-Type: application/json`

**Request Body:**
```json
{
  "fromUserId": "UserA",
  "fromType": "AVAILABLE",
  "toUserId": "UserB", 
  "toType": "AVAILABLE",
  "amount": 50.00,
  "description": "Transfer description"
}
```

**Response:**
```json
{
  "success": true,
  "message": "Transfer completed successfully"
}
```

**Features:**
- ✅ Automatic idempotency key generation (SHA-256 based)
- ✅ Account existence validation before processing
- ✅ 60-minute idempotency cache with automatic cleanup
- ✅ Async processing with CompletableFuture

---

#### `POST /api/transfer/batch`
**Atomic Batch Transfers**

Execute multiple transfers atomically - all succeed or all fail.

**Request Body:**
```json
{
  "transfers": [
    {
      "fromUserId": "UserA",
      "fromType": "AVAILABLE",
      "toUserId": "UserB",
      "toType": "AVAILABLE", 
      "amount": 10.00,
      "description": "Batch transfer 1"
    },
    {
      "fromUserId": "UserA",
      "fromType": "AVAILABLE",
      "toUserId": "Bank",
      "toType": "AVAILABLE",
      "amount": 20.00,
      "description": "Batch transfer 2"
    }
  ]
}
```

**Features:**
- ✅ All-or-nothing atomic execution
- ✅ Multiple account type support
- ✅ Comprehensive error handling

---

#### `POST /api/transfer/demo`
**Demo Transfer Scenario**

Predefined demo transfers for testing: UserA → UserB (10) and UserA → Bank (20).

**Features:**
- ✅ Automatic account creation if needed
- ✅ Predefined test scenario execution

---

### **Balance Operations** (`/api/balance`)

#### `GET /api/balance/user/{userId}`
**Get All User Account Balances**

Retrieve all account balances for a specific user.

**Response:**
```json
{
  "userId": "UserA",
  "brokerageBalance": 1000.00,
  "exchangeBalance": 500.00, 
  "availableBalance": 2500.00
}
```

**Features:**
- ✅ Single API call for all account types
- ✅ RocksDB optimization for fast queries
- ✅ Automatic zero balance for non-existent accounts

---

#### `GET /api/balance/account/{userId}/{accountType}`
**Get Specific Account Balance**

Query balance for a specific account type.

**Path Parameters:**
- `userId`: User identifier
- `accountType`: `available`, `brokerage`, or `exchange`

**Response:**
```json
{
  "userId": "UserA",
  "accountType": "AVAILABLE",
  "balance": 2500.00
}
```

**Features:**
- ✅ Fast RocksDB-first lookup
- ✅ Fallback to state machine if needed
- ✅ Input validation for account types

---

#### `POST /api/balance/create`
**Create New Account**

Create a new account for a user with specified type.

**Request Body:**
```json
{
  "userId": "UserC",
  "accountType": "AVAILABLE"
}
```

**Response:**
```json
{
  "success": true,
  "message": "Account created successfully"
}
```

**Features:**
- ✅ Idempotent account creation (returns 200 for existing accounts)
- ✅ Support for all account types
- ✅ Automatic balance initialization to zero

---

### **Administrative Operations** (`/api/admin`)

#### `GET /api/admin/metrics/mysql-writer`
**MySQL Batch Writer Performance Metrics**

Retrieve performance statistics from the async MySQL batch writer.

**Response:**
```json
{
  "metrics": {
    "totalEventsProcessed": 15420,
    "totalBatchesProcessed": 1542,
    "averageBatchSize": 10.0,
    "processingRate": "1542 events/sec"
  },
  "timestamp": 1703123456789
}
```

**Features:**
- ✅ Real-time performance monitoring
- ✅ Batch processing statistics
- ✅ Throughput analysis

---

#### `GET /api/admin/idempotency/stats`
**Idempotency Cache Statistics**

View idempotency cache usage and statistics.

**Response:**
```json
{
  "totalEntries": 150,
  "processingEntries": 5,
  "completedEntries": 145
}
```

**Features:**
- ✅ Cache utilization monitoring
- ✅ Processing status tracking
- ✅ Memory usage insights

---

### **Data Management** (`/api/data`)

#### `GET /api/data/status`
**Data Synchronization Status**

Get current status of RocksDB and MySQL data synchronization.

**Response:**
```json
{
  "rocksdbInitialized": "ALWAYS_REINITIALIZED_FROM_MYSQL_ON_STARTUP",
  "timestamp": 1703123456789,
  "status": "OK"
}
```

---

#### `POST /api/data/initialize`
**Force RocksDB Reinitialization**

Trigger manual reinitialization of RocksDB from MySQL data.

**Response:**
```json
{
  "success": true,
  "message": "RocksDB initialization triggered.",
  "timestamp": 1703123456789
}
```

**Features:**
- ✅ Manual data synchronization
- ✅ Recovery from corruption scenarios
- ✅ Startup initialization automation

---

### **Raft Cluster Management** (`/api/raft`)

#### `GET /api/raft/status`
**Raft Node Status Information**

Get detailed status of the current Raft node and cluster.

**Response:**
```json
{
  "nodeId": "node1",
  "groupId": "ledger-raft-group",
  "peers": "127.0.0.1:8091,127.0.0.1:8092,127.0.0.1:8093",
  "dataPath": "./raft-data",
  "status": "ACTIVE",
  "enabled": true,
  "message": "JRaft node is running",
  "leader": "127.0.0.1:8091",
  "term": 15,
  "role": "LEADER",
  "timestamp": "2023-12-21T10:30:45"
}
```

**Features:**
- ✅ Real-time cluster health monitoring
- ✅ Leader election status
- ✅ Node role and term information

---

#### `GET /api/raft/config`
**Raft Configuration Details**

Retrieve current Raft node configuration.

**Response:**
```json
{
  "nodeId": "node1",
  "groupId": "ledger-raft-group", 
  "peers": "127.0.0.1:8091,127.0.0.1:8092,127.0.0.1:8093",
  "dataPath": "./raft-data",
  "enabled": true,
  "timestamp": "2023-12-21T10:30:45"
}
```

---

## 🏗️ Architecture Components

### **Account Types**
- **AVAILABLE**: General available funds for transfers
- **BROKERAGE**: Funds allocated for brokerage operations  
- **EXCHANGE**: Funds allocated for exchange operations

### **Account ID Format**
Accounts are uniquely identified using the format: `{userId}:{accountType}`
- Example: `UserA:available`, `UserB:brokerage`

### **Idempotency System**
- **Auto-generated Keys**: SHA-256 hash of request content
- **Custom Keys**: Via `Idempotency-Key` header
- **Cache Duration**: 60 minutes with automatic cleanup
- **Thread-safe**: ConcurrentHashMap implementation

### **Async MySQL Batch Writer**
- **LMAX Disruptor**: High-performance event processing
- **Configurable Batching**: Batch size and flush intervals
- **Multiple Strategies**: Ring buffer or traditional queue
- **Performance Metrics**: Real-time throughput monitoring

---

## 🔧 Configuration

### **Database Settings**
```properties
# HikariCP Configuration
spring.datasource.hikari.initialization-fail-timeout=10000
spring.datasource.hikari.maximum-pool-size=20

# MySQL Batch Writer Settings  
mysql.batch.size=16384
mysql.batch.interval.ms=100
mysql.writer.thread.count=4
mysql.ring.buffer.size=16384
mysql.use.ring.buffer=true
```

### **Raft Configuration**
```properties
# JRaft Settings
raft.enabled=true
raft.node-id=node1
raft.group-id=ledger-raft-group
raft.peers=127.0.0.1:8091,127.0.0.1:8092,127.0.0.1:8093
raft.data-path=./raft-data
```

---

## 📊 Health & Monitoring

### **Spring Boot Actuator Endpoints**
- `GET /actuator/health` - Overall application health
- `GET /actuator/info` - Application information
- `GET /actuator/metrics` - Performance metrics
- `GET /actuator/health/db` - Database connectivity

### **API Documentation**
- **Swagger UI**: `http://localhost:8090/swagger-ui.html`
- **OpenAPI JSON**: `http://localhost:8090/api-docs`

---

## 🛡️ Error Handling

### **HTTP Status Codes**
- **200 OK**: Successful operation or idempotent response
- **400 Bad Request**: Invalid parameters, insufficient funds
- **404 Not Found**: Non-existent accounts or users
- **500 Internal Server Error**: System issues

### **Account Validation**
- Pre-transfer account existence validation
- Fast-fail for non-existent accounts
- Comprehensive error messages

### **Resilience Features**
- Database connection timeout handling
- Automatic retry mechanisms
- Graceful degradation strategies

---

## 🚦 Performance Characteristics

### **Throughput Optimization**
- **RocksDB**: Sub-millisecond balance queries
- **Async Processing**: Non-blocking operations
- **Batch Writing**: High-throughput MySQL persistence
- **Connection Pooling**: Optimized database access

### **Scalability Features**
- **Distributed Architecture**: Multi-node Raft cluster
- **Horizontal Scaling**: Add nodes for increased capacity
- **Load Distribution**: Automatic leader election and failover

---

## 📝 Usage Examples

### **Complete Transfer Workflow**
```bash
# 1. Create accounts
POST /api/balance/create
{
  "userId": "Alice",
  "accountType": "AVAILABLE"
}

# 2. Check balance
GET /api/balance/user/Alice

# 3. Execute transfer with idempotency
POST /api/transfer/single
Idempotency-Key: transfer-alice-bob-001
{
  "fromUserId": "Alice",
  "fromType": "AVAILABLE", 
  "toUserId": "Bob",
  "toType": "AVAILABLE",
  "amount": 100.00,
  "description": "Payment for services"
}

# 4. Verify balances
GET /api/balance/user/Alice
GET /api/balance/user/Bob
```

### **Monitoring Workflow**
```bash
# Check system health
GET /actuator/health

# Monitor Raft cluster
GET /api/raft/status

# View performance metrics
GET /api/admin/metrics/mysql-writer

# Check idempotency cache
GET /api/admin/idempotency/stats
```

---

This documentation covers all the major features and APIs of the JRaft Ledger Application, providing a comprehensive guide for developers and operators working with the system. 