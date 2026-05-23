# Ledger Platform — Product Overview

**Version**: 0.1.0-SNAPSHOT
**Architecture**: Raft-based distributed ledger with CQRS + Event Sourcing
**Deployment**: 3-node Raft cluster + MySQL View Layer + Kafka event stream

---

## 1. Platform Vision

Next-generation internal ledger for financial institutions. Designed for:

- **Strong consistency**: Every posting, reversal, adjustment goes through Raft quorum commit
- **High throughput**: 10,000 TPS peak with P95 latency ≤ 3ms
- **Audit trail**: Immutable journal entries, 7-year retention, full traceability
- **Multi-currency**: Real-time balance tracking across currencies and balance types
- **Regulatory compliance**: Maker-checker, period closing, reconciliation L1/L2/L3

---

## 2. Core Concepts

### 2.1 Account

Account represents a ledger entity (client, company, nostro, suspense). Each account:

- Has unique `accountId`
- Tracks multiple `BalanceType` (AVAILABLE, TRADEAHEAD, LOCKED, FROZEN)
- Supports multi-currency balances per type
- Lifecycle: ACTIVE → FROZEN → CLOSED

**Account Operations:**
- Create account with initial balance types
- Freeze / unfreeze (suspend operations)
- Close (requires zero balance)

### 2.2 Balance Type

Balance types define semantics for each balance category:

| BalanceType | Description | allowNegative | zeroFloorEnforce |
|-------------|-------------|---------------|------------------|
| AVAILABLE_BALANCE | Settlement-ready funds | false | true |
| TRADEAHEAD_BALANCE | Pre-trade allocation | true | false |
| LOCKED_BALANCE | Maker-checker pending | false | false |
| FROZEN_BALANCE | Regulatory hold | false | false |

Balance type registry configurable via admin API. Each type has:
- `allowNegative`: Permit overdraft (TRADEAHEAD allows -500,000)
- `overdrawnAlertThreshold`: Trigger PagerDuty when crossed
- `creditLimit`: Max positive balance if applicable

### 2.3 Position (NEW in v0.2)

Position field enables granular balance tracking per account:

| Position | Description | Use Case |
|----------|-------------|----------|
| CURRENT | Real-time operational balance | Available for trading/settlement |
| LOCKED | Pending Maker-Checker approval | Adjustment draft awaiting checker |
| FROZEN | Regulatory/legal hold | AML investigation, court order |

**Position rules:**
- Each JournalLine specifies `position` field
- Balance query can filter by single position or aggregate all positions
- Posting API v2 leg-level structure supports position-aware entries

**Example:**
```
Account: CLIENT_ACC_001
BalanceType: AVAILABLE_BALANCE
Currency: USD

CURRENT:    +5,000.00  ← available now
LOCKED:     +2,000.00  ← pending adjustment approval
FROZEN:     +1,000.00  ← AML hold
Aggregate:  +8,000.00  ← total across positions
```

---

## 3. Key Features

### F-002: Posting API v2

Multi-leg atomic posting with position support:

```json
{
  "requestId": "req-001",
  "businessEventType": "RFQ",
  "valueDate": "2026-05-23",
  "legs": [
    {
      "legId": "leg-1",
      "postingType": "RFQ_CLIENT",
      "lines": [
        {"accountId": "CLIENT_ACC", "balanceType": "AVAILABLE", "position": "CURRENT", "entryType": "DEBIT", "amount": "1000.00", "currency": "USD"},
        {"accountId": "COMPANY_ACC", "balanceType": "AVAILABLE", "position": "CURRENT", "entryType": "CREDIT", "amount": "1000.00", "currency": "USD"}
      ]
    },
    {
      "legId": "leg-2",
      "postingType": "RFQ_COMPANY",
      "lines": [...]
    }
  ]
}
```

**Properties:**
- Atomic across all legs (single Raft commit)
- Idempotent by `requestId` (UUID v7)
- Balance floor enforcement before commit
- Account queue serialization (per-account ordering)

### F-003: Manual Adjustment (Maker-Checker)

Draft → Approve/Reject workflow:

- **Maker**: Creates adjustment draft with reason
- **Checker**: Approves or rejects (different operator required)
- **Draft expiry**: Auto-reject if `expiresAt` passed
- **Audit**: Full maker-checker trail with timestamps

### F-004: Reversal

Reverse posted journal with cross-period support:

- Original journal status → REVERSED
- New journal created with `journalType=REVERSAL`
- `crossPeriod=true` when valueDate crosses accounting period boundary
- Reversal chain trackable via `/ledger/journals/{id}/chain`

### F-005: Balance Query v2

Three query modes:

| Mode | Endpoint | Response Time | Data Source |
|------|----------|---------------|-------------|
| Live | `/ledger/balances?position=CURRENT` | ≤ 2ms | In-memory State Machine |
| As-of | `/ledger/balances/as-of?asOf=2026-05-20` | ≤ 30ms | Learner → MySQL View Layer |
| Batch | `/ledger/balances/batch` | ≤ 50ms | Multiple accounts |

**Position filtering:**
```
GET /ledger/balances?accountId=X&balanceType=AVAILABLE&position=CURRENT
GET /ledger/balances?accountId=X&aggregate=true  ← sum across positions
```

### F-007: Reconciliation L1/L2/L3

Three-level reconciliation:

| Level | Description | Frequency | Output |
|-------|-------------|-----------|--------|
| L1 | RocksDB vs MySQL View Layer | EOD daily | OPEN cases if mismatch |
| L2 | Internal ledger vs external system (SWIFT) | T+1 | Reconciliation report |
| L3 | System-wide balance consistency check | Weekly | All accounts sum to zero |

**Case resolution:**
- OPEN → IN_PROGRESS → RESOLVED / WAIVED
- Resolution creates adjustment journal

### F-009: Accounting Period / EOD

Period lifecycle:

```
OPEN → CLOSING → CLOSED
```

- **OPEN**: Accept new postings
- **CLOSING**: Block new postings, allow reversals only
- **CLOSED**: Immutable, L1 reconciliation must pass

**EOD workflow:**
1. Trigger period close
2. Run L1 reconciliation
3. Block new postings
4. Generate snapshot

---

## 4. Architecture

![Architecture Diagram](docs/architecture-diagram.png)

### 4.1 Write Path (Raft + State Machine)

```
Client → REST API → Account Queue → Raft Leader → State Machine → RocksDB
                                        ↓
                                    Quorum replicate to Followers
                                        ↓
                                    Learner → Kafka → MySQL View Layer
```

**Key components:**
- **Account Queue**: Per-account `LinkedBlockingQueue` ensures serial ordering
- **Raft Cluster**: 3 voting nodes (1 Leader + 2 Followers), quorum = 2
- **State Machine**: In-memory balance cache + RocksDB journal store
- **WriteBatch**: Atomic commit (journal + balance + accountSeq) in single RocksDB batch

### 4.2 Read Path (CQRS)

| Query Type | Path | Latency Target |
|------------|------|----------------|
| Balance (live) | State Machine in-memory | ≤ 2ms |
| Balance (as-of) | Learner → MySQL | ≤ 30ms |
| Journal query | MySQL View Layer | ≤ 30ms |

**Projection Service** (Learner):
- Consumes `BalanceChangeEvent` from Kafka
- Writes to MySQL `journal_line` table (sharded by accountId)
- Provides historical query API on port 8089

### 4.3 Event Stream (Kafka)

Topics:
- `ledger.balance.change.v1`: BalanceChangeEvent with accountSeq for gap detection
- `ledger.posting.completion.v1`: Journal completion for downstream systems

**BalanceChangeEvent payload:**
```json
{
  "accountId": "CLIENT_ACC",
  "balanceType": "AVAILABLE_BALANCE",
  "position": "CURRENT",
  "currency": "USD",
  "balanceBefore": "5000.00",
  "balanceAfter": "4000.00",
  "accountSeq": 1247,
  "journalId": "journal-001",
  "timestamp": "2026-05-23T15:00:00Z"
}
```

**Gap detection:**
- Consumer tracks `accountSeq` per (accountId, balanceType, currency)
- Missing seq → trigger alert (data loss or Kafka lag)

---

## 5. Non-Functional Requirements (NFR)

### Performance

| Metric | Target |
|--------|--------|
| Posting P95 latency | ≤ 3ms |
| Posting P99 latency | ≤ 10ms |
| Balance Query (live) P95 | ≤ 2ms |
| Balance Query (as-of) P95 | ≤ 30ms |
| Posting TPS (peak) | ≥ 10,000 TPS |

### Availability

- Annual availability: ≥ 99.99% (≤ 52 minutes downtime)
- RTO: ≤ 1 minute (snapshot + replay)
- RPO: 0 (Raft quorum commit guarantees durability)

### Consistency

| Operation | Level |
|-----------|-------|
| Posting / Reversal / Adjustment | Strong (Raft quorum) |
| Balance Query (live) | Strong (Leader in-memory) |
| Journal Query | Eventual (Learner lag ≤ 1s) |

### GC & JVM

- **ZGC**: Max pause < 1ms, alert if > 5ms
- Heap: 2GB per node, fixed size
- Virtual Threads: Java 21, Spring Boot 3

---

## 6. Observability

### Prometheus Metrics

Endpoints: `http://localhost:8081/actuator/prometheus`

Key metrics:
- `ledger_posting_duration_seconds` (P50/P95/P99)
- `ledger_balance_query_duration_seconds` (live vs as-of)
- `ledger_account_queue_depth` (per account)
- `ledger_raft_last_applied_index` (replication lag)
- `jvm_gc_pause_seconds_max` (GC health)

### Grafana Dashboard

`http://localhost:3000` — pre-configured panels:
- Posting P95 latency (threshold: red if > 3ms)
- Balance query latency (threshold: red if > 2ms)
- Raft leader status
- Account queue depth (backpressure indicator)
- GC pause time
- JVM heap usage

### Alerting

Critical alerts (PagerDuty):
- Posting P99 > 50ms sustained
- GC pause > 5ms single occurrence
- Account queue depth > 500
- Raft follower lag > 100 entries
- L1 reconciliation OPEN cases exist after EOD

---

## 7. Developer Quick Start

### Run locally (Docker Compose)

```bash
docker-compose up -d
```

Services started:
- MySQL (3306) — View Layer
- Kafka (9092) + Kafka UI (8080)
- Ledger nodes 1/2/3 (8081/8082/8083, Raft ports 28081-28083)
- Projection service (8089)
- Prometheus (9090)
- Grafana (3000)

### First posting

```bash
curl -X POST http://localhost:8081/ledger/postings \
  -H "Content-Type: application/json" \
  -d '{
    "requestId": "req-001",
    "businessEventType": "TRANSFER",
    "valueDate": "2026-05-23",
    "legs": [{
      "legId": "leg-1",
      "postingType": "TRANSFER",
      "lines": [
        {"accountId": "COMPANY_FX_ACC", "balanceType": "AVAILABLE_BALANCE", "position": "CURRENT", "entryType": "DEBIT", "amount": "100.00", "currency": "USD"},
        {"accountId": "CLIENT_ACC_001", "balanceType": "AVAILABLE_BALANCE", "position": "CURRENT", "entryType": "CREDIT", "amount": "100.00", "currency": "USD"}
      ]
    }]
  }'
```

### Check balance

```bash
curl "http://localhost:8081/ledger/balances?accountId=CLIENT_ACC_001&balanceType=AVAILABLE_BALANCE&position=CURRENT&currency=USD"
```

### Monitor Raft status

```bash
curl http://localhost:8081/ledger/cluster/raft-status | jq .
```

---

## 8. Technology Stack

| Component | Technology |
|-----------|------------|
| Language | Java 21 (Virtual Threads) |
| Framework | Spring Boot 3.4 |
| Raft | SOFAJRaft 1.3.15 |
| Storage | RocksDB 9.10 (append-only journal) |
| View Layer | MySQL 8.4 + MyBatis |
| Event Stream | Apache Kafka 3.9 |
| Metrics | Prometheus + Micrometer |
| Dashboards | Grafana |
| Sharding | ShardingSphere-JDBC (journal_line by accountId) |

---

## 9. References

- [Full Requirements](requirement/LEDGER-PLATFORM-FULL-REQUIREMENTS.md)
- [NFR Specification](requirement/NFR-non-functional-requirements.md)
- [ADR-001: Raft + CQRS Architecture](requirement/ADR-001-raft-cqrs-architecture.md)
- [OPS-001: SRE Operational Guidelines](requirement/OPS-001-sre-operational-guidelines.md)
- [TDD Test Cases](requirement/TDD-TEST-CASES.md)
- [Postman Collection](postman/Ledger-Platform.postman_collection.json)

---

## 10. Roadmap

**v0.2 (current):**
- Position field (CURRENT/LOCKED/FROZEN)
- Balance query by position
- Posting API v2 leg-level structure

**v0.3 (planned):**
- Multi-Raft-Group sharding (account groups)
- 5-node cluster (3 voting + 2 learners)
- Cross-DC disaster recovery

**v0.4 (planned):**
- FX rate integration
- Interest accrual engine
- Regulatory reporting APIs