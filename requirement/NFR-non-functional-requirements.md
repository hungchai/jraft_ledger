# NFR — 非功能需求規格

**文件版本**: v0.3
**功能**: 非功能需求（NFR）
**系統**: Next-Gen Internal Ledger Platform
**狀態**: Draft for Review

> **v0.3 變更摘要**：新增 NFR-16（Raft 集群規模與容錯）與 NFR-17（節點同步監控）。
> **v0.2 變更摘要**：新增 NFR-13（JVM & GC）、NFR-14（Account Queue）、NFR-15（accountSeq Overflow Policy）；NFR-9 Observability 補充 GC pause 告警和 accountSeq gap 告警。

---

## 1. 性能（Performance）

| 指標 | 目標 | 測試條件 |
|---|---|---|
| Posting P95 延遲 | ≤ 3ms | 1000 並發，含 hotspot 帳戶（COMPANY_ACC） |
| Posting P99 延遲 | ≤ 10ms | 同上 |
| Balance Query P95（Active 帳戶） | ≤ 2ms | 讀 in-memory State Machine |
| Balance Query P95（Inactive 帳戶） | ≤ 5ms | 讀 RocksDB warm-up |
| Journal 點查 P95 | ≤ 10ms | MySQL View Layer，索引命中 |
| 帳戶流水查詢 P95（50 條） | ≤ 30ms | MySQL View Layer |
| Manual Adjustment Approve P95 | ≤ 10ms | 走 Raft |
| Reversal P95 | ≤ 5ms | 走 Raft |
| Learner 同步延遲（正常負載） | ≤ 1s | Raft Leader → MySQL View Layer |
| EOD 全流程（含 L1/L2 對帳） | ≤ 30 分鐘 | 每日 100 萬筆 Journal |

---

## 2. 吞吐量（Throughput）

| 指標 | 目標 |
|---|---|
| Posting TPS（峰值） | ≥ 10,000 TPS |
| Balance Query QPS | ≥ 50,000 QPS |
| Journal Query QPS | ≥ 5,000 QPS |

---

## 3. 可用性（Availability）

| 指標 | 目標 |
|---|---|
| 系統可用性（年度） | ≥ 99.99%（年停機 ≤ 52 分鐘） |
| Raft Leader 故障恢復時間（RTO） | ≤ 30 秒（含選舉 + State Machine 恢復） |
| 計劃維護停機視窗 | 每月 ≤ 30 分鐘（非 EOD 時段） |
| 多 AZ 部署 | 3 節點跨 AZ，允許 1 個 AZ 故障不影響服務 |

---

## 4. 數據持久性（Durability）

| 指標 | 目標 |
|---|---|
| RPO（Recovery Point Objective） | 0（Raft Quorum commit 即持久，不丟已確認帳務） |
| RTO（Recovery Time Objective） | ≤ 1 分鐘（Snapshot + Replay） |
| Journal 保留年限 | ≥ 7 年（合規要求） |
| Audit Log 保留年限 | ≥ 7 年 |

---

## 5. 一致性（Consistency）

| 場景 | 一致性級別 |
|---|---|
| Posting / Reversal / Adjustment 寫入 | **強一致性**（Raft Quorum commit） |
| Balance Query（實時） | **強一致性**（讀 Leader in-memory State Machine） |
| Journal Query | **最終一致性**（Learner 同步，延遲 ≤ 1s） |
| Reconciliation 對帳 | **最終一致性**（T+0 日內完成） |

---

## 6. 不可變性（Immutability）

- 所有已過帳 JournalLine **禁止 UPDATE / DELETE**
- 只允許 append（新增 Journal / Reversal / Adjustment）
- RocksDB 和 MySQL View Layer 均以 append-only 模式維護 JournalLine

---

## 7. 冪等性（Idempotency）

- 所有寫操作（Posting / Reversal / Adjustment Approve）均支持冪等
- 冪等 Key：`requestId`（UUID v7），TTL ≥ 24 小時
- 重試相同 `requestId` 返回原始結果，不重複入帳
- In-memory idempotency store + DB unique constraint 雙層保障

---

## 8. 安全性（Security）

| 要求 | 說明 |
|---|---|
| 身份認證 | 所有 API 需通過 JWT / mTLS 認證 |
| 授權 | RBAC，區分只讀角色（Viewer）、操作角色（Operator）、審批角色（Checker）、管理角色（Admin） |
| Maker-Checker | Manual Adjustment 強制雙人複核，不可繞過 |
| 審計日誌 | 所有寫操作記錄操作員、時間、IP、traceId，保留 7 年 |
| 敏感數據 | 帳戶 ID、金額在日誌中脫敏處理 |

---

## 9. 可觀測性（Observability）【v0.2 更新】

| 要求 | 說明 |
|---|---|
| 分布式追蹤 | 所有請求帶 traceId / spanId，接入 Jaeger / Zipkin |
| Metrics | Prometheus 暴露 TPS、P50/P95/P99 延遲、Queue 積壓、Raft term、Learner lag、**GC pause time、Account Queue depth per account** |
| 告警（原有） | Posting P99 > 50ms、Queue 積壓 > 1000、Learner lag > 10s、L1 對帳失敗 → PagerDuty |
| 告警（新增） | **任意單次 GC pause > 5ms → PagerDuty**；**BalanceChangeEvent accountSeq gap 偵測失敗 → PagerDuty**；**任意 accountSeq ≥ Long.MAX_VALUE × 80% → PagerDuty（理論預警，永遠不應觸發）** |
| 日誌 | 結構化 JSON 日誌，帶 journalId、requestId、accountId、traceId |
| 每筆帳務可追溯 | 任意餘額變化可在 5 分鐘內追到 source event、operator、rule version、journal chain |

---

## 10. 容量規劃（Capacity）

| 指標 | 假設值 | 說明 |
|---|---|---|
| 帳戶總數 | 1,000,000 | Active 帳戶約 100,000 常駐記憶體 |
| 日均 Journal 數 | 5,000,000 | 每日 500 萬筆帳務 |
| 每筆 Journal 平均 JournalLine 數 | 4 | RFQ 場景通常 4 條 |
| 日均 JournalLine 數 | 20,000,000 | |
| RocksDB 日增量 | ~10 GB | 估算 500 bytes / JournalLine |
| MySQL View Layer 日增量 | ~20 GB | 含索引 |
| State Machine 記憶體（Active 帳戶） | ~2 GB | 100,000 帳戶 × 5 BalanceType × ~4KB |
| Raft Log Snapshot 間隔 | 100,000 條 | 約每 1 分鐘一次快照（10,000 TPS 峰值） |

---

## 11. 災難恢復（DR）

| 要求 | 說明 |
|---|---|
| 多 AZ 部署 | 3 個 Raft 節點分佈在 3 個 AZ |
| 跨 DC 容災 | Raft Learner 可部署在異地 DC，作為 DR 節點 |
| RocksDB 備份 | 每日全量 RocksDB checkpoint 備份到對象存儲（S3 / OSS） |
| 恢復演練 | 每季度進行一次完整 DR 演練，驗證 RTO ≤ 1 分鐘 |

---

## 12. 技術約束

| 約束 | 說明 |
|---|---|
| 語言 / 框架 | Java 21 + Spring Boot 3，使用 Virtual Threads |
| Raft 庫 | SOFAJRaft（評估 Apache Ratis 作為備選） |
| 本地持久化 | RocksDB（Java API） |
| View Layer DB | MySQL 8.0+（MyBatis，禁止 ORM） |
| 消息總線 | Kafka（Learner 同步輸出帳務事件供下游系統消費） |
| 禁止使用 | Hibernate / JPA / Redis（寫路徑）/ 直接寫 MySQL 繞過 Raft |

---

## 13. JVM & GC【v0.2 新增】

P95 ≤ 3ms 的目標在 10,000 TPS 下，GC pause 是主要的不可控延遲來源。預設 G1GC 的 pause 目標為 200ms，遠超整個 Posting P95 預算，必須強制指定低延遲 GC。

### 13.1 GC Collector

| 要求 | 規格 |
|---|---|
| **強制使用** | ZGC（`-XX:+UseZGC`）或 Shenandoah（`-XX:+UseShenandoahGC`），二選一，**禁止使用 G1GC / ParallelGC** |
| 推薦選擇 | **ZGC**（Java 21 Production-ready，concurrent，pause < 1ms） |
| 備選 | Shenandoah（pause 特性相近，適合較小 heap） |
| **禁止** | G1GC（默認）、ParallelGC — pause 不可預測，無法保證 P99 ≤ 10ms |

### 13.2 JVM 啟動參數（State Machine / Raft Leader 節點）

```bash
# GC
-XX:+UseZGC
-XX:MaxGCPauseMillis=1          # ZGC concurrent，pause 目標 < 1ms

# Heap：固定大小，避免 resize 觸發 Full GC
-Xms8g
-Xmx8g

# GC Logging（接入 Prometheus GCEasy / JVM metrics exporter）
-Xlog:gc*:file=/var/log/ledger/gc.log:time,uptime:filecount=10,filesize=50m

# Virtual Thread（Java 21）
# 不需額外參數，Spring Boot 3 + Virtual Threads 默認啟用
```

### 13.3 GC Pause 預算

| 場景 | 最大允許 GC Pause | 說明 |
|---|---|---|
| 正常運行 | ≤ 1ms（ZGC concurrent） | 不影響 Posting P95 |
| 最壞情況 | ≤ 5ms | 超過此值觸發 PagerDuty 告警 |
| **禁止** | > 10ms（P99 budget） | GC pause 單次超過 P99 預算，即屬配置錯誤 |

### 13.4 Hot Path 物件分配原則

State Machine apply() 在 10,000 TPS 峰值下每秒執行 10,000 次，hot path 的 heap allocation 直接影響 GC 頻率：

| 原則 | 說明 |
|---|---|
| **BalanceEntry 複用** | Account Worker 線程（Virtual Thread，per-account 串行）可使用 ThreadLocal pool 複用 `BalanceEntry`，避免每次 apply 產生新物件 |
| **WriteBatch 序列化 Buffer 複用** | RocksDB `WriteBatch` 序列化使用 ThreadLocal `ByteBuffer`（direct，off-heap），避免每次 apply 分配 `byte[]` |
| **Immutable record 設計** | `AccountBalanceKey` 使用 Java record，JVM 可做 escape analysis 優化，減少 heap 分配 |
| **避免 boxing** | balanceStore / idempotencyStore 的 value 使用 primitive-friendly 結構，避免 `Long` / `Double` autoboxing |

### 13.5 GC Metrics（Prometheus）

```
# 必須暴露的 JVM GC metrics：
jvm_gc_pause_seconds{cause, gc}         # 每次 GC pause 時長
jvm_gc_pause_seconds_max                # 最近最大 pause
jvm_memory_used_bytes{area="heap"}      # 堆使用量
jvm_memory_max_bytes{area="heap"}       # 堆上限
jvm_gc_live_data_size_bytes             # 存活數據大小（ZGC）

# 告警規則（Prometheus AlertManager）：
ALERT GCPauseTooLong
  IF jvm_gc_pause_seconds_max > 0.005   # 5ms
  FOR 1m
  SEVERITY critical
  ANNOTATIONS { summary = "GC pause exceeded 5ms, P99 at risk" }
```

---

## 14. Account Queue 設計約束【v0.2 新增】

Account Queue 是 Ledger 寫路徑的核心排隊機制，每個帳戶一條獨立 queue，保證 per-account 串行化。

### 14.1 當前實現

```
Queue 類型：java.util.concurrent.LinkedBlockingQueue
Worker：    Java 21 Virtual Thread（每個 Account Queue 一個 Virtual Thread worker）
部署方式：  per-account，動態創建，inactive 帳戶的 queue 在無請求時自動回收
```

### 14.2 Queue 容量設計

| 參數 | 值 | 說明 |
|---|---|---|
| 單帳戶 Queue 容量上限 | 1,000 個請求 | 超過觸發背壓（HTTP 429 / gRPC RESOURCE_EXHAUSTED） |
| Global Request Queue 容量 | 50,000 個請求 | 所有帳戶入口 queue 緩衝，按 accountId routing 前的等待 |
| Queue 積壓告警閾值 | 任意 account queue depth > 500 持續 30s | 表示該帳戶請求速率超過 State Machine 處理能力 |
| Queue 滿時行為 | **快速失敗**，立即返回 HTTP 429，不阻塞 caller | 避免 caller 端 timeout 堆積 |

### 14.3 升級路徑（如 GC 壓力過大）

`LinkedBlockingQueue` 每次 `offer()` 分配一個 `Node<E>` 物件，在 10,000 TPS 峰值下每秒產生大量短命物件。若 GC 調優後仍無法滿足 P99，可按以下路徑升級，**無需修改 Raft 或 State Machine 架構**：

```
Phase 1（默認）: LinkedBlockingQueue
  → 簡單，夠用，Java 標準庫

Phase 2（如 GC 壓力可見）: JCTools MpscArrayQueue
  → Lock-free MPSC（Multi-Producer Single-Consumer）
  → 無 Node 物件，減少 GC allocation ~60%
  → 預分配固定大小陣列，避免動態擴容
  → API 相近，改動最小

Phase 3（如 Phase 2 仍不足）: Agrona ManyToOneConcurrentArrayQueue
  → Off-heap，完全 zero allocation
  → 需引入 Agrona 依賴，複雜度上升
```

> **當前選擇為 Phase 1**，Phase 2 / 3 僅在性能測試（TC-NFR-01 / TC-NFR-02）未達標時啟動。

### 14.4 背壓（Backpressure）機制

```
Client → HTTP/gRPC → Global Request Queue
                              │
                     Queue full (>50,000)?
                              │ YES
                              ▼
                     HTTP 429 / RESOURCE_EXHAUSTED（立即返回）

                              │ NO
                              ▼
                     Account Queue routing（by accountId）
                              │
                     Account Queue full (>1,000)?
                              │ YES
                              ▼
                     HTTP 429（單帳戶背壓）

                              │ NO
                              ▼
                     Account Worker → Raft → State Machine
```

---

## 15. accountSeq Overflow Policy【v0.2 新增】

`accountSeq` 是 per-account per-balanceType per-currency 的單調遞增序號，用於 BalanceChangeEvent 的下游 gap 偵測。

### 15.1 溢出分析

```
類型：long（64-bit signed，max = 9,223,372,036,854,775,807，約 9.2 × 10¹⁸）

最壞場景估算（hotspot 帳戶 COMPANY_FX_ACC）：
  10,000 TPS × 1 JournalLine / posting = 10,000 seq 遞增 / 秒
  溢出時間 = 9.2 × 10¹⁸ ÷ 10,000 ÷ 86,400 ÷ 365 ≈ 29,247,120 年

結論：long 在任何可預見的業務場景下均不會溢出。
```

### 15.2 設計決策

| 決策 | 理由 |
|---|---|
| **使用 `long`，不使用 `BigInteger`** | 29+ 百萬年壽命，無實際溢出風險；`BigInteger` 引入 heap allocation 和序列化複雜度 |
| **禁止 wrap-around（迴繞）** | 若 `long` 溢出後從負數開始，下游 consumer 會誤判為 gap，觸發大量誤報告警，不可接受 |
| **不使用 unsigned long** | Java 不原生支持 unsigned long；`Long.compareUnsigned()` 雖可用，但增加代碼理解成本，收益不足 |

### 15.3 預警機制

雖然溢出不可能發生，仍需一條告警作為安全網：

```java
// State Machine apply() 中，accountSeq 遞增後執行一次檢查
private static final long OVERFLOW_WARN_THRESHOLD = Long.MAX_VALUE / 100 * 80;
// ≈ 7.37 × 10¹⁸，距溢出還有約 5.86 × 10¹⁸（~18,636,500 年）

if (nextSeq >= OVERFLOW_WARN_THRESHOLD) {
    // 這條 log 在正常情況下永遠不會出現
    log.error("[SEQ_OVERFLOW_WARN] accountId={} balanceType={} currency={} seq={}",
        key.accountId(), key.balanceType(), key.currency(), nextSeq);
    // 同時觸發 PagerDuty（見 NFR-9 告警規則）
}
```

```
# Prometheus 告警規則
ALERT AccountSeqOverflowRisk
  IF ledger_account_seq_max > 7370000000000000000   # 80% of Long.MAX_VALUE
  FOR 1m
  SEVERITY critical
  ANNOTATIONS { summary = "accountSeq approaching Long.MAX_VALUE — investigate immediately" }
```

---

## 16. Raft 集群規模與容錯【v0.3 新增】

Raft 協議的數據安全與一致性建基於過半數決（Quorum）機制：任何日誌提交必須獲得集群內超過半數節點的成功複製與確認。系統總節點數遵循 **N = 2F + 1** 公式，其中 N 為總節點數，F 為可容忍的故障節點數量。

### 16.1 最小配置

| 總 Voting Nodes | Follower 數 | Quorum | 可容忍故障 | 適用場景 |
|----------------|------------|--------|-----------|---------|
| 3 | 2 | 2 | 1 台 | 開發 / 測試 / 低風險環境 |
| 5 | 4 | 3 | 2 台 | **金融級生產環境** |

> **2 節點無法運作。** 2 節點的 Quorum = 2，任意 1 台故障即失去多數派，Leader 無法提交任何新日誌，系統不可用。Raft 的最小實用配置為 3 Voting Nodes。

### 16.2 生產推薦配置：5 節點

```
3 Voting Nodes（1 Leader + 2 Follower）
  ├─ 參與 Raft 投票和日誌複製
  ├─ 跨 3 個 AZ 部署，每個 AZ 一個 voting node
  └─ Quorum = 3，允許 2 個 voting node 同時故障

2 Learner Nodes（non-voting）
  ├─ 不參與投票，不影響 Quorum 計算
  ├─ 異步同步 Raft Log → MySQL View Layer
  └─ 可部署於異地 DC 作為 DR 節點（可選）
```

| 對比維度 | 3 Voting Nodes | 5 Nodes（3 Voting + 2 Learner） |
|---------|---------------|-------------------------------|
| Quorum | 2 | 3 |
| 容忍 voting node 故障 | 1 台 | 2 台 |
| 滾動升級 | 風險較高（僅 1 台冗餘） | 可逐一重啟，不影響 Quorum |
| AZ 級故障 | 允許 1 個 AZ 故障 | 允許 2 個 AZ 故障（含 voting） |
| Learner 水平擴展 | 需額外部署 | 內建 2 Learner，可按需擴展 |
| 適用場景 | 開發 / 測試 | **金融級生產** |

### 16.3 邊際收益遞減

超過 5 Voting Nodes 後，每增加 2 個節點才多容忍 1 個故障，但代價呈非線性增長：

| 節點數增加 | 額外容錯收益 | 代價 |
|-----------|------------|------|
| 3 → 5 | +1 容錯（1 → 2） | 網路開銷 +67%，可接受 |
| 5 → 7 | +1 容錯（2 → 3） | 網路開銷 +40%，Quorum 增大，選舉競爭概率上升 |
| 7 → 9 | +1 容錯（3 → 4） | 複製延遲明顯增加，運維複雜度顯著 |

> **結論：5 Voting Nodes（可配若干 Learner）是金融場景的最佳平衡點。** 7+ Voting Nodes 僅在極端可用性要求（≥ 99.999%）時考慮，且建議配合 Multi-Raft-Group 分片以控制單集群規模。

### 16.4 與其他 NFR 的關聯

| 關聯章節 | 關係 |
|---------|------|
| NFR-3 可用性 | 集群規模直接決定可用性目標是否可達成（≥ 99.99% 需 ≥ 3 Voting Nodes 跨 AZ） |
| NFR-4 數據持久性 | Quorum commit 保證 RPO = 0，節點數越多副本越多 |
| NFR-5 一致性 | 強一致性依賴 Quorum 機制，投票節點數決定一致性安全邊界 |
| NFR-11 災難恢復 | Learner 可部署為異地 DR 節點，不影響線上 Quorum |
| ADR-001 §5.1 | 集群配置的架構決策背景與 Raft 庫選型理由 |

---

## 17. 節點同步監控 [v0.3 新增]

Raft Quorum 保證已提交資料的持久性，但並不會自動以運維可用的格式暴露每個節點的複製延遲或 Follower 健康狀態。需要專用的監控端點與衍生指標，以便在影響可用性之前偵測腦裂、網路分區或慢速 Follower。

### 17.1 端點

```
GET /ledger/cluster/raft-status
```

**回應範例（Follower 節點）：**

```json
{
  "nodeId": "node2",
  "isLeader": false,
  "term": 5,
  "lastAppliedIndex": 1247,
  "peers": ["node1:28080", "node2:28080", "node3:28080"],
  "alivePeers": []
}
```

**回應範例（Leader 節點）：**

```json
{
  "nodeId": "node1",
  "isLeader": true,
  "term": 5,
  "lastAppliedIndex": 1248,
  "peers": ["node1:28080", "node2:28080", "node3:28080"],
  "alivePeers": ["node2:28080", "node3:28080"]
}
```

### 17.2 指標語義

| 欄位 | 類型 | 說明 |
|---|---|---|
| `nodeId` | string | 節點唯一識別碼（與設定中的 `ledger.group-id` 對應） |
| `isLeader` | boolean | 此節點是否為當前 Raft Leader |
| `term` | long | 當前 Raft term；跨節點 term 不一致表示正在選舉或發生腦裂 |
| `lastAppliedIndex` | long | 此節點 State Machine 已套用的最後一筆 Log Index |
| `peers` | string[] | 已配置的 Peer 清單（來自 `PEER_NODES` 環境變數或預設為自身） |
| `alivePeers` | string[] | Leader 視為存活的 Peer（Follower 上為空，因 SOFAJRaft 僅向 Leader 暴露此資訊） |

### 17.3 複製延遲判讀

複製延遲透過輪詢同一端點後比較各節點的 `lastAppliedIndex` 得出：

```
lag(node) = leader.lastAppliedIndex - node.lastAppliedIndex
```

| 延遲條件 | 含義 | 運維動作 |
|---|---|---|
| 所有節點 `lag == 0` | 集群完全同步 | 無 |
| `0 < lag ≤ 10` 持續 ≤ 5 秒 | 正常瞬態延遲 | 持續觀察 |
| `lag > 100` 持續 > 10 秒 | 慢速 Follower 或網路分區 | 調查 Follower GC / 網路；考慮重啟 Follower |
| `lag` 單調遞增 | Follower 已停滯或當機 | 重啟 Follower 節點；若持續發生，替換節點並觸發 Snapshot 還原 |
| 跨節點 `term` 不一致 | 腦裂或正在選舉 | 檢查 Quorum；確保多數節點可互相連線；切勿手動強制提升 Follower |

### 17.4 告警閾值

| 告警名稱 | 條件 | 嚴重程度 | 回應 |
|---|---|---|---|
| `RaftFollowerLagHigh` | 任一 Follower 的 `lastAppliedIndex` 落後 Leader > 100 筆，持續 > 30 秒 | WARNING | 通知 on-call；調查 Follower 效能 |
| `RaftFollowerLagCritical` | 任一 Follower 的 `lastAppliedIndex` 落後 Leader > 1000 筆，持續 > 60 秒 | CRITICAL | 通知 on-call；準備重啟或替換 Follower |
| `RaftTermDivergence` | 任兩節點回報的 `term` 不一致持續 > 10 秒 | CRITICAL | 通知 on-call；可能發生腦裂——採取行動前須先確認 Quorum |
| `RaftLeaderMissing` | 連續 > 30 秒無任何節點回報 `isLeader == true` | CRITICAL | 通知 on-call；集群已失去領導者——檢查網路分區，必要時重啟節點 |
| `RaftAlivePeersLow` | Leader 的 `alivePeers` 數量 < (`peers` 數量 / 2)，持續 > 10 秒 | WARNING | 通知 on-call；集群處於少數狀態，有失去 Quorum 的風險 |

### 17.5 與可觀測性整合

- **Prometheus**：Sidecar 或應用本身應將 `ledger_raft_last_applied_index{node_id}` 以 Gauge 形式暴露。延遲可在 PromQL 中計算：
  ```promql
  max(ledger_raft_last_applied_index) - ledger_raft_last_applied_index
  ```
- **健康檢查**：`/actuator/health`（或同等端點）應包含 Raft 指示器；若啟動後 > 60 秒 `lastAppliedIndex` 仍為 0，表示節點尚未加入集群，狀態應回傳 `DOWN`。
- **儀表板**：Grafana 面板顯示每節點的 `lastAppliedIndex`、Leader term 與存活 Peer 數量。

### 17.6 Standalone 模式

當 Raft 停用（單節點開發 / 測試）時，端點回傳：

```json
{
  "mode": "standalone"
}
```

此模式下不應觸發任何複製延遲告警。
