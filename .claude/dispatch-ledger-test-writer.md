# Dispatch: ledger-test-writer

**From**: ledger-orchestrator
**Task**: Add cross-node Raft consistency checks to smoke-test.sh
**Agent**: ledger-test-writer (see `.claude/agents/ledger-test-writer.md`)

## Context

### Background
Bug fix deployed: async `persistApply()` made synchronous, local RocksDB snapshot fallback removed from `onSnapshotLoad()`, and pre-Raft-start `restoreFromSnapshot()` removed from `LedgerConfig`. These changes fix balance divergence across Raft nodes (non-deterministic state machine replay).

Need smoke tests to verify cross-node consistency on every run.

### File to modify
`/Users/tomma/GIT/jraft_ledger/scripts/smoke-test.sh`

### Existing test structure
- Uses `check()` function: `check "desc" "expected" "$actual"`
- Unique account IDs per run: `CLIENT_ACC_${SUFFIX}`
- `$BASE` defaults to `http://localhost:8081`
- Tests 1-10: health, create accounts, deposit, withdraw, balance check, journal query, idempotency

## Requirements

### A. Multi-node balance consistency

After the main flow (before Results summary), add cross-node checks:

```bash
# Determine peer node URLs from BASE
NODE1="${BASE}"
NODE2="${BASE/8081/8082}"
NODE3="${BASE/8081/8083}"

# Query balance on all 3 nodes, verify amounts are identical
B1=$(curl -s "$NODE1/ledger/balances?accountId=$CLIENT_ACC&balanceType=AVAILABLE_BALANCE&currency=USD")
B2=$(curl -s "$NODE2/ledger/balances?accountId=$CLIENT_ACC&balanceType=AVAILABLE_BALANCE&currency=USD")
B3=$(curl -s "$NODE3/ledger/balances?accountId=$CLIENT_ACC&balanceType=AVAILABLE_BALANCE&currency=USD")
```

Use a `jsonval()` helper function to extract JSON fields cleanly via python3.

### B. Raft index consistency

Query `GET /ledger/cluster/raft-status` on all 3 nodes, verify:
- `lastAppliedIndex` consistent across nodes (±1 tolerance — leader applies before followers)
- `smRaftLogIndex` identical across nodes
- `smJournalSeq` identical across nodes

### C. MySQL projection consistency

Check MySQL `account_balance` table matches SM balance:
```bash
docker exec ledger-mysql mysql -u ledger -pledger123 ledger_view -sN \
  -e "SELECT amount FROM account_balance WHERE account_account_id = '$CLIENT_ACC' AND balance_type = 'AVAILABLE_BALANCE' AND currency = 'USD'"
```
Graceful skip if docker/MySQL not reachable.

## Constraints

- Match existing bash style (`set -e`, `check()` function)
- Don't break existing tests 1-10
- Use `python3 -c` for JSON parsing (no `jq` dependency)
- MySQL check optional — skip if docker not available
- Section header: `=== Raft Cluster Consistency Checks ===`
- Run before the "Results" summary

## Verify

```bash
bash scripts/smoke-test.sh http://localhost:8081
# Expect: 17 passed, 0 failed
```

## Status

**Completed** — committed in `612e959`. 17/17 smoke tests pass.
