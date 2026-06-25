# Raft 存储 / fsync 三方对照测试计划

**状态**: 草案 — 待批准后再改部署代码、再开跑
**日期**: 2026-06-25
**分支**: `v3-fix-soak`(含 dual-fsync 开关 + k6 NODES 补丁)
**背景**: 实测一笔 posting 服务端 5.81ms,其中 **Raft 法定多数提交 ≈ 3.58ms(62%)** 为最大头。但 3.58ms 中 fsync 分量**从未被单独测出**(strace attach 错线程、iostat w_await 是累计值)。且全程跑在**突发型 `c7i-flex.large`** 上,CPU 限流嫌疑未排除。本计划用控制变量法,分离三个嫌疑:**CPU 限流 / 存储 fsync 延迟 / quorum 结构**,并在每一组都验证**跨节点一致性与账务正确性**。

---

## 1. 目标(假设证伪)

| 假设 | 验证手段 | 证伪条件 |
|---|---|---|
| H1: 3.58ms 主因是 `c7i-flex` CPU 限流 | c7i-flex → c7i(非突发),同规格 | 换 c7i 后 raft_wait_apply 基本不降 → H1 伪 |
| H2: 主因是 gp3 fsync 延迟高 | fio 直测 fdatasync clat + io2/NVMe 对照 | gp3 fio fsync ~0.3ms → H2 伪 |
| H3: 主因是 quorum 结构(2×fsync + RTT,不可压) | 换最快盘(NVMe)后仍剩的那部分 | NVMe 下 raft_wait_apply 仍 ~2ms+ → 结构占大头 |

---

## 2. 控制变量

**全程固定**(跨所有 arm 不变):
- 集群拓扑:3 raft 节点 + 1 mgmt
- **可用区:强制同一 AZ**(保持 RTT 恒定;现状默认子网已同 AZ)
- JVM opts、分支、k6 场景与负载曲线
- fsync 开关:`RAFT_LOG_FSYNC=true`、`ROCKSDB_FSYNC=false`
- RaftOptions 默认、command-queue 默认(batchSize=16/batchWaitMs=1)—— 本轮**不动**这俩,避免污染存储结论

**每 arm 只变一项**:

| Arm | 机型 | Raft 日志盘 | 隔离的变量 |
|---|---|---|---|
| **0 基线复现** | c7i-flex.large | gp3(根卷) | 复现早先 3.58ms,确认可重复 |
| **1** | **c7i.large** | gp3 | CPU 限流(突发→持续) |
| **2** | c7i.large | **io2**(单独卷) | EBS 延迟档(gp3→io2) |
| **3** | **c6id.large** | **本地 NVMe**(instance-store) | 存储延迟下限 |

> ⚠️ Arm 3 的 c6id(Ice Lake)与 c7i(Sapphire Rapids)CPU 代际不同,是一个 confound。缓解:每 arm 都用 **fio 直测存储**(与机型无关)+ **mpstat 测 CPU**,即使机型变,也能把"盘"和"CPU"各自拆出来。
> 另:Arm 2/3 把 **raft 日志与 RocksDB state 分到不同卷**,使 fio/iostat 能干净隔离 raft 盘。

---

## 3. 性能测量(每 arm)

| 维度 | 工具 | 关键指标 |
|---|---|---|
| A. 存储 | `fio`(4k, iodepth=1, `--fdatasync=1`, 30s, 打 raft 日志挂载点) | fdatasync clat **p50/p95/p99** |
| B. CPU | `mpstat 1`(负载中)+ CloudWatch `CPUCreditBalance`(flex arm) | %usr/%sys/**%steal**/%idle |
| C. 网络 | `ss -tin`(leader→follower, 端口 28080) | rtt smoothed/var |
| D. 服务端计时器 | `/actuator/prometheus`(sum/count→avg) | `ledger_raft_wait_apply`、`raft_total`、`apply_total`、`rocksdb_write`、`posting_duration` |
| E. 客户端 | k6 | `http_req_duration` p50/p95/p99、吞吐 |
| F.(可选)| JRaft Dropwizard→Micrometer 桥 | `append-logs`(leader fsync) vs `replicate-entries`(RTT+远端) |

**负载曲线**(每 arm 相同):梯度 10 → 50 → 100 → 200 VU,每档 2 分钟。低档保留 sleep 看单条延迟,高档去 sleep 看吞吐 + fsync 摊薄。

---

## 4. 一致性与正确性(每 arm 必跑,硬门禁)

> 性能是"比较排名";**正确性是"通过/不通过"——任一组不过,该存储配置即判不安全。**

1. **Raft 一致性(parity)**:负载排空后,轮询全 3 节点 `/ledger/cluster/raft-status`,断言 `lastAppliedIndex` 与 `smJournalSeq` **三节点相等**。(基线已见 applied=11832 smJnl=21387 一致)
2. **价值守恒**:全系统 Σ DEBIT == Σ CREDIT;余额总和 == 种子总额,无凭空增减。复用 `scripts/failover/verify-conservation.py`、`recon-full.py`。
3. **跨节点状态等价**:从每个节点导出 balance/journal,断言三节点**哈希一致**(状态机确定性)。
4. **幂等**:抽样重放 requestId,断言返回缓存结果、无双重记账。
5. **NVMe 持久性/正确性(Arm 3 关键)**:
   - **擦盘重建**:杀一个节点并**清空其 raft+rocksdb 数据**(模拟 instance-store 丢失)→ 重启 → 断言它从 leader 的 snapshot+log **重新同步并收敛到相同状态**(parity 恢复)。这一条直接证明"丢本地盘→quorum 重建"的安全前提,是 NVMe 能用的依据。
   - **leader 故障切换**:负载中杀 leader → 断言选出新 leader、已提交事务不丢、未提交不重复计。复用 `scripts/failover/run-failover-test.sh` / `idempotency-failover.sh`。
6. **L1 对账**(若可用):`JOURNAL_UNBALANCED` 必须为 0。

**正确性门禁(全 arm 必须全过)**:三节点 parity 相等 ∧ 守恒成立 ∧ 跨节点状态哈希相等 ∧(Arm 3)擦盘后正确重建 ∧ 故障切换无丢/无重。

---

## 5. 执行流程(每 arm 顺序跑,降成本)

```
up(对应机型+盘) → fio+mpstat 基准 → 梯度压测 → 负载排空
  → 一致性&正确性套件 → collect(指标+k6 json) → down
```
顺序而非并行(省钱)。结果汇总成 gp3/io2/NVMe 对照表 + 正确性通过矩阵。

**强制收尾(避免长时间计费)**:
- 每 arm 跑完**立即 `down`**(终止实例 + 删 SG + 删 key),不留到下一 arm。
- 全部跑完执行 `aws-soak.sh sweep`(按 `Project=jraft-soak` tag 兜底清除所有实例/SG/key/io2 卷)。
- 收尾后 `describe-instances` 断言**为空**;io2 卷需单独确认已 `delete-volume`(`down`/`sweep` 要补 io2 卷删除逻辑,见 §6)。
- TTL 6h 自动 shutdown 仅兜底,**不可依赖**;以显式 `down`/`sweep` 为准。

---

## 6. 需要的部署代码改动(本计划阶段仅列出,**不执行**)

- `aws-soak.sh`:
  - 新增 `--disk gp3|io2|nvme`;机型按 arm 传 `--node-type`。
  - **io2**:创建+挂载 io2 卷 → mkfs → mount `/raft` → `LEDGER_RAFT_DATA_PATH` 指过去。
  - **nvme**:探测本地 NVMe 设备 → mkfs → mount → raft 路径指过去。
  - raft 日志卷与 RocksDB state 卷**分离**。
- 新脚本 `fsync-bench.sh`:fio fdatasync + mpstat。
- 新脚本 `consistency-check.sh`:parity + 守恒 + 跨节点哈希 + 擦盘重建 + leader 切换(整合现有 `scripts/failover/*.py`)。
- ✅ 已改:`aws-soak.sh` 默认机型 c7i-flex → **c7i.large**(本计划 commit 前置)。

---

## 7. 成本估算

4 arm × 4 实例 × ~45min × ~$0.10-0.13/h ≈ **几美元**;io2 小卷加几美分。TTL 6h 失效兜底。每 arm 跑完即 `down`。

---

## 8. 预期结论与下一步

- 若 **Arm1(c7i+gp3)就把 3.58ms 砍下来** → CPU 限流是主因,**换盘免谈**,只需生产改用非突发机型。
- 若 Arm1 没降、**Arm2/3 才降** → 存储延迟是主因,按 io2(可持久、贴生产)优先于 NVMe(最快但丢盘风险)选型。
- 若三组都剩 ~2ms+ → quorum 结构占大头,优化方向转向**命令队列流水线化**(让多 Task 并发进 raft,RaftOptions 批量才生效)而非存储。
