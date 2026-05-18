# NFR — 非功能需求規格

**文件版本**: v0.1  
**功能**: 非功能需求（NFR）  
**系統**: Next-Gen Internal Ledger Platform  
**狀態**: Draft for Review

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

## 9. 可觀測性（Observability）

| 要求 | 說明 |
|---|---|
| 分布式追蹤 | 所有請求帶 traceId / spanId，接入 Jaeger / Zipkin |
| Metrics | Prometheus 暴露 TPS、P50/P95/P99 延遲、Queue 積壓、Raft term、Learner lag |
| 告警 | Posting P99 > 50ms、Queue 積壓 > 1000、Learner lag > 10s、L1 對帳失敗 → PagerDuty |
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
