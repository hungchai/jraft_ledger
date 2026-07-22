# F-003 Manual Adjustment — 功能需求規格

**文件版本**: v0.1  
**功能**: F-003 Manual Adjustment（人工調帳）  
**系統**: Next-Gen Internal Ledger Platform  
**狀態**: Draft for Review  
**依賴**: ADR-001、F-002 Posting API、F-008 State Machine Design

---

## 1. 功能概述

Manual Adjustment 是由操作員直接發起的單邊或雙邊帳務調整，不依附於任何業務事件（如交易、費用）。它用於處理以下場景：系統對帳差異補帳、利息手動入帳、費用豁免、系統遷移數據修正。

**與 Posting 的區別**：
- Posting 由業務系統發起，有明確的業務事件 ID
- Manual Adjustment 由人工操作員發起，必須有審批記錄
- Manual Adjustment 的 `journalType = MANUAL_ADJUSTMENT`，在報表和對帳中單獨統計

---

## 2. 適用場景

| 場景 | 說明 |
|---|---|
| 對帳差異補帳 | 外部清算返回差額，需手動補入 |
| 利息手動入帳 | 計息系統故障，需人工補入利息 |
| 費用豁免 | 已入帳費用需人工豁免（Reversal + 豁免入帳） |
| 系統遷移 | 舊系統餘額搬到新 Ledger 的初始入帳 |
| 錯誤修正 | 無法通過 Reversal 解決的複雜場景 |

---

## 3. Maker-Checker 強制要求

**所有 Manual Adjustment 必須經過 Maker-Checker 雙重審批**，這是 ibank 合規要求，不可繞過：

```
Maker（製作者）:
  提交 Adjustment Draft（草稿狀態）
  → 系統做前置校驗，但不執行
  → 返回 draftId

Checker（審核者）:
  審閱 Draft 內容
  → 批准（Approve）→ 系統執行 Adjustment
  → 拒絕（Reject）→ Draft 作廢

約束：
  - Maker 和 Checker 不能是同一人
  - Draft 有效期：24 小時（可配置）
  - 超時未審批：自動作廢
  - Checker 批准後不可撤回（需用 Reversal）
```

---

## 4. API 設計

### 4.1 Step 1：Maker 提交草稿

```
POST /ledger/adjustments/draft
```

**Request Body**

| 欄位 | 類型 | 必填 | 說明 |
|---|---|---|---|
| `draftRequestId` | `string` | ✅ | 冪等鍵 |
| `adjustmentType` | `enum` | ✅ | 調整類型，見下表 |
| `adjustmentReason` | `string` | ✅ | 自由文字說明（最長 1000 字） |
| `valueDate` | `date` | ✅ | 帳務生效日 |
| `makerId` | `string` | ✅ | Maker 操作員 ID |
| `legs` | `list<Leg>` | ✅ | 同 F-002 Posting 格式 |
| `supportingRef` | `string` | ❌ | 支撐文件編號（如對帳報告 ID） |
| `metadata` | `map` | ❌ | 擴展欄位 |

**adjustmentType 枚舉**

| Code | 說明 |
|---|---|
| `RECONCILIATION_ADJUSTMENT` | 對帳差異補帳 |
| `INTEREST_ADJUSTMENT` | 利息手動調整 |
| `FEE_WAIVER` | 費用豁免 |
| `MIGRATION_ENTRY` | 系統遷移入帳 |
| `ERROR_CORRECTION` | 錯誤修正 |
| `REGULATORY_ADJUSTMENT` | 監管要求調整 |

**Response（草稿創建成功）**

```json
{
  "draftRequestId": "draft-req-abc123",
  "draftId": "ADJ-DRAFT-20260516-000001",
  "status": "PENDING_APPROVAL",
  "expiresAt": "2026-05-17T14:30:00.000Z",
  "makerId": "ops-user-001"
}
```

### 4.2 Step 2：Checker 審批

```
POST /ledger/adjustments/{draftId}/approve
POST /ledger/adjustments/{draftId}/reject
```

**Approve Request Body**

| 欄位 | 類型 | 必填 | 說明 |
|---|---|---|---|
| `requestId` | `string` | ✅ | 冪等鍵（防止重複審批） |
| `checkerId` | `string` | ✅ | Checker 操作員 ID（不能等於 makerId） |
| `checkerNote` | `string` | ❌ | 審批備注 |

**Reject Request Body**

| 欄位 | 類型 | 必填 | 說明 |
|---|---|---|---|
| `requestId` | `string` | ✅ | 冪等鍵 |
| `checkerId` | `string` | ✅ | Checker 操作員 ID |
| `rejectReason` | `string` | ✅ | 拒絕原因 |

---

## 5. 校驗規則

### 5.1 Draft 創建時校驗（Maker 提交）

| # | 規則 | 錯誤碼 |
|---|---|---|
| V-01 | legs 格式合法，借貸平衡 | `JOURNAL_UNBALANCED` |
| V-02 | 所有 accountId 存在且狀態為 ACTIVE | `ACCOUNT_NOT_FOUND` |
| V-03 | 所有 balanceType 在 Registry 中存在 | `BALANCE_TYPE_NOT_FOUND` |
| V-04 | `adjustmentType` 合法 | `INVALID_ADJUSTMENT_TYPE` |

**注意：Draft 創建時不做餘額校驗**，餘額校驗在 Checker Approve 並執行時才做。

### 5.2 Checker Approve 時校驗

| # | 規則 | 錯誤碼 |
|---|---|---|
| V-05 | `checkerId ≠ makerId` | `MAKER_CHECKER_SAME_PERSON` |
| V-06 | Draft 未過期（在 expiresAt 之前） | `DRAFT_EXPIRED` |
| V-07 | Draft status = `PENDING_APPROVAL` | `DRAFT_NOT_PENDING` |
| V-08 | 餘額校驗（同 F-002 V-08 ~ V-12） | 見 F-002 |

---

## 6. 執行流程

### 6.1 Maker 提交草稿（不走 Raft）

```
Draft 只做校驗和儲存，不入帳
→ 寫 MySQL adjustments_draft 表
→ 返回 draftId
→ 不提交 RaftCommand，不更新 State Machine
```

### 6.2 Checker Approve → 執行 Adjustment（走 Raft）

```
1. [Network Layer]
   校驗 checkerId ≠ makerId，Draft 未過期

2. [Ledger Layer]
   從 MySQL 載入 Draft 的 legs 內容
   → 按 accountId 升序路由到 Account Queue

3. [Account Queue Coordinator]
   冪等檢查（approve requestId）
   餘額校驗（V-08，讀 in-memory State Machine）
   構建 ADJUSTMENT_CMD

4. [Raft Layer]
   提交 ADJUSTMENT_CMD → Quorum commit

5. [State Machine Apply]
   生成 Journal（journalType = MANUAL_ADJUSTMENT）
   生成 JournalLine
   更新 in-memory balance
   寫 RocksDB WriteBatch
   更新 Draft status = EXECUTED（通過 Learner 同步到 MySQL）

6. [Response]
   返回 adjustmentJournalId
```

---

## 7. Draft 狀態機

```
        Maker 提交
             │
             ▼
    [PENDING_APPROVAL]
        │         │
    Checker    Checker
    Approve    Reject      Draft 過期（24h）
        │         │              │
        ▼         ▼              ▼
   [APPROVED] [REJECTED]    [EXPIRED]
        │
        ▼
   [EXECUTED]（入帳完成）
        │
        ▼
   [REVERSED]（如後續被 Reversal）
```

---

## 8. 審計要求

每筆 Manual Adjustment 在 MySQL 保存完整審計鏈路：

| 欄位 | 說明 |
|---|---|
| `draftId` | 草稿 ID |
| `adjustmentJournalId` | 最終入帳的 Journal ID |
| `makerId` + `makeTime` | 誰在何時提交草稿 |
| `checkerId` + `checkTime` | 誰在何時批准 |
| `checkerNote` | 審批備注 |
| `adjustmentType` | 調整類型 |
| `adjustmentReason` | 原因說明 |
| `supportingRef` | 支撐文件 |

---

## 9. 性能目標

| 操作 | 目標 |
|---|---|
| Draft 創建 P95 | ≤ 100ms（只寫 MySQL，不走 Raft） |
| Checker Approve P95 | ≤ 10ms（走 Raft，略高於 Posting） |

---

## 10. 驗收標準

| # | 驗收條件 | 測試方式 |
|---|---|---|
| AC-01 | Maker 提交 Draft，系統返回 draftId，未入帳 | 功能測試 |
| AC-02 | Checker 批准後，帳務正確入帳，Journal 類型為 MANUAL_ADJUSTMENT | 功能測試 |
| AC-03 | Checker 和 Maker 為同一人，返回 `MAKER_CHECKER_SAME_PERSON` | 功能測試 |
| AC-04 | 24 小時後未審批的 Draft，狀態自動變為 EXPIRED | 功能測試 |
| AC-05 | 對 EXPIRED / REJECTED / EXECUTED 的 Draft 執行 Approve，返回 `DRAFT_NOT_PENDING` | 功能測試 |
| AC-06 | 審批記錄（makerId、checkerId、時間）完整保存在 MySQL | 審計測試 |
| AC-07 | Approve 操作冪等，相同 requestId 重試不重複入帳 | 冪等測試 |
| AC-08 | Manual Adjustment Journal 在報表中獨立統計，與業務 Posting 分開 | 報表測試 |
