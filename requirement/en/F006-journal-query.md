# F-006 Journal Query — Functional Requirements Specification

**Document Version**: v0.1  
**Feature**: F-006 Journal Query (Ledger transaction query)  
**System**: Next-Gen Internal Ledger Platform  
**Status**: Draft for Review  
**Dependencies**: ADR-001 (CQRS Architecture), F-002, F-003, F-004, F-008 State Machine

---

## 1. Feature Overview

Journal Query provides query capabilities for all ledger transactions, reading from the MySQL View Layer (asynchronously synced by Learner). It supports multi-dimensional filtering, pagination, and sorting.

**Important**: Journal Query reads the MySQL View Layer and is eventually consistent (typically lagging behind Leader by < 1 second). For scenarios requiring strong consistency (e.g. real-time balance), use F-005 Balance Query.

---

## 2. Query Dimensions

The system supports the following query entry points, which can be combined:

| Dimension | Field | Description |
|---|---|---|
| Journal | `journalId` | Exact query for a single Journal |
| Account | `accountId` | Query all transactions for an account |
| Business Event | `businessEventRef` | Query all ledger entries for a business event (e.g. RFQ-ID) |
| Request | `requestId` | Query the result of a Posting request |
| Time Range | `bookedFrom` + `bookedTo` | By booking time range |
| Value Date | `valueDateFrom` + `valueDateTo` | By ledger effective date range |
| Journal Type | `journalType` | `NORMAL`, `REVERSAL`, `MANUAL_ADJUSTMENT` |
| Status | `status` | `CONFIRMED`, `REVERSED` |
| Currency | `currency` | ISO 4217 |
| Balance Type | `balanceType` | Filter by Balance Type |
| Operator | `operatorId` | Operator for Manual Adjustment |

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

### 3.2 Query Transactions by Account (Paginated)

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

### 3.3 Query Ledger by Business Event (Full Chain Traceability)

```
GET /ledger/journals?businessEventRef=RFQ-2026051600123
```

Returns all Journals related to this business event, including:
- Original Posting Journal
- Reversal Journal (if any)
- Rebook Journal (if any)
- Full chain (`originalJournalId` → `reversalJournalId`)

### 3.4 Query by requestId (Idempotency Confirmation)

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

To support the above query performance, the following indexes must be created on the MySQL journal and journal_line tables:

```sql
-- journal table
CREATE INDEX idx_journal_account_booked  ON journal_line (account_id, booked_at DESC);
CREATE INDEX idx_journal_biz_event       ON journal      (business_event_ref);
CREATE INDEX idx_journal_request_id      ON journal      (request_id);
CREATE INDEX idx_journal_value_date      ON journal      (value_date, account_id);
CREATE INDEX idx_journal_type_status     ON journal      (journal_type, status);

-- journal_line table
CREATE INDEX idx_line_journal_id         ON journal_line (journal_id);
CREATE INDEX idx_line_account_currency   ON journal_line (account_id, currency, balance_type);
```

---

## 6. Performance Targets

| Query Type | Target | Description |
|---|---|---|
| Single Journal point query | P95 ≤ 10ms | Index hit |
| Account transactions (50 rows) | P95 ≤ 30ms | Paginated query |
| Business event full chain | P95 ≤ 50ms | Cross-table join |
| Full chain traceability (chain) | P95 ≤ 100ms | Recursive association query |

---

## 7. Acceptance Criteria

| # | Acceptance Condition | Test Method |
|---|---|---|
| AC-01 | Query by journalId returns complete JournalLines | Functional Test |
| AC-02 | Query by accountId + time range returns correct pagination | Functional Test |
| AC-03 | Query by businessEventRef returns all Journals including original, Reversal, and Rebook | Functional Test |
| AC-04 | chain API correctly returns the full chain of before-and-after associations | Functional Test |
| AC-05 | After Posting completes, Learner syncs within 1 second and the Journal is queryable | Consistency Test |
| AC-06 | Account transaction query for 50 rows, P95 ≤ 30ms | Performance Test |
| AC-07 | `dataSource` field correctly indicates `VIEW_LAYER` | Functional Test |
