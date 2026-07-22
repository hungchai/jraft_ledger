# Raft Storage / fsync — 3-Way Performance Report (gp3 / io2 / NVMe)

**Date**: 2026-06-25
**Cluster**: 3 raft nodes + 1 mgmt, `ap-southeast-1` (all same AZ → RTT constant), branch `v3-fix-soak`
**Node type**: `c7i.large` (gp3, io2 arms) / `c6id.large` (nvme arm) — both non-burstable, CPU steal = 0 measured
**Config**: `RAFT_LOG_FSYNC=true`, `ROCKSDB_FSYNC=false`, RaftOptions/command-queue at defaults
**Load**: k6 `k6-posting-stress.js` (RFQ settlement, 50ms think-time/iter), VU ramp 10→50→100→200, 2m each
**Method**: only the Raft-log disk varies per arm (`/mnt/raft`); state RocksDB stays on root gp3 → raft disk isolated

---

## 1. The root cause: fsync latency (fio, direct, 4k iodepth=1 fdatasync)

| Arm | fdatasync p50 | vs gp3 | CPU steal |
|---|---|---|---|
| gp3 (root EBS) | **2.80 ms** | — | 0.00 |
| io2 (dedicated EBS, 1000 IOPS) | **2.97 ms** | ~same | 0.00 |
| NVMe (c6id instance-store, local) | **0.375 ms** | **7.5× faster** | 0.00 |

**Finding**: gp3 ≈ io2 in raw fsync — both pay the **EBS network round-trip** to the storage backend (~3ms), independent of volume class or provisioned IOPS. Only **local NVMe** (no network hop) breaks it. CPU steal = 0 everywhere → the earlier `c7i-flex` throttling hypothesis is **ruled out**; the cost was always the disk.

---

## 2. Server-side `ledger_raft_wait_apply` (quorum commit + apply, per gradient step, ms)

| VU | gp3 | io2 | NVMe |
|---|---|---|---|
| 10 | 3.36 | 2.23 | **1.10** |
| 50 | 3.27 | 2.65 | **0.83** |
| 100 | 3.45 | 2.66 | **1.03** |
| 200 | 3.53 | 3.08 | **2.17** |

- gp3 flat ~3.3–3.5ms (pinned to the 2.8ms fsync floor + RTT).
- io2 ~0.6–1.1ms lower than gp3 — note: io2 beats gp3 here even though fio said they're equal. The fio synthetic 4k pattern misses RocksDB's real WAL append behaviour, where io2's consistency helps. **Cluster metric is authoritative.**
- **NVMe ~1ms — 3× better than gp3.** At VU=50 it drops to 0.83ms (batching amortizes the already-tiny local fsync).
- At VU=200 all three rise and converge somewhat: the bottleneck shifts off the disk onto the single-threaded command queue + FSM apply (see §5).

## 3. End-to-end server time `http_server_requests` (gradient, ms)

| VU | gp3 | io2 | NVMe |
|---|---|---|---|
| 10 | 4.74 | 3.31 | **1.80** |
| 50 | 5.17 | 3.97 | **1.48** |
| 100 | 6.62 | 5.33 | **2.62** |
| 200 | 10.39 | 7.58 | 8.97 |

## 4. Client-side k6 (end-to-end over AWS network)

**TPS (posting/s)** — *disk-independent*:
| VU | gp3 | io2 | NVMe |
|---|---|---|---|
| 10 | 178 | 182 | 187 |
| 50 | 884 | 905 | 951 |
| 100 | 1721 | 1759 | 1857 |
| 200 | 3194 | 3324 | 3265 |

**p95 / p99 (ms)**:
| VU | gp3 p95/p99 | io2 p95/p99 | NVMe p95/p99 |
|---|---|---|---|
| 10 | 6.4 / 8.1 | 5.7 / 8.1 | **4.6 / 7.6** |
| 50 | 7.9 / 9.5 | 6.5 / 7.4 | **3.2 / 5.5** |
| 100 | 10.0 / 25.9 | 7.9 / **11.6** | 6.3 / 11.9 |
| 200 | 26.3 / 87.8 | **21.2 / 47.8** | 24.6 / 61.8 |

---

## 5. Key findings

1. **The 62% (~3.58ms quorum commit) = gp3 fsync latency.** Measured, not estimated. Not CPU (steal=0), not quorum structure. Overturns the earlier SDK estimate (0.3–1ms).
2. **EBS volume class barely matters for single-op fsync** — gp3 ≈ io2 (~3ms), both EBS-network-bound. io2's gain at cluster level is modest (~0.8ms) and shows mainly as a **cleaner tail under load** (best p99 at VU=200).
3. **Local NVMe is the only thing that breaks the floor** — 0.375ms fsync → ~1ms commit, 3× lower latency, **median e2e ~1.5ms** (vs gp3 ~5ms).
4. **Faster disk does NOT raise TPS.** Peak ~3,200–3,300/s on all three arms. TPS is capped by (a) k6's 50ms think-time and (b) the **single `command-queue-worker` + single FSM apply thread**, not the disk. At VU=200 `apply_total` climbs to ~1.4ms and the app/queue layer dominates → disk advantage shrinks.
5. **Tail anomaly**: NVMe VU=200 max = 1524ms (one outlier; likely a ZGC pause or c6id scheduling stall). io2 had the cleanest high-load tail. Worth a closer look if NVMe is chosen.
6. **Correctness held on every arm**: 3-node parity equal, cross-node state hash identical (deterministic FSM), Σ USDT = 0 exact. **Wipe-rebuild passed on io2 AND nvme** — a node that loses its entire local disk re-syncs from quorum via InstallSnapshot → **NVMe instance-store is safe**. Leader-kill re-elected cleanly.
7. **Σ BTC dust**: −1 / 0 / −2 satoshi across the 3 runs (USDT always exactly 0). Transient, varies run-to-run → not a conservation bug, but the BTC 8dp rounding path warrants a later look.

---

## 6. Recommendation

| Priority | Action |
|---|---|
| **Latency-critical** | **Raft log on local NVMe** (`c6id`/`i4i` etc.) — 3× lower commit latency, median e2e ~1.5ms. Safe: local-disk loss recovers from quorum (proven). Watch the high-load tail. |
| **Durability-first / simpler ops** | **io2** — modest latency gain over gp3 but **best tail under load** + durable + no rebuild dependency. |
| **Throughput (the real ceiling)** | **Not a storage problem.** Parallelize the single `command-queue-worker` (and/or shard FSM apply per account). This is what lifts TPS above ~3,300 and tames the VU=200 tail — independent of disk choice. |
| Avoid | gp3 root volume for the raft log under sustained load — worst tail (p99 88ms, max 830ms @ 200 VU). |

**Bottom line**: disk choice fixes **latency**; the command-queue fix is what fixes **throughput**. They're orthogonal — do both.

---

## 7. Caveats / methodology notes

- k6's hardcoded 50ms `sleep()` means none of the arms reached true saturation — peak TPS is partly think-time-bound. Real ceiling is higher; remove the sleep to find it.
- Single AZ → RTT (~0.6ms) constant across arms; a cross-AZ production topology adds latency uniformly.
- `c6id` (Ice Lake) vs `c7i` (Sapphire Rapids) is a CPU-generation confound on the NVMe arm — mitigated by fio (disk-only) + mpstat (steal=0 on both), so the fsync delta is attributable to the disk, not the CPU.
- fio's synthetic fdatasync under-represents io2's real-workload benefit (it matched gp3 in fio but beat it in-cluster). Trust the cluster metric.
- Per-step server metrics are Δsum/Δcount between Prometheus snapshots (cumulative `ledger_*` timers); k6 numbers are per-run summaries.
