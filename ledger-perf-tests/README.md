# ledger-perf-tests

Performance / load-test module for the Ledger Platform.

## Goal

Use `ledger-client-sdk` (`LedgerClient`) to exercise the Raft cluster under load,
validating throughput and latency targets from the stress-test plan.

## Planned Benchmarks

| Benchmark | Description | Target |
|-----------|-------------|--------|
| `PostingBenchmark` | RFQ-style postings via `LedgerClient.post()` | P95 ≤ 3 ms |
| `HotspotBenchmark` | Concurrent same-account deposits/withdrawals | No drift |
| `FailoverBenchmark` | Leader failover with retry | Zero lost requests |
| `ReadWriteBenchmark` | Interleaved balance queries and postings | Consistent reads |

## Usage

```bash
mvn clean package -pl ledger-perf-tests -am
java -jar ledger-perf-tests/target/ledger-perf-tests.jar
```

## Dependencies

- `ledger-client-sdk` — leader discovery, retries, failover
- `ledger-core` — `PostingCommand`, `CommandResult`, DTOs
- JMH — micro-benchmark harness
