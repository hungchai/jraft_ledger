# F-009 Accounting Period / EOD — 功能需求規格

**文件版本**: v0.1  
**功能**: F-009 Accounting Period & EOD 關期  
**系統**: Next-Gen Internal Ledger Platform  
**狀態**: Draft for Review  
**依賴**: ADR-001、F-005 Balance Snapshot、F-007 Reconciliation

---

## 1. 功能概述

Accounting Period 管理帳務切期邏輯：控制哪個帳期開放過帳、哪個帳期已關閉、以及 EOD（End of Day）關期時的快照與對帳觸發。

---

## 2. 帳期狀態機

```
      開盤
        │
        ▼
     [OPEN]         ← 正常過帳均落在此帳期
        │
  EOD 觸發關期
        │
        ▼
   [CLOSING]        ← 禁止新 Posting，等待 EOD 任務完成
        │
  EOD 任務完成
        │
        ▼
    [CLOSED]        ← 不可過帳；跨期 Reversal 需標記 crossPeriod=true
        │
  下一帳期開盤
        │
        ▼
   [OPEN] (T+1)
```

---

## 3. EOD 任務序列

帳期關閉時，系統按以下**嚴格順序**執行 EOD 任務：

```
Step 1  停止接受新 Posting（帳期狀態 → CLOSING）
Step 2  等待所有 in-flight Raft Command 完成（drain queue）
Step 3  觸發 State Machine Snapshot（F-008）
Step 4  Learner 確認 MySQL View Layer 已追上 Snapshot Index
Step 5  執行 L1 對帳（Journal 借貸平衡 + Balance 一致性）
Step 6  執行 L2 對帳（子帳對總帳）
Step 7  生成 EOD Balance Snapshot（所有帳戶當前餘額）
Step 8  生成 Reconciliation Report
Step 9  帳期狀態 → CLOSED
Step 10 新帳期 OPEN（T+1）
```

任何步驟失敗：發出告警，人工介入，不自動跳過。

---

## 4. 帳期配置

| 欄位 | 說明 |
|---|---|
| `periodId` | 帳期唯一 ID（如 `2026-05-16`） |
| `openTime` | 帳期開放時間 |
| `scheduledCloseTime` | 計劃關期時間（如每日 23:30） |
| `actualCloseTime` | 實際關期完成時間 |
| `status` | OPEN / CLOSING / CLOSED |
| `eodTaskStatus` | 各 EOD 子任務狀態 JSON |

---

## 5. 跨帳期規則

| 情況 | 處理 |
|---|---|
| 對已 CLOSED 帳期做 Posting | 拒絕，返回 `PERIOD_CLOSED` |
| 對已 CLOSED 帳期做 Reversal | 允許，但標記 `crossPeriod=true` |
| 對已 CLOSED 帳期做 Manual Adjustment | 允許，但需額外審批（Checker 需在審批備注確認跨期） |

---

## 6. API 設計

```
# 查詢帳期列表
GET /ledger/accounting-periods?status=OPEN

# 手動觸發 EOD（測試或補跑）
POST /ledger/accounting-periods/{periodId}/eod/trigger

# 查詢 EOD 任務狀態
GET /ledger/accounting-periods/{periodId}/eod/status
```

---

## 7. 驗收標準

| # | 驗收條件 | 測試方式 |
|---|---|---|
| AC-01 | EOD 任務按嚴格順序執行，任一步驟失敗不繼續 | 功能測試 |
| AC-02 | CLOSING 期間新 Posting 請求返回 `PERIOD_CLOSED` | 功能測試 |
| AC-03 | 跨帳期 Reversal 標記 `crossPeriod=true` | 功能測試 |
| AC-04 | EOD 完成後 EOD Balance Snapshot 與 State Machine 一致 | 對帳測試 |
| AC-05 | EOD 全流程（含 L1/L2 對帳）在 30 分鐘內完成 | 性能測試 |
