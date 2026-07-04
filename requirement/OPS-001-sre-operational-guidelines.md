# OPS-001 SRE / DevOps 運維指南

**版本**: v0.1  
**日期**: 2026-05-22  
**狀態**: Draft for Review  
**讀者**: SRE、平台工程師、On-call 工程師

---

## 目錄

1. [RocksDB 壓實（Compaction）](#1-rocksdb-壓實compaction)
2. [Raft 節點不同步復原](#2-raft-節點不同步復原)
3. [MySQL View Layer 不同步復原](#3-mysql-view-layer-不同步復原)
4. [Prometheus / Grafana 監控](#4-prometheus--grafana-監控)

---

## 1. RocksDB 壓實（Compaction）

### 1.1 為何需要壓實

RocksDB 使用 LSM-Tree（Log-Structured Merge-Tree）架構。寫入以追加方式寫入 WAL 與 memtable，並定期刷盤為不可變的 SST 檔案。隨著時間推移：

- **SST 檔案數量增加** → 讀取放大（read amplification）上升，查詢需掃描更多檔案
- **磁碟空間膨脹** → 被刪除或覆寫的 key 仍存在於舊層級的 SST 中，直到被壓實
- **讀取延遲惡化** → 尤其影響按需從 RocksDB 載入的冷帳戶查詢

以每日 2,000 萬筆 JournalLine（約 10GB RocksDB 增長）計算，壓實對維持 NFR 延遲目標至關重要。

### 1.2 現況

`RocksDBManager`（`ledger-core/.../rocksdb/RocksDBManager.java`）目前**未暴露壓實 API**。RocksDB 內部背景壓實線程會自動處理，但：

- 預設背景壓實可能無法跟上 10k TPS 峰值寫入
- 手動全量壓實可確保 EOD 對帳查詢的效能可預測

### 1.3 建議流程：每日全量壓實

**步驟一 — 在 RocksDBManager 新增壓實 API**

```java
// RocksDBManager.java
public void compactAll() throws Exception {
    log.info("開始對 {} 個 column family 進行 RocksDB 全量壓實", columnFamilyHandles.size());
    for (ColumnFamilyHandle handle : columnFamilyHandles.values()) {
        rocksDB.compactRange(handle);
    }
    log.info("RocksDB 壓實完成");
}
```

**步驟二 — 透過 Spring 定時任務於離峰時段執行**

```java
@Component
public class RocksDBCompactionJob {

    private final RocksDBManager rocksDBManager;

    public RocksDBCompactionJob(RocksDBManager rocksDBManager) {
        this.rocksDBManager = rocksDBManager;
    }

    // 每日 03:00 執行（EOD 後、開市前）
    @Scheduled(cron = "0 0 3 * * *")
    public void compact() {
        try {
            rocksDBManager.compactAll();
        } catch (Exception e) {
            // 觸發 PagerDuty — 壓實失敗將導致次日查詢效能下降
            throw new RuntimeException("RocksDB 壓實失敗", e);
        }
    }
}
```

**步驟三 — 啟用 Spring Boot 定時任務**

```java
@EnableScheduling
@SpringBootApplication
public class LedgerApplication { ... }
```

### 1.4 壓實監控

| 指標 | 告警閾值 | 含義 |
|---|---|---|
| `rocksdb_total_sst_files_size` | > 50 GB / 節點 | 未壓實數據增長過大 |
| `rocksdb_compaction_pending` | > 10 持續 > 30 分鐘 | 壓實速度跟不上寫入速度 |
| `rocksdb_num_running_compactions` | 峰值時段持續為 0 > 2 小時 | 背景壓實停滯 |
| 壓實任務執行時間 | > 60 分鐘 | 可能需要對 column family 進行分片 |

### 1.5 緊急手動壓實

若市場時段讀取延遲飆升：

```bash
# 透過 JMX 或 actuator endpoint 觸發壓實（待實作）
curl -X POST http://ledger-node:8080/actuator/compact

# 或帶 --rocksdb.force-compact-on-start 標誌重啟節點（若已新增）
```

**注意**：全量壓實為 I/O 密集型操作，市場時段執行可能導致：
- 寫入延遲暫時飆升（≥ 20ms）
- CPU 使用率上升（壓實線程）

**緩解**：透過 `ColumnFamilyOptions.setMaxSubcompactions(2)` 限制壓實線程數。

---

## 2. Raft 節點不同步復原

### 2.1 偵測

使用 `/ledger/cluster/raft-status` 端點（NFR-17）輪詢所有節點：

```bash
# 輪詢所有節點
for node in node1 node2 node3; do
  curl -s http://${node}:8080/ledger/cluster/raft-status | jq .
done
```

**健康狀態範例：**
```json
{
  "nodeId": "node2",
  "isLeader": false,
  "term": 5,
  "lastAppliedIndex": 124857,
  "alivePeers": ["node1:28080", "node3:28080"]
}
```

**異常特徵：**

| 特徵 | 嚴重程度 | 可能原因 |
|---|---|---|
| `lastAppliedIndex` 落後 Leader > 100 筆持續 > 30 秒 | WARNING | Follower 過慢（GC、網路） |
| `lastAppliedIndex` 落後 Leader > 1000 筆持續 > 60 秒 | CRITICAL | Follower 停滯或當機 |
| `alivePeers` 數量 < Quorum | CRITICAL | 網路分區 |
| 連續 > 30 秒無任何節點回報 `isLeader: true` | CRITICAL | 腦裂或完全失去 Leader |
| 跨節點 `term` 不一致持續 > 10 秒 | CRITICAL | 腦裂 |

### 2.2 診斷流程圖

```
偵測到 Follower 延遲？
        │
        ▼
┌─────────────────────┐
│ lag ≤ 100 筆        │  →  僅監控；Raft 會自動追趕
└─────────────────────┘
        │ lag > 100
        ▼
┌─────────────────────┐
│  節點是否可連線？    │  →  檢查：curl /actuator/health
│  （HTTP 200？）      │
└─────────────────────┘
        │                    │
      YES                  NO
        │                    │
        ▼                    ▼
┌──────────────┐     ┌─────────────────────┐
│ GC 停頓？    │     │ 節點當機 / 無回應    │
│ CPU > 90%？  │     │                     │
└──────────────┘     └─────────────────────┘
        │                    │
      YES                  │
        │                  ▼
        ▼          ┌─────────────────────┐
┌──────────────┐   │  重啟節點            │
│ 等待 60 秒   │   │  → 自動從 Leader     │
│ 若未追趕     │   │    接收 Snapshot     │
│ 則進入修復   │   │    恢復              │
└──────────────┘   └─────────────────────┘
        │
      未追趕
        ▼
┌─────────────────────┐
│ 觸發手動 Snapshot   │
│ 安裝（2.3 節）      │
└─────────────────────┘
```

### 2.3 復原流程

#### 情境 A：慢速 Follower（小延遲，節點健康）

**無需人工介入。** SOFAJRaft 會自動將缺少的 log 複製到 Follower。監控 `lastAppliedIndex` 直至收斂。

**若延遲持續 > 5 分鐘：**

```bash
# 1. 檢查節點日誌中的重複錯誤
kubectl logs ledger-node-2 --tail=200 | grep -i "raft\|error\|warn"

# 2. 檢查 Leader 與 Follower 之間的網路延遲
ping <leader-ip>

# 3. 若 GC 停頓為根因，確認 ZGC 已啟用
jcmd <pid> VM.flags | grep UseZGC
```

#### 情境 B：停滯 Follower（大延遲，節點可連線）

Follower 的 State Machine 嚴重落後。強制透過 log replay 追趕可能耗時過長，應改為從 Leader 安裝 Snapshot。

```bash
# 1. 若 SOFAJRaft CLI 工具可用，透過 CLI 觸發 Snapshot 安裝

# 2. 若無 CLI，直接重啟 Follower 節點：
#    - 停止節點
#    - 刪除本地 Raft log 與 snapshot 目錄
#    - 啟動節點 — 會自動向 Leader 請求 Snapshot

kubectl exec ledger-node-2 -- sh -c '
  # 刪除前先備份
  mv /data/ledger/raft/log /data/ledger/raft/log.bak.$(date +%s)
  mv /data/ledger/raft/snapshot /data/ledger/raft/snapshot.bak.$(date +%s)
'

# 重啟 Pod / 服務
kubectl rollout restart deployment/ledger-node-2
```

**重啟後發生的事：**
1. 節點以空 log 啟動為 Follower
2. 聯繫 Leader，請求最新 Snapshot
3. Leader 透過 `onSnapshotSave()` → `onSnapshotLoad()` 發送 Snapshot
4. Follower 套用 Snapshot，再追趕剩餘 log
5. 在 RTO ≤ 1 分鐘內恢復服務（NFR-4）

#### 情境 C：損毀 Follower（apply 時反覆當機）

若 Follower 在 `onApply()` 或 `onSnapshotLoad()` 時反覆當機，其本地 RocksDB 可能已損毀。

```bash
# 1. 停止節點
kubectl scale deployment ledger-node-2 --replicas=0

# 2. 刪除所有本地資料（RocksDB + Raft 元資料 + Snapshots）
kubectl exec ledger-node-2 -- rm -rf /data/ledger/rocksdb/*
kubectl exec ledger-node-2 -- rm -rf /data/ledger/raft/*

# 3. 啟動節點 — 從 Leader 進行完整狀態轉移
kubectl scale deployment ledger-node-2 --replicas=1
```

**警告**：請勿同時對超過一個節點執行資料刪除。若 3 個節點中有 2 個同時遺失資料，集群將失去 Quorum。

#### 情境 D：腦裂（Term 不一致）

```bash
# 1. 識別真正的 Leader（term 最高且 alivePeers 包含多數者）
for node in node1 node2 node3; do
  curl -s http://${node}:8080/ledger/cluster/raft-status | jq '{nodeId, isLeader, term, alivePeers}'
done
```

**解決方式：**
- 若某一節點 `isLeader: true` 且 `alivePeers` 包含多數節點 → 該節點為真正 Leader
- term 較低的節點會自動降級為 Follower 並重新加入
- 若罕見地出現兩個節點同時宣稱為 Leader，**手動降級**假 Leader：

```bash
# 呼叫 SOFAJRaft Node.resetElectionTimeout() 或重啟假 Leader
# 強制其成為 Follower
kubectl rollout restart deployment/ledger-node-false-leader
```

**絕對不要**在無法確認其持有最新資料的情況下，手動強制提升 Follower 為 Leader。

### 2.4 預防措施

| 措施 | 實現方式 |
|---|---|
| 監控複製延遲 | NFR-17 告警：`RaftFollowerLagHigh`、`RaftFollowerLagCritical` |
| 強制使用 ZGC | NFR-13：`-XX:+UseZGC` 防止長 GC 停頓導致複製中斷 |
| 網路冗餘 | 多 AZ 部署，節點間具備冗餘鏈路 |
| Snapshot 間隔 | `nodeOptions.setSnapshotIntervalSecs(3600)` — 每小時 Snapshot 確保 Follower 可快速追趕 |
| 磁碟 I/O | Raft log 與 RocksDB 資料使用獨立 SSD；避免與 OS 共用 |

---

## 3. MySQL View Layer 不同步復原

### 3.1 偵測

MySQL View Layer 為最終一致性。正常延遲 ≤ 1 秒（NFR-5）。以下方式可偵測偏離：

**方法 A — 比對 lastAppliedIndex**

```bash
# Leader 已套用的最大 index
LEADER_INDEX=$(curl -s http://node1:8080/ledger/cluster/raft-status | jq '.lastAppliedIndex')

# MySQL 最近 5 分鐘的 journal 數量（代表已投影的進度）
MYSQL_COUNT=$(mysql -h ledger-mysql -u ledger -p ledger123 -N -e \
  "SELECT COUNT(*) FROM journal WHERE created_at > DATE_SUB(NOW(), INTERVAL 5 MINUTE)")

# 若 Leader 5 分鐘內寫入 1000 筆但 MySQL 顯示 < 900，則存在延遲
```

**方法 B — Kafka Consumer Lag 監控**

```bash
# 檢查 projection consumer 的 consumer group lag
kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --group ledger-projection --describe
```

| Lag 狀態 | 含義 | 動作 |
|---|---|---|
| `CURRENT-OFFSET == LOG-END-OFFSET` | Consumer 已追上 | 無 |
| `LOG-END-OFFSET - CURRENT-OFFSET` > 1000 持續 > 60 秒 | Consumer 落後 | 調查（3.3 節） |
| Consumer group 中無此 consumer | Consumer 當機或斷線 | 重啟 consumer |

**方法 C — L1 對帳不符**

EOD 對帳（F-007）比較：
- RocksDB 中所有 JournalLine 的總和（真相源）
- MySQL View Layer 中所有 JournalLine 的總和

若不符 > 0 → MySQL 不同步。

### 3.2 根因分析

| 症狀 | 可能原因 | 證據 |
|---|---|---|
| 所有 MySQL 表均勻落後 | ProjectionConsumer 當機或停止 | Kafka consumer group 消失；無 consumer 日誌 |
| 僅 `journal_line` 落後 | ShardingSphere 路由錯誤或分片表遺失 | MySQL 錯誤日誌：「Table doesn't exist」 |
| `account_balance` 過時但 `journal` 正常 | `accountBalanceMapper.upsertBalance()` 失敗 | Consumer 日誌：「Failed to upsert balance」 |
| 間歇性缺漏行 | Kafka consumer 在 DB commit 前 auto-commit offset | Consumer 配置：`enable.auto.commit=true`（應為 `false` 並手動 ack） |
| MySQL 出現重複行 | ProjectionConsumer 重複處理同一事件 | 冪等鍵未在 INSERT 前檢查 |

### 3.3 復原流程

#### 情境 A：Consumer Lag（Consumer 存活但過慢）

```bash
# 1. 檢查 consumer CPU 與 heap
jcmd <consumer-pid> GC.heap_info
jcmd <consumer-pid> Thread.print | grep -c "kafka"

# 2. 若 CPU 正常，檢查 MySQL 寫入瓶頸
mysql -e "SHOW PROCESSLIST;"  # 尋找長時間執行的 INSERT

# 3. 擴展 consumer（若 topic 已分區）
# 目前 ledger.balance.change.v1 為單一分區 — 擴展需先增加分區數
kafka-topics.sh --bootstrap-server localhost:9092 \
  --alter --topic ledger.balance.change.v1 --partitions 4

# 4. 重啟 consumer 清除可能卡住的狀態
kubectl rollout restart deployment/ledger-projection
```

#### 情境 B：Consumer 當機（Consumer Group 中無此 Consumer）

```bash
# 1. 重啟 projection consumer
kubectl rollout restart deployment/ledger-projection

# 2. 確認其重新加入 consumer group
kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --group ledger-projection --describe

# 3. 監控 lag 直至追上
watch -n 5 'kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --group ledger-projection --describe'
```

**重要**：Consumer 已實作冪等（INSERT 並 catch exception）。重啟是安全的 — 重複事件會被忽略。

#### 情境 C：部分資料損毀（MySQL 缺漏部分行）

若僅特定帳戶或時間區間缺漏：

```bash
# 1. 識別受影響的 account_id 與時間區間
mysql -e "SELECT account_id, MIN(created_at), MAX(created_at)
  FROM journal_line WHERE journal_id IN (SELECT journal_id FROM journal
  WHERE created_at > '2026-05-20 00:00:00') GROUP BY account_id;"

# 2. 從 RocksDB State Machine 重播
# LedgerStateMachine 的 journalStore 保存所有 journal（記憶體 + RocksDB）
# 匯出受影響的 journal 並手動重新插入
```

**程式化重播（建議實作為管理員工具）：**

```java
// 管理端點：從 RocksDB 重播 journal 至 MySQL
@PostMapping("/admin/replay-journals")
public ResponseEntity<?> replayJournals(
        @RequestParam String startJournalId,
        @RequestParam String endJournalId) {
    // 1. 從 journalStore（記憶體或 RocksDB）讀取 journal
    // 2. 對每筆 journal 直接呼叫 ProjectionConsumer 邏輯
    // 3. 以冪等檢查寫入 MySQL
}
```

#### 情境 D：全量 MySQL 重建（View Layer 完全損毀）

若 MySQL 資料根本錯誤（例如餘額錯誤、流水缺漏），從頭重建：

```bash
# 步驟一 — 停止 projection consumer
kubectl scale deployment ledger-projection --replicas=0

# 步驟二 — 清空 MySQL View Layer 表
mysql -e "
  SET FOREIGN_KEY_CHECKS = 0;
  TRUNCATE TABLE journal;
  TRUNCATE TABLE journal_line_0;
  TRUNCATE TABLE journal_line_1;
  TRUNCATE TABLE journal_line_2;
  TRUNCATE TABLE journal_line_3;
  TRUNCATE TABLE account_balance;
  TRUNCATE TABLE account;
  SET FOREIGN_KEY_CHECKS = 1;
"

# 步驟三 — 將 Kafka consumer offset 重設至最開頭
kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --group ledger-projection --topic ledger.balance.change.v1 \
  --reset-offsets --to-earliest --execute

kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --group ledger-projection --topic ledger.account.v1 \
  --reset-offsets --to-earliest --execute

# 步驟四 — 重啟 projection consumer
kubectl scale deployment ledger-projection --replicas=1

# 步驟五 — 監控直至追上
kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --group ledger-projection --describe
```

**重建時間估算：**
- 100 萬筆 journal ≈ 15–30 分鐘（取決於 MySQL 寫入吞吐量）
- 500 萬筆 journal ≈ 1–2 小時
- 重建期間流水查詢返回舊資料（EOD 時段可接受；市場時段不可接受）

**替代方案：從 State Machine Snapshot 重建（而非 Kafka replay）**

若 Kafka retention 小於損毀時間窗口：

```bash
# 1. 從 Leader 的 RocksDB 匯出 Snapshot
# Snapshot 包含所有餘額、流水與帳戶資料

# 2. 解析 Snapshot JSON 並批量寫入 MySQL
# 比逐事件重播更快，但需自訂腳本
```

### 3.4 預防措施

| 措施 | 實現方式 |
|---|---|
| Consumer lag 告警 | Prometheus：`kafka_consumer_lag` > 1000 持續 > 60 秒 → PagerDuty |
| Consumer 健康檢查 | `/actuator/health` 包含 Kafka consumer 指示器 |
| 冪等 INSERT | `INSERT ... ON DUPLICATE KEY UPDATE` 或 catch-exception 模式（已實作） |
| MySQL 複製（選配） | 部署 MySQL read replica 供報表查詢；primary 保留給 projection 寫入 |
| Kafka retention | `retention.ms = 7 days` 最低；確保有足夠歷史進行重建 |
| EOD 對帳 | L1 對帳（RocksDB vs MySQL）每晚執行；T+0 內發現偏離 |

---

## 4. Prometheus / Grafana 監控

### 4.1 存取端點

| 服務 | URL | 預設帳號 |
|---|---|---|
| Prometheus | http://localhost:9090 |無 |
| Grafana | http://localhost:3000 | admin / admin123 |
| Node 1 metrics | http://localhost:8081/actuator/prometheus | 無 |
| Node 2 metrics | http://localhost:8082/actuator/prometheus | 網 |
| Node 3 metrics | http://localhost:8083/actuator/prometheus | 網 |
| Projection metrics | http://localhost:8089/actuator/prometheus | 網 |

### 4.2 Prometheus Targets 狀態

檢查所有 scrape targets 是否 UP：

```bash
curl -s http://localhost:9090/api/v1/targets | jq '.data.activeTargets[] | {job:.labels.job,health:.health}'
```

預期輸出：

```
{"job": "ledger-nodes", "health": "up"}
{"job": "projection", "health": "up"}
{"job": "prometheus", "health": "up"}
```

若 target 狀態為 `down`，檢查：

- Docker container 是否運行：`docker ps`
- Actuator endpoint 是否暴露：`curl http://ledger-node-1:8080/actuator/prometheus`
- Prometheus config 載入正確：`docker exec ledger-prometheus cat /etc/prometheus/prometheus.yml`

### 4.3 Grafana Dashboard

預設 dashboard（`grafana/provisioning/dashboards/ledger-main.json`）包含以下 panel：

| Panel | Metrics | NFR 目標 |
|---|---|---|
| Posting P95 latency | `ledger_posting_duration_seconds{quantile="0.95"}` | ≤ 3ms |
| Balance Query latency | `ledger_balance_query_duration_seconds{quantile="0.95",queryType="live"}` | ≤ 2ms |
| Raft Leader status | `ledger_raft_is_leader` | 1 (single node = 1) |
| Account Queue depth | `ledger_account_queue_depth` | < 500 |
| GC Pause time | `jvm_gc_pause_seconds_max` | < 5ms |
| Kafka consumer lag | `kafka_consumer_lag` | < 1000 |

Dashboard JSON 配置見 `grafana/provisioning/dashboards/ledger-main.json`。

### 4.4 常用 PromQL 查詢

```promql
# Posting P95 近 5 分鐘
histogram_quantile(0.95, rate(ledger_posting_duration_seconds_bucket[5m]))

# Balance Query P95 (live)
histogram_quantile(0.95, rate(ledger_balance_query_duration_seconds_bucket{queryType="live"}[5m]))

# 最大 GC pause 近 1 分鐘
max(jvm_gc_pause_seconds_max)

# Raft Leader 節點
max_by (node_id) (ledger_raft_is_leader)

# Account queue 最深的帳戶
topk(5, ledger_account_queue_depth)
```

### 4.5 告警規則配置

Prometheus AlertManager 霈配置以下規則（見 `prometheus/alert-rules.yml`）：

| 告警名稱 |條件 | 嚴重程度 |
|---|---|---|
| PostingP95High | histogram_quantile(0.95, rate(ledger_posting_duration_seconds_bucket[5m])) > 0.003 | WARNING |
| PostingP99Critical | histogram_quantile(0.99, rate(ledger_posting_duration_seconds_bucket[5m])) > 0.05 | CRITICAL |
| GCPauseTooLong | jvm_gc_pause_seconds_max > 0.005 | CRITICAL |
| QueueBacklogHigh | ledger_account_queue_depth > 500 | WARNING |
| RaftFollowerLagHigh | max(ledger_raft_last_applied_index) - ledger_raft_last_applied_index > 100 | WARNING |

---

## 附錄 A：緊急聯絡與 Runbook 參考

| 情境 | 第一反應 | 升級 |
|---|---|---|
| RocksDB 磁碟滿了 | 觸發壓實；擴展 EBS | 平台工程師 |
| Raft Quorum 遺失 | **勿同時重啟多個節點**；識別分區原因 | SRE Lead |
| 所有 MySQL 資料損毀 | 啟動全量重建（從 Kafka） | DBA + SRE Lead |
| Posting P99 > 50ms | 檢查 GC 停頓、queue backlog、Raft term | On-call SRE |

## 附錄 B：快速指令參考

```bash
# RocksDB SST 檔案數量與大小
ls -lh /data/ledger/rocksdb/*.sst | wc -l

# 所有 Raft 節點狀態
for n in node1 node2 node3; do curl -s http://${n}:8080/ledger/cluster/raft-status | jq -c '{nodeId,isLeader,term,lastAppliedIndex}'; done

# Kafka consumer lag
kafka-consumer-groups.sh --bootstrap-server localhost:9092 --group ledger-projection --describe

# MySQL 最近一小時 journal 數量
mysql -e "SELECT COUNT(*) FROM journal WHERE created_at > DATE_SUB(NOW(), INTERVAL 1 HOUR);"

# ZGC 停頓檢查
jcmd <pid> GC.run_finalization  # 或檢查 GC log 中 > 5ms 的停頓
```
