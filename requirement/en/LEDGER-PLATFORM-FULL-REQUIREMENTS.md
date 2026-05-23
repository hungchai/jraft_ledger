# Next-Gen Internal Ledger Platform
## Technical Requirements Specification (Full Document)

**Version**: v0.5  
**Date**: 2026-05-23  
**Status**: Draft for Review  
**System**: Next-Gen Internal Ledger Platform  
**Positioning**: iBank core ledger foundation, supporting multi-entity, multi-product, multi-currency, multi-ledger double-entry bookkeeping

---

## Document Description

This document contains the complete technical requirements specification for the Next-Gen Internal Ledger Platform, covering architectural decisions, functional requirements, and non-functional requirements. All requirements are based on the following core principles:

- **Raft + CQRS + Account-Level Queue Architecture** (ADR-001)
- **Double-entry bookkeeping; debits and credits always balance**
- **Append-only immutable journal entries**
- **Synchronous atomic booking to prevent duplicate payouts**
- **High performance for hotspot accounts (COMPANY_ACC RFQ scenario)**
- **Traceable, reconcilable, and auditable**

### Revision History

| Version | Date | Changes | Author |
|---|---|---|---|
| v0.1 | 2026-05-16 | Initial draft | Ledger Platform Team |
| v0.2 | 2026-05-22 | ADR-001 Section 2.2: Added sofa-common-tools version compatibility note (resolves Spring Boot 3.4.4 + SOFAJRaft 1.3.15 logback conflict) | Ledger Platform Team |
| v0.3 | 2026-05-23 | F-002/F-005/F-008: Added `position` field (CURRENT/LOCKED/FROZEN) to support balance position tracking; AccountBalanceKey expanded to (accountId, balanceType, position, currency); Added validation rule V-13 | Ledger Platform Team |
| v0.4 | 2026-05-23 | Added Core Concepts chapter: defines Account, BalanceType, Position and their relationships | Ledger Platform Team |
| v0.5 | 2026-05-23 | Merged docs/architecture.md, docs/persistence-flow.md into Appendix A/B/C; Added F-013 Idempotency & Hotspot Account Concurrency spec | Ledger Platform Team |

---

## Table of Contents

| Document | Chapter | Description |
|---|---|---|
| Core Concepts | Core Concepts | Account, BalanceType, Position definitions and relationships |
| ADR-001 | Architecture Decision | Rationale for Raft + CQRS + Account-Level Queue selection |
| F-001 | Balance Type Registry | Balance Type configuration management; add new types without code changes |
| F-002 | Posting API v2 | Core booking API supporting Multi-Account atomic Posting |
| F-003 | Manual Adjustment | Manual adjustments with mandatory Maker-Checker approval |
| F-004 | Reversal | Reverse posted journals via append-only offsetting |
| F-005 | Balance Query v2 | Real-time balance queries reading in-memory State Machine |
| F-006 | Journal Query | Journal entry queries with full chain traceability |
| F-007 | Reconciliation | L1/L2/L3 three-layer reconciliation with discrepancy tracking |
| F-008 | State Machine Design | Core Raft State Machine design |
| F-009 | Accounting Period / EOD | Accounting period management and EOD closing flow |
| F-010 | Account Management | Account lifecycle management |
| F-011 | Balance Change Event / Kafka Outbox | Kafka balance change event publishing |
| F-013 | Idempotency & Hotspot Concurrency | Idempotency and hotspot account concurrency handling |
| OPS-001 | SRE Operational Guidelines | RocksDB compaction, Raft recovery, MySQL sync recovery |
| NFR | Non-Functional Requirements | Performance, availability, consistency, security, capacity |
| Appendix A | Module Dependency & Docker Compose | Module dependency graph and deployment architecture |
| Appendix B | Detailed Persistence Flow | Detailed posting persistence flow |
| Appendix C | Overall Architecture (English) | English reference overall architecture diagram |

---

## Core Concepts

This section defines the three core concepts of the Ledger Platform: Account, BalanceType, and Position. These concepts run through all functional requirements (F-001 to F-011).

### Account

Account represents a ledger entity, such as a client account, company account, Nostro account, or Suspense account.

**Core Attributes:**
- `accountId`: unique identifier (e.g. `CLIENT_ACC_001`, `COMPANY_FX_ACC`)
- `status`: lifecycle status (`ACTIVE` → `FROZEN` → `CLOSED`)
- `balanceTypes`: list of supported balance types (configured at initialisation)
- `currencies`: list of supported currencies
- `metadata`: client info, legal-entity code, product code, etc.

**Lifecycle:**

```
CREATE → ACTIVE → FROZEN → CLOSED
          ↑         ↓
        UNFREEZE
```

- **CREATE**: create Account, initialise BalanceType (F-010)
- **ACTIVE**: accepts Posting, Query
- **FROZEN**: pauses all operations (AML investigation, legal freeze)
- **CLOSED**: balance must be 0 before closing; no further operations allowed

**See F-010 Account Management for detailed specification.**

---

### BalanceType

BalanceType defines the business semantics and constraint rules of a balance. Each Account may have multiple BalanceTypes.

**Core Attributes:**
- `typeCode`: balance type code (e.g. `AVAILABLE_BALANCE`, `TRADEAHEAD_BALANCE`)
- `allowNegative`: whether negative balance (overdraft) is allowed
- `zeroFloorEnforce`: whether balance is forced ≥ 0
- `overdrawnAlertThreshold`: negative-balance alert threshold (e.g. -500,000)
- `creditLimit`: positive-balance upper limit (e.g. 1,000,000)

**Default BalanceTypes:**

| typeCode | allowNegative | zeroFloorEnforce | overdrawnAlertThreshold | Purpose |
|---|---|---|---|---|
| AVAILABLE_BALANCE | false | true | N/A | available balance (settlement, withdrawal) |
| TRADEAHEAD_BALANCE | true | false | -500,000 | trade pre-positioning (RFQ pre-debit) |
| LOCKED_BALANCE | false | false | N/A | Maker-Checker pending-approval balance |
| FROZEN_BALANCE | false | false | N/A | regulatory frozen balance |

**Constraint Rules:**

```
allowNegative=false:
  → reject Posting if afterBalance < 0 (INSUFFICIENT_BALANCE)

allowNegative=true:
  → allow negative balance down to overdrawnAlertThreshold
  → trigger PagerDuty alert if threshold exceeded

zeroFloorEnforce=true:
  → force balance ≥ 0, even when allowNegative=true
```

**See F-001 Balance Type Registry for detailed specification.**

---

### Position

Position is a field added in v0.4 to distinguish different sub-balance states under the same BalanceType.

**Position Types:**

| Position | Description | Usage Scenario |
|---|---|---|
| CURRENT | immediately available balance | normal trading, settlement, withdrawal |
| LOCKED | locked pending-approval balance | Maker-Checker adjustment draft pending approval |
| FROZEN | regulatory frozen balance | AML investigation, court order, regulatory freeze |

**JournalLine Structure (v0.4):**

```json
{
  "accountId": "CLIENT_ACC_001",
  "balanceType": "AVAILABLE_BALANCE",
  "position": "CURRENT",
  "currency": "USD",
  "entryType": "DEBIT",
  "amount": "1000.00"
}
```

**AccountBalanceKey Expansion:**

```
AccountBalanceKey = (accountId, balanceType, position, currency)
```

Each Account maintains an independent balance for every BalanceType + Position + Currency combination.

**Balance Aggregate Query:**

```
GET /ledger/balances?accountId=X&balanceType=AVAILABLE&position=CURRENT
  → returns CURRENT sub-balance

GET /ledger/balances?accountId=X&aggregate=true
  → returns sum of all Positions (CURRENT + LOCKED + FROZEN)
```

**See F-002 Posting API v2 §3.4 Position Field and F-005 Balance Query v2 for detailed specification.**

---

### Core Concepts Summary Table

| Concept | Definition | Related Feature |
|---|---|---|
| Account | ledger entity, lifecycle ACTIVE/FROZEN/CLOSED | F-010 Account Management |
| BalanceType | balance type, defines overdraft/creditLimit rules | F-001 Balance Type Registry |
| Position | sub-balance position CURRENT/LOCKED/FROZEN | F-002 Posting v2, F-005 Balance Query |

**Relationship:**

```
Account
  └─ balanceTypes: [AVAILABLE, TRADEAHEAD, LOCKED, FROZEN]
      └─ positions: [CURRENT, LOCKED, FROZEN]
          └─ currencies: [USD, HKD, EUR]
              └─ balance: 5000.00
```

Each balance is uniquely located by a four-dimensional key:

```
(accountId, balanceType, position, currency) → balance
```

---

## System Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              CLIENT LAYER                                    │
│          HTTP/gRPC  →  Posting / Reversal / Adjustment / Query               │
└───────────────────────────────────┬─────────────────────────────────────────┘
                                    │
                    ┌───────────────┴───────────────┐
                    ▼                               ▼
         ┌────────────────────┐          ┌────────────────────┐
         │   Write Request    │          │   Read Request     │
         │  (Posting/Rev/Adj) │          │ (Balance / Journal)│
         └─────────┬──────────┘          └─────────┬──────────┘
                   │                               │
                   ▼                               ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                        LEDGER WRITE DOMAIN (Raft Cluster)                    │
│                                                                              │
│   ┌─────────────────────────────┐   ┌───────────────────────────────────┐   │
│   │   Ledger RESTful Controller │   │        SOFAJRaft Cluster          │   │
│   │  ┌─────────────────────┐    │   │  ┌────────┐    ┌─────────────┐   │   │
│   │  │ AccountQueueManager │    │   │  │ Leader │◄──►│  Follower   │   │   │
│   │  │(LinkedBlockingQueue │    │   │  │        │    │             │   │   │
│   │  │ per account + VT)   │    │   │  │ RocksDB│    │  RocksDB    │   │   │
│   │  └──────────┬──────────┘    │   │  └───┬────┘    └──────┬──────┘   │   │
│   │             │               │   │      │                │          │   │
│   │             ▼               │   │      │  Raft Log      │          │   │
│   │  ┌─────────────────────┐    │   │      ▼                ▼          │   │
│   │  │ LedgerRaftStateMach │◄───┼───┼────►┌─────────────────────────┐  │   │
│   │  │ · onApply()         │    │   │     │      Learner (non-voting)│  │   │
│   │  │ · snapshotSave/Load │    │   │     │  Async sync → Kafka      │  │   │
│   │  └──────────┬──────────┘    │   │     └─────────────────────────┘  │   │
│   │             │               │   └───────────────────────────────────┘   │
│   │             ▼               │                                           │
│   │  ┌─────────────────────┐    │                                           │
│   │  │  LedgerStateMachine │    │                                           │
│   │  │  · applyPosting()   │    │                                           │
│   │  │  · applyReversal()  │    │                                           │
│   │  └──────────┬──────────┘    │                                           │
│   │             │               │                                           │
│   │    ┌────────┼────────┐      │                                           │
│   │    ▼        ▼        ▼      │                                           │
│   │ ┌──────┐ ┌──────┐ ┌────────┐│                                           │
│   │ │balSt │ │jourSt│ │idempSt ││                                           │
│   │ └──┬───┘ └──┬───┘ └───┬────┘│                                           │
│   │    │        │         │     │                                           │
│   │    ▼        ▼         │     │                                           │
│   │ ┌────────┐ ┌────────┐ │     │                                           │
│   │ │RocksDB │ │ Kafka  │◄┘     │                                           │
│   │ │(WAL)   │ │ Producer      │                                           │
│   │ └────────┘ └────────┘       │                                           │
│   └─────────────────────────────┘                                           │
│                                                                              │
└───────────────────────────────────┬─────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                        PROJECTION / READ DOMAIN                              │
│                                                                              │
│   ┌─────────────────────────────┐   ┌───────────────────────────────────┐   │
│   │   ProjectionConsumer        │   │   ProjectionQueryController       │   │
│   │   (Kafka Listener)          │   │   · /query/journal                │   │
│   │   · onBalanceChange()       │   │   · /query/balance                │   │
│   │   · onAccountCreated()      │   └───────────────────────────────────┘   │
│   └─────────────┬───────────────┘                                           │
│                 │                                                            │
│                 ▼                                                            │
│   ┌─────────────────────────────────────────────────────────────────────┐   │
│   │              ShardingSphere-JDBC  →  MySQL View Layer                │   │
│   │                                                                      │   │
│   │   ┌──────────┐  ┌──────────────┐  ┌──────────────────────────────┐   │   │
│   │   │  journal │  │account_balance│  │ journal_line_0 .. _3 (sharded)│   │   │
│   │   │ (single) │  │   (single)    │  │     by account_id hash       │   │   │
│   │   └──────────┘  └──────────────┘  └──────────────────────────────┘   │   │
│   └─────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│   Query Paths:                                                               │
│   ┌─────────────────┐      ┌─────────────────────────────────────────────┐   │
│   │ Balance Query   │─────→│  Read Leader in-memory State Machine        │   │
│   │ (real-time)     │      │  Strong consistency · P95 ≤ 2ms             │   │
│   └─────────────────┘      └─────────────────────────────────────────────┘   │
│                                                                              │
│   ┌─────────────────┐      ┌─────────────────────────────────────────────┐   │
│   │ Journal Query   │─────→│  Read MySQL View Layer                      │   │
│   │ (eventual)      │      │  Eventual consistency · lag ≤ 1s            │   │
│   └─────────────────┘      └─────────────────────────────────────────────┘   │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

# ADR-001 Ledger Core Architecture Decision: Raft + CQRS + Account-Level Queue

**Decision Status**: Accepted  
**Decision Date**: 2026-05-16  
**Decision Owner**: Ledger Platform Team  
**Impact Scope**: F-002 Posting, F-003 Manual Adjustment, F-004 Reversal, F-005 Balance Query, F-006 Journal Query, F-007 Reconciliation, F-008 State Machine

---

## 1. Background and Problem

The Internal Ledger Platform must simultaneously satisfy three conflicting requirements:

1. **Synchronous atomic booking**: Every Posting / Reversal / Adjustment must be fully atomic; asynchronous booking is not allowed
2. **RFQ company account hotspot**: All client RFQ trades settle against the same company account; traditional DB row locks cause severe lock contention under high concurrency
3. **Extremely low latency**: Posting P95 ≤ 3ms, Balance Query P95 ≤ 2ms

Traditional Spring Boot multi-node + PostgreSQL solutions cannot simultaneously satisfy atomicity and low latency under hotspot accounts; Shard / Rebalancing solutions introduce fund management complexity.

---

## 2. Decision

Adopt the **Raft + CQRS + Account-Level In-Memory Queue** architecture, referencing the Binance Ledger production solution.

### 2.1 Core Principles

- **Write Path**: All ledger write operations (Posting / Reversal / Adjustment) go through the Raft consensus protocol, serialized by the Leader node's in-memory State Machine, persisted to RocksDB, without direct writes to MySQL
- **Read Path**: Balance queries read directly from the Leader in-memory State Machine; Journal / reconciliation queries read from the MySQL View Layer
- **Sync Mechanism**: Raft Learner nodes listen to the Raft Log and asynchronously sync to MySQL for query and reconciliation use
- **Account Serialization**: Each account maintains an in-memory queue processed by a single virtual thread, eliminating account-level concurrency conflicts

### 2.2 Raft Library Selection

Adopt **SOFAJRaft** (Ant Group / Alibaba open source), rationale:
- Native Java, integrates well with Spring Boot
- Supports Multi-Raft-Group, enabling horizontal scaling by account grouping
- Production validated (same tech stack as Ant Financial)
- Supports Learner role, suitable for CQRS read-write separation

**Dependency Version Management**:
- SOFAJRaft 1.3.15 depends on sofa-common-tools for log initialization
- sofa-common-tools 1.0.12 (SOFAJRaft default) is incompatible with Spring Boot 3.4.4's logback 1.5.x
- **Solution**: Override sofa-common-tools to 2.1.1+ in `pom.xml` `dependencyManagement`
- sofa-common-tools 2.1.1 supports logback 1.5.x, removes call to `ContextInitializer.configureByResource(URL)`

### 2.3 Overall Architecture

```
Client Request
      |
      ▼
┌─────────────────────────────────────────────────┐
│              Ledger Write Domain                 │
│                                                  │
│  ┌────────────┐   ┌──────────────────────────┐  │
│  │   Network  │   │       Raft Cluster        │  │
│  │   Layer    │   │  ┌────────┐  ┌─────────┐ │  │
│  │ (gRPC/HTTP)│   │  │ Leader │  │Follower │ │  │
│  └─────┬──────┘   │  │        │  │         │ │  │
│        │          │  │RocksDB │  │ RocksDB │ │  │
│  ┌─────▼──────┐   │  └────────┘  └─────────┘ │  │
│  │   Ledger   │◄──►       ↕ Raft Log          │  │
│  │   Layer    │   │  ┌─────────┐              │  │
│  │            │   │  │ Learner │ non-voting   │  │
│  │  Account   │   │  └────┬────┘              │  │
│  │  Queue     │   └───────│───────────────────┘  │
│  │  (per acct)│           │ async push            │
│  │            │           ▼                      │
│  │  State     │   ┌───────────────┐              │
│  │  Machine   │   │  View Layer   │              │
│  │ (in-memory │   │  (MySQL)      │              │
│  │  balance)  │   │  journal_line │              │
│  └────────────┘   │  account_bal  │              │
│                   │  snapshot     │              │
└───────────────────┴───────────────┴──────────────┘
         ↑ Write                  ↑ Read
    Posting / Rev / Adj      Journal / Recon / Report
```

---

## 3. Write Path Details

### 3.1 Request Pipeline

```
1. Network Layer     Receive HTTP/gRPC request, deserialize, place in request_queue
2. Ledger Layer      Take from request_queue, perform pre-validation (schema / auth)
                     Route to corresponding Account Queue by accountId
3. Account Queue     One LinkedBlockingQueue per account
                     Each queue corresponds to one Java 21 Virtual Thread (worker)
4. Account Worker    Take task from queue, execute serially:
                     a. Idempotency check (in-memory idempotency map)
                     b. Balance validation (read in-memory State Machine)
                     c. Prepare Raft Command (serialize ledger instruction)
5. Raft Layer        Submit Command to Raft Group
                     Leader replicates to Follower, commit after Quorum
6. State Machine     apply Raft log, execute ledger calculation:
                     a. Update in-memory balance
                     b. Generate journal_line record
                     c. Write RocksDB (persistence)
7. Response          Return result to client via response_queue
```

### 3.2 Multi-Account Journal Atomicity

RFQ scenario involves CLIENT_ACC + COMPANY_ACC two accounts:

```
Problem: Two accounts may be in different Account Queues; how to guarantee atomicity?

Solution: Two-Phase Locking via Account Queue Coordinator

Phase 1 – Lock (sort by accountId ascending to avoid deadlock):
  1. Sort CLIENT_ACC and COMPANY_ACC by ID ascending
  2. Submit to respective Account Queues sequentially and obtain "lock tickets"
  3. After both queues are ready, submit a single Multi-Account Raft Command

Phase 2 – Apply (single Raft Command):
  One Raft Command contains all JournalLines
  State Machine apply updates all accounts' in-memory balances at once
  → Externally appears as atomic
```

---

## 4. Read Path Details

### 4.1 Balance Query

- Read directly from Leader's in-memory State Machine
- No RocksDB, no MySQL
- P95 target: ≤ 2ms (pure in-memory read)

### 4.2 Journal / Audit / Reconciliation Query

- Read from MySQL View Layer (asynchronously synced by Learner)
- May have slight delay (typically < 1 second)
- P95 target: ≤ 100ms

### 4.3 Consistency Guarantees

- Balance Query is strongly consistent (read Leader in-memory, always latest)
- Journal Query is eventually consistent (Learner async sync, may lag slightly)
- Reconciliation scenarios allow eventual consistency (T+0 reconciliation allows minute-level delay)

---

## 5. High Availability Strategy

### 5.1 Raft Cluster Configuration

```
Standard config: 3 nodes (1 Leader + 2 Follower)
HA config: 5 nodes (1 Leader + 2 Follower + 2 Learner)

Quorum = (N+1)/2:
  3 nodes → Quorum = 2, allows 1 node failure
  5 nodes → Quorum = 3, allows 2 node failures

Learner does not participate in voting, does not affect Quorum calculation
```

### 5.2 Leader Failure Recovery

```
Leader down → Raft automatically elects new Leader
Election time: typically 150–300ms (SOFAJRaft default)
New Leader recovers State Machine from RocksDB + Raft Log replay
Recovery time: depends on number of logs since last snapshot
→ Periodic State Machine Snapshots needed to control replay time
```

### 5.3 In-flight Request Handling

- During Leader switch, in-flight Raft Commands that haven't reached Quorum will return errors to the client
- Client retries with idempotencyKey, new Leader processes normally (idempotency guarantee)

---

## 6. Persistence Strategy

### 6.1 RocksDB (Write Domain)

- Stores Raft Log + State Machine Snapshot
- Each journal_line written as WAL to ensure crash recovery via replay
- Periodic State Machine Snapshots to prevent unbounded Raft Log growth

### 6.2 MySQL (View Layer)

- Written asynchronously by Learner
- Stores complete journals, account_balance snapshots, reconciliation reports
- Read-only usage, not on the write path

### 6.3 Data Guarantee

```
RocksDB: Strong durability, the single source of truth for ledger data
MySQL: Eventually consistent query view, can be rebuilt from RocksDB replay
```

---

## 7. Technical Constraints

| Constraint | Description |
|---|---|
| All ledger write operations must go through Raft | Direct MySQL writes bypassing Raft are prohibited |
| Balance queries must read in-memory State Machine | Reading MySQL balance is prohibited (may be stale) |
| Account Worker must be single-threaded serial | Concurrent execution of multiple journals on the same account is prohibited |
| No ORM | Hibernate / JPA prohibited; RocksDB uses RocksDB Java API, MySQL uses MyBatis |
| State Machine Snapshot interval ≤ 100,000 Raft Logs | Control failure recovery time |

---

## 8. Risks and Mitigations

| Risk | Severity | Mitigation |
|---|---|---|
| Leader single-node throughput ceiling | Medium | Multi-Raft-Group horizontal scaling by account grouping |
| State Machine out of memory | Medium | Only retain active account balances; cold accounts loaded from RocksDB on demand |
| Learner sync delay causing Journal query inconsistency | Low | Indicate query timestamp, note "eventual consistency" |
| RocksDB corruption | Low | Multi-replica Raft, any Follower can be used for data recovery |
| SOFAJRaft version discontinuation risk | Low | Evaluate Apache Ratis as alternative |

---

## 9. Alternatives Considered

| Option | Reason for Rejection |
|---|---|
| Traditional Spring Boot + PostgreSQL row lock | Cannot meet P95 under hotspot accounts |
| Shard + Rebalancing | Fund management complexity, rebalancing introduces funding gap risk |
| Pre-authorized Limit + async Settlement | Does not meet "synchronous atomic booking" requirement |
| Redis as balance cache | Consistency risk unacceptable (financial scenario) |
| LMAX Disruptor | Solves thread messaging latency but not DB round-trip; not applicable |


---

# F-001 Balance Type Registry — Functional Requirements Specification

**Document Version**: v0.2  
**Feature**: F-001 Balance Type Registry  
**System**: Next-Gen Internal Ledger Platform  
**Positioning Note**: This Ledger is a pure Booking Engine, positioned as a high-frequency, high-concurrency ledger processing core. Configuration management, Limit control, Maker-Checker, and client-level Overrides are the responsibility of upstream Domains; this system does not handle them.  
**Status**: Draft for Review

---

## 1. Feature Overview

This feature defines the "Balance Type Registry" mechanism, allowing the Ledger Platform to add, modify, or deactivate Balance Types via pure configuration, **without modifying any Source Code and without redeployment**.

Each Balance Type has a set of independent behavioral attributes, such as: whether negative values are allowed, sign direction, calculation source rules, visibility scope, posting type mapping, etc. The system reads configuration from the Registry uniformly during Balance calculation and validation, dynamically applying corresponding rules via the strategy pattern.

### System Boundary

| Responsibility | This Ledger | Upstream Domain |
|---|---|---|
| Balance calculation and booking | ✅ | ❌ |
| Balance Type configuration read and execution | ✅ | ❌ |
| Balance Type configuration add/modify | ✅ (provides API) | ✅ (caller) |
| Maker-Checker approval flow | ❌ | ✅ |
| Client/account-level Limit control | ❌ | ✅ |
| Account opening, client KYC | ❌ | ✅ |

> **Design Principle**: The Ledger only knows "execute what the configuration says". The correctness of business rules is the responsibility of the configurator (upstream Domain); the Ledger is responsible for executing efficiently and correctly.

### Design Goals

- **Extensibility**: Adding a new Balance Type only requires calling the Registry API to add a configuration record, no code change / release needed.
- **High Performance**: Registry configuration is fully cached at the application layer; the Balance calculation path has zero DB queries, supporting 100,000 concurrent level.
- **Traceability**: Each configuration has complete version history; Balance calculation results carry `configVersion`, traceable to the calculation rule version.
- **Hot Update**: Configuration changes take effect across all nodes within 5 seconds, no restart needed.

---

## 2. Core Concepts

### 2.1 What is a Balance Type

A Balance Type is a balance view of an account under a specific business perspective, for example:

| Balance Type | Business Meaning | Typical Scenario |
|---|---|---|
| `AVAILABLE_BALANCE` | Customer's currently available amount | Payout validation, credit judgment |
| `CURRENT_BALANCE` | Account's actual book balance | Accounting reconciliation, EOD settlement |
| `PENDING_BALANCE` | Amount not yet settled but held | In-trade, T+N settlement |
| `HOLD_BALANCE` | Amount frozen / legally seized | Compliance freeze, collateral |
| `BROKERAGE_BALANCE` | Securities-related available amount | Brokerage business |
| `TRADE_AHEAD_BALANCE` | Trade pre-positioning (negative balance) | Short selling, pre-authorized credit |
| `COLLATERAL_BALANCE` | Collateral calculation balance | Margin Lending |
| `SHADOW_BALANCE` | Shadow ledger / management ledger view | Internal reports, regulatory filing |

The above are examples only; the system **does not hard-code any type names or logic**; all types are dynamically executed from Registry configuration.

---

## 3. Balance Type Configuration Attributes (Registry Schema)

### 3.1 Basic Identity Attributes

| Attribute | Type | Required | Description |
|---|---|---|---|
| `typeCode` | `string` | ✅ | Globally unique, uppercase snake_case, e.g. `TRADE_AHEAD_BALANCE`. Immutable after creation. |
| `displayName` | `i18n map` | ✅ | Multi-language display name, e.g. `{"en": "Trade Ahead Balance", "zh-HK": "交易前置餘額"}` |
| `description` | `string` | ✅ | Business description for documentation and audit reports |
| `category` | `enum` | ✅ | `ACTUAL` / `PROJECTED` / `RESERVED` / `SHADOW` |
| `status` | `enum` | ✅ | `ACTIVE` / `INACTIVE` / `DEPRECATED` |
| `effectiveFrom` | `datetime` | ✅ | Effective date |
| `effectiveTo` | `datetime` | ❌ | Expiration date, null means long-term valid |

### 3.2 Sign and Direction Attributes (Core)

| Attribute | Type | Required | Description |
|---|---|---|---|
| `signConvention` | `enum` | ✅ | `NORMAL_CREDIT`: credit increases balance (standard) / `NORMAL_DEBIT`: debit increases balance (reverse) |
| `allowNegative` | `boolean` | ✅ | Whether negative balance is allowed |
| `negativeSemantics` | `enum` | Conditional | Required when `allowNegative=true`; business meaning of negative: `OVERDRAFT` / `SHORT_POSITION` / `PRE_AUTHORIZED` / `CREDIT_UTILIZATION` |
| `zeroFloorEnforce` | `boolean` | ✅ | Whether to enforce a zero floor (automatically false when `allowNegative=true`) |
| `overdrawnAlertThreshold` | `decimal` | ❌ | Overdraft alert threshold (negative), e.g. `-500000`, only effective when `allowNegative=true` |

**Sign Semantics Constraints (applicable to all Posting paths, including Manual Adjustment):**

```
IF allowNegative=false:
  Any Posting / Adjustment must not result in Balance < 0
  Violation: reject request, return BALANCE_FLOOR_BREACH

IF allowNegative=true:
  Any Posting / Adjustment must not result in Balance > 0
  (Such Balances are by business definition always negative or zero; positive violates business semantics)
  Violation: reject request, return BALANCE_CEILING_BREACH
```

> These two rules are **hard non-bypassable validations** of the system, no exceptions, no forced override.

**TRADE_AHEAD_BALANCE Example:**

```
signConvention     = NORMAL_DEBIT     → debit booking = balance increase (pre-positioning increase)
allowNegative      = true             → normal state, negative balance is not abnormal
negativeSemantics  = PRE_AUTHORIZED   → negative = valid pre-authorized utilization
zeroFloorEnforce   = false            → no zero floor
overdrawnAlertThreshold = -500000     → alert only when exceeding this value
```

### 3.3 Calculation Source Rules (Composition Rules)

| Attribute | Type | Required | Description |
|---|---|---|---|
| `compositionLogic` | `enum` | ✅ | `SUM`: sum entries matching criteria / `FORMULA`: formula calculation based on other Balance Types |
| `compositionRules` | `list<CompositionRule>` | Conditional | Required when `compositionLogic=SUM` |
| `formula` | `string` | Conditional | Required when `compositionLogic=FORMULA`, only allows referencing defined `typeCode`, e.g. `CURRENT_BALANCE - HOLD_BALANCE - PENDING_BALANCE` |

**CompositionRule Structure:**

| Attribute | Type | Description |
|---|---|---|
| `includedPostingTypes` | `list<string>` | Posting types included in calculation |
| `excludedPostingTypes` | `list<string>` | Explicitly excluded posting types |
| `includedEntryStates` | `list<enum>` | Included journal entry states: `CONFIRMED` / `PENDING` / `PROVISIONAL` |
| `sign` | `enum` | Contribution direction of this rule: `ADD` / `SUBTRACT` |

> **Formula Safety Limit**: formula only supports arithmetic references to existing `typeCode`, no arbitrary expressions. Circular references are validated and rejected at configuration write time.

> **Implementation Status**: As of the current codebase, only `INDEPENDENT` balance types are supported. `FORMULA` composition logic (e.g. `CURRENT_BALANCE - HOLD_BALANCE - PENDING_BALANCE`) is documented here as the intended design but is **not yet implemented**. All balance types are currently treated as directly-postable buckets.

### 3.4 Currency Attributes

| Attribute | Type | Required | Description |
|---|---|---|---|
| `currencyScope` | `enum` | ✅ | `SINGLE_CCY` / `MULTI_CCY` / `BASE_CCY_ONLY` |
| `fxRevaluationEnabled` | `boolean` | ✅ | Whether FX Revaluation is supported |
| `fxRevaluationRateSource` | `enum` | ❌ | `MID_RATE` / `BID_RATE` / `ASK_RATE` / `CLOSING_RATE` |

### 3.5 Visibility and Access Control

| Attribute | Type | Required | Description |
|---|---|---|---|
| `visibilityScope` | `list<enum>` | ✅ | `INTERNAL_ONLY` / `PRODUCT_API` / `CLIENT_FACING` / `REGULATORY` |
| `queryableByClient` | `boolean` | ✅ | Whether this Balance can be returned via client query API |
| `requiredPermissions` | `list<string>` | ❌ | Permission codes required to read this Balance |

### 3.6 Alert and Monitoring Attributes

| Attribute | Type | Required | Description |
|---|---|---|---|
| `monitoringEnabled` | `boolean` | ✅ | Whether balance monitoring is enabled |
| `alertRules` | `list<AlertRule>` | ❌ | Alert rule list |

**AlertRule Structure:**

| Attribute | Type | Description |
|---|---|---|
| `condition` | `enum` | `BELOW_THRESHOLD` / `ABOVE_THRESHOLD` / `EQUALS_ZERO` / `NEGATIVE` |
| `threshold` | `decimal` | Threshold amount |
| `severity` | `enum` | `INFO` / `WARNING` / `CRITICAL` |
| `notificationChannel` | `list<string>` | `["EMAIL", "PAGERDUTY", "SLACK"]` |

### 3.7 Snapshot and Cache Attributes

| Attribute | Type | Required | Description |
|---|---|---|---|
| `snapshotEnabled` | `boolean` | ✅ | Whether periodic snapshots are enabled |
| `snapshotFrequency` | `enum` | ❌ | `EOD` / `INTRADAY_HOURLY` / `ON_CHANGE` |
| `cacheEnabled` | `boolean` | ✅ | Whether in-memory caching of this Balance is allowed |
| `cacheTtlSeconds` | `integer` | ❌ | Cache TTL in seconds, 0 means no cache |

### 3.8 Version Control Attributes

| Attribute | Type | Description |
|---|---|---|
| `configVersion` | `integer` | Auto-incremented on each modification, starting from 1 |
| `createdBy` | `string` | Creator operator ID (passed by upstream Domain) |
| `createdAt` | `datetime` | Creation time |
| `lastModifiedBy` | `string` | Last modifier |
| `lastModifiedAt` | `datetime` | Last modification time |
| `changeReason` | `string` | Change reason must be filled for each modification |

---

## 4. Complete Configuration Examples

### 4.1 AVAILABLE_BALANCE (Formula-based, negative not allowed)

```json
{
  "typeCode": "AVAILABLE_BALANCE",
  "displayName": {"en": "Available Balance", "zh-HK": "可用餘額"},
  "category": "PROJECTED",
  "status": "ACTIVE",
  "signConvention": "NORMAL_CREDIT",
  "allowNegative": false,
  "zeroFloorEnforce": true,
  "compositionLogic": "FORMULA",
  "formula": "CURRENT_BALANCE - HOLD_BALANCE - PENDING_BALANCE",
  "currencyScope": "SINGLE_CCY",
  "fxRevaluationEnabled": false,
  "visibilityScope": ["PRODUCT_API", "CLIENT_FACING"],
  "queryableByClient": true,
  "snapshotEnabled": false,
  "cacheEnabled": true,
  "cacheTtlSeconds": 5,
  "monitoringEnabled": true,
  "alertRules": [
    {"condition": "BELOW_THRESHOLD", "threshold": 0, "severity": "WARNING", "notificationChannel": ["SLACK"]}
  ]
}
```

### 4.2 TRADE_AHEAD_BALANCE (Negative balance type, always negative or zero)

```json
{
  "typeCode": "TRADE_AHEAD_BALANCE",
  "displayName": {"en": "Trade Ahead Balance", "zh-HK": "交易前置餘額"},
  "category": "RESERVED",
  "status": "ACTIVE",
  "signConvention": "NORMAL_DEBIT",
  "allowNegative": true,
  "negativeSemantics": "PRE_AUTHORIZED",
  "zeroFloorEnforce": false,
  "overdrawnAlertThreshold": -500000.00,
  "compositionLogic": "SUM",
  "compositionRules": [
    {
      "includedPostingTypes": ["TRADE_COMMITMENT", "TRADE_EXECUTION"],
      "includedEntryStates": ["CONFIRMED", "PENDING"],
      "sign": "SUBTRACT"
    }
  ],
  "currencyScope": "SINGLE_CCY",
  "fxRevaluationEnabled": false,
  "visibilityScope": ["INTERNAL_ONLY", "PRODUCT_API"],
  "queryableByClient": false,
  "requiredPermissions": ["TRADE_BALANCE_READ"],
  "snapshotEnabled": true,
  "snapshotFrequency": "EOD",
  "cacheEnabled": true,
  "cacheTtlSeconds": 2,
  "monitoringEnabled": true,
  "alertRules": [
    {"condition": "BELOW_THRESHOLD", "threshold": -500000.00, "severity": "CRITICAL", "notificationChannel": ["PAGERDUTY", "EMAIL"]}
  ]
}
```

### 4.3 HOLD_BALANCE (Freeze type, negative not allowed)

```json
{
  "typeCode": "HOLD_BALANCE",
  "displayName": {"en": "Hold Balance", "zh-HK": "凍結餘額"},
  "category": "RESERVED",
  "status": "ACTIVE",
  "signConvention": "NORMAL_CREDIT",
  "allowNegative": false,
  "zeroFloorEnforce": true,
  "compositionLogic": "SUM",
  "compositionRules": [
    {
      "includedPostingTypes": ["COMPLIANCE_FREEZE", "LEGAL_HOLD", "COLLATERAL_PLEDGE"],
      "includedEntryStates": ["CONFIRMED"],
      "sign": "ADD"
    }
  ],
  "currencyScope": "SINGLE_CCY",
  "fxRevaluationEnabled": false,
  "visibilityScope": ["INTERNAL_ONLY", "REGULATORY"],
  "queryableByClient": false,
  "requiredPermissions": ["COMPLIANCE_BALANCE_READ"],
  "snapshotEnabled": true,
  "snapshotFrequency": "EOD",
  "cacheEnabled": true,
  "cacheTtlSeconds": 30,
  "monitoringEnabled": false
}
```

---

## 5. High-Frequency Performance Design (100,000 Concurrent)

### 5.1 Three-Layer Cache Architecture

```
Request path (reading Registry during Balance calculation):

L1: Process-level in-memory cache (local to each service instance)
    → Target hit rate: > 99.9%
    → Update method: actively refresh after subscribing to config_updated event
    → Data structure: HashMap<typeCode, BalanceTypeConfig>, lock-free read

L2: Distributed cache (Redis Cluster)
    → Fallback when L1 miss
    → Key: ledger:registry:balance_type:{typeCode}
    → TTL: 60s (passive expiration as safety net)

L3: DB (PostgreSQL / Aurora)
    → Source of truth
    → Only queried when both L1/L2 miss (not reached in normal path)
```

### 5.2 Configuration Hot Update Flow

```
1. Upstream Domain calls PUT /admin/ledger/balance-types/{typeCode}
2. Ledger Registry Service validates configuration legality (circular reference, schema validation)
3. Write to DB + history snapshot
4. Publish config_updated event to Message Bus
5. All Ledger Engine nodes subscribe and complete L1 refresh within 5 seconds
SLA: Configuration change from write to full-node effective ≤ 5 seconds
```

---

## 6. Data Model

### 6.1 balance_type_registry Table (MySQL 8.0)

All View Layer tables adopt the following audit column design:
- `id BIGINT AUTO_INCREMENT PRIMARY KEY`: surrogate key for internal joins and sharding
- `created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)`: creation time
- `updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)`: last update time
- Business keys (e.g. `type_code`, `account_id`, `journal_id`) are `NOT NULL UNIQUE`, not used as primary keys

```sql
CREATE TABLE balance_type_registry (
  id                           BIGINT AUTO_INCREMENT PRIMARY KEY,
  type_code                    VARCHAR(64)   NOT NULL UNIQUE,
  display_name                 JSON          NOT NULL,
  description                  TEXT          NOT NULL,
  category                     VARCHAR(32)   NOT NULL,
  status                       VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE',
  sign_convention              VARCHAR(32)   NOT NULL,
  allow_negative               BOOLEAN       NOT NULL DEFAULT FALSE,
  negative_semantics           VARCHAR(32),
  zero_floor_enforce           BOOLEAN       NOT NULL DEFAULT TRUE,
  currency_scope               VARCHAR(32)   NOT NULL,
  config_version               INTEGER       NOT NULL DEFAULT 1,
  created_by                   VARCHAR(64)   NOT NULL,
  created_at                   TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at                   TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  last_modified_by             VARCHAR(64),
  last_modified_at             TIMESTAMP(6),
  change_reason                TEXT          NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

> **Complete View Layer Schema**: See project root `init.sql`, containing full DDL for `journal`, `journal_line`, `account`, `account_balance`, and all other tables.
>
> **account_balance Table Structure (v0.3 Update)**:
> - Added `position VARCHAR(16) NOT NULL` field (values CURRENT / LOCKED / FROZEN)
> - UNIQUE KEY updated from `(account_id, balance_type, currency)` to `(account_id, balance_type, position, currency)`
> - `frozen_amount` / `locked_amount` retained as legacy fields (can be used for report compatibility), but primary balance tracking uses the `position` mechanism
> - Idempotent UPSERT uses `(account_id, balance_type, position, currency)` as UNIQUE KEY

---

## 7. API Design

```
POST   /admin/ledger/balance-types                        -- Create
PUT    /admin/ledger/balance-types/{typeCode}             -- Update (generates new configVersion)
PATCH  /admin/ledger/balance-types/{typeCode}/status      -- Enable / Disable
GET    /admin/ledger/balance-types                        -- Query all
GET    /admin/ledger/balance-types/{typeCode}             -- Query single
GET    /admin/ledger/balance-types/{typeCode}/history     -- Query configuration history
```

All write operations must include `changeReason`, otherwise return `400 CHANGE_REASON_REQUIRED`.

---

## 8. Acceptance Criteria

| # | Acceptance Condition | Test Method |
|---|---|---|
| AC-01 | Adding a Balance Type only requires calling POST API, no code change or restart needed | Functional test |
| AC-02 | After configuration change, all nodes' L1 cache completes refresh within 5 seconds | Hot update test |
| AC-03 | For `allowNegative=false` Balance, any Posting / Adjustment must not result in < 0 | Unit test |
| AC-04 | For `allowNegative=true` Balance, any Posting / Adjustment must not result in > 0 | Unit test |
| AC-05 | When `TRADE_AHEAD_BALANCE` falls below `overdrawnAlertThreshold`, emit alert event but do not reject posting | Integration test |
| AC-06 | Each configuration modification has a complete snapshot record in `balance_type_config_history` | Audit test |
| AC-07 | Balance query Response contains `configVersion` | API test |
| AC-08 | Disabled Balance Type does not participate in calculation, query returns empty | Functional test |
| AC-09 | Modification request without `changeReason` is rejected | Validation test |
| AC-10 | Under 100,000 concurrent load test, Registry reads all go through L1 cache, DB query count is 0 | Performance test |
| AC-11 | Circular reference in Formula configuration is validated and rejected at write time | Boundary test |


---

# F-002 v2 Posting API — Batch Atomic Posting (Raft Architecture Update)

**Document Version**: v0.2 (updated based on ADR-001)  
**Feature**: F-002 Posting API  
**System**: Next-Gen Internal Ledger Platform  
**Status**: Draft for Review  
**Change Summary**: Write path changed from PostgreSQL direct write to Raft State Machine; Balance validation changed from DB read to in-memory read

---

## 1. Architecture Prerequisites (per ADR-001)

- **Write Path**: All Posting requests are serialized by Raft Leader's Account-Level Queue, State Machine updates in-memory balance, persisted to RocksDB
- **No DB direct write**: Posting write path does not touch MySQL at all; MySQL is asynchronously synced by Learner for queries
- **Balance validation**: Reads in-memory State Machine, not DB, P95 ≤ 2ms

---

## 2. Feature Overview

Posting API accepts ledger requests containing one or more legs, atomically executed on the Raft Leader node: double-entry generation, balance validation, State Machine update, RocksDB persistence, and asynchronously synced to MySQL View Layer via Learner.

---

## 3. Request Structure

### 3.1 Top-Level Request Body

| Field | Type | Required | Description |
|---|---|---|---|
| `requestId` | `string` | ✅ | Idempotency key, globally unique (UUID v7 recommended) |
| `businessEventType` | `string` | ✅ | Business event type, e.g. `RFQ_SETTLEMENT`, `WITHDRAWAL`, `FEE` |
| `businessEventRef` | `string` | ✅ | Upstream business event ID |
| `valueDate` | `date` | ✅ | Ledger effective date |
| `legs` | `list<Leg>` | ✅ | At least one leg, each leg contains two JournalLines (debit + credit) |
| `metadata` | `map<string,string>` | ❌ | Extension fields, e.g. traceId, operator ID |

### 3.2 Leg Structure

| Field | Type | Required | Description |
|---|---|---|---|
| `legId` | `string` | ✅ | Unique ID for this leg (generated by caller) |
| `postingType` | `string` | ✅ | Posting type, e.g. `TRADE_SETTLEMENT`, `FEE`, `INTEREST` |
| `lines` | `list<JournalLine>` | ✅ | Must contain at least one DEBIT + CREDIT pair |

### 3.3 JournalLine Structure

| Field | Type | Required | Description |
|---|---|---|---|
| `accountId` | `string` | ✅ | Target account |
| `balanceType` | `string` | ✅ | Must exist in Balance Type Registry (F-001) |
| `position` | `enum` | ✅ | Balance position: `CURRENT` / `LOCKED` / `FROZEN` (see position description) |
| `currency` | `string` | ✅ | ISO 4217 currency code |
| `entryType` | `enum` | ✅ | `DEBIT` / `CREDIT` |
| `amount` | `decimal` | ✅ | Must be > 0 |
| `description` | `string` | ❌ | Entry description |

**Position Field Description**

Position is used to distinguish different balance buckets under the same account and same Balance Type:

| Position | Description | Typical Scenario |
|---|---|---|
| `CURRENT` | Normal available balance position | General trading booking |
| `LOCKED` | Locked balance (e.g. in-trade) | RFQ pre-settlement lock, pending confirmation trade |
| `FROZEN` | Frozen balance (compliance, legal seizure) | Compliance freeze, collateral seizure |

Under the same (accountId, balanceType, currency), there can be balances for multiple positions; each position is calculated and validated independently.

### 3.5 RFQ Scenario Request Example

```json
{
  "requestId": "req-550e8400-e29b-41d4-a716-446655440000",
  "businessEventType": "RFQ_SETTLEMENT",
  "businessEventRef": "RFQ-2026051600123",
  "valueDate": "2026-05-16",
  "legs": [
    {
      "legId": "leg-001",
      "postingType": "TRADE_SETTLEMENT",
      "lines": [
        {
          "accountId": "CLIENT_ACC_001",
          "balanceType": "AVAILABLE_BALANCE",
          "currency": "USD",
          "entryType": "DEBIT",
          "amount": 800000.00,
          "description": "RFQ Client USD sell"
        },
        {
          "accountId": "COMPANY_FX_ACC",
          "balanceType": "AVAILABLE_BALANCE",
          "currency": "USD",
          "entryType": "CREDIT",
          "amount": 800000.00,
          "description": "RFQ Company USD receive"
        }
      ]
    },
    {
      "legId": "leg-002",
      "postingType": "TRADE_SETTLEMENT",
      "lines": [
        {
          "accountId": "COMPANY_FX_ACC",
          "balanceType": "AVAILABLE_BALANCE",
          "currency": "HKD",
          "entryType": "DEBIT",
          "amount": 6240000.00,
          "description": "RFQ Company HKD pay"
        },
        {
          "accountId": "CLIENT_ACC_001",
          "balanceType": "AVAILABLE_BALANCE",
          "currency": "HKD",
          "entryType": "CREDIT",
          "amount": 6240000.00,
          "description": "RFQ Client HKD receive"
        }
      ]
    }
  ]
}
```

---

## 4. Validation Rules

### 4.1 Pre-validation (Network Layer, before Raft)

| # | Rule | Error Code |
|---|---|---|
| V-01 | `requestId` format valid | `INVALID_REQUEST_ID` |
| V-02 | `legs` contains at least one | `LEGS_EMPTY` |
| V-03 | All `balanceType` in each leg must exist in Registry and be ACTIVE | `BALANCE_TYPE_NOT_FOUND` |
| V-04 | `amount` must be > 0 | `INVALID_AMOUNT` |
| V-05 | DEBIT total = CREDIT total per currency for each leg | `JOURNAL_UNBALANCED` |

### 4.2 Idempotency Validation (Account Worker, in-memory)

| # | Rule | Result |
|---|---|---|
| V-06 | `requestId` already exists and `COMPLETED`, return original result directly | Idempotent success |
| V-07 | `requestId` already exists and `PROCESSING`, return `409 PROCESSING` | Retry later |

### 4.3 Business Validation (State Machine, in-memory balance)

| # | Rule | Error Code |
|---|---|---|
| V-08 | Each involved account must exist | `ACCOUNT_NOT_FOUND` |
| V-09 | Each account's corresponding balanceType + position + currency must be initialized | `BALANCE_NOT_INITIALIZED` |
| V-10 | For `allowNegative=false` balance, DEBIT must not go below 0 | `INSUFFICIENT_BALANCE` |
| V-11 | For `allowNegative=true` balance (e.g. TRADE_AHEAD_BALANCE), CREDIT must not go above 0 | `CREDIT_EXCEEDS_LIMIT` |
| V-12 | Account is not frozen (`account.status = ACTIVE`) | `ACCOUNT_FROZEN` |
| V-13 | Balance of `LOCKED` / `FROZEN` position must not be negative (even if `allowNegative=true`) | `POSITION_BALANCE_FLOOR_BREACH` |

**V-13 Detailed Description**:

```
LOCKED / FROZEN position is restricted balance; by business semantics, overdraft is not allowed.
Even if Balance Type config allowNegative=true (e.g. TRADE_AHEAD_BALANCE),
LOCKED / FROZEN position is still subject to the following constraint:

IF position IN ('LOCKED', 'FROZEN'):
  DEBIT result balance must not < 0 → reject, return POSITION_BALANCE_FLOOR_BREACH

Only CURRENT position can follow the Balance Type's allowNegative setting.
```

---

## 5. Execution Flow (Raft Write Path)

```
1. [Network Layer]
   Receive HTTP request → deserialize → pre-validation (V-01 ~ V-05)
   → place in request_queue

2. [Ledger Layer]
   Take from request_queue
   → sort all involved accountIds ascending (deadlock prevention)
   → route to respective Account Queues

3. [Account Queue Coordinator]
   Wait for all involved accounts' queues to be ready (Multi-Account coordination)
   → idempotency check (V-06 ~ V-07)
   → business validation (V-08 ~ V-12, read in-memory State Machine)
   → build RaftCommand (contains all JournalLines)

4. [Raft Layer]
   Submit RaftCommand to Raft Group
   → Leader writes Raft Log
   → replicate to Follower (commit after Quorum)

5. [State Machine Apply]
   Apply Raft Log:
   a. Generate Journal (journalId, journalType=NORMAL)
   b. Generate all JournalLines (including balanceBefore, balanceAfter)
   c. Atomically update all involved accounts' in-memory balance
   d. Write RocksDB (journal + balance, WAL guarantees durability)
   e. Update in-memory idempotency map (requestId → result)

6. [Learner Async Sync]
   Learner listens to Raft Log
   → asynchronously write to MySQL journal_line, account_balance (View Layer)

7. [Response]
   Return result to client via response_queue
```

---

## 6. Multi-Account Atomicity (RFQ Scenario)

RFQ involves CLIENT_ACC + COMPANY_ACC (hotspot) two accounts:

```
Traditional problem:
  CLIENT_ACC is in Account Queue A
  COMPANY_ACC is in Account Queue B
  How to guarantee atomicity across queues?

Solution: Multi-Account RaftCommand

1. Sort involved accounts by accountId ascending (CLIENT_ACC_001 < COMPANY_FX_ACC)
2. Obtain "coordination tokens" in both Account Queues sequentially
3. After both queues are ready, build a single RaftCommand containing all legs
4. When RaftCommand applies in State Machine:
   - Update all accounts' in-memory balances at once
   - Treated as atomic operation
5. Any account validation failure → entire RaftCommand rejected, all account balances unchanged
```

COMPANY_ACC is a hotspot, but because all requests are serialized in the same Account Queue, **there is no lock contention, only nanosecond-level queue waiting**.

---

## 7. Response Structure

### 7.1 Success Response (HTTP 200)

```json
{
  "requestId": "req-550e8400-e29b-41d4-a716-446655440000",
  "status": "COMPLETED",
  "journalId": "JNL-20260516-000012345",
  "bookedAt": "2026-05-16T10:30:22.341Z",
  "legs": [
    {
      "legId": "leg-001",
      "lines": [
        {
          "journalLineId": "JL-000024689",
          "accountId": "CLIENT_ACC_001",
          "balanceType": "AVAILABLE_BALANCE",
          "currency": "USD",
          "entryType": "DEBIT",
          "amount": 800000.00,
          "balanceBefore": 1000000.00,
          "balanceAfter": 200000.00
        },
        {
          "journalLineId": "JL-000024690",
          "accountId": "COMPANY_FX_ACC",
          "balanceType": "AVAILABLE_BALANCE",
          "currency": "USD",
          "entryType": "CREDIT",
          "amount": 800000.00,
          "balanceBefore": 5000000.00,
          "balanceAfter": 5800000.00
        }
      ]
    }
  ]
}
```

### 7.2 Failure Response (HTTP 422)

```json
{
  "requestId": "req-550e8400-e29b-41d4-a716-446655440001",
  "status": "REJECTED",
  "errors": [
    {
      "errorCode": "INSUFFICIENT_BALANCE",
      "accountId": "CLIENT_ACC_001",
      "balanceType": "AVAILABLE_BALANCE",
      "currency": "USD",
      "required": 800000.00,
      "available": 500000.00
    }
  ]
}
```

---

## 8. Performance Targets (per ADR-001)

| Metric | Target | Achievement Principle |
|---|---|---|
| Posting P95 | ≤ 3ms | No MySQL touch, pure in-memory + RocksDB WAL |
| Balance validation P95 | ≤ 0.5ms | Direct read from in-memory State Machine |
| Hotspot account (COMPANY_ACC) | Same as normal accounts | Account Queue serializes, no DB row lock contention |
| Idempotent retry P95 | ≤ 1ms | In-memory idempotency map hit |

---

## 9. Acceptance Criteria

| # | Acceptance Condition | Test Method |
|---|---|---|
| AC-01 | RFQ dual-account Posting, CLIENT_ACC and COMPANY_ACC balances updated atomically | Functional test |
| AC-02 | When `allowNegative=false` account has insufficient balance, entire request is rejected, all account balances unchanged | Functional test |
| AC-03 | Same `requestId` retried 1000 times, only 1 valid Journal generated | Idempotency test |
| AC-04 | 1000 concurrent RFQ hitting same COMPANY_ACC, Posting P95 ≤ 3ms | Performance test |
| AC-05 | Under COMPANY_ACC hotspot, no duplicate payouts, no balance inconsistency | Concurrency safety test |
| AC-06 | After Raft Leader failure, in-flight requests can retry successfully with idempotencyKey | Failure recovery test |
| AC-07 | After Posting completes, Learner syncs to MySQL View Layer within 1 second | Consistency test |
| AC-08 | Each Posting can find complete balanceBefore / balanceAfter in MySQL Journal | Audit test |


---

# F-003 Manual Adjustment — Functional Requirements Specification

**Document Version**: v0.1  
**Feature**: F-003 Manual Adjustment  
**System**: Next-Gen Internal Ledger Platform  
**Status**: Draft for Review  
**Dependencies**: ADR-001, F-002 Posting API, F-008 State Machine Design

---

## 1. Feature Overview

Manual Adjustment is a unilateral or bilateral ledger adjustment initiated directly by an operator, not attached to any business event (e.g. trade, fee). It is used for the following scenarios: system reconciliation discrepancy correction, manual interest booking, fee waiver, system migration data correction.

**Difference from Posting**:
- Posting is initiated by business systems with a clear business event ID
- Manual Adjustment is initiated by a human operator and must have approval records
- Manual Adjustment's `journalType = MANUAL_ADJUSTMENT`, separately counted in reports and reconciliation

---

## 2. Applicable Scenarios

| Scenario | Description |
|---|---|
| Reconciliation discrepancy correction | External settlement returns difference, needs manual correction |
| Manual interest booking | Interest calculation system failure, needs manual interest booking |
| Fee waiver | Booked fee needs manual waiver (Reversal + waiver booking) |
| System migration | Old system balance moved to new Ledger initial booking |
| Error correction | Complex scenarios that cannot be resolved by Reversal |

---

## 3. Maker-Checker Mandatory Requirement

**All Manual Adjustments must go through Maker-Checker dual approval**, this is an ibank compliance requirement and cannot be bypassed:

```
Maker:
  Submit Adjustment Draft (draft status)
  → System performs pre-validation but does not execute
  → Return draftId

Checker:
  Review Draft content
  → Approve → System executes Adjustment
  → Reject → Draft is voided

Constraints:
  - Maker and Checker cannot be the same person
  - Draft validity: 24 hours (configurable)
  - Expired without approval: automatically voided
  - Checker approval cannot be withdrawn (use Reversal if needed)
```

---

## 4. API Design

### 4.1 Step 1: Maker Submits Draft

```
POST /ledger/adjustments/draft
```

**Request Body**

| Field | Type | Required | Description |
|---|---|---|---|
| `draftRequestId` | `string` | ✅ | Idempotency key |
| `adjustmentType` | `enum` | ✅ | Adjustment type, see table below |
| `adjustmentReason` | `string` | ✅ | Free text (max 1000 characters) |
| `valueDate` | `date` | ✅ | Ledger effective date |
| `makerId` | `string` | ✅ | Maker operator ID |
| `legs` | `list<Leg>` | ✅ | Same format as F-002 Posting |
| `supportingRef` | `string` | ❌ | Supporting document number (e.g. reconciliation report ID) |
| `metadata` | `map` | ❌ | Extension fields |

**adjustmentType Enum**

| Code | Description |
|---|---|
| `RECONCILIATION_ADJUSTMENT` | Reconciliation discrepancy correction |
| `INTEREST_ADJUSTMENT` | Manual interest adjustment |
| `FEE_WAIVER` | Fee waiver |
| `MIGRATION_ENTRY` | System migration booking |
| `ERROR_CORRECTION` | Error correction |
| `REGULATORY_ADJUSTMENT` | Regulatory requirement adjustment |

**Response (Draft Created Successfully)**

```json
{
  "draftRequestId": "draft-req-abc123",
  "draftId": "ADJ-DRAFT-20260516-000001",
  "status": "PENDING_APPROVAL",
  "expiresAt": "2026-05-17T14:30:00.000Z",
  "makerId": "ops-user-001"
}
```

### 4.2 Step 2: Checker Approval

```
POST /ledger/adjustments/{draftId}/approve
POST /ledger/adjustments/{draftId}/reject
```

**Approve Request Body**

| Field | Type | Required | Description |
|---|---|---|---|
| `requestId` | `string` | ✅ | Idempotency key (prevent duplicate approval) |
| `checkerId` | `string` | ✅ | Checker operator ID (cannot equal makerId) |
| `checkerNote` | `string` | ❌ | Approval note |

**Reject Request Body**

| Field | Type | Required | Description |
|---|---|---|---|
| `requestId` | `string` | ✅ | Idempotency key |
| `checkerId` | `string` | ✅ | Checker operator ID |
| `rejectReason` | `string` | ✅ | Rejection reason |

---

## 5. Validation Rules

### 5.1 Draft Creation Validation (Maker Submit)

| # | Rule | Error Code |
|---|---|---|
| V-01 | legs format valid, debit-credit balanced | `JOURNAL_UNBALANCED` |
| V-02 | All accountIds exist and status is ACTIVE | `ACCOUNT_NOT_FOUND` |
| V-03 | All balanceTypes exist in Registry | `BALANCE_TYPE_NOT_FOUND` |
| V-04 | `adjustmentType` valid | `INVALID_ADJUSTMENT_TYPE` |

**Note: Balance validation is NOT performed at Draft creation**, balance validation is done when Checker approves and executes.

### 5.2 Checker Approve Validation

| # | Rule | Error Code |
|---|---|---|
| V-05 | `checkerId ≠ makerId` | `MAKER_CHECKER_SAME_PERSON` |
| V-06 | Draft not expired (before expiresAt) | `DRAFT_EXPIRED` |
| V-07 | Draft status = `PENDING_APPROVAL` | `DRAFT_NOT_PENDING` |
| V-08 | Balance validation (same as F-002 V-08 ~ V-12) | See F-002 |

---

## 6. Execution Flow

### 6.1 Maker Submits Draft (does not go through Raft)

```
Draft only validates and stores, does not book
→ Write to MySQL adjustments_draft table
→ Return draftId
→ Do not submit RaftCommand, do not update State Machine
```

### 6.2 Checker Approve → Execute Adjustment (goes through Raft)

```
1. [Network Layer]
   Validate checkerId ≠ makerId, Draft not expired

2. [Ledger Layer]
   Load Draft legs content from MySQL
   → route to Account Queue by accountId ascending

3. [Account Queue Coordinator]
   Idempotency check (approve requestId)
   Balance validation (V-08, read in-memory State Machine)
   Build ADJUSTMENT_CMD

4. [Raft Layer]
   Submit ADJUSTMENT_CMD → Quorum commit

5. [State Machine Apply]
   Generate Journal (journalType = MANUAL_ADJUSTMENT)
   Generate JournalLine
   Update in-memory balance
   Write RocksDB WriteBatch
   Update Draft status = EXECUTED (synced to MySQL via Learner)

6. [Response]
   Return adjustmentJournalId
```

---

## 7. Draft State Machine

```
        Maker Submit
             |
             ▼
    [PENDING_APPROVAL]
        |         |
    Checker    Checker
    Approve    Reject      Draft Expired (24h)
        |         |              |
        ▼         ▼              ▼
   [APPROVED] [REJECTED]    [EXPIRED]
        |
        ▼
   [EXECUTED] (booking complete)
        |
        ▼
   [REVERSED] (if later Reversed)
```

---

## 8. Audit Requirements

Each Manual Adjustment saves a complete audit chain in MySQL:

| Field | Description |
|---|---|
| `draftId` | Draft ID |
| `adjustmentJournalId` | Final booked Journal ID |
| `makerId` + `makeTime` | Who submitted draft and when |
| `checkerId` + `checkTime` | Who approved and when |
| `checkerNote` | Approval note |
| `adjustmentType` | Adjustment type |
| `adjustmentReason` | Reason description |
| `supportingRef` | Supporting document |

---

## 9. Performance Targets

| Operation | Target |
|---|---|
| Draft creation P95 | ≤ 100ms (only writes MySQL, does not go through Raft) |
| Checker Approve P95 | ≤ 10ms (goes through Raft, slightly higher than Posting) |

---

## 10. Acceptance Criteria

| # | Acceptance Condition | Test Method |
|---|---|---|
| AC-01 | Maker submits Draft, system returns draftId, not booked | Functional test |
| AC-02 | After Checker approves, ledger is correctly booked, Journal type is MANUAL_ADJUSTMENT | Functional test |
| AC-03 | Checker and Maker are the same person, returns `MAKER_CHECKER_SAME_PERSON` | Functional test |
| AC-04 | Draft not approved after 24 hours, status automatically becomes EXPIRED | Functional test |
| AC-05 | Approve on EXPIRED / REJECTED / EXECUTED Draft returns `DRAFT_NOT_PENDING` | Functional test |
| AC-06 | Approval records (makerId, checkerId, timestamps) are completely saved in MySQL | Audit test |
| AC-07 | Approve operation is idempotent, same requestId retry does not duplicate booking | Idempotency test |
| AC-08 | Manual Adjustment Journal is independently counted in reports, separate from business Posting | Report test |


---

# F-004 Reversal — Functional Requirements Specification

**Document Version**: v0.1  
**Feature**: F-004 Reversal  
**System**: Next-Gen Internal Ledger Platform  
**Status**: Draft for Review  
**Dependencies**: ADR-001, F-002 Posting API, F-008 State Machine Design

---

## 1. Feature Overview

Reversal is a complete offset of a posted Journal. It generates a mirror Journal, swapping all JournalLines' DEBIT / CREDIT, with the same amount, so the net effect of the two Journals is zero.

**Core Principles**:
- Posted entries **cannot be modified or deleted**, only Reversed
- Reversal itself is also a Journal, equally append-only, cannot be modified
- After Reversal, if re-booking is needed, a new Posting request must be submitted (Rebook)

---

## 2. Applicable Scenarios

| Scenario | Description |
|---|---|
| Trade cancellation | RFQ trade cancelled by client or system, need to reverse booked entry |
| Wrong booking | Posting used wrong amount, currency, account; need to Reverse then Rebook |
| System reconciliation discrepancy | External settlement result differs from internal ledger; need to reverse then re-reconcile |
| EOD adjustment | Error discovered before accounting period close; need same-day reversal |

---

## 3. Constraints

| # | Constraint | Description |
|---|---|---|
| C-01 | Can only Reverse `status = CONFIRMED` Journal | Already Reversed Journal cannot be Reversed again |
| C-02 | Cannot partially Reverse | Must reverse all JournalLines of the entire Journal; reversing a single leg is not allowed |
| C-03 | Reversal does not check balance sufficiency | Reversal is an offsetting operation and must succeed; if balance is insufficient (e.g. funds already used), system still executes Reversal, allowing corresponding negative balance (handled by subsequent processes) |
| C-04 | Reversal itself cannot be Reversed | Prevent infinite reversal chain |
| C-05 | Cross-period Reversal must be marked | If original Journal's valueDate is in a closed accounting period, mark `crossPeriod=true` for report system handling |

---

## 4. Request Structure

### 4.1 API

```
POST /ledger/journals/{originalJournalId}/reversal
```

### 4.2 Request Body

| Field | Type | Required | Description |
|---|---|---|---|
| `requestId` | `string` | ✅ | Idempotency key, globally unique (UUID v7) |
| `reversalReason` | `string` | ✅ | Reversal reason, free text (max 500 characters) |
| `reversalReasonCode` | `enum` | ✅ | Reason code, see table below |
| `valueDate` | `date` | ✅ | Reversal's ledger effective date (can differ from original Journal) |
| `operatorId` | `string` | ✅ | Operator ID for audit |
| `approvalRef` | `string` | ❌ | Approval number (if system requires maker-checker) |
| `metadata` | `map<string,string>` | ❌ | Extension fields |

### 4.3 reversalReasonCode Enum

| Code | Description |
|---|---|
| `TRADE_CANCELLED` | Trade cancelled |
| `WRONG_AMOUNT` | Wrong amount |
| `WRONG_ACCOUNT` | Wrong account |
| `WRONG_CURRENCY` | Wrong currency |
| `SYSTEM_ERROR` | System error |
| `RECONCILIATION_ADJUSTMENT` | Reconciliation adjustment |
| `COMPLIANCE_REQUIREMENT` | Compliance requirement |
| `OTHER` | Other (requires reversalReason explanation) |

### 4.4 Request Example

```json
{
  "requestId": "rev-req-7f3a9b2c-1234-5678-abcd-ef0123456789",
  "reversalReason": "RFQ trade cancelled by client, original trade RFQ-2026051600123",
  "reversalReasonCode": "TRADE_CANCELLED",
  "valueDate": "2026-05-16",
  "operatorId": "ops-user-001",
  "approvalRef": "APPR-2026051600456"
}
```

---

## 5. Validation Rules

### 5.1 Pre-validation (Network Layer)

| # | Rule | Error Code |
|---|---|---|
| V-01 | `requestId` format valid | `INVALID_REQUEST_ID` |
| V-02 | `originalJournalId` format valid | `INVALID_JOURNAL_ID` |
| V-03 | `reversalReasonCode` within enum range | `INVALID_REASON_CODE` |
| V-04 | `operatorId` not empty | `MISSING_OPERATOR` |

### 5.2 Business Validation (State Machine, in-memory)

| # | Rule | Error Code |
|---|---|---|
| V-05 | Journal corresponding to `originalJournalId` exists | `JOURNAL_NOT_FOUND` |
| V-06 | Original Journal `status = CONFIRMED` | `JOURNAL_ALREADY_REVERSED` |
| V-07 | Original Journal `journalType ≠ REVERSAL` | `CANNOT_REVERSE_REVERSAL` |
| V-08 | Idempotency: if `requestId` already exists, return original result directly | — |

### 5.3 Cross-Period Marking

| # | Rule | Handling |
|---|---|---|
| V-09 | If original Journal's `valueDate` is in a closed accounting period | Mark `crossPeriod=true`, still allow Reversal execution, but notify report system |

---

## 6. Execution Flow (Raft Write Path)

```
1. [Network Layer]
   Receive POST request → pre-validation (V-01 ~ V-04)
   → place in request_queue

2. [Ledger Layer]
   Take from request_queue
   → read all involved accountIds from original Journal in State Machine
   → sort by accountId ascending
   → route to respective Account Queues (Multi-Account Coordinator)

3. [Account Queue Coordinator]
   Wait for all involved accounts' queues to be ready
   → idempotency check (V-08)
   → business validation (V-05 ~ V-09)
   → build REVERSAL_CMD

4. [Raft Layer]
   Submit REVERSAL_CMD → Leader replicates to Follower → Quorum commit

5. [State Machine Apply]
   a. Generate Reversal Journal:
      journalType = REVERSAL
      originalJournalId = {originalJournalId}
      status = CONFIRMED
      crossPeriod = true/false

   b. Generate mirror JournalLines (reverse each line):
      Original DEBIT → CREDIT
      Original CREDIT → DEBIT
      Amount unchanged
      balanceBefore / balanceAfter calculated based on current State Machine

   c. Update original Journal status:
      original Journal.status = REVERSED
      original Journal.reversalJournalId = {new Reversal journalId}

   d. Atomically update all involved accounts' in-memory balance

   e. Atomically write to RocksDB via WriteBatch:
      - New Reversal Journal
      - All new JournalLines
      - Update original Journal (status + reversalJournalId)
      - Update balance

6. [Learner Async Sync]
   Learner syncs Reversal Journal and updated original Journal to MySQL View Layer

7. [Response]
   Return Reversal result
```

---

## 7. State Machine Journal State Machine

```
         Posting
            |
            ▼
       [CONFIRMED]  ←─── State after normal posting
            |
            | Reversal
            ▼
       [REVERSED]   ←─── Cannot be Reversed again

       [REVERSAL]   ←─── Reversal Journal's own journalType, status remains CONFIRMED
                         Cannot be Reversed (V-07 validation)
```

---

## 8. Response Structure

### 8.1 Success Response (HTTP 200)

```json
{
  "requestId": "rev-req-7f3a9b2c-1234-5678-abcd-ef0123456789",
  "status": "COMPLETED",
  "reversalJournalId": "JNL-20260516-000012346",
  "originalJournalId": "JNL-20260516-000012345",
  "crossPeriod": false,
  "bookedAt": "2026-05-16T14:22:11.500Z",
  "legs": [
    {
      "lines": [
        {
          "journalLineId": "JL-000024693",
          "accountId": "CLIENT_ACC_001",
          "balanceType": "AVAILABLE_BALANCE",
          "currency": "USD",
          "entryType": "CREDIT",
          "amount": 800000.00,
          "balanceBefore": 200000.00,
          "balanceAfter": 1000000.00
        },
        {
          "journalLineId": "JL-000024694",
          "accountId": "COMPANY_FX_ACC",
          "balanceType": "AVAILABLE_BALANCE",
          "currency": "USD",
          "entryType": "DEBIT",
          "amount": 800000.00,
          "balanceBefore": 5800000.00,
          "balanceAfter": 5000000.00
        }
      ]
    }
  ]
}
```

### 8.2 Failure Response (HTTP 422)

```json
{
  "requestId": "rev-req-7f3a9b2c-1234-5678-abcd-ef0123456789",
  "status": "REJECTED",
  "errors": [
    {
      "errorCode": "JOURNAL_ALREADY_REVERSED",
      "originalJournalId": "JNL-20260516-000012345",
      "reversalJournalId": "JNL-20260516-000012346"
    }
  ]
}
```

---

## 9. Rebook Flow (Re-booking after Reversal)

Rebook is not an independent feature; it is a new Posting submitted after Reversal:

```
Step 1: POST /ledger/journals/{wrongJournalId}/reversal
         → Obtain reversalJournalId

Step 2: POST /ledger/postings
         → Submit correct Posting
         → metadata carries {"rebookForReversal": "reversalJournalId"}

Two steps are independent, each idempotent, time gap allowed between them
```

---

## 10. Audit Requirements

Each Reversal must save the following complete chain in MySQL View Layer:

```
original_journal_id  ──→  reversal_journal_id
                          ├─ reversalReasonCode
                          ├─ reversalReason (free text)
                          ├─ operatorId
                          ├─ approvalRef
                          ├─ crossPeriod
                          └─ bookedAt
```

Full chain can be queried from either end, supporting F-007 Reconciliation discrepancy tracking.

---

## 11. Performance Targets

| Metric | Target |
|---|---|
| Reversal Posting P95 | ≤ 5ms (slightly higher than Posting, needs extra read of original Journal) |
| Idempotent retry P95 | ≤ 1ms |

---

## 12. Acceptance Criteria

| # | Acceptance Condition | Test Method |
|---|---|---|
| AC-01 | After Reversal, original Journal status = REVERSED, balance restored to original value | Functional test |
| AC-02 | Reversing an already REVERSED Journal returns `JOURNAL_ALREADY_REVERSED` | Functional test |
| AC-03 | Reversing a journalType=REVERSAL Journal returns `CANNOT_REVERSE_REVERSAL` | Functional test |
| AC-04 | Same `requestId` retried 1000 times, only 1 Reversal Journal generated | Idempotency test |
| AC-05 | Reversal does not check balance sufficiency; even with insufficient balance, execution succeeds | Functional test |
| AC-06 | Cross-period Reversal correctly marks `crossPeriod=true` | Functional test |
| AC-07 | Reversal and original Journal are bidirectionally queryable in MySQL View Layer | Audit test |
| AC-08 | Rebook flow (Reversal + new Posting) is idempotent for each step, can be independently retried | Functional test |


---

# F-005 v2 Balance Query & Snapshot (Raft Architecture Update)

**Document Version**: v0.2 (updated based on ADR-001)  
**Feature**: F-005 Balance Query & Snapshot  
**System**: Next-Gen Internal Ledger Platform  
**Status**: Draft for Review  
**Change Summary**: Real-time Balance query path changed from MySQL to in-memory State Machine; snapshot mechanism unchanged, written to MySQL by Learner

---

## 1. Architecture Prerequisites (per ADR-001)

| Query Type | Data Source | Latency Target |
|---|---|---|
| Real-time balance query | Raft Leader in-memory State Machine | P95 ≤ 2ms |
| As-of historical snapshot query | MySQL View Layer (Learner synced) | P95 ≤ 30ms |
| EOD snapshot query | MySQL View Layer | P95 ≤ 30ms |
| Journal Replay (fallback) | MySQL journal_line | P95 ≤ 5s |

---

## 2. Real-Time Balance Query

### 2.1 Query Path

```
Client → Ledger Service (Leader node)
                |
                ▼
    in-memory State Machine
    ConcurrentHashMap<AccountKey, BalanceMap>
                |
                ▼
         Direct read and return
         No network, no disk
         P95 < 0.5ms (read itself)
         + network latency ≈ 1–2ms
         = P95 ≤ 2ms ✅
```

**Important**: Balance queries must be routed to the **Raft Leader node**; reading from Follower may return stale data.

### 2.2 API (v0.3 updated)

```
GET /ledger/accounts/{accountId}/balances
    ?types=AVAILABLE_BALANCE,TRADE_AHEAD_BALANCE
    &currency=USD
    &position=CURRENT          // optional: CURRENT / LOCKED / FROZEN
```

**Query Parameters:**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `types` | `string` | ❌ | Comma-separated balance type codes |
| `currency` | `string` | ❌ | ISO 4217 currency code |
| `position` | `enum` | ❌ | Balance position: `CURRENT` / `LOCKED` / `FROZEN` |
| `aggregate` | `boolean` | ❌ | If `true`, return sum of all positions per balance type |

> If `position` is omitted and `aggregate=false`, default to `CURRENT` position only.
> If `aggregate=true`, `position` parameter is ignored.

### 2.3 Response New Fields

```json
{
  "accountId": "CLIENT_ACC_001",
  "currency": "USD",
  "queryTime": "2026-05-16T10:35:00.000Z",
  "isRealtime": true,
  "dataSource": "STATE_MACHINE",
  "raftLeaderId": "node-001",
  "balances": [
    {
      "typeCode": "AVAILABLE_BALANCE",
      "amount": 200000.00,
      "allowNegative": false,
      "configVersion": 3,
      "lastJournalId": "JNL-20260516-000012345",
      "stateVersion": 1024,
      "positions": {
        "CURRENT": 200000.00,
        "LOCKED": 0.00,
        "FROZEN": 0.00
      }
    },
    {
      "typeCode": "TRADE_AHEAD_BALANCE",
      "amount": -45000.00,
      "allowNegative": true,
      "negativeSemantics": "PRE_AUTHORIZED",
      "configVersion": 1,
      "stateVersion": 987,
      "positions": {
        "CURRENT": -45000.00,
        "LOCKED": 0.00,
        "FROZEN": 0.00
      }
    }
  ]
}
```

New field descriptions:
- `dataSource`: `STATE_MACHINE` (in-memory) / `EOD_SNAPSHOT` / `JOURNAL_REPLAY`
- `raftLeaderId`: Returns current Leader node ID for diagnostics
- `stateVersion`: State Machine version number (i.e. Raft Log Index) for tracking
- `positions`: Per-position balance breakdown (v0.3). `amount` field equals sum of all positions.

---

## 3. Batch Balance Query (unchanged, performance greatly improved)

```
POST /ledger/accounts/balances/batch
```

Because it reads from in-memory State Machine, batch query performance is greatly improved:

| Metric | v0.1 (MySQL) | v0.2 (State Machine) |
|---|---|---|
| 100 accounts batch query P95 | 50ms | ≤ 5ms |
| 200 accounts batch query P95 | 100ms | ≤ 10ms |

---

## 4. State Machine Internal Data Structure

```java
// In-memory State Machine Balance storage structure (v0.3)
// Key: AccountKey = (accountId, balanceType, position, currency)
// Value: BalanceEntry

class BalanceEntry {
    BigDecimal amount;        // Current balance
    long stateVersion;        // Corresponding Raft Log Index
    String lastJournalId;     // Last Journal ID
    Instant lastUpdatedAt;    // Last update time
}

ConcurrentHashMap<AccountBalanceKey, BalanceEntry> balanceStore;

// Position Query Support
// Aggregate query sums all positions for a given (accountId, balanceType, currency)
Map<String, BigDecimal> getPositions(String accountId, String balanceType, String currency) {
    return Stream.of(Position.values())
        .collect(Collectors.toMap(
            Position::name,
            pos -> balanceStore.getOrDefault(
                new AccountBalanceKey(accountId, balanceType, pos.name(), currency),
                BalanceEntry.ZERO
            ).amount
        ));
}
```

**Reads are lock-free** (Account Worker writes, readers only do snapshot reads); under Java 21 ConcurrentHashMap read operations are essentially contention-free.

---

## 5. As-of Historical Snapshot Query (architecture unchanged, data source adjusted)

As-of queries read from MySQL View Layer (data already synced by Learner):

```
Query logic priority:

1. Find the nearest snapshot before asOf time from MySQL balance_snapshot
   ├─ found → return, mark dataSource=EOD_SNAPSHOT
   └─ not found → do Journal Replay from MySQL journal_line

Note: As-of queries do not read in-memory State Machine
Because State Machine only keeps "current" state
Historical states are in MySQL View Layer or RocksDB snapshot
```

---

## 6. EOD Snapshot Mechanism

### 6.1 Snapshot Generation Methods (updated)

v0.2 EOD Snapshot has two sources:

**Source A: State Machine Snapshot (recommended)**
- Raft Leader periodically (or on trigger) takes snapshot of State Machine
- Snapshot contains current balances of all accounts, all balance types
- Synced to MySQL balance_snapshot table via Learner
- Advantage: Fully accurate, consistent with in-memory

**Source B: Learner incremental sync trigger**
- Learner continuously syncs journal_line to MySQL
- When accounting period closes, Learner triggers EOD Snapshot Job, aggregating snapshot from MySQL journal_line
- Advantage: Does not depend on Leader, Learner can complete independently

### 6.2 Snapshot Trigger

```
Trigger conditions:
  1. Accounting period close (F-009 AccountingPeriod close)
  2. Scheduled task (daily 23:59)
  3. Manual trigger (admin API)
  4. State Machine Snapshot (auto-triggered by Raft every 100,000 logs)
```

---

## 7. Consistency Guarantees

| Scenario | Consistency Guarantee |
|---|---|
| Real-time Balance query (read State Machine) | **Strong consistency**: always the latest committed state |
| As-of snapshot query (read MySQL) | **Eventual consistency**: may lag Leader by up to 1 second |
| EOD Snapshot | **Strong consistency**: generated from State Machine Snapshot |
| Reconciliation (read MySQL) | **Eventual consistency**, reconciliation allows minute-level delay |

---

## 8. Acceptance Criteria

| # | Acceptance Condition | Test Method |
|---|---|---|
| AC-01 | Real-time Balance query reads in-memory State Machine, P95 ≤ 2ms | Performance test |
| AC-02 | After Posting completes, next Balance query immediately reflects new balance (no delay) | Consistency test |
| AC-03 | `TRADE_AHEAD_BALANCE` negative balance correctly returned, `dataSource=STATE_MACHINE` | Functional test |
| AC-04 | Batch query 200 accounts, P95 ≤ 10ms | Performance test |
| AC-05 | As-of query returns correct historical balance, `dataSource=EOD_SNAPSHOT` or `JOURNAL_REPLAY` | Functional test |
| AC-06 | After Raft Leader switch, new Leader's Balance query results consistent with old Leader | Failure test |
| AC-07 | EOD Snapshot and State Machine Snapshot balances are completely consistent | Reconciliation test |


---

# F-006 Journal Query — Functional Requirements Specification

**Document Version**: v0.1  
**Feature**: F-006 Journal Query  
**System**: Next-Gen Internal Ledger Platform  
**Status**: Draft for Review  
**Dependencies**: ADR-001 (CQRS architecture), F-002, F-003, F-004, F-008 State Machine

---

## 1. Feature Overview

Journal Query provides query capability for all ledger entries, reading from MySQL View Layer (Learner async sync), supporting multi-dimensional filtering, pagination, and sorting.

**Important**: Journal Query reads MySQL View Layer, which is eventually consistent (typically lags Leader < 1 second). For scenarios requiring strong consistency (e.g. real-time balance), use F-005 Balance Query.

---

## 2. Query Dimensions

The system supports the following query dimensions, which can be combined:

| Dimension | Field | Description |
|---|---|---|
| Journal | `journalId` | Exact query for single Journal |
| Account | `accountId` | Query all entries for an account |
| Business Event | `businessEventRef` | Query all ledger entries for a business event (e.g. RFQ-ID) |
| Request | `requestId` | Query result of a Posting request |
| Time Range | `bookedFrom` + `bookedTo` | By booking time range |
| Value Date | `valueDateFrom` + `valueDateTo` | By ledger effective date range |
| Journal Type | `journalType` | `NORMAL`, `REVERSAL`, `MANUAL_ADJUSTMENT` |
| Status | `status` | `CONFIRMED`, `REVERSED` |
| Currency | `currency` | ISO 4217 |
| Balance Type | `balanceType` | Filter by Balance Type |
| Position | `position` | Filter by position: `CURRENT` / `LOCKED` / `FROZEN` |
| Operator | `operatorId` | Manual Adjustment operator |

---

## 3. API Design

### 3.1 Query Single Journal (with all JournalLines)

```
GET /ledger/journals/{journalId}
```

**Response**

```json
{
  "journalId": "JNL-20260516-000012345",
  "journalType": "NORMAL",
  "status": "REVERSED",
  "requestId": "req-550e8400-e29b-41d4-a716-446655440000",
  "businessEventType": "RFQ_SETTLEMENT",
  "businessEventRef": "RFQ-2026051600123",
  "valueDate": "2026-05-16",
  "bookedAt": "2026-05-16T10:30:22.341Z",
  "reversalJournalId": "JNL-20260516-000012346",
  "lines": [
    {
      "journalLineId": "JL-000024689",
      "accountId": "CLIENT_ACC_001",
      "balanceType": "AVAILABLE_BALANCE",
      "position": "CURRENT",
      "currency": "USD",
      "entryType": "DEBIT",
      "amount": 800000.00,
      "balanceBefore": 1000000.00,
      "balanceAfter": 200000.00
    }
  ],
  "dataSource": "VIEW_LAYER",
  "viewLayerDelay": "< 1s"
}
```

### 3.2 Query Entries by Account (paginated)

```
GET /ledger/accounts/{accountId}/journals
    ?currency=USD
    &balanceType=AVAILABLE_BALANCE
    &journalType=NORMAL
    &valueDateFrom=2026-05-01
    &valueDateTo=2026-05-16
    &page=0
    &size=50
    &sort=bookedAt,desc
```

**Response**

```json
{
  "accountId": "CLIENT_ACC_001",
  "totalCount": 1250,
  "page": 0,
  "size": 50,
  "items": [ ... ],
  "dataSource": "VIEW_LAYER"
}
```

### 3.3 Query Entries by Business Event (full chain traceability)

```
GET /ledger/journals?businessEventRef=RFQ-2026051600123
```

Returns all related Journals for this business event, including:
- Original Posting Journal
- Reversal Journal (if any)
- Rebook Journal (if any)
- Complete chain (`originalJournalId` → `reversalJournalId`)

### 3.4 Query by requestId (idempotency confirmation)

```
GET /ledger/journals?requestId=req-550e8400-e29b-41d4-a716-446655440000
```

Used by upstream systems to confirm whether a Posting was successfully booked.

---

## 4. Full Chain Traceability

For any Journal, the complete before-and-after association chain can be queried:

```
GET /ledger/journals/{journalId}/chain
```

**Response**

```json
{
  "chain": [
    {
      "journalId": "JNL-20260516-000012345",
      "journalType": "NORMAL",
      "status": "REVERSED",
      "businessEventRef": "RFQ-2026051600123",
      "bookedAt": "2026-05-16T10:30:22.341Z",
      "relationship": "ORIGINAL"
    },
    {
      "journalId": "JNL-20260516-000012346",
      "journalType": "REVERSAL",
      "status": "CONFIRMED",
      "bookedAt": "2026-05-16T14:22:11.500Z",
      "relationship": "REVERSAL_OF"
    },
    {
      "journalId": "JNL-20260516-000012399",
      "journalType": "NORMAL",
      "status": "CONFIRMED",
      "businessEventRef": "RFQ-2026051600123-REBOOK",
      "bookedAt": "2026-05-16T14:25:00.000Z",
      "relationship": "REBOOK_AFTER_REVERSAL"
    }
  ]
}
```

---

## 5. MySQL View Layer Index Design

To support the above query performance, the following indexes must be created on MySQL journal and journal_line tables. Complete DDL (including `id BIGINT AUTO_INCREMENT PRIMARY KEY`, `created_at`, `updated_at`) is in project root `init.sql`.

```sql
-- journal table
CREATE INDEX idx_request_id       ON journal (request_id);
CREATE INDEX idx_business_event_ref ON journal (business_event_ref);
CREATE INDEX idx_created_at       ON journal (created_at);

-- journal_line table
CREATE INDEX idx_journal_id       ON journal_line (journal_id);
CREATE INDEX idx_account_id       ON journal_line (account_id);
CREATE INDEX idx_account_balance  ON journal_line (account_id, balance_type, position, currency);

-- account table
CREATE INDEX idx_owner_id         ON account (owner_id);

-- account_balance table (v0.3: includes position field)
-- UNIQUE KEY: (account_id, balance_type, position, currency)
CREATE INDEX idx_account_id       ON account_balance (account_id);
```

---

## 6. Performance Targets

| Query Type | Target | Description |
|---|---|---|
| Single Journal point query | P95 ≤ 10ms | Index hit |
| Account entries (50 items) | P95 ≤ 30ms | Paginated query |
| Business event full chain | P95 ≤ 50ms | Cross-table join |
| Full chain traceability (chain) | P95 ≤ 100ms | Recursive join query |

---

## 7. Acceptance Criteria

| # | Acceptance Condition | Test Method |
|---|---|---|
| AC-01 | Exact query by journalId returns complete JournalLine | Functional test |
| AC-02 | Query by accountId + time range, pagination correct | Functional test |
| AC-03 | Query by businessEventRef returns original + Reversal + Rebook all Journals | Functional test |
| AC-04 | chain API correctly returns complete before-and-after association chain | Functional test |
| AC-05 | After Posting completes, Learner syncs within 1 second, Journal is queryable | Consistency test |
| AC-06 | Account entries 50-item query, P95 ≤ 30ms | Performance test |
| AC-07 | `dataSource` field correctly indicates `VIEW_LAYER` | Functional test |


---

# F-007 Reconciliation — Functional Requirements Specification

**Document Version**: v0.1  
**Feature**: F-007 Reconciliation  
**System**: Next-Gen Internal Ledger Platform  
**Status**: Draft for Review  
**Dependencies**: ADR-001, F-005 Balance Query, F-006 Journal Query, F-008 State Machine

---

## 1. Feature Overview

Reconciliation provides three-layer reconciliation capability:

| Level | Name | Description |
|---|---|---|
| L1 | **Internal Journal Reconciliation** | Verify all Journals are debit-credit balanced, journal entries consistent with Balance |
| L2 | **Sub-ledger to General Ledger** | Sum of all client account Balances should equal corresponding company general ledger account |
| L3 | **External Settlement Reconciliation** | Compare internal ledger entries with external settlement files (SWIFT, HKICL, etc.) |

---

## 2. L1: Internal Journal Reconciliation

### 2.1 Reconciliation Logic

```
Executed at each accounting period (EOD):

1. Journal debit-credit balance validation:
   SELECT journalId, SUM(CASE WHEN entryType='DEBIT' THEN amount ELSE -amount END) AS net
   FROM journal_line
   GROUP BY journalId
   HAVING ABS(net) > 0.001
   → Any non-zero result = data anomaly

2. Balance consistency validation:
   a. Take EOD Snapshot balance at period end
   b. Calculate theoretical Balance from previous EOD Snapshot + current period all JournalLines
   c. Difference > 0 → anomaly

3. State Machine vs MySQL View Layer consistency validation:
   a. Read all account Balances from Leader in-memory State Machine
   b. Read latest balance_snapshot from MySQL View Layer
   c. Difference > Learner sync delay range → anomaly
```

### 2.2 Trigger Timing

| Trigger | Description |
|---|---|
| Accounting period close (daily EOD) | Main reconciliation window |
| Manual trigger | Admin API for troubleshooting |
| New Learner node joins | Validate Snapshot Transfer correctness |

---

## 3. L2: Sub-ledger to General Ledger

### 3.1 Reconciliation Logic

Using RFQ scenario as example:

```
COMPANY_FX_ACC (general ledger)
  = SUM (all CLIENT_ACC AVAILABLE_BALANCE in USD)
    + COMPANY_FX_ACC's own NOSTRO_BALANCE

Defined in Reconciliation Config:
  {
    "reconRuleId": "RFQ-USD-CONTROL",
    "controlAccount": "COMPANY_FX_ACC",
    "controlBalanceType": "AVAILABLE_BALANCE",
    "currency": "USD",
    "sumAccounts": {
      "accountFilter": "accountType=CLIENT",
      "balanceType": "AVAILABLE_BALANCE",
      "currency": "USD"
    },
    "tolerance": 0.01
  }
```

Executed daily at EOD; differences exceeding tolerance generate Reconciliation Case.

---

## 4. L3: External Settlement Reconciliation

### 4.1 Reconciliation Flow

```
Step 1: Receive external settlement file
  Supported formats: CSV, SWIFT MT940 / MT950, HKICL RTGS message
  File uploaded via API or automatically fetched via SFTP

Step 2: Parse file, generate ExternalSettlementRecord

Step 3: Match internal JournalLine by the following Key:
  Primary Key: externalRef (external transaction ID)
  Secondary Key: amount + currency + valueDate

Step 4: Classification:
  MATCHED     → Both sides match, no discrepancy
  INTERNAL_ONLY → Internal has, external does not
  EXTERNAL_ONLY → External has, internal does not
  AMOUNT_MISMATCH → Both sides have but amounts differ
  DATE_MISMATCH → Amounts match but valueDate differs

Step 5: Generate Reconciliation Report + Reconciliation Case (discrepancy items)

Step 6: Discrepancy items require manual handling or system correction:
  INTERNAL_ONLY → Confirm whether Reversal is needed
  EXTERNAL_ONLY → Supplement Posting (Manual Adjustment)
  AMOUNT_MISMATCH → Reversal + Rebook
```

---

## 5. Reconciliation Case Management

Each discrepancy generates a Reconciliation Case, tracking the complete lifecycle from discovery to resolution:

### 5.1 Case State Machine

```
          Discover discrepancy
              |
              ▼
          [OPEN]
              |
    Manual or system assignment
              |
              ▼
        [IN_PROGRESS]
         |         |
    Correction    No correction needed
         |         |
         ▼         ▼
    [RESOLVED]  [WAIVED]
```

### 5.2 Case Structure

| Field | Description |
|---|---|
| `caseId` | Case unique ID |
| `reconType` | L1 / L2 / L3 |
| `discrepancyType` | BALANCE_MISMATCH / INTERNAL_ONLY / EXTERNAL_ONLY / AMOUNT_MISMATCH |
| `accountId` | Involved account |
| `currency` | Currency |
| `internalAmount` | Internal ledger amount |
| `externalAmount` | External settlement amount (L3 only) |
| `discrepancyAmount` | Discrepancy amount |
| `originalJournalId` | Related internal Journal |
| `externalRef` | External transaction ID |
| `status` | OPEN / IN_PROGRESS / RESOLVED / WAIVED |
| `assignedTo` | Handler |
| `resolutionAction` | Resolution method (REVERSAL / ADJUSTMENT / WAIVED) |
| `resolutionJournalId` | Correction Journal ID (if any) |
| `resolvedAt` | Resolution time |

---

## 6. Reconciliation Report

Standardized report generated after each reconciliation:

```json
{
  "reportId": "RECON-RPT-20260516",
  "reconDate": "2026-05-16",
  "generatedAt": "2026-05-16T23:45:00.000Z",
  "l1Summary": {
    "totalJournals": 125000,
    "balancedJournals": 125000,
    "unbalancedJournals": 0,
    "balanceConsistencyPassed": true
  },
  "l2Summary": {
    "rulesChecked": 5,
    "rulesPassed": 5,
    "rulesFailed": 0
  },
  "l3Summary": {
    "externalFiles": 3,
    "totalExternalRecords": 12500,
    "matched": 12498,
    "internalOnly": 1,
    "externalOnly": 1,
    "amountMismatch": 0,
    "openCases": 2
  }
}
```

---

## 7. API Design

```
# Trigger manual reconciliation
POST /ledger/reconciliation/trigger
  { "reconDate": "2026-05-16", "reconType": "L1" }

# Query reconciliation report
GET /ledger/reconciliation/reports?date=2026-05-16

# Query unresolved cases
GET /ledger/reconciliation/cases?status=OPEN&reconType=L3

# Update case status
PATCH /ledger/reconciliation/cases/{caseId}
  { "status": "RESOLVED", "resolutionAction": "ADJUSTMENT", "resolutionJournalId": "..." }

# Upload external settlement file (L3)
POST /ledger/reconciliation/external-files
  Content-Type: multipart/form-data
```

---

## 8. Performance Targets

| Operation | Target |
|---|---|
| L1 Journal debit-credit balance validation (daily 1 million entries) | ≤ 10 minutes |
| L2 Sub-ledger to general ledger (1000 accounts) | ≤ 1 minute |
| L3 External file comparison (100,000 records) | ≤ 5 minutes |
| Reconciliation Report generation | ≤ 2 minutes |

---

## 9. Acceptance Criteria

| # | Acceptance Condition | Test Method |
|---|---|---|
| AC-01 | L1 validation can detect artificially created unbalanced Journals | Functional test |
| AC-02 | L1 Balance consistency validation can detect State Machine and MySQL out-of-sync situations | Failure injection test |
| AC-03 | L2 sub-ledger sum correct, discrepancy exceeding tolerance generates Case | Functional test |
| AC-04 | L3 external file comparison, MATCHED / INTERNAL_ONLY / EXTERNAL_ONLY classification correct | Functional test |
| AC-05 | Reconciliation Case complete flow from OPEN to RESOLVED | Functional test |
| AC-06 | Daily EOD automatically triggers reconciliation, generates Report | Automated test |
| AC-07 | L1 million-entry Journal validation completes within 10 minutes | Performance test |
| AC-08 | Reconciliation Report and Case are permanently saved in MySQL, historical queries supported | Functional test |


---

# F-008 State Machine Design — Functional Requirements Specification

**Document Version**: v0.1  
**Feature**: F-008 State Machine Design  
**System**: Next-Gen Internal Ledger Platform  
**Status**: Draft for Review  
**Dependencies**: ADR-001 (Raft + CQRS architecture)

---

## 1. Feature Overview

State Machine is the core computing unit of the Ledger Platform, running on the Raft Leader node, responsible for:

1. **Apply Raft Log**: Receive committed Raft Commands, execute ledger calculations
2. **Maintain in-memory Balance**: Latest balances of all accounts, all Balance Types
3. **Generate Journal records**: Each apply produces complete journal + journal_line
4. **Persist to RocksDB**: Ensure crash recovery
5. **Periodic Snapshot**: Control Raft Log growth, speed up failure recovery

---

## 2. Data Structures

### 2.1 In-Memory Balance Store

```java
// Account balance Key (v0.3: expanded to include position)
record AccountBalanceKey(
    String accountId,
    String balanceType,
    String position,            // CURRENT / LOCKED / FROZEN
    String currency
) {}

// Account balance Entry
record BalanceEntry(
    BigDecimal amount,          // Current balance
    long stateVersion,          // Last updated Raft Log Index
    String lastJournalId,       // Last Journal ID
    Instant lastUpdatedAt       // Last update time
) {}

// Balance Store: lock-free read, Account Worker serial write
ConcurrentHashMap<AccountBalanceKey, BalanceEntry> balanceStore;

// Position-aware query helper
Map<String, BigDecimal> getPositionBalances(String accountId, String balanceType, String currency) {
    Map<String, BigDecimal> result = new HashMap<>();
    for (Position pos : Position.values()) {
        AccountBalanceKey key = new AccountBalanceKey(accountId, balanceType, pos.name(), currency);
        BalanceEntry entry = balanceStore.getOrDefault(key, BalanceEntry.ZERO);
        result.put(pos.name(), entry.amount);
    }
    return result;
}
```

### 2.2 In-Memory Idempotency Store

```java
// Idempotency Key: requestId (includes posting / reversal / adjustment)
// Value: completed result summary
record IdempotencyEntry(
    String requestId,
    String status,          // COMPLETED / REJECTED
    String journalId,       // journalId on success
    List<String> errors,    // Error list on failure
    Instant completedAt
) {}

// TTL: retain 24 hours (configurable), prevent unbounded map growth
// Implementation: ConcurrentHashMap + periodic eviction job
ConcurrentHashMap<String, IdempotencyEntry> idempotencyStore;
```

### 2.3 Account Metadata Store

```java
// Account status (ACTIVE / FROZEN / CLOSED)
ConcurrentHashMap<String, AccountMeta> accountMetaStore;

record AccountMeta(
    String accountId,
    String status,
    Instant createdAt,
    Set<String> allowedBalanceTypes
) {}
```

### 2.4 Balance Type Config Store

```java
// Balance Type configuration (from F-001 Balance Type Registry)
// Loaded from RocksDB at State Machine startup
// Updated via special RaftCommand when configuration changes
ConcurrentHashMap<String, BalanceTypeConfig> balanceTypeConfigStore;

record BalanceTypeConfig(
    String typeCode,
    boolean allowNegative,
    String negativeSemantics,   // PRE_AUTHORIZED / OVERDRAFT / etc.
    String signConvention,      // NORMAL_CREDIT / NORMAL_DEBIT
    String formula,             // Optional, used for FORMULA type
    int configVersion
) {}
```

---

## 3. Raft Command Types

All ledger operations are serialized into RaftCommand and submitted to Raft Group:

| Command Type | Source | Description |
|---|---|---|
| `POSTING_CMD` | F-002 Posting API | Normal booking |
| `REVERSAL_CMD` | F-004 Reversal API | Reverse existing Journal |
| `ADJUSTMENT_CMD` | F-003 Manual Adjustment API | Manual adjustment |
| `ACCOUNT_CREATE_CMD` | Account management API | Create new account |
| `ACCOUNT_FREEZE_CMD` | Account management API | Freeze account |
| `BALANCE_TYPE_CONFIG_CMD` | F-001 Registry management API | Update Balance Type configuration |
| `SNAPSHOT_CMD` | System internal | Trigger State Machine Snapshot |

---

## 4. Apply Flow

### 4.1 POSTING_CMD Apply

```
Input: PostingCommand {
  requestId, businessEventType, businessEventRef,
  valueDate, legs: [ { legId, lines: [ JournalLineCmd ] } ]
}

Execution steps:

1. Idempotency check
   if idempotencyStore.contains(requestId):
     return idempotencyStore.get(requestId)  // return original result directly

2. Account status check
   for each accountId in command:
     if accountMetaStore.get(accountId).status != ACTIVE:
       return REJECTED(ACCOUNT_FROZEN)

3. Balance validation (read balanceStore, calculate after value)
   for each JournalLineCmd:
     balanceTypeConfig = balanceTypeConfigStore.get(balanceType)
     currentBalance = balanceStore.get(AccountBalanceKey)
     afterBalance = compute(currentBalance, entryType, amount, signConvention)

     if !allowNegative && afterBalance < 0:
       return REJECTED(INSUFFICIENT_BALANCE)
     if allowNegative && afterBalance > 0:
       return REJECTED(CREDIT_EXCEEDS_LIMIT)

4. Generate Journal
   journalId = generateJournalId(raftLogIndex)
   journal = Journal {
     journalId, journalType=NORMAL,
     requestId, businessEventType, businessEventRef,
     valueDate, status=CONFIRMED,
     createdAt=now()
   }

5. Generate JournalLine
   for each JournalLineCmd:
     balanceBefore = balanceStore.get(key).amount
     balanceAfter = compute(balanceBefore, ...)
     journalLine = JournalLine {
       journalLineId, journalId, legId,
       accountId, balanceType, currency,
       entryType, amount,
       balanceBefore, balanceAfter,
       configVersion, createdAt=now()
     }

6. Atomically update balanceStore
   for each journalLine:
     balanceStore.put(key, BalanceEntry(balanceAfter, raftLogIndex, journalId, now()))

7. Persist to RocksDB
   rocksDB.put(CF_JOURNAL, journalId, serialize(journal))
   for each journalLine:
     rocksDB.put(CF_JOURNAL_LINE, journalLineId, serialize(journalLine))
   for each AccountBalanceKey:
     rocksDB.put(CF_BALANCE, key, serialize(balanceStore.get(key)))
   // Above three puts in same RocksDB WriteBatch, atomic commit

8. Update idempotencyStore
   idempotencyStore.put(requestId, IdempotencyEntry(COMPLETED, journalId, ...))

9. Return PostingResult
```

### 4.2 REVERSAL_CMD Apply

```
1. Idempotency check (same as above)
2. Load original Journal + all JournalLines
3. Validate: original Journal status = CONFIRMED (not reversed)
4. Generate Reversal Journal
5. Generate mirror JournalLines (DEBIT ↔ CREDIT swapped)
6. No sign semantics validation (Reversal is offsetting, inherently valid)
7. Atomically update balanceStore (rollback related balances)
8. Update original Journal status = REVERSED
9. Write RocksDB, update idempotencyStore, return result
```

### 4.3 ADJUSTMENT_CMD Apply

```
1. Idempotency check (same as above)
2. Read balanceStore for sign semantics validation (must comply with allowNegative rules)
3. Generate Adjustment Journal (journalType=MANUAL_ADJUSTMENT)
4. Atomically update balanceStore
5. Write RocksDB, update idempotencyStore, return result
```

---

## 5. RocksDB Storage Design

### 5.1 Column Family Design

```
CF_JOURNAL          Stores Journal header records
CF_JOURNAL_LINE     Stores JournalLine records
CF_BALANCE          Stores account balance snapshots (latest values)
CF_IDEMPOTENCY      Stores idempotency records (requestId → result)
CF_ACCOUNT_META     Stores account metadata
CF_BALANCE_TYPE     Stores Balance Type configurations
CF_SM_SNAPSHOT      Stores State Machine Snapshots
```

### 5.2 Key Design

```
CF_JOURNAL:
  Key: journal_id (lexicographic)
  → Fast point query by journal_id

CF_JOURNAL_LINE:
  Key: journal_id + "#" + journal_line_id
  → Prefix scan by journal_id retrieves all lines of a Journal

CF_BALANCE (v0.3):
  Key: account_id + "#" + balance_type + "#" + position + "#" + currency
  → Prefix scan by account_id retrieves all balances of an account
  → Prefix scan by account_id + "#" + balance_type retrieves all positions of a balance type

CF_IDEMPOTENCY:
  Key: request_id
  Value: Serialized IdempotencyEntry + TTL timestamp
```

### 5.3 WriteBatch Atomicity Guarantee

Each apply of a RaftCommand packages all RocksDB write operations into a `WriteBatch`, ensuring journal / journal_line / balance are atomically persisted:

```java
WriteBatch batch = new WriteBatch();
batch.put(CF_JOURNAL, journalKey, journalBytes);
batch.put(CF_JOURNAL_LINE, line1Key, line1Bytes);
batch.put(CF_JOURNAL_LINE, line2Key, line2Bytes);
batch.put(CF_BALANCE, balKey1, balBytes1);
batch.put(CF_BALANCE, balKey2, balBytes2);
rocksDB.write(writeOptions, batch);
// WriteBatch write is atomic: either all succeed or all fail
```

---

## 6. State Machine Snapshot

### 6.1 Snapshot Trigger Conditions

| Trigger | Description |
|---|---|
| Raft Log reaches 100,000 entries | Auto-trigger to prevent unbounded Log growth |
| Accounting period close | Force Snapshot at EOD as reconciliation baseline |
| Manual trigger | Admin API for upgrade or pre-disaster recovery |

### 6.2 Snapshot Content

```
Snapshot contains:
  1. Complete snapshot of all accounts' balanceStore
  2. Snapshot of all accounts' accountMetaStore
  3. Snapshot of all Balance Type configurations
  4. Corresponding Raft Log Index (lastAppliedIndex)
  5. Generation timestamp

Snapshot format:
  Serialized as Protobuf / Kryo, written to CF_SM_SNAPSHOT
  Also saved to local disk via SOFAJRaft's SnapshotWriter
  Follower can directly copy Snapshot, accelerating new node join
```

### 6.3 Failure Recovery Flow

```
Leader down → new Leader elected

New Leader recovery steps:
  1. Load latest Snapshot from CF_SM_SNAPSHOT
     → Restore balanceStore / accountMetaStore / balanceTypeConfigStore
  2. Replay all Commands after lastAppliedIndex from Raft Log
     → Apply sequentially to replay all changes after Snapshot
  3. State Machine fully recovered, begin serving

Recovery time estimate:
  Snapshot load: < 10 seconds (depends on account count)
  Raft Log replay: < 30 seconds (100,000 logs × 0.3ms/cmd)
  Total: < 1 minute (normal scenario)
```

---

## 7. Learner Sync Design

### 7.1 Learner Role

Learner is a non-voting member of Raft, receives Leader's Raft Log but does not participate in election:

```
Raft Leader
    |
    | replicate Raft Log (async, does not block Quorum)
    ▼
Raft Learner
    |
    ▼
  Learner State Machine
    | apply Raft Log, convert ledger events to MySQL write operations
    ▼
  MySQL View Layer
    ├─ journal (for F-006 Journal Query)
    ├─ journal_line (for F-006 Journal Query)
    ├─ account (account data, for F-010 Account Query)
    ├─ account_balance (for F-007 Reconciliation; includes frozen_amount, locked_amount)
    ├─ balance_type_registry (Balance Type configuration)
    └─ balance_snapshot (for F-005 As-of Query)
```

### 7.2 Sync Delay

- Normal case: Learner lags Leader < 1 second
- High load: < 5 seconds (Learner has write buffer for batch MySQL writes)
- Query side marks `dataSource` so caller knows data may be slightly stale

### 7.3 Learner MySQL Write Design

To avoid Learner becoming a bottleneck, Learner adopts batch write strategy:

```
Learner buffers 500ms or 1000 Raft Log entries (whichever comes first)
→ Batch INSERT INTO journal_line VALUES (...)
→ Batch UPSERT account_balance SET amount, account_seq, last_journal_id
  (frozen_amount, locked_amount preserved and not overwritten)
→ commit
```

---

## 8. Cold Account Management

Account count may reach millions; not all accounts can reside in memory:

```
Active Set: Accounts with transactions in past 24 hours → Resident in balanceStore (memory)
Inactive Set: No transactions for over 24 hours → Evicted from balanceStore, only kept in RocksDB

Reading Inactive account balance:
  1. Query balanceStore → miss
  2. Read from RocksDB CF_BALANCE (< 1ms)
  3. Load into balanceStore (warm up)

Writing Inactive account:
  Account Worker loads balance from RocksDB at startup
  → Then normal apply flow
```

---

## 9. Performance Targets

| Operation | Target | Description |
|---|---|---|
| Balance read (Active account) | < 0.1ms | ConcurrentHashMap direct read |
| Balance read (Inactive account) | < 1ms | RocksDB read |
| RaftCommand Apply (single account) | < 1ms | State Machine + RocksDB WriteBatch |
| RaftCommand Apply (multi-account RFQ) | < 2ms | Multi-account WriteBatch |
| State Machine Snapshot (1 million accounts) | < 30s | Batch serialization |
| Failure recovery (Snapshot + 100,000 Replay) | < 1 min | See 6.3 |

---

## 10. Acceptance Criteria

| # | Acceptance Condition | Test Method |
|---|---|---|
| AC-01 | After Posting, in-memory Balance updates immediately, no delay | Functional test |
| AC-02 | Same requestId hits in idempotencyStore, returns original result directly without re-apply | Idempotency test |
| AC-03 | RocksDB WriteBatch atomicity: simulate crash mid-write, data consistent after restart | Failure test |
| AC-04 | After State Machine Snapshot, recovered Balance is completely consistent with pre-Snapshot | Recovery test |
| AC-05 | Failure recovery (Snapshot + Replay) completes within 1 minute | Performance test |
| AC-06 | After Inactive account evicted, next access correctly warms up from RocksDB | Functional test |
| AC-07 | Learner sync delay under normal load < 1 second | Consistency test |
| AC-08 | 1 million account State Machine, Balance read P95 ≤ 0.1ms (Active) / ≤ 1ms (Inactive) | Performance test |
| AC-09 | After BALANCE_TYPE_CONFIG_CMD apply, new allowNegative rules take effect immediately | Functional test |
| AC-10 | Multi-Account RaftCommand (RFQ scenario) applies atomically in State Machine, no partial update | Atomicity test |


---

# F-009 Accounting Period / EOD — Functional Requirements Specification

**Document Version**: v0.1  
**Feature**: F-009 Accounting Period & EOD Close  
**System**: Next-Gen Internal Ledger Platform  
**Status**: Draft for Review  
**Dependencies**: ADR-001, F-005 Balance Snapshot, F-007 Reconciliation

---

## 1. Feature Overview

Accounting Period manages ledger period switching logic: controlling which period is open for booking, which period is closed, and snapshot and reconciliation triggering during EOD (End of Day) close.

---

## 2. Accounting Period State Machine

```
      Open
        |
        ▼
     [OPEN]         ← Normal bookings fall in this period
        |
  EOD trigger close
        |
        ▼
   [CLOSING]        ← New Posting prohibited, waiting for EOD tasks to complete
        |
  EOD tasks complete
        |
        ▼
    [CLOSED]        ← No booking allowed; cross-period Reversal must mark crossPeriod=true
        |
  Next period open
        |
        ▼
   [OPEN] (T+1)
```

---

## 3. EOD Task Sequence

When closing an accounting period, the system executes EOD tasks in the following **strict order**:

```
Step 1  Stop accepting new Posting (period status → CLOSING)
Step 2  Wait for all in-flight Raft Commands to complete (drain queue)
Step 3  Trigger State Machine Snapshot (F-008)
Step 4  Learner confirms MySQL View Layer has caught up to Snapshot Index
Step 5  Execute L1 reconciliation (Journal debit-credit balance + Balance consistency)
Step 6  Execute L2 reconciliation (sub-ledger to general ledger)
Step 7  Generate EOD Balance Snapshot (all accounts' current balances)
Step 8  Generate Reconciliation Report
Step 9  Period status → CLOSED
Step 10 New period OPEN (T+1)
```

Any step failure: raise alert, manual intervention, do not auto-skip.

---

## 4. Accounting Period Configuration

| Field | Description |
|---|---|
| `periodId` | Period unique ID (e.g. `2026-05-16`) |
| `openTime` | Period open time |
| `scheduledCloseTime` | Scheduled close time (e.g. daily 23:30) |
| `actualCloseTime` | Actual close completion time |
| `status` | OPEN / CLOSING / CLOSED |
| `eodTaskStatus` | EOD sub-task status JSON |

---

## 5. Cross-Period Rules

| Situation | Handling |
|---|---|
| Posting to CLOSED period | Reject, return `PERIOD_CLOSED` |
| Reversal to CLOSED period | Allow, but mark `crossPeriod=true` |
| Manual Adjustment to CLOSED period | Allow, but requires additional approval (Checker must confirm cross-period in approval note) |

---

## 6. API Design

```
# Query period list
GET /ledger/accounting-periods?status=OPEN

# Manually trigger EOD (testing or re-run)
POST /ledger/accounting-periods/{periodId}/eod/trigger

# Query EOD task status
GET /ledger/accounting-periods/{periodId}/eod/status
```

---

## 7. Acceptance Criteria

| # | Acceptance Condition | Test Method |
|---|---|---|
| AC-01 | EOD tasks execute in strict order, any step failure does not continue | Functional test |
| AC-02 | New Posting requests during CLOSING return `PERIOD_CLOSED` | Functional test |
| AC-03 | Cross-period Reversal marks `crossPeriod=true` | Functional test |
| AC-04 | After EOD completes, EOD Balance Snapshot is consistent with State Machine | Reconciliation test |
| AC-05 | EOD full flow (including L1/L2 reconciliation) completes within 30 minutes | Performance test |


---

# F-010 Account Management — Functional Requirements Specification

**Document Version**: v0.1  
**Feature**: F-010 Account Management  
**System**: Next-Gen Internal Ledger Platform  
**Status**: Draft for Review  
**Dependencies**: ADR-001, F-001 Balance Type Registry, F-008 State Machine

---

## 1. Feature Overview

Account Management manages the complete lifecycle of accounts, including creation, Balance Type initialization, freeze, unfreeze, close, and maintains account metadata for Posting, Balance Query, and Reconciliation use.

---

## 2. Account Types

| Type | Description | Example |
|---|---|---|
| `CLIENT` | Client account | `CLIENT_ACC_001` |
| `COMPANY` | Company own account (including RFQ counterparty account) | `COMPANY_FX_ACC` |
| `SUSPENSE` | Suspense account (settlement discrepancy temporary hold) | `SUSPENSE_USD_001` |
| `NOSTRO` | Our account at counterparty bank | `NOSTRO_HSBC_USD` |
| `CONTROL` | General ledger control account (for L2 reconciliation) | `CONTROL_CLIENT_USD` |

---

## 3. Account State Machine

```
   Create
    |
    ▼
 [ACTIVE]  ←──────────────────┐
    |                         |
    | Freeze                Unfreeze
    ▼                         |
[FROZEN] ─────────────────────┘
    |
    | Close (requires zero balance)
    ▼
 [CLOSED]  ← No booking, no unfreeze, query only
```

---

## 4. API Design

### 4.1 Create Account

```
POST /ledger/accounts
```

**Request Body**

| Field | Type | Required | Description |
|---|---|---|---|
| `accountId` | `string` | ✅ | Account unique ID (specified by caller, must be unique) |
| `accountType` | `enum` | ✅ | CLIENT / COMPANY / SUSPENSE / NOSTRO / CONTROL |
| `displayName` | `string` | ✅ | Display name |
| `ownerId` | `string` | ❌ | Account owner ID (required for client accounts) |
| `balanceInitializations` | `list` | ✅ | Initialized balance type + currency (default initial balance 0) |
| `metadata` | `map` | ❌ | Extension fields |

**balanceInitializations Structure (v0.3)**

Each initialization must specify `balanceType` + `position` + `currency`. Default initial balance is 0.

```json
[
  { "balanceType": "AVAILABLE_BALANCE", "position": "CURRENT", "currency": "USD" },
  { "balanceType": "AVAILABLE_BALANCE", "position": "CURRENT", "currency": "HKD" },
  { "balanceType": "TRADE_AHEAD_BALANCE", "position": "CURRENT", "currency": "USD" }
]
```

> `position` is required as of v0.3. If omitted, defaults to `CURRENT`.

**Account creation goes through Raft**: Account metadata must be created in State Machine to ensure consistency across all nodes.

### 4.2 Freeze / Unfreeze / Close

```
POST /ledger/accounts/{accountId}/freeze
POST /ledger/accounts/{accountId}/unfreeze
POST /ledger/accounts/{accountId}/close
```

- Freeze / unfreeze go through Raft (affects State Machine account status)
- Close must validate all Balance Type balances are zero, otherwise reject

### 4.3 Query Account

```
GET /ledger/accounts/{accountId}
GET /ledger/accounts?accountType=CLIENT&ownerId=CUST-001
```

Read from MySQL View Layer (eventual consistency).

### 4.4 Add Balance Type to Existing Account

```
POST /ledger/accounts/{accountId}/balance-types
{ "balanceType": "BROKERAGE_BALANCE", "position": "CURRENT", "currency": "USD" }
```

Go through Raft, initialize new balance entry in State Machine (initial value 0).

---

## 5. Validation Rules

| # | Rule | Error Code |
|---|---|---|
| V-01 | `accountId` globally unique | `ACCOUNT_ALREADY_EXISTS` |
| V-02 | `balanceType` must exist in F-001 Registry | `BALANCE_TYPE_NOT_FOUND` |
| V-03 | All Balances must be zero when closing account | `ACCOUNT_HAS_NON_ZERO_BALANCE` |
| V-04 | CLOSED account cannot be unfrozen or reactivated | `ACCOUNT_CLOSED` |
| V-05 | CLIENT type account requires `ownerId` at creation | `MISSING_OWNER_ID` |

---

## 6. Acceptance Criteria

| # | Acceptance Condition | Test Method |
|---|---|---|
| AC-01 | After account creation, State Machine can immediately query account metadata | Functional test |
| AC-02 | After freezing account, Posting to this account returns `ACCOUNT_FROZEN` | Functional test |
| AC-03 | After unfreezing, Posting executes normally | Functional test |
| AC-04 | Closing account with non-zero balance returns `ACCOUNT_HAS_NON_ZERO_BALANCE` | Functional test |
| AC-05 | After adding Balance Type, Posting to this type can be done immediately | Functional test |


---

# NFR — Non-Functional Requirements Specification

**Document Version**: v0.1  
**Feature**: Non-Functional Requirements (NFR)  
**System**: Next-Gen Internal Ledger Platform  
**Status**: Draft for Review

---

## 1. Performance

| Metric | Target | Test Condition |
|---|---|---|
| Posting P95 latency | ≤ 3ms | 1000 concurrent, including hotspot account (COMPANY_ACC) |
| Posting P99 latency | ≤ 10ms | Same as above |
| Balance Query P95 (Active account) | ≤ 2ms | Read in-memory State Machine |
| Balance Query P95 (Inactive account) | ≤ 5ms | Read RocksDB warm-up |
| Journal point query P95 | ≤ 10ms | MySQL View Layer, index hit |
| Account entries query P95 (50 items) | ≤ 30ms | MySQL View Layer |
| Manual Adjustment Approve P95 | ≤ 10ms | Go through Raft |
| Reversal P95 | ≤ 5ms | Go through Raft |
| Learner sync delay (normal load) | ≤ 1s | Raft Leader → MySQL View Layer |
| EOD full flow (including L1/L2 reconciliation) | ≤ 30 minutes | Daily 1 million Journals |

---

## 2. Throughput

| Metric | Target |
|---|---|
| Posting TPS (peak) | ≥ 10,000 TPS |
| Balance Query QPS | ≥ 50,000 QPS |
| Journal Query QPS | ≥ 5,000 QPS |

---

## 3. Availability

| Metric | Target |
|---|---|
| System availability (annual) | ≥ 99.99% (annual downtime ≤ 52 minutes) |
| Raft Leader failure recovery time (RTO) | ≤ 30 seconds (including election + State Machine recovery) |
| Planned maintenance downtime window | ≤ 30 minutes monthly (non-EOD period) |
| Multi-AZ deployment | 3 nodes across AZs, 1 AZ failure does not affect service |

---

## 4. Data Durability

| Metric | Target |
|---|---|
| RPO (Recovery Point Objective) | 0 (Raft Quorum commit is persistent, no confirmed ledger data lost) |
| RTO (Recovery Time Objective) | ≤ 1 minute (Snapshot + Replay) |
| Journal retention period | ≥ 7 years (compliance requirement) |
| Audit Log retention period | ≥ 7 years |

---

## 5. Consistency

| Scenario | Consistency Level |
|---|---|
| Posting / Reversal / Adjustment write | **Strong consistency** (Raft Quorum commit) |
| Balance Query (real-time) | **Strong consistency** (read Leader in-memory State Machine) |
| Journal Query | **Eventual consistency** (Learner sync, delay ≤ 1s) |
| Reconciliation | **Eventual consistency** (T+0 completion within day) |

---

## 6. Immutability

- All posted JournalLines **prohibit UPDATE / DELETE**
- Only append allowed (new Journal / Reversal / Adjustment)
- RocksDB and MySQL View Layer both maintain JournalLine in append-only mode

---

## 7. Idempotency

- All write operations (Posting / Reversal / Adjustment Approve) support idempotency
- Idempotency Key: `requestId` (UUID v7), TTL ≥ 24 hours
- Retry with same `requestId` returns original result, no duplicate booking
- In-memory idempotency store + DB unique constraint dual guarantee

---

## 8. Security

| Requirement | Description |
|---|---|
| Authentication | All APIs require JWT / mTLS authentication |
| Authorization | RBAC, distinguishing read-only (Viewer), operator (Operator), approval (Checker), admin (Admin) roles |
| Maker-Checker | Manual Adjustment mandatory dual approval, cannot be bypassed |
| Audit Log | All write operations record operator, time, IP, traceId, retain 7 years |
| Sensitive Data | Account ID, amount desensitized in logs |

---

## 9. Observability

| Requirement | Description |
|---|---|
| Distributed tracing | All requests carry traceId / spanId, integrated with Jaeger / Zipkin |
| Metrics | Prometheus exposes TPS, P50/P95/P99 latency, Queue backlog, Raft term, Learner lag |
| Alerting | Posting P99 > 50ms, Queue backlog > 1000, Learner lag > 10s, L1 reconciliation failure → PagerDuty |
| Logging | Structured JSON logs, with journalId, requestId, accountId, traceId |
| Every entry traceable | Any balance change can trace to source event, operator, rule version, journal chain within 5 minutes |

---

## 10. Capacity Planning

| Metric | Assumed Value | Description |
|---|---|---|
| Total accounts | 1,000,000 | Active accounts ~ 100,000 resident in memory |
| Daily Journal count | 5,000,000 | Daily 5 million ledger entries |
| Average JournalLines per Journal | 4 | RFQ scenario typically 4 lines |
| Daily JournalLine count | 20,000,000 | |
| RocksDB daily growth | ~10 GB | Estimated 500 bytes / JournalLine |
| MySQL View Layer daily growth | ~20 GB | Including indexes |
| State Machine memory (Active accounts) | ~2 GB | 100,000 accounts × 5 BalanceType × ~4KB |
| Raft Log Snapshot interval | 100,000 entries | About 1 snapshot per minute (10,000 TPS peak) |

---

## 11. Disaster Recovery (DR)

| Requirement | Description |
|---|---|
| Multi-AZ deployment | 3 Raft nodes distributed across 3 AZs |
| Cross-DC disaster recovery | Raft Learner can be deployed in remote DC as DR node |
| RocksDB backup | Daily full RocksDB checkpoint backup to object storage (S3 / OSS) |
| Recovery drill | Quarterly full DR drill, verify RTO ≤ 1 minute |

---

## 12. Technical Constraints

| Constraint | Description |
|---|---|
| Language / Framework | Java 21 + Spring Boot 3, using Virtual Threads |
| Raft Library | SOFAJRaft (evaluate Apache Ratis as alternative) |
| Local persistence | RocksDB (Java API) |
| View Layer DB | MySQL 8.0+ (MyBatis, ORM prohibited) |
| Message Bus | Kafka (Learner sync outputs ledger events for downstream consumption) |
| Prohibited | Hibernate / JPA / Redis (write path) / Direct MySQL write bypassing Raft |


---

# Appendix A — Module Dependency Graph & Docker Compose Stack

> This appendix is merged from `docs/architecture.md`, supplementing ADR-001 with deployment view and module dependencies.

## A.1 Docker Compose Service Architecture

```
┌─────────────────────────────────────────────────────────┐
│                   Docker Compose Stack                    │
│                                                          │
│  ledger-node-1   :8081,28081    Raft Leader/Follower     │
│  ledger-node-2   :8082,28082    Raft Leader/Follower     │
│  ledger-node-3   :8083,28083    Raft Leader/Follower     │
│  ledger-mysql    :3306          MySQL 8.4 View Layer     │
│  ledger-kafka    :9092          Kafka Broker             │
│  ledger-projection :8089        Projection Consumer      │
│                                                          │
│  Host mounts: ./jraft_ledger/{node1,node2,node3,mysql,kafka} │
│  Network: ledger-net (bridge)                            │
└─────────────────────────────────────────────────────────┘
```

## A.2 Module Dependency Graph

```
ledger-core        ← domain, state machine, rocksdb, stores
    ↑
ledger-dao         ← MyBatis mappers (depends on core)
    ↑
ledger-service     ← business services (depends on core + dao)
    ↑
ledger-restful     ← Spring Boot + REST controllers (depends on service)
ledger-feign        ← OpenFeign clients (depends on core)
ledger-projection   ← Kafka consumer → MySQL (depends on core + dao)
```

---

# Appendix B — Detailed Persistence Flow (Posting API)

> This appendix is merged from `docs/persistence-flow.md`, supplementing F-002 with detailed posting persistence flow.

## B.1 Complete Persistence Flow Diagram

```
                          ┌─────────────────────────────┐
                          │         Client / Caller       │
                          └─────────────┬───────────────┘
                                        │ POST /ledger/postings
                                        ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                         REST Layer (any node)                            │
│                                                                          │
│  ┌──────────────────────────────────┐                                    │
│  │       PostingController          │  1. Deserialize JSON → PostingCmd │
│  │                                  │  2. Check NodeRole.isLeader()     │
│  │   ❌ FOLLOWER → HTTP 503         │     (writes only on leader)       │
│  │   ✅ LEADER  → delegate          │                                    │
│  └──────────────┬───────────────────┘                                    │
│                 │                                                        │
│  ┌──────────────▼───────────────────┐                                    │
│  │        PostingService            │  Thin wrapper → delegates         │
│  └──────────────┬───────────────────┘                                    │
└─────────────────┼────────────────────────────────────────────────────────┘
                  │
                  ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                     LedgerStateMachine.applyPosting()                     │
│                    (synchronized — single-threaded)                       │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │ 1. IDEMPOTENCY CHECK                                             │   │
│  │    idempotencyStore.contains(requestId)?                         │   │
│  │    → YES: return cached result (no re-execution)                 │   │
│  │    → NO:  continue                                               │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │ 2. ACCOUNT STATUS CHECK                                          │   │
│  │    accountMetaStore.get(accountId).status == ACTIVE?             │   │
│  │    → FROZEN: reject ACCOUNT_FROZEN                               │   │
│  │    → CLOSED: reject ACCOUNT_CLOSED                               │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │ 3. PER-LEG BALANCE CHECK                                         │   │
│  │    DEBIT total == CREDIT total per leg?                          │   │
│  │    → unbalanced: reject JOURNAL_UNBALANCED                       │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │ 4. BALANCE VALIDATION (in-memory)                                │   │
│  │    for each JournalLine:                                         │   │
│  │      config = balanceTypeConfigStore.get(type)                   │   │
│  │      after = computeAfterBalance(current, entryType, amount)     │   │
│  │      if !allowNegative && after < 0 → INSUFFICIENT_BALANCE      │   │
│  │      if  allowNegative && after > 0 → CREDIT_EXCEEDS_LIMIT      │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │ 5. GENERATE JOURNAL                                              │   │
│  │    journalId = "JNL-NNNN"                                        │   │
│  │    for each line:                                                │   │
│  │      journalLineId = journalId + "-01"                           │   │
│  │      JournalLine{ balanceBefore, balanceAfter, amount, ... }     │   │
│  │    Journal{ journalType=NORMAL, status=CONFIRMED, lines, ... }   │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │ 6. ATOMIC BALANCE UPDATE (in-memory)                             │   │
│  │    for each line:                                                │   │
│  │      nextSeq = current.accountSeq + 1                             │   │
│  │      balanceStore.put(key, BalanceEntry{after, nextSeq, ...})    │   │
│  │    (accountSeq overflow check: if >= 80% Long.MAX_VALUE → alert) │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │ 7. EVENT PUBLISHING (in-memory → outbox)                         │   │
│  │    for each line:                                                │   │
│  │      event = BalanceChangeEvent{accountSeq, prevAccountSeq, ...} │   │
│  │      eventListener.onEvent(event)   ← Kafka / test capture       │   │
│  │      outboxStore.enqueue(event)     ← RocksDB outbox             │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │ 8. IDEMPOTENCY RECORD                                            │   │
│  │    idempotencyStore.put(requestId, COMPLETED)                    │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │ 9. PERSIST (if persistAfterApply=true)                           │   │
│  │    takeSnapshot() → serialize ALL state to RocksDB:              │   │
│  │                                                                   │   │
│  │    ┌─────────────────────────────────────────────────────────┐   │   │
│  │    │                   RocksDB (Source of Truth)              │   │   │
│  │    │                                                          │   │   │
│  │    │  CF_JOURNAL       → journalId → Journal JSON             │   │   │
│  │    │  CF_JOURNAL_LINE  → journalLineId → JournalLine JSON     │   │   │
│  │    │  CF_BALANCE       → key → BalanceEntry JSON (accountSeq) │   │   │
│  │    │  CF_IDEMPOTENCY   → requestId → IdempotencyEntry JSON    │   │   │
│  │    │  CF_ACCOUNT_META  → accountId → Account JSON             │   │   │
│  │    │  CF_BALANCE_TYPE  → typeCode → BalanceTypeConfig JSON   │   │   │
│  │    │  CF_SM_SNAPSHOT   → full state snapshot                  │   │   │
│  │    │  CF_OUTBOX        → eventId → BalanceChangeEvent JSON    │   │   │
│  │    │                                                          │   │   │
│  │    │  📁 /var/lib/ledger/rocksdb (Docker)                    │   │   │
│  │    │  📁 ./jraft_ledger/node1 (host mount)                    │   │   │
│  │    └─────────────────────────────────────────────────────────┘   │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │ 10. RETURN                                                        │   │
│  │     CommandResult{ status=COMPLETED, journalId="JNL-0001" }      │   │
│  └──────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────┘
                  │
                  │  (async, via Learner or sync service)
                  ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                       MySQL View Layer (Read-Only)                       │
│                                                                          │
│   journal (table)               journal_line (table)                     │
│   ┌──────────────────┐          ┌──────────────────────────┐            │
│   │ journal_id (PK)  │◄─────────│ journal_id (FK)          │            │
│   │ journal_type      │          │ journal_line_id (PK)     │            │
│   │ request_id        │          │ leg_id                  │            │
│   │ business_event_ref │         │ account_id              │            │
│   │ value_date        │          │ balance_type            │            │
│   │ status            │          │ currency                │            │
│   │ cross_period      │          │ entry_type              │            │
│   │ created_at        │          │ amount                  │            │
│   └──────────────────┘          │ balance_before          │            │
│                                  │ balance_after           │            │
│   account (table)                │ config_version          │            │
│   ┌──────────────────┐          │ created_at              │            │
│   │ account_id (PK)  │          └──────────────────────────┘            │
│   │ account_type      │                                                  │
│   │ owner_id          │   balance_type_registry (table)                  │
│   │ status            │   ┌──────────────────────────┐                   │
│   └──────────────────┘   │ type_code (PK)            │                   │
│                           │ config_version            │                   │
│                           └──────────────────────────┘                   │
│                                                                          │
│   Synced by: Raft Learner (async) or JournalSyncService                  │
│   Used for:  Journal Query (F-006), Reconciliation (F-007)               │
│   NOT used:  Balance Query (reads from in-memory StateMachine)           │
└─────────────────────────────────────────────────────────────────────────┘
```

## B.2 Kafka Outbox → Projection Flow

```
                          ┌─────────────────────┐
                          │   Kafka (Event Bus)  │
                          │                      │
                          │  Topic:              │
                          │  ledger.balance.     │
                          │  change.v1           │
                          │                      │
                          │  64 partitions       │
                          │  LZ4 compression     │
                          │  acks=all            │
                          └─────────────────────┘
                               ▲
                               │ AsyncKafkaPublisher
                               │ (reads from CF_OUTBOX)
                               │
                          ┌────┴────────────┐
                          │  OutboxStore     │
                          │  (RocksDB CF)    │
                          └─────────────────┘
```

## B.3 Recovery Flow (on restart)

```
  App Start
      │
      ▼
  RocksDBManager.open(path)
      │
      ▼
  LedgerStateMachine.restoreFromSnapshot()
      │  reads CF_SM_SNAPSHOT → deserializes:
      │    • balanceStore (with accountSeq)
      │    • accountMetaStore
      │    • balanceTypeConfigStore
      │    • journalStore
      │    • idempotencyStore
      │    • raftLogIndex, journalSequence
      │
      ▼
  StateMachine ready — all state restored
      │
      ▼
  New writes persist via takeSnapshot() after each apply
```

---

# Appendix C — Overall Architecture (English Reference)

> This appendix is merged from `docs/architecture.md`, providing an English overall architecture diagram for cross-team reference.

## C.1 Architecture Diagram

```
                            ┌──────────────────────────────┐
                            │         API Consumer          │
                            │    (RFQ Engine, Withdrawal,   │
                            │     Order Mgmt, Admin)        │
                            └──────┬───────────┬───────────┘
                                   │           │
                         Writes   │           │  Reads (any node)
                      (leader only)│           │
                                   │           │
        ┌──────────────────────────┼───────────┼──────────────────────────┐
        │                          ▼           ▼                          │
        │   ┌─────────────────────────────────────────────────────────┐   │
        │   │              Raft Cluster (3 nodes)                      │   │
        │   │                                                          │   │
        │   │   ┌──────────────┐  ┌──────────────┐  ┌──────────────┐   │   │
        │   │   │  Ledger-1    │  │  Ledger-2    │  │  Ledger-3    │   │   │
        │   │   │  (Leader)    │  │  (Follower)  │  │  (Follower)  │   │   │
        │   │   │  :8081       │  │  :8082       │  │  :8083       │   │   │
        │   │   │              │  │              │  │              │   │   │
        │   │   │ ┌──────────┐ │  │ ┌──────────┐ │  │ ┌──────────┐ │   │   │
        │   │   │ │StateMach │◄┼──┼─┤StateMach │ │  │ │StateMach │ │   │   │
        │   │   │ │(in-mem)  │ │  │ │(in-mem)  │ │  │ │(in-mem)  │ │   │   │
        │   │   │ └────┬─────┘ │  │ └──────────┘ │  │ └──────────┘ │   │   │
        │   │   │      │       │  │              │  │              │   │   │
        │   │   │ ┌────▼─────┐ │  │              │  │              │   │   │
        │   │   │ │ RocksDB  │ │  │              │  │              │   │   │
        │   │   │ │(persist) │ │  │              │  │              │   │   │
        │   │   │ └──────────┘ │  │              │  │              │   │   │
        │   │   └──────┬───────┘  └──────────────┘  └──────────────┘   │   │
        │   │          │                                                 │   │
        │   │          │ Raft Log Replication (Bolt RPC)                 │   │
        │   │          │                                                 │   │
        │   │   ┌──────▼──────────────────────────────────────────────┐ │   │
        │   │   │                   Kafka Cluster                      │ │   │
        │   │   │                                                      │ │   │
        │   │   │  Topic: ledger.balance.change.v1      (per-line)     │ │   │
        │   │   │  Topic: ledger.posting.completion.v1  (per-request)  │ │   │
        │   │   │                                                      │ │   │
        │   │   │  Partition Key: accountId:balanceType:currency       │ │   │
        │   │   │  64 partitions, LZ4 compression, 7-day retention     │ │   │
        │   │   └──────────┬───────────────────────────────────────────┘ │   │
        │   │              │                                              │   │
        │   │              │  Async consume (at-least-once)               │   │
        │   │              ▼                                              │   │
        │   │   ┌──────────────────────────────────────────────────────┐ │   │
        │   │   │           Projection Service (CQRS Read Side)         │ │   │
        │   │   │                                                       │ │   │
        │   │   │  • Consumes Kafka balance.change.v1                  │ │   │
        │   │   │  • Projects to MySQL: journal, journal_line, balance  │ │   │
        │   │   │  • Idempotent (INSERT ... ON DUPLICATE KEY)           │ │   │
        │   │   │  • Stateless — can scale horizontally                │ │   │
        │   │   └──────────┬───────────────────────────────────────────┘ │   │
        │   │              │                                              │   │
        │   │              ▼                                              │   │
        │   │   ┌──────────────────────────────────────────────────────┐ │   │
        │   │   │               MySQL 8.4 (View Layer)                  │ │   │
        │   │   │                                                       │ │   │
        │   │   │  Tables: journal, journal_line, account,              │ │   │
        │   │   │          balance_type_registry, balance_snapshot      │ │   │
        │   │   │                                                       │ │   │
        │   │   │  Used by: Journal Query, Reconciliation               │ │   │
        │   │   │  NOT used by: Balance Query (reads in-memory)        │ │   │
        │   │   └──────────────────────────────────────────────────────┘ │   │
        │   └────────────────────────────────────────────────────────────┘   │
        └────────────────────────────────────────────────────────────────────┘
```

## C.2 Data Flow

```
 POST /ledger/postings
        │
        ▼
  PostingController (leader check)
        │
        ▼
  LedgerStateMachine.applyPosting()
        │
        ├──1. Idempotency check (in-memory)
        ├──2. Account status check (in-memory)
        ├──3. Balance validation (in-memory)
        ├──4. Generate Journal + JournalLines
        ├──5. Update balanceStore (in-memory, accountSeq++)
        ├──6. Publish BalanceChangeEvent → listener
        ├──7. Enqueue event to OutboxStore (RocksDB CF_OUTBOX)
        ├──8. Record idempotency
        ├──9. takeSnapshot() → RocksDB (all CFs)
        └──10. Return CommandResult
                │
                ▼
         KafkaEventPublisher.onEvent(event)
                │
                ▼
         Kafka Topic: ledger.balance.change.v1
                │
                ▼
         ProjectionConsumer.onBalanceChange(message)
                │
                ▼
         MySQL: INSERT journal + journal_line
```

## C.3 CQRS Read/Write Split

| Operation | Which Node | Storage | Consistency |
|---|---|---|---|
| Posting / Reversal / Adjustment | **Leader only** | RocksDB | Strong (Raft Quorum) |
| Balance Query | **Any node** | In-memory StateMachine | Eventual (~ms lag) |
| Journal Query | **Any node** | MySQL (via Projection) | Eventual (~1s lag) |
| Reconciliation | **Any node** | MySQL + in-memory | Eventual (T+0) |

---
