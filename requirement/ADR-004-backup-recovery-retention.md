# ADR-004 備份、恢復與資料保留策略

**決策狀態**: Proposed
**決策日期**: 2026-07-08
**決策人**: Ledger Platform Team
**影響範圍**: F-008 State Machine, F-009 Accounting Period / EOD, F-011 BalanceChangeEvent / Outbox, ADR-001 (Raft + CQRS)

> **v0.1 初版**：定義備份分層策略、恢復鏈路劃分（權威 vs 派生）、RocksDB 保留窗口 + EOD 歸檔。承接 2026-07-06/07 24h soak（259M postings）暴露的容量問題：帳本狀態以 ~8GB/h @3k TPS 線性增長，217GB NVMe 24h 即達 189GB。

---

## 1. 背景與問題

2026-07-06/07 的 24 小時 soak（3k TPS 持續、259,086,084 筆）驗證了系統的持續吞吐能力，同時暴露一個結構性問題：**帳本狀態只增不減**。

- state RocksDB 以 **~8GB/h @3k TPS** 增長（~750 B/posting：journal + 4 journal_lines + idempotency row），24h 達 189GB / 217GB NVMe，貼邊
- 三層資料各有不同的持久化與恢復語義，先前沒有統一的備份/恢復/保留規格
- 缺一個明確原則：**哪條鏈路是「權威恢復源」、哪條是「派生災備」**，兩者混用會導致從最終一致的下游恢復出錯位的帳本狀態

本 ADR 定義：(1) 備份分層；(2) 恢復鏈路劃分；(3) RocksDB 有界保留窗口 + EOD 歸檔的技術實現。

---

## 2. 核心原則

### 2.1 兩條恢復鏈路，嚴格分離

系統的資料流是單向下游派生：

```
Raft log (①權威)  →  apply  →  state RocksDB (②權威快取)  →  outbox → Kafka → projection → MySQL/PG (③派生讀模型)
```

| 鏈路 | 組成 | 恢復語義 | 用途 |
| --- | --- | --- | --- |
| **權威恢復鏈** | Raft log + RocksDB checkpoint | 確定性重放，位級重現 | 節點崩潰/資料損毀後重建帳本狀態 |
| **派生災備鏈** | MySQL projection dump + S3 歸檔 | 最終一致、經投影轉換、有 lag | 查詢側災備、審計留存、離線分析 |

**鐵律**：帳本狀態的權威恢復**只走權威鏈**（Raft log 重放 / checkpoint 還原 / InstallSnapshot）。派生鏈（MySQL/S3）**永遠不作為帳本恢復源** —— 它可能比帳本落後數秒，且經過投影轉換，用它恢復會引入錯位。派生鏈只服務查詢與審計。

### 2.2 為什麼 checkpoint 是備份基礎單元

PR #31 已實現 RocksDB Checkpoint 快照（硬鏈結 SST，IO O(1)，含 `lastAppliedIndex` 一致點）。備份策略**復用這個既有機制**，不引入第二套（如 `BackupEngine`）：

- checkpoint 是硬鏈結，本地成本近零；跨檔案系統退化為 copy
- 天然攜帶 `lastAppliedIndex` → 恢復時對齊，避免「游標隨機停下」的中間態
- 與 jraft snapshot 路徑同源，已在 24h soak + 對抗性 review 中驗證（含並發讀者鎖保護，commit `b981a0a`）

**不採用 `BackupEngine`**：它預設依賴 WAL 做一致點，而我們 `disableWAL=true`；且功能與 checkpoint 重疊，等於維護第二套要單獨驗證的機制。

---

## 3. 備份分層策略

| 頻率 | 內容 | 機制 | 存儲 | 保留 |
| --- | --- | --- | --- | --- |
| **每小時** | state RocksDB checkpoint | 硬鏈結到本地備份目錄（復用 PR #31 checkpoint） | 本地 NVMe | 24 小時 |
| **每日** | jraft snapshot 產物（在 follower 上取，不干擾 leader） | 取 **jraft 每 600s 已產生的 snapshot**（含一致點），壓縮上傳 | S3 Standard-IA | 7 天 |
| **每週** | MySQL projection 全量 dump | `mysqldump` 唯讀 view（journal/balance） | S3 Glacier | 4 週 |
| **每月** | **完整還原驗證**（restore drill） | 真跑一次 checkpoint → 全新節點恢復 → 對帳 | Glacier Deep Archive | 12 個月 |

### 3.1 每小時 — 本地快速恢復點

Checkpoint 到本地另一目錄（硬鏈結，秒級、近零 IO）。用途：單節點崩潰後最快的本地恢復點，避免走跨節點 InstallSnapshot。保留 24 份滾動。

### 3.2 每日 — 異地災備（關鍵修正：在 follower 上取 jraft snapshot 產物）

**不要對 live RocksDB 目錄做 raw checkpoint** —— follower 的 RocksDB 隨時在 apply 新命令，raw 目錄拍到的是任意中間態，`lastAppliedIndex` 不確定，對帳本審計是硬傷。

正解：取 **jraft 每 600s 已經產生的 snapshot 產物**（`onSnapshotSave` 輸出，含 `state_machine_snapshot` blob + `cp_` 前綴的 checkpoint 檔），它天然是一致點。選 follower 執行以免佔用 leader 的 apply/publish 資源。壓縮後上傳 S3 Standard-IA。

### 3.3 每週 — 派生讀模型 dump（僅查詢/審計）

MySQL projection 是 `RocksDB → Kafka → projection → MySQL` 末端，最終一致、有 lag、經投影轉換。全量 dump **定位為查詢側災備 + 審計留存，非權威恢復源**。

成本警示：journal 是雙入帳明細，按 24h soak 實測 ~8GB/h 增長，全量 dump 體量與時長需按真實 TPS 核算；建議 dump 前先確認 projection lag 已收斂（避免 dump 到追趕中的部分狀態）。

### 3.4 每月 — 還原驗證（唯一強制真跑項）

> **「沒驗證過恢復的備份 = 沒有備份」** —— 本專案 2026-07 的血淚教訓：不實測的性能結論全是假象；備份同理。

每月真跑一次完整 restore：取一份 checkpoint → 在全新節點恢復 → 與同期 MySQL 對帳（Σ DEBIT − Σ CREDIT = 0、per-node seq 一致、抽樣餘額比對）。驗證通過的備份存 Glacier Deep Archive 留 12 個月（合規/審計）。這是唯一必須端到端跑通的環節,其餘備份可只驗完整性（checksum + 行數）。

---

## 4. RocksDB 保留窗口 + EOD 歸檔

備份解決「壞了能恢復」；保留窗口解決「盤不會滿」。二者正交。

### 4.1 核心洞察：存多久由業務窗口決定，不由磁碟決定

帳本 append-only 不可刪（CLAUDE.md 2.1、DB append-only trigger），但**不代表全部歷史必須常駐熱盤**。state RocksDB 內部唯一需要全史的用途是：

1. **Reversal 校驗** —— apply 時讀原 journal（是否存在/已 reversed/跨期/原始 lines 做鏡像分錄）
2. **節點引導** —— snapshot 整庫複製

而 (1) 有業務窗口（如 90 天後不允許沖正）,(2) 只需當前狀態。所以 RocksDB 只需保留一個**有界操作窗口 = 業務 reversal 窗口**，窗口外歸檔到 S3。

磁碟從「無限增長」變成「窗口天數 × TPS 的常數」：生產 100 TPS × 90 天 ≈ 580GB（一塊盤搞定）；壓測 3k TPS 則必須配短窗口。

### 4.2 技術實現：EOD 歸檔 + Raft 複製的範圍刪除

刪除必須三節點確定性一致 → **刪除走 Raft，搬運走後台**。

```
EOD 任務（leader-only，同 outbox drain 模式）:
  1. 前置門禁：會計期 CLOSED（F-009）+ L1/L2/L3 對帳全 PASS（F-007）
  2. 導出（後台線程，不進共識路徑）:
       checkpointTo(tmpDir)                    ← 復用 PR #31 O(1) checkpoint（唯讀一致點）
       從只讀 checkpoint 流式導出 cutoff 前的
       journal/journal_line → S3（Parquet + SHA256 manifest）
       回讀校驗 + 行數 vs MySQL 對帳
  3. 驗證通過 → leader 提議 Raft 命令:
       ArchiveTruncate(cutoffJournalId, s3ManifestRef)
                          │
                          ▼ 複製到多數派
  4. apply（三節點同一邏輯，確定性）:
       deleteRange(journal,      [0, cutoffKey))   ← journalId 時間有序（snowflake/UUIDv7）
       deleteRange(journal_line, [0, cutoffKey))     → 前綴即時間，range delete = 一個墓碑，微秒級
       state.archivedWatermark = cutoffJournalId
  5. 物理回收（每節點本地後台，時機不需一致）:
       CompactRange(兩個 CF)   ← 被 rate limiter 管著，不重演 IO 風暴
  6. 下一次 checkpoint 自動變小
```

### 4.3 語義收口

- **Reversal 門檻**：apply 先查 `journalId >= archivedWatermark`，否則回 `REVERSAL_WINDOW_EXPIRED`。watermark 是複製狀態 → 三節點判定一致
- **查詢**：F-006 journal query 本來就走 MySQL；更老走 S3/Athena（低頻審計）
- **窗口長度**：`ledger.retention.reversal-window-days` properties（ADR-001 §2.11 config 規範）
- **完整性**：全史 = S3 歸檔 + RocksDB 熱窗口，審計可重建

### 4.4 Idempotency CF 單獨處理

idempotency CF 不走 Raft 範圍刪除（它不是帳本，是去重快取）—— 直接 `ColumnFamilyOptions.setTtl(N)`,compaction 順手回收，讀側已有 `createdAtMillis` 校驗兜懶刪邊界。壓測 10-30 分鐘、生產按 reversal 窗口配。（見 [[perf-optimization-levers]] 兩層設計）

---

## 5. 運維護欄

| 護欄 | 閾值 | 動作 |
| --- | --- | --- |
| 磁碟水位告警 | 70% / 85% | PagerDuty warning（納入監控 §4.3） |
| 磁碟寫入準入 | 95% | 拒絕新 posting（比照 QUEUE_FULL）—— 寧可拒單，不可半截 WriteBatch 死在滿盤 |
| 容量規劃公式 | — | `~750 B/posting × TPS × 熱窗口天數` |
| projection lag 門檻 | dump 前 | lag 未收斂不 dump |

---

## 6. 決策後果

✅ 權威恢復（Raft/checkpoint）與派生災備（MySQL/S3）清楚分離，杜絕從最終一致下游恢復帳本的錯位風險。磁碟從無限增長變有界常數。備份復用已驗證的 checkpoint 機制，不引第二套。每月強制 restore 演練杜絕「假備份」。

❌ EOD 歸檔 + ArchiveTruncate 是新的狀態機命令 + S3 導出器 + 門禁邏輯，需實作與測試（~3-4 天，關鍵零件 checkpoint / 時間有序 key / rate limiter / 會計期門禁均已存在）。歸檔窗口外的查詢延遲較高（S3/Athena 路徑，可接受，屬低頻審計）。跨檔案系統 checkpoint 退化為 copy，本地備份目錄應與資料同盤。

---

## 7. 實作狀態

| 項 | 狀態 |
| --- | --- |
| RocksDB Checkpoint 快照（O(1)、含一致點、並發讀者鎖保護） | ✅ Done — PR #31 |
| 每小時本地 checkpoint 備份 | Open |
| 每日 follower snapshot → S3-IA | Open |
| 每週 MySQL dump → Glacier | Open |
| 每月 restore 演練 | Open |
| Idempotency CF `setTtl` | Open — backlog（兩層設計） |
| EOD 歸檔 + `ArchiveTruncate` + `archivedWatermark` + `REVERSAL_WINDOW_EXPIRED` | Open — 本 ADR 核心待實作項 |
| 磁碟水位告警 70/85/95% | Open |
