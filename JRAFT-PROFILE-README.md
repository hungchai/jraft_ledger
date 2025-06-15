# JRaft Profile Configuration Guide

## 🎯 Overview

This ledger system supports two operational modes:

1. **Standalone Mode** (`local` profile) - Single node, no consensus
2. **Distributed Mode** (`raft` profile) - Multi-node with JRaft consensus

## 🚀 Quick Start

### Standalone Mode (Default)
```bash
# Uses SimpleLedgerStateMachine directly
mvn spring-boot:run -Dspring-boot.run.profiles=local
# Application runs on port 8090
```

### Distributed Mode (JRaft Enabled)
```bash
# Uses JRaftLedgerStateMachine with consensus
mvn spring-boot:run -Dspring-boot.run.profiles=raft
# Application runs on port 8091
```

### Using the Test Script
```bash
./test-raft-profile.sh
```

## 📋 Profile Comparison

| Feature | Local Profile | Raft Profile |
|---------|---------------|--------------|
| **Port** | 8090 | 8091 |
| **Consensus** | None | JRaft |
| **State Machine** | SimpleLedgerStateMachine | JRaftLedgerStateMachine |
| **Data Path** | `./rocksdb-data` | `./rocksdb-data-node1` |
| **Raft Data** | N/A | `./raft-data-node1` |
| **High Availability** | No | Yes |
| **Distributed** | No | Yes |

## 🔧 JRaft Configuration

### Key Properties (`application-raft.properties`)

```properties
# JRaft Enable Flag
raft.enabled=true

# Node Configuration
raft.node-id=node1
raft.current-node.ip=127.0.0.1
raft.current-node.port=8091

# Cluster Configuration
raft.group-id=ledger-raft-group
raft.peers=127.0.0.1:8091,127.0.0.1:8092,127.0.0.1:8093

# Data Paths
raft.data-path=./raft-data-node1
rocksdb.data-path=./rocksdb-data-node1

# Performance Tuning
raft.election-timeout-ms=5000
raft.snapshot-interval-secs=30
```

## 🏗️ Architecture Differences

### Standalone Mode Flow
```
Client Request → REST Controller → LedgerService → SimpleLedgerStateMachine → RocksDB + MySQL
```

### Distributed Mode Flow
```
Client Request → REST Controller → LedgerService → JRaft Node → JRaftLedgerStateMachine → RocksDB + MySQL
                                                      ↓
                                              Consensus with other nodes
```

## 🔄 How JRaft Synchronizes with RocksDB

### 1. **Command Processing**
- All operations (transfers, account creation) are submitted as commands to JRaft
- Commands are replicated across all nodes in the cluster
- Only committed commands are applied to RocksDB

### 2. **State Machine Integration**
```java
@Override
public void onApply(Iterator iterator) {
    while (iterator.hasNext()) {
        String command = new String(iterator.getData().array());
        processCommand(command); // Updates RocksDB
        iterator.next();
    }
}
```

### 3. **Consensus Flow**
1. **Client Request** → REST API
2. **Command Creation** → Format: `TRANSFER:fromUser:fromType:toUser:toType:amount:description`
3. **JRaft Submission** → Command sent to JRaft cluster
4. **Leader Replication** → Leader replicates to followers
5. **Consensus Achievement** → Majority agreement reached
6. **State Machine Apply** → Command applied to RocksDB
7. **MySQL Async Write** → Background batch write to MySQL

### 4. **Data Consistency**
- **RocksDB**: Immediate consistency within the cluster
- **MySQL**: Eventual consistency via async batch writer
- **Snapshots**: Periodic RocksDB state snapshots for recovery

## 🌐 Multi-Node Setup

### Node 1 (Leader Candidate)
```bash
mvn spring-boot:run -Dspring-boot.run.profiles=raft
# Port: 8091, Data: ./raft-data-node1
```

### Node 2 (Additional Profile Needed)
Create `application-raft-node2.properties`:
```properties
server.port=8092
raft.current-node.port=8092
raft.data-path=./raft-data-node2
rocksdb.data-path=./rocksdb-data-node2
```

### Node 3 (Additional Profile Needed)
Create `application-raft-node3.properties`:
```properties
server.port=8093
raft.current-node.port=8093
raft.data-path=./raft-data-node3
rocksdb.data-path=./rocksdb-data-node3
```

## 🧪 Testing JRaft Mode

### 1. Start with JRaft Profile
```bash
mvn spring-boot:run -Dspring-boot.run.profiles=raft
```

### 2. Check JRaft Status
```bash
curl http://localhost:8091/api/raft/status | jq .
```

Expected response:
```json
{
  "nodeId": "node1",
  "groupId": "ledger-raft-group",
  "status": "ACTIVE",
  "enabled": true,
  "role": "LEADER",
  "message": "JRaft node is running"
}
```

### 3. Test Transfer with Consensus
```bash
curl -X POST http://localhost:8091/api/transfer/single \
  -H 'Content-Type: application/json' \
  -d '{
    "fromUserId": "Bank",
    "fromType": "AVAILABLE", 
    "toUserId": "UserA",
    "toType": "AVAILABLE",
    "amount": 100,
    "description": "JRaft consensus transfer"
  }'
```

## 📊 Monitoring JRaft

### Health Endpoints
- `/actuator/health` - Overall health including JRaft
- `/api/raft/status` - Detailed JRaft node status
- `/api/raft/config` - JRaft configuration

### Logs to Monitor
```bash
# JRaft consensus logs
logging.level.com.alipay.sofa.jraft=INFO

# State machine logs  
logging.level.com.example.ledger.state=DEBUG

# Raft manager logs
logging.level.com.example.ledger.raft=DEBUG
```

## 🔍 Troubleshooting

### Common Issues

1. **Port Conflicts**
   - JRaft mode uses port 8091 by default
   - Ensure port is available

2. **Data Directory Conflicts**
   - JRaft creates separate data directories
   - Clean up with: `rm -rf ./raft-data-node1 ./rocksdb-data-node1`

3. **Cluster Formation**
   - Single node will run as standalone leader
   - For true cluster, need multiple nodes

### Debug Commands
```bash
# Check if JRaft is enabled
curl http://localhost:8091/api/raft/config

# Monitor application logs
tail -f logs/application.log | grep -E "(JRaft|Raft)"

# Check data directories
ls -la ./raft-data-node1/
ls -la ./rocksdb-data-node1/
```

## 🎯 Use Cases

### When to Use Standalone Mode (`local`)
- Development and testing
- Single-node deployments
- Simple ledger operations
- No high availability requirements

### When to Use Distributed Mode (`raft`)
- Production environments
- High availability requirements
- Multi-node deployments
- Strong consistency needs
- Fault tolerance requirements

## 🔧 Performance Considerations

### JRaft Mode Performance
- **Latency**: Higher due to consensus overhead
- **Throughput**: Limited by network and consensus
- **Consistency**: Strong consistency guaranteed
- **Availability**: High availability with multiple nodes

### Standalone Mode Performance  
- **Latency**: Lower, direct operations
- **Throughput**: Higher, no consensus overhead
- **Consistency**: Local consistency only
- **Availability**: Single point of failure

## 📝 Next Steps

1. **Test both profiles** to understand the differences
2. **Monitor performance** in your specific environment
3. **Plan cluster topology** for production deployment
4. **Implement proper monitoring** and alerting
5. **Consider backup strategies** for both RocksDB and MySQL data

## Transfer Endpoint Updates

The transfer endpoints (`/api/transfer/single` and `/api/transfer/batch`) now include account existence validation. If either the source or destination account does not exist, the API will return a `404 Not Found` error with a descriptive message, ensuring a better user experience by failing fast before any database operations are attempted. 

## Account Creation

- The `/api/balance/create` endpoint now returns:
  - `200 OK` with message "Account created successfully" when a new account is created
  - `200 OK` with message "Account already exists" when the account already exists
  - `400 Bad Request` with message "Failed to create account" if creation fails

This ensures clear feedback about account existence without treating it as an error case. 

## Idempotency Support

The `/api/transfer/single` endpoint now supports idempotency to prevent duplicate transfers:

### How It Works
- **Custom Idempotency Key**: Provide an `Idempotency-Key` header with a unique identifier
- **Auto-generated Key**: If no key is provided, the system generates one based on request content (SHA-256 hash)
- **Cache Duration**: Results are cached for 60 minutes
- **Duplicate Detection**: Identical requests return the cached response without processing

### Usage Examples
```http
# With custom idempotency key
POST /api/transfer/single
Idempotency-Key: my-unique-key-123
Content-Type: application/json

{
  "fromUserId": "UserA",
  "fromType": "AVAILABLE",
  "toUserId": "UserB", 
  "toType": "AVAILABLE",
  "amount": 100.00,
  "description": "Payment for services"
}

# Auto-generated key (based on request content)
POST /api/transfer/single
Content-Type: application/json

{
  "fromUserId": "UserA",
  "fromType": "AVAILABLE", 
  "toUserId": "UserB",
  "toType": "AVAILABLE",
  "amount": 100.00,
  "description": "Payment for services"
}
```

### Monitoring
- Check idempotency cache statistics: `GET /api/admin/idempotency/stats`
- Automatic cleanup of expired entries every 30 minutes 