# TDD Test Cases — Next-Gen Internal Ledger Platform

**版本**: v0.16
**日期**: 2026-06-15
**方法**: Test-Driven Development（Red → Green → Refactor）
**框架**: JUnit 5 + Mockito + AssertJ + Testcontainers（MySQL）+ RocksDB embedded

> **v0.15 變更摘要**：新增 Module 19.5（Projection Deadlock Safety），新增 TC-PROJ-11~12。涵蓋 journal_line 批次寫入前按 (accountAccountId, journalLineId) 排序以避免 InnoDB gap-lock 死鎖，以及死鎖發生時 bounded retry。
>
> **v0.16 變更摘要**：新增 TC-F011-13~17（JournalEventEnvelope 1-record-per-journal）。驗證 applyPosting 走 `onPosting(envelope)` 路徑、EmitGate 關閉時零發送、CF_OUTBOX 一筆 journal 一筆 envelope 條目、AsyncOutboxPublisher 走 envelope 路徑。對應 v0.3 F-011 §8 envelope 模型。
>
> **v0.14 變更摘要**：新增 Module 15.1（Apache Ratis Engine，ADR-003），新增 TC-RAFT-RATIS-01~04 與 TC-NFR-OOM-01。涵蓋 Ratis log 提交餘額變更、冪等重放、snapshot 還原、引擎可切換，以及 OOM 災難復原（0 已提交遺失 + 乾淨復原 + 無跨節點分歧）。
>
> **v0.13 變更摘要**：新增 Module 22（SnowflakeIdGenerator 時鐘回退容錯），新增 TC-SNOW-01~04。涵蓋 monotonic ID、序列號回捲、≤5ms 時鐘回退等待、>5ms 時鐘回退拒絕。
>
> **v0.12 變更摘要**：新增 Module 21（F-016 Micrometer Metrics & Observability），新增 TC-F016-01~10。涵蓋 Metrics 暴露、標籤正確性、Alert Rule 觸發、Histogram bucket SLO、Projection gauges 獨立暴露。
>
> **v0.11 變更摘要**：新增 TC-F011-10~12（Outbox callback-driven deletion）。驗證 KafkaEventPublisher send callback 刪除 outbox、AsyncOutboxPublisher 不再直接調用 markSent、熱路徑 callback 即時清理 outbox。
>
> **v0.10 變更摘要**：新增 Module 20（F-015 Config Abstraction），新增 TC-F015-01~05。ConfigService 介面 + SpringConfigService 實作，移除 LedgerConfig 中所有 `System.getenv().getOrDefault()`。
>
> **v0.9 變更摘要**：修正 AccountBalanceKey 描述為 v0.7 三維鍵 (accountId, balanceType, currency)，移除 position 作為物理鍵組件。TC-F008-30、TC-F002-11 調整 AccountBalanceKey 斷言以匹配 v0.7 修正。
>
> **v0.8 變更摘要**：新增 enriched error detail 測試案例（TC-F002-16~20、TC-F004-08~09、TC-F008-31~35、TC-F013-11~12）。CommandResult 與 IdempotencyEntry 新增 `errorDetails` Map，用於在 REJECTED 回應中攜帶觸發錯誤的實體上下文（accountId、journalId、balanceType、currency、position 等）。
>
> **v0.7 變更摘要**：修復 AccountQueueManager submitAsync 永遠不完成 future 的缺陷（P0 HOTFIX）。新增 TC-QUEUE-04（submitAsync success path）、TC-QUEUE-05（submitAsync exception path），補齊 async future 完成契約的測試覆蓋。
>
> **v0.6 變更摘要**：新增 Module 19（F-012 Projection MySQL View Layer v2），新增 TC-PROJ-01~08。涵蓋 projection_event_log idempotency、accountSeq guard 防止 stale overwrite、surrogate FK chain 一致性、Kafka 重播冪等。TDD 執行計劃補充 Phase 8。
>
> **v0.5 變更摘要**：新增 Module 18（F-014 Java Client SDK），新增 TC-F014-01~08。TDD 執行計劃補充 Phase 7。
>
> **v0.4 變更摘要**：新增 Module 13（F-013 Idempotency & Hotspot Account Concurrency），新增 TC-F013-01~10。TDD 執行計劃補充 Phase 6.5。
>
> **v0.3 變更摘要**：新增 position 字段支持（AccountBalanceKey 複合鍵擴展）。新增 TC-F002-11~15（Posting position）、TC-F005-07~10（Balance Query position）、TC-F008-27~30（State Machine position 驗證規則 V-13）。更新現有測試用例以反映 position 為邏輯映射概念，物理鍵格式為 (accountId, balanceType, currency)。
>
> **v0.2 變更摘要**：新增 Module 3 Section 3.4（TC-F008-19 ~ TC-F008-26，accountSeq State Machine）、Module 11 Section 11.1（TC-F011-01 ~ TC-F011-07，BalanceChangeEvent accountSeq），TDD 執行計劃補充 Phase 3.5。

---

## 測試分層策略

```
┌─────────────────────────────────────┐
│         E2E / Integration Tests      │  少量，覆蓋關鍵業務流程
├─────────────────────────────────────┤
│         Service / UseCase Tests      │  中量，覆蓋業務規則
├─────────────────────────────────────┤
│         Unit Tests                   │  大量，覆蓋所有核心邏輯
└─────────────────────────────────────┘
```

原則：
- Unit Test 覆蓋率目標：≥ 90%
- 每個 test case 只測一件事
- Test 名稱格式：`[方法名]_[情境]_[預期結果]`
- 所有 test 必須在 CI 環境無外部依賴下運行（embedded RocksDB / Testcontainers）

---

## Module 1：Balance Type Registry（F-001）

### 1.1 BalanceTypeConfigService

```
TC-F001-01  getConfig_existingActiveType_returnsConfig
            Given: AVAILABLE_BALANCE 已在 Registry 中，status=ACTIVE
            When:  getConfig("AVAILABLE_BALANCE")
            Then:  返回正確 config，allowNegative=false

TC-F001-02  getConfig_nonExistentType_throwsBalanceTypeNotFoundException
            Given: "UNKNOWN_TYPE" 不在 Registry
            When:  getConfig("UNKNOWN_TYPE")
            Then:  拋出 BalanceTypeNotFoundException

TC-F001-03  getConfig_inactiveType_throwsBalanceTypeInactiveException
            Given: "OLD_TYPE" status=INACTIVE
            When:  getConfig("OLD_TYPE")
            Then:  拋出 BalanceTypeInactiveException

TC-F001-04  registerType_newType_successfullyRegistered
            Given: "BROKERAGE_BALANCE" 不存在
            When:  registerType(BalanceTypeConfig{code="BROKERAGE_BALANCE", allowNegative=false})
            Then:  Registry 中存在該 type，status=ACTIVE，configVersion=1

TC-F001-05  registerType_duplicateCode_throwsDuplicateBalanceTypeException
            Given: "AVAILABLE_BALANCE" 已存在
            When:  registerType(BalanceTypeConfig{code="AVAILABLE_BALANCE"})
            Then:  拋出 DuplicateBalanceTypeException

TC-F001-06  registerType_tradeAheadBalance_allowNegativeTrue_signConventionNormalDebit
            Given: 新類型 TRADE_AHEAD_BALANCE，allowNegative=true，negativeSemantics=PRE_AUTHORIZED
            When:  registerType(...)
            Then:  config.allowNegative=true，config.negativeSemantics=PRE_AUTHORIZED

TC-F001-07  updateConfig_existingType_configVersionIncremented
            Given: "AVAILABLE_BALANCE" configVersion=1
            When:  updateConfig("AVAILABLE_BALANCE", newConfig)
            Then:  configVersion=2，變更記錄保存

TC-F001-08  deactivateType_existingType_statusBecomesInactive
            Given: "OLD_TYPE" status=ACTIVE
            When:  deactivateType("OLD_TYPE")
            Then:  status=INACTIVE，後續 getConfig 拋出 BalanceTypeInactiveException
```

---

## Module 2：Account Management（F-010）

### 2.1 AccountService

```
TC-F010-01  createAccount_validInput_accountCreatedInStateMachine
            Given: accountId="CLIENT_ACC_001"，type=CLIENT，ownerId="CUST-001"
            When:  createAccount(...)
            Then:  State Machine 中帳戶存在，status=ACTIVE

TC-F010-02  createAccount_duplicateAccountId_throwsAccountAlreadyExistsException
            Given: "CLIENT_ACC_001" 已存在
            When:  createAccount(accountId="CLIENT_ACC_001")
            Then:  拋出 AccountAlreadyExistsException

TC-F010-03  createAccount_clientTypeWithoutOwnerId_throwsMissingOwnerIdException
            Given: type=CLIENT，ownerId=null
            When:  createAccount(...)
            Then:  拋出 MissingOwnerIdException

TC-F010-04  createAccount_withBalanceInitializations_balancesInitializedToZero
            Given: 初始化 AVAILABLE_BALANCE/USD + AVAILABLE_BALANCE/HKD
            When:  createAccount(...)
            Then:  balanceStore 中兩個 key 均存在，amount=0

TC-F010-05  createAccount_withUnknownBalanceType_throwsBalanceTypeNotFoundException
            Given: balanceInitializations 包含 "UNKNOWN_TYPE"
            When:  createAccount(...)
            Then:  拋出 BalanceTypeNotFoundException

TC-F010-06  freezeAccount_activeAccount_statusBecomeFrozen
            Given: "CLIENT_ACC_001" status=ACTIVE
            When:  freezeAccount("CLIENT_ACC_001")
            Then:  State Machine 中 status=FROZEN

TC-F010-07  unfreezeAccount_frozenAccount_statusBecomeActive
            Given: "CLIENT_ACC_001" status=FROZEN
            When:  unfreezeAccount("CLIENT_ACC_001")
            Then:  status=ACTIVE

TC-F010-08  closeAccount_withNonZeroBalance_throwsAccountHasNonZeroBalanceException
            Given: "CLIENT_ACC_001" AVAILABLE_BALANCE/USD = 100.00
            When:  closeAccount("CLIENT_ACC_001")
            Then:  拋出 AccountHasNonZeroBalanceException

TC-F010-09  closeAccount_withAllZeroBalances_statusBecomeClosed
            Given: 所有 balance = 0
            When:  closeAccount("CLIENT_ACC_001")
            Then:  status=CLOSED

TC-F010-10  closeAccount_closedAccount_cannotBeUnfrozen
            Given: status=CLOSED
            When:  unfreezeAccount("CLIENT_ACC_001")
            Then:  拋出 AccountClosedException

TC-F010-11  addBalanceType_existingAccount_newBalanceInitializedToZero
            Given: "CLIENT_ACC_001" 已存在，無 BROKERAGE_BALANCE/USD
            When:  addBalanceType("CLIENT_ACC_001", "BROKERAGE_BALANCE", "USD")
            Then:  balanceStore 新 key 存在，amount=0
```

---

## Module 3：State Machine（F-008）

### 3.1 LedgerStateMachine — Balance 操作

```
TC-F008-01  applyPosting_singleDebit_balanceDecreased
            Given: CLIENT_ACC_001 AVAILABLE_BALANCE/USD = 1000.00
            When:  apply PostingCommand{ DEBIT 300.00 }
            Then:  balance = 700.00，stateVersion 遞增

TC-F008-02  applyPosting_singleCredit_balanceIncreased
            Given: CLIENT_ACC_001 AVAILABLE_BALANCE/USD = 1000.00
            When:  apply PostingCommand{ CREDIT 500.00 }
            Then:  balance = 1500.00

TC-F008-03  applyPosting_debitExceedsBalance_allowNegativeFalse_commandRejected
            Given: balance = 100.00，allowNegative=false
            When:  apply PostingCommand{ DEBIT 200.00 }
            Then:  CommandResult.status=REJECTED，errorCode=INSUFFICIENT_BALANCE
                   balance 保持 100.00 不變

TC-F008-04  applyPosting_tradeAheadBalance_allowNegativeTrue_negativeBalanceAllowed
            Given: TRADE_AHEAD_BALANCE/USD = 0.00，allowNegative=true
            When:  apply PostingCommand{ DEBIT 50000.00 }
            Then:  balance = -50000.00，CommandResult.status=COMPLETED

TC-F008-05  applyPosting_tradeAheadBalance_creditAboveZero_commandRejected
            Given: TRADE_AHEAD_BALANCE/USD = -10000.00，allowNegative=true
            When:  apply PostingCommand{ CREDIT 20000.00 }（結果會 > 0）
            Then:  CommandResult.status=REJECTED，errorCode=CREDIT_EXCEEDS_LIMIT

TC-F008-06  applyPosting_multiAccount_atomicUpdate
            Given: CLIENT_ACC_001 USD = 1000.00，COMPANY_FX_ACC USD = 5000.00
            When:  apply PostingCommand{ CLIENT DEBIT 800 + COMPANY CREDIT 800 }
            Then:  CLIENT = 200.00，COMPANY = 5800.00，兩者同時更新

TC-F008-07  applyPosting_frozenAccount_commandRejected
            Given: CLIENT_ACC_001 status=FROZEN
            When:  apply PostingCommand{ DEBIT 100.00 }
            Then:  CommandResult.status=REJECTED，errorCode=ACCOUNT_FROZEN

TC-F008-08  applyPosting_idempotency_sameRequestId_returnsSameResult
            Given: requestId="req-001" 已成功執行，balance=700.00
            When:  再次 apply 相同 requestId
            Then:  返回原始 CommandResult，balance 仍為 700.00（不重複扣減）

TC-F008-09  applyPosting_journalUnbalanced_commandRejected
            Given: legs 中 DEBIT total ≠ CREDIT total
            When:  apply PostingCommand
            Then:  CommandResult.status=REJECTED，errorCode=JOURNAL_UNBALANCED

TC-F008-10  applyPosting_generateJournalLine_balanceBeforeAndAfterCorrect
            Given: CLIENT balance = 1000.00
            When:  apply DEBIT 300.00
            Then:  JournalLine.balanceBefore=1000.00，balanceBefore=700.00
```

### 3.2 LedgerStateMachine — Reversal

```
TC-F008-11  applyReversal_confirmedJournal_balanceReverted
            Given: 原 Journal DEBIT 300.00 已執行，balance=700.00
            When:  apply ReversalCommand{ originalJournalId }
            Then:  balance=1000.00，原 Journal status=REVERSED

TC-F008-12  applyReversal_alreadyReversedJournal_commandRejected
            Given: 原 Journal status=REVERSED
            When:  apply ReversalCommand
            Then:  CommandResult.status=REJECTED，errorCode=JOURNAL_ALREADY_REVERSED

TC-F008-13  applyReversal_reversalJournal_commandRejected
            Given: journalType=REVERSAL 的 Journal
            When:  apply ReversalCommand
            Then:  CommandResult.status=REJECTED，errorCode=CANNOT_REVERSE_REVERSAL

TC-F008-14  applyReversal_noBalanceCheck_executesEvenIfInsufficientBalance
            Given: 原 Journal CREDIT 1000.00，但帳戶餘額已被其他交易消耗為 0
            When:  apply ReversalCommand（DEBIT 1000.00 回去，餘額會變負）
            Then:  CommandResult.status=COMPLETED，balance=-1000.00（允許跌負）

TC-F008-15  applyReversal_crossPeriod_markedCorrectly
            Given: 原 Journal valueDate 在已關閉帳期
            When:  apply ReversalCommand
            Then:  Reversal Journal crossPeriod=true
```

### 3.3 LedgerStateMachine — Snapshot & Replay

```
TC-F008-16  takeSnapshot_allBalancesSerializedAndRestored
            Given: 5 個帳戶各有不同 balance
            When:  takeSnapshot() → 清空 State Machine → restoreFromSnapshot()
            Then:  所有 balance 完全一致，stateVersion 一致

TC-F008-17  replayFromLog_afterSnapshot_balanceCorrect
            Given: Snapshot at index=100，index 101-110 有 10 筆 PostingCommand
            When:  restore Snapshot + replay log 101-110
            Then:  最終 balance 等於直接執行 110 筆的結果

TC-F008-18  inactiveAccount_evictedFromMemory_reloadedFromRocksDB
            Given: 帳戶 24 小時無交易，被 evict
            When:  apply PostingCommand 到該帳戶
            Then:  從 RocksDB warm-up，balance 正確，繼續執行
```

### 3.4 LedgerStateMachine — accountSeq【v0.2 新增】

```
TC-F008-19  applyPosting_firstEver_accountSeqStartsAtOne
            Given: CLIENT_ACC_001 AVAILABLE_BALANCE/USD 尚未有任何交易（accountSeq 不存在）
            When:  apply PostingCommand{ DEBIT 100.00 }
            Then:  BalanceEntry.accountSeq == 1

TC-F008-20  applyPosting_subsequentPosting_accountSeqIncremented
            Given: CLIENT_ACC_001 AVAILABLE_BALANCE/USD accountSeq == 5
            When:  apply PostingCommand{ DEBIT 100.00 }
            Then:  BalanceEntry.accountSeq == 6

TC-F008-21  applyReversal_accountSeqIncremented
            Given: CLIENT_ACC_001 AVAILABLE_BALANCE/USD accountSeq == 10
            When:  apply ReversalCommand
            Then:  BalanceEntry.accountSeq == 11
                   （Reversal 同樣遞增 seq，因為是 balance 變動）

TC-F008-22  applyAdjustment_accountSeqIncremented
            Given: CLIENT_ACC_001 AVAILABLE_BALANCE/USD accountSeq == 20
            When:  apply AdjustmentCommand
            Then:  BalanceEntry.accountSeq == 21

TC-F008-23  applyPosting_differentBalanceType_seqIndependent
            Given: CLIENT_ACC_001 AVAILABLE_BALANCE/USD  accountSeq == 5
                   CLIENT_ACC_001 TRADE_AHEAD_BALANCE/USD accountSeq == 3
            When:  apply PostingCommand 同時更新兩個 balance type
            Then:  AVAILABLE_BALANCE/USD  accountSeq == 6
                   TRADE_AHEAD_BALANCE/USD accountSeq == 4
                   （不同 key 的 seq 互相獨立）

TC-F008-24  takeSnapshot_accountSeqSerializedAndRestored
            Given: CLIENT_ACC_001 AVAILABLE_BALANCE/USD accountSeq == 42
            When:  takeSnapshot() → restoreFromSnapshot()
            Then:  BalanceEntry.accountSeq == 42
                   （Snapshot 必須序列化 accountSeq，不得丟失）

TC-F008-25  replayFromLog_afterSnapshot_accountSeqContinues
            Given: Snapshot at index=100：accountSeq == 42
                   Raft Log index 101-105：5 條 PostingCommand
            When:  restore Snapshot → replay log 101-105
            Then:  accountSeq == 47（42 + 5）

TC-F008-26  restartNode_accountSeqResumesFromRocksDB
            Given: CLIENT_ACC_001 accountSeq == 99，已寫入 RocksDB CF_BALANCE
                   模擬 JVM crash（無 Snapshot）
            When:  重啟，從 Raft Log replay
            Then:  accountSeq 從 RocksDB 恢復，下一條事件 accountSeq == 100
                   不得重置為 0 或 1
```

### 3.5 LedgerStateMachine — Position 驗證規則 V-13【v0.3 新增】

```
TC-F008-27  applyPosting_lockedPositionDebitToNegative_rejected
            Given: AVAILABLE_BALANCE/USD/LOCKED = 50.00，allowNegative=false
            When:  apply PostingCommand{ position=LOCKED, DEBIT 100.00 }
            Then:  CommandResult.status=REJECTED，errorCode=INSUFFICIENT_BALANCE
                   balance 保持 50.00 不變
                   （驗證規則 V-13：LOCKED position 不能為負）

TC-F008-28  applyPosting_frozenPositionDebitToNegative_rejected
            Given: AVAILABLE_BALANCE/USD/FROZEN = 30.00，allowNegative=false
            When:  apply PostingCommand{ position=FROZEN, DEBIT 50.00 }
            Then:  CommandResult.status=REJECTED，errorCode=INSUFFICIENT_BALANCE
                   balance 保持 30.00 不變
                   （驗證規則 V-13：FROZEN position 不能為負）

TC-F008-29  applyPosting_currentPositionDebitToNegative_allowNegativeFalse_rejected
            Given: AVAILABLE_BALANCE/USD/CURRENT = 100.00，allowNegative=false
            When:  apply PostingCommand{ position=CURRENT, DEBIT 200.00 }
            Then:  CommandResult.status=REJECTED，errorCode=INSUFFICIENT_BALANCE
                   （CURRENT position 遵循 allowNegative 配置）

TC-F008-30  applyPosting_positionInJournalLine_correctlyRecorded
            Given: CLIENT_ACC_001 AVAILABLE_BALANCE/USD/CURRENT = 1000.00
            When:  apply PostingCommand{ position=LOCKED, DEBIT 100.00 }
            Then:  JournalLine.position=LOCKED
                   AccountBalanceKey = (CLIENT_ACC_001, AVAILABLE_BALANCE, USD)
                   對應 locked_amount 更新正確
                   balance 更新正確

TC-F008-31  applyPosting_accountNotFound_errorDetailsContainAccountId
            Given: accountId="GHOST_ACC" 不存在於 accountMetaStore
            When:  apply PostingCommand{ GHOST_ACC DEBIT 100 }
            Then:  CommandResult.status=REJECTED，errorCode=ACCOUNT_NOT_FOUND
                   CommandResult.errorDetails={"accountId":"GHOST_ACC"}

TC-F008-32  applyPosting_insufficientBalance_errorDetailsContainKeyFields
            Given: CLIENT_ACC_001 AVAILABLE_BALANCE/USD/CURRENT = 100.00，allowNegative=false
            When:  apply PostingCommand{ CLIENT_ACC_001 DEBIT 200 }
            Then:  CommandResult.status=REJECTED，errorCode=INSUFFICIENT_BALANCE
                   CommandResult.errorDetails 包含 accountId、balanceType、currency、position

TC-F008-33  applyReversal_journalNotFound_errorDetailsContainJournalId
            Given: originalJournalId="JNL-9999" 不存在
            When:  apply ReversalCommand{ originalJournalId=JNL-9999 }
            Then:  CommandResult.status=REJECTED，errorCode=JOURNAL_NOT_FOUND
                   CommandResult.errorDetails={"journalId":"JNL-9999"}

TC-F008-34  applyPosting_idempotency_rejectedRequestReturnsSameErrorDetails
            Given: req-001 第一次被拒絕（ACCOUNT_NOT_FOUND，accountId=GHOST_ACC）
            When:  第二次以相同 req-001 apply
            Then:  返回相同 CommandResult（含相同 errorDetails），不重新執行帳務邏輯

TC-F008-35  applyPosting_idempotency_afterRestart_replayedRejectedRequestReturnsSameErrorDetails
            Given: req-002 被拒絕並已寫入 RocksDB idempotency CF
            When:  重啟 State Machine（restoreFromSnapshot）後以相同 req-002 apply
            Then:  從 RocksDB 恢復的 idempotencyStore 命中，返回原 errorDetails
```

---

## Module 4：Posting API（F-002）

### 4.1 PostingService

```
TC-F002-01  post_validSingleLeg_returnsCompletedResult
            Given: 有效 PostingRequest，CLIENT DEBIT + COMPANY CREDIT，各 800 USD
            When:  post(request)
            Then:  PostingResult.status=COMPLETED，journalId 不為空

TC-F002-02  post_insufficientBalance_returnsRejectedResult
            Given: CLIENT balance=100，請求 DEBIT 500
            When:  post(request)
            Then:  PostingResult.status=REJECTED，errorCode=INSUFFICIENT_BALANCE

TC-F002-03  post_unknownAccount_returnsRejectedResult
            Given: accountId="GHOST_ACC" 不存在
            When:  post(request)
            Then:  PostingResult.status=REJECTED，errorCode=ACCOUNT_NOT_FOUND

TC-F002-04  post_sameRequestIdTwice_idempotentResult
            Given: requestId="req-abc" 第一次成功
            When:  第二次 post(相同 request)
            Then:  返回第一次相同的 PostingResult，balance 不重複變動

TC-F002-05  post_unbalancedJournal_returnsBadRequest
            Given: DEBIT 100 + CREDIT 99（不平衡）
            When:  post(request)
            Then:  HTTP 400 / errorCode=JOURNAL_UNBALANCED

TC-F002-06  post_rfqScenario_twoAccounts_atomicUpdate
            Given: CLIENT_ACC_001 USD=1000，COMPANY_FX_ACC USD=5000
            When:  post(CLIENT DEBIT 800 + COMPANY CREDIT 800)
            Then:  CLIENT=200，COMPANY=5800，Journal 包含 2 條 JournalLine

TC-F002-07  post_frozenAccount_returnsRejectedResult
            Given: CLIENT_ACC_001 status=FROZEN
            When:  post(request to CLIENT_ACC_001)
            Then:  PostingResult.status=REJECTED，errorCode=ACCOUNT_FROZEN

TC-F002-08  post_concurrentSameAccount_noDoubleDebit
            Given: CLIENT balance=1000，1000 並發請求各 DEBIT 1（總計 1000）
            When:  全部執行
            Then:  balance=0，Journal 精確 1000 筆，無重複

TC-F002-09  post_hotspotCompanyAccount_1000Concurrent_noDuplicate
            Given: COMPANY_FX_ACC，1000 並發不同 CLIENT CREDIT 進來
            When:  全部執行
            Then:  所有 Journal 唯一，COMPANY balance 精確等於所有 CREDIT 之和

TC-F002-10  post_inactiveBalanceType_returnsBadRequest
            Given: balanceType="OLD_TYPE" status=INACTIVE
            When:  post(request)
            Then:  HTTP 400 / errorCode=BALANCE_TYPE_NOT_FOUND

TC-F002-11  post_withPositionCurrent_defaultPositionUsed
            Given: PostingRequest.Line 未指定 position
            When:  post(request)
            Then:  JournalLine.position=CURRENT，對應 amount（CURRENT）欄位更新正確

TC-F002-12  post_withPositionLocked_lockedBalanceUpdated
            Given: CLIENT_ACC_001 AVAILABLE_BALANCE/USD/LOCKED = 500.00
            When:  post(request with position=LOCKED, DEBIT 100)
            Then:  LOCKED balance = 400.00，CURRENT balance 不變

TC-F002-13  post_lockedPositionDebitToNegative_returnsRejected
            Given: AVAILABLE_BALANCE/USD/LOCKED = 50.00，allowNegative=false
            When:  post(request with position=LOCKED, DEBIT 100)
            Then:  PostingResult.status=REJECTED，errorCode=INSUFFICIENT_BALANCE
                   （驗證規則 V-13：LOCKED position 不能為負）

TC-F002-14  post_frozenPositionDebitToNegative_returnsRejected
            Given: AVAILABLE_BALANCE/USD/FROZEN = 30.00，allowNegative=false
            When:  post(request with position=FROZEN, DEBIT 50)
            Then:  PostingResult.status=REJECTED，errorCode=INSUFFICIENT_BALANCE
                   （驗證規則 V-13：FROZEN position 不能為負）

TC-F002-15  post_multiPositionSameAccount_independentBalances
            Given: CLIENT_ACC_001 有 AVAILABLE_BALANCE/USD/CURRENT=1000，
                   AVAILABLE_BALANCE/USD/LOCKED=200，AVAILABLE_BALANCE/USD/FROZEN=50
            When:  post(同時 DEBIT CURRENT 100 + CREDIT LOCKED 50)
            Then:  CURRENT=900，LOCKED=250，FROZEN=50（三者獨立）

TC-F002-16  post_unknownAccount_errorDetailsContainAccountId
            Given: accountId="GHOST_ACC" 不存在
            When:  post(request to GHOST_ACC)
            Then:  HTTP 400，errorCode=ACCOUNT_NOT_FOUND
                   回應 body.errorDetails.accountId="GHOST_ACC"

TC-F002-17  post_frozenAccount_errorDetailsContainAccountId
            Given: CLIENT_ACC_001 status=FROZEN
            When:  post(request to CLIENT_ACC_001)
            Then:  HTTP 400，errorCode=ACCOUNT_FROZEN
                   回應 body.errorDetails.accountId="CLIENT_ACC_001"

TC-F002-18  post_insufficientBalance_errorDetailsContainAccountIdBalanceTypeCurrencyPosition
            Given: CLIENT_ACC_001 AVAILABLE_BALANCE/USD/CURRENT=100，allowNegative=false
            When:  post(DEBIT 200)
            Then:  HTTP 400，errorCode=INSUFFICIENT_BALANCE
                   回應 body.errorDetails 包含 accountId、balanceType、currency、position

TC-F002-19  post_journalUnbalanced_errorDetailsContainCurrencyAndMismatchAmounts
            Given: DEBIT 100 USD + CREDIT 99 USD
            When:  post(request)
            Then:  HTTP 400，errorCode=JOURNAL_UNBALANCED
                   回應 body.errorDetails 包含 currency、debitTotal、creditTotal

TC-F002-20  post_seedNotAllowedForClient_errorDetailsContainAccountId
            Given: CLIENT_ACC_001，單行 leg（seed posting）
            When:  post(single-line leg to CLIENT_ACC_001)
            Then:  HTTP 400，errorCode=SEED_NOT_ALLOWED_FOR_CLIENT
                   回應 body.errorDetails.accountId="CLIENT_ACC_001"
```

---

## Module 5：Reversal API（F-004）

```
TC-F004-01  reverse_confirmedJournal_reversalJournalCreated
            Given: 原 Journal status=CONFIRMED
            When:  reverse(originalJournalId, request)
            Then:  ReversalResult.status=COMPLETED，reversalJournalId 不為空
                   原 Journal status=REVERSED

TC-F004-02  reverse_alreadyReversedJournal_returnsRejected
            Given: 原 Journal status=REVERSED
            When:  reverse(originalJournalId)
            Then:  ReversalResult.status=REJECTED，errorCode=JOURNAL_ALREADY_REVERSED

TC-F004-03  reverse_reversalJournal_returnsRejected
            Given: journalType=REVERSAL
            When:  reverse(reversalJournalId)
            Then:  ReversalResult.status=REJECTED，errorCode=CANNOT_REVERSE_REVERSAL

TC-F004-04  reverse_sameRequestIdTwice_idempotent
            Given: requestId="rev-001" 第一次成功
            When:  第二次 reverse(相同 requestId)
            Then:  返回第一次相同的 ReversalResult，不重複 reverse

TC-F004-05  reverse_crossPeriodJournal_markedCrossPeriod
            Given: 原 Journal valueDate 在已關閉帳期
            When:  reverse(...)
            Then:  ReversalResult.crossPeriod=true

TC-F004-06  reverse_mirrorsOriginalLines_debitCreditSwapped
            Given: 原 Journal：CLIENT DEBIT 800 + COMPANY CREDIT 800
            When:  reverse(...)
            Then:  Reversal Journal：CLIENT CREDIT 800 + COMPANY DEBIT 800
                   金額完全相同，方向完全相反

TC-F004-07  reverse_insufficientBalance_stillExecutes
            Given: 原 Journal CREDIT 1000（CLIENT 收到錢），但 CLIENT 已轉走，balance=0
            When:  reverse（CLIENT DEBIT 1000 回去，balance 會跌負）
            Then:  ReversalResult.status=COMPLETED，balance=-1000（不做餘額校驗）

TC-F004-08  reverse_journalNotFound_errorDetailsContainOriginalJournalId
            Given: originalJournalId="JNL-9999" 不存在
            When:  reverse(originalJournalId=JNL-9999)
            Then:  ReversalResult.status=REJECTED，errorCode=JOURNAL_NOT_FOUND
                   回應 body.errorDetails.journalId="JNL-9999"

TC-F004-09  reverse_journalAlreadyReversed_errorDetailsContainOriginalJournalId
            Given: 原 Journal status=REVERSED
            When:  reverse(已 reversed journal)
            Then:  ReversalResult.status=REJECTED，errorCode=JOURNAL_ALREADY_REVERSED
                   回應 body.errorDetails.journalId=原 journalId
```

---

## Module 6：Manual Adjustment（F-003）

```
TC-F003-01  createDraft_validInput_draftCreatedWithPendingStatus
            Given: 有效 AdjustmentDraftRequest
            When:  createDraft(request)
            Then:  Draft 存在，status=PENDING_APPROVAL，未入帳

TC-F003-02  createDraft_unbalancedLegs_returnsBadRequest
            Given: DEBIT 100 + CREDIT 99
            When:  createDraft(request)
            Then:  HTTP 400 / errorCode=JOURNAL_UNBALANCED

TC-F003-03  approveDraft_validChecker_adjustmentPosted
            Given: Draft status=PENDING_APPROVAL，balance=1000
            When:  approveDraft(draftId, checkerId="checker-001")
            Then:  Draft status=EXECUTED，Journal 入帳，balance 變動

TC-F003-04  approveDraft_samePersonAsMaker_throwsMakerCheckerSamePersonException
            Given: makerId="ops-001"
            When:  approveDraft(draftId, checkerId="ops-001")
            Then:  拋出 MakerCheckerSamePersonException，Draft 不執行

TC-F003-05  approveDraft_expiredDraft_throwsDraftExpiredException
            Given: Draft expiresAt 已過期
            When:  approveDraft(draftId)
            Then:  拋出 DraftExpiredException

TC-F003-06  approveDraft_alreadyExecutedDraft_throwsDraftNotPendingException
            Given: Draft status=EXECUTED
            When:  approveDraft(draftId)
            Then:  拋出 DraftNotPendingException

TC-F003-07  rejectDraft_validChecker_draftStatusRejected
            Given: Draft status=PENDING_APPROVAL
            When:  rejectDraft(draftId, checkerId, rejectReason)
            Then:  Draft status=REJECTED，未入帳

TC-F003-08  approveDraft_idempotent_sameRequestIdTwice_notDoublePosted
            Given: approveRequestId="appr-001" 第一次成功入帳
            When:  第二次 approveDraft(相同 requestId)
            Then:  返回原結果，balance 不重複變動
```

---

## Module 7：Balance Query（F-005）

```
TC-F005-01  getBalance_activeAccount_returnsCurrentBalance
            Given: CLIENT_ACC_001 AVAILABLE_BALANCE/USD = 700.00（State Machine 最新值）
            When:  getBalance("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD")
            Then:  amount=700.00，dataSource=STATE_MACHINE

TC-F005-02  getBalance_afterPosting_immediatelyReflectsNewBalance
            Given: balance=1000.00，執行 DEBIT 300
            When:  立即 getBalance（同一請求周期）
            Then:  amount=700.00（無延遲，強一致）

TC-F005-03  getBalance_tradeAheadNegativeBalance_returnsNegativeValue
            Given: TRADE_AHEAD_BALANCE/USD = -45000.00
            When:  getBalance(...)
            Then:  amount=-45000.00，allowNegative=true

TC-F005-04  getBalance_unknownAccount_throwsAccountNotFoundException
            Given: accountId 不存在
            When:  getBalance(...)
            Then:  拋出 AccountNotFoundException

TC-F005-05  getBatchBalances_multipleAccounts_allReturnedCorrectly
            Given: 200 個帳戶各有不同 balance
            When:  getBatchBalances([200 個 key])
            Then:  返回 200 個正確 balance，dataSource=STATE_MACHINE

TC-F005-06  getAsOfBalance_historicalSnapshot_returnsSnapshotBalance
            Given: EOD Snapshot at 2026-05-15，CLIENT balance=500.00
                   今日 balance=700.00
            When:  getAsOfBalance("CLIENT_ACC_001", asOf="2026-05-15")
            Then:  amount=500.00，dataSource=EOD_SNAPSHOT

TC-F005-07  getBalance_withPosition_returnsPositionBalance
            Given: CLIENT_ACC_001 AVAILABLE_BALANCE/USD/CURRENT=1000，
                   AVAILABLE_BALANCE/USD/LOCKED=200
            When:  getBalance("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD", position=LOCKED)
            Then:  amount=200.00，position=LOCKED

TC-F005-08  getBalance_defaultPosition_returnsCurrentBalance
            Given: CLIENT_ACC_001 AVAILABLE_BALANCE/USD/CURRENT=1000，
                   AVAILABLE_BALANCE/USD/LOCKED=200
            When:  getBalance("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD")（未指定 position）
            Then:  amount=1000.00，position=CURRENT（默認）

TC-F005-09  getBalance_allPositions_returnsPositionsMap
            Given: CLIENT_ACC_001 AVAILABLE_BALANCE/USD 有 CURRENT=1000, LOCKED=200, FROZEN=50
            When:  getBalance("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD", includeAllPositions=true)
            Then:  BalanceQueryResult.positions = {CURRENT: 1000, LOCKED: 200, FROZEN: 50}

TC-F005-10  getBatchBalances_withPositionKeys_allReturnedCorrectly
            Given: 3 個帳戶各有 CURRENT/LOCKED/FROZEN position balance
            When:  getBatchBalances([3 個 AccountBalanceKey with different positions])
            Then:  返回 3 個正確 balance，每個包含 position 信息
```

---

## Module 8：Journal Query（F-006）

```
TC-F006-01  getJournal_existingJournalId_returnsJournalWithLines
            Given: JNL-001 已同步到 MySQL View Layer
            When:  getJournal("JNL-001")
            Then:  返回 Journal 及所有 JournalLine，dataSource=VIEW_LAYER

TC-F006-02  getJournalsByAccount_withFilters_returnsPagedResults
            Given: CLIENT_ACC_001 有 1250 筆 Journal
            When:  getJournals(accountId="CLIENT_ACC_001", page=0, size=50)
            Then:  返回 50 筆，totalCount=1250

TC-F006-03  getJournalsByBusinessEventRef_rfqId_returnsAllRelatedJournals
            Given: RFQ-001 有原始 Journal + Reversal Journal
            When:  getJournals(businessEventRef="RFQ-001")
            Then:  返回 2 筆，包含 NORMAL + REVERSAL 類型

TC-F006-04  getJournalChain_originalJournal_returnsFullChain
            Given: 原始 → Reversal → Rebook 三筆 Journal
            When:  getChain(originalJournalId)
            Then:  chain 包含 3 筆，關係正確標明

TC-F006-05  getJournalsByRequestId_confirmsIdempotency
            Given: requestId="req-abc" 對應 JNL-001
            When:  getJournals(requestId="req-abc")
            Then:  返回 JNL-001
```

---

## Module 9：Reconciliation（F-007）

```
TC-F007-01  runL1Reconciliation_allJournalsBalanced_noDiscrepancies
            Given: 100 筆 Journal，全部借貸平衡
            When:  runL1Reconciliation(date)
            Then:  Report.l1Summary.unbalancedJournals=0

TC-F007-02  runL1Reconciliation_unbalancedJournal_discrepancyDetected
            Given: 1 筆 Journal 被人為修改為不平衡（注入測試）
            When:  runL1Reconciliation(date)
            Then:  Report.l1Summary.unbalancedJournals=1，產生 Case

TC-F007-03  runL1Reconciliation_balanceMismatch_detectedAndCaseCreated
            Given: State Machine balance ≠ MySQL balance（注入不一致）
            When:  runL1Reconciliation(date)
            Then:  balanceConsistencyPassed=false，Case 產生

TC-F007-04  runL2Reconciliation_subAccountsSumMatchControl_noCases
            Given: 10 個 CLIENT 帳戶 USD 各 100，CONTROL_CLIENT_USD = 1000
            When:  runL2Reconciliation(rule="RFQ-USD-CONTROL")
            Then:  rulesPassed=1，無 Case

TC-F007-05  runL2Reconciliation_subAccountsSumMismatch_caseCreated
            Given: 10 個 CLIENT 帳戶 USD 各 100（共 1000），CONTROL = 999（差 1）
                   tolerance=0.01
            When:  runL2Reconciliation(rule)
            Then:  rulesFailed=1，Case 產生，discrepancyAmount=1.00

TC-F007-06  runL3Reconciliation_externalFileMatched_allMatched
            Given: 100 筆外部清算，100 筆內部 Journal，key 完全對應
            When:  runL3Reconciliation(externalFile)
            Then:  matched=100，internalOnly=0，externalOnly=0

TC-F007-07  runL3Reconciliation_internalOnly_caseCreated
            Given: 內部有 1 筆 Journal，外部清算文件沒有
            When:  runL3Reconciliation(externalFile)
            Then:  internalOnly=1，Case 產生，type=INTERNAL_ONLY

TC-F007-08  runL3Reconciliation_amountMismatch_caseCreated
            Given: 同一 externalRef，內部 800.00，外部 810.00
            When:  runL3Reconciliation(externalFile)
            Then:  amountMismatch=1，Case 產生，discrepancyAmount=10.00
```

---

## Module 10：Accounting Period / EOD（F-009）

```
TC-F009-01  closeperiod_openPeriod_eodTasksExecutedInOrder
            Given: 帳期 2026-05-16 status=OPEN
            When:  triggerEOD("2026-05-16")
            Then:  各 EOD 步驟按順序執行（Snapshot → Recon → Snapshot → Report → CLOSED）

TC-F009-02  postDuringClosing_returnsperiodClosedError
            Given: 帳期 status=CLOSING
            When:  post(PostingRequest)
            Then:  PostingResult.status=REJECTED，errorCode=PERIOD_CLOSED

TC-F009-03  postToClosedPeriod_returnsperiodClosedError
            Given: 帳期 status=CLOSED
            When:  post(PostingRequest，valueDate 在已關閉帳期)
            Then:  PostingResult.status=REJECTED，errorCode=PERIOD_CLOSED

TC-F009-04  reverseInClosedPeriod_allowedWithCrossPeriodFlag
            Given: 原 Journal valueDate 在 CLOSED 帳期
            When:  reverse(...)
            Then:  ReversalResult.status=COMPLETED，crossPeriod=true

TC-F009-05  eodBalanceSnapshot_matchesStateMachine
            Given: EOD 執行完成
            When:  比較 EOD Snapshot balance vs State Machine balance（同時間點）
            Then:  所有帳戶 balance 完全一致，差異為 0
```

---

## Module 11：RocksDB 持久性測試

```
TC-ROCKS-01  writeBatch_atomic_journalAndBalanceConsistentAfterCrash
             Given: 模擬寫 WriteBatch 中途 JVM crash（注入）
             When:  重啟後從 RocksDB 讀取
             Then:  journal 和 balance 要嘛都存在，要嘛都不存在（原子性）

TC-ROCKS-02  walReplay_afterCleanRestart_balanceRecovered
             Given: 執行 100 筆 PostingCommand，正常關閉
             When:  重啟，從 WAL + Snapshot replay
             Then:  所有 balance 與關閉前完全一致

TC-ROCKS-03  columnFamilyIsolation_writeToOneCF_notAffectOthers
             Given: 寫 CF_JOURNAL 一筆
             When:  讀 CF_BALANCE
             Then:  CF_BALANCE 不受影響，key 不衝突
```

### 11.1 BalanceChangeEvent — accountSeq + position【v0.2 新增，v0.3 更新】

```
TC-F011-01  publishEvent_firstPosting_accountSeqIsOne
            Given: CLIENT_ACC_001 AVAILABLE_BALANCE/USD/CURRENT 首次過帳
            When:  State Machine apply → publish BalanceChangeEvent (version 1.2)
            Then:  event.accountSeq == 1
                   event.prevAccountSeq == 0（代表無前序）
                   event.position == CURRENT

TC-F011-02  publishEvent_subsequentPosting_accountSeqIncremented
            Given: 上一條事件 accountSeq == 41
            When:  下一筆 Posting apply → publish
            Then:  event.accountSeq == 42
                   event.prevAccountSeq == 41

TC-F011-03  publishEvent_reversal_accountSeqIncremented
            Given: 上一條事件 accountSeq == 10
            When:  Reversal apply → publish
            Then:  event.accountSeq == 11
                   event.prevAccountSeq == 10

TC-F011-04  consumer_detectsGap_whenSeqNotConsecutive
            Given: Consumer 已收到 accountSeq == 100
            When:  下一條收到的事件 accountSeq == 102（prevAccountSeq == 101）
                   但 101 從未到達
            Then:  Consumer 判定 gap（prevAccountSeq 102 ≠ 上一條 accountSeq 100 + 1），觸發告警

TC-F011-05  consumer_noDuplicateAlert_whenIdempotentRetry
            Given: Outbox at-least-once 重發同一條事件
                   event.accountSeq == 50，event.idempotencyKey 相同
            When:  Consumer 收到重複事件
            Then:  Consumer 按 idempotencyKey 去重，不誤判為 gap
                   （重複事件 accountSeq 相同，不是新的 seq）

TC-F011-06  restartNode_outboxResend_accountSeqUnchanged
            Given: State Machine apply 時 accountSeq == 77，寫入 Outbox
                   節點重啟，Outbox 重發
            When:  Consumer 收到重發事件
            Then:  event.accountSeq 仍為 77，與原始發送一致
                   （seq 在 apply 時已確定，Outbox 重發不改變值）

TC-F011-07  multiBalanceType_samePosting_seqIndependentPerKey
            Given: 一筆 RFQ Posting 同時更新：
                   CLIENT_ACC_001 AVAILABLE_BALANCE/USD/CURRENT（上一條 seq=10）
                   CLIENT_ACC_001 TRADE_AHEAD_BALANCE/USD/CURRENT（上一條 seq=5）
            When:  publish 兩個 BalanceChangeEvent
            Then:  AVAILABLE_BALANCE event.accountSeq == 11
                   TRADE_AHEAD_BALANCE event.accountSeq == 6
                   （兩個 key 的 seq 各自遞增，互不影響）

TC-F011-08  publishEvent_withPosition_eventContainsPosition【v0.3 新增】
            Given: CLIENT_ACC_001 AVAILABLE_BALANCE/USD/LOCKED = 500.00
            When:  Posting apply with position=LOCKED → publish
            Then:  event.position == LOCKED
                   event.accountBalanceKey == (CLIENT_ACC_001, AVAILABLE_BALANCE, USD, LOCKED)

TC-F011-09  multiPosition_sameAccount_independentSeqPerPosition【v0.3 新增】
            Given: CLIENT_ACC_001 AVAILABLE_BALANCE/USD 有：
                   CURRENT position accountSeq == 10
                   LOCKED position accountSeq == 5
            When:  同一筆 Posting 分別更新 CURRENT + LOCKED
            Then:  CURRENT event.accountSeq == 11
                   LOCKED event.accountSeq == 6
                   （不同 position 的 seq 各自遞增，互不影響）

TC-F011-10  kafkaPublisher_callback_deletesOutbox_onSuccess【v0.11 新增】
            Given: CF_OUTBOX 中有一筆殘留事件（模擬 crash 後重啟）
            When:  KafkaEventPublisher.onEvent(event) 發送成功，flush() 等待 callback
            Then:  outboxStore.readPending() 返回空（callback 已調用 markSent）

TC-F011-11  asyncOutboxPublisher_doesNotCallMarkSentDirectly【v0.11 新增】
            Given: CF_OUTBOX 中有一筆事件，使用 FakePublisher（不觸發 callback）
            When:  AsyncOutboxPublisher 掃描並調用 kafkaPublisher.onEvent(event)
            Then:  事件仍保留在 CF_OUTBOX（AsyncOutboxPublisher 未直接調用 markSent）

TC-F011-12  hotPath_callback_deletesOutboxImmediately【v0.11 新增】
            Given: StateMachine 熱路徑發送，KafkaEventPublisher 已設置 outboxStore
            When:  applyPosting → onEvent(event) → enqueue → flush → publisher.flush()
            Then:  outboxStore.readPending() 返回空（熱路徑 callback 即時清理）

TC-F011-13  applyPosting_oneEnvelopePerJournal【v0.16 新增】
            Given: 4-line posting，EmitGate enabled，eventListener 為 EnvelopeCountingListener
            When:  applyPosting(4-line cmd)
            Then:  envelopes.get() == 1；events.get() == 0（不再 per-line 呼叫）
                  envelope.type == "JOURNAL"；envelope.journalId 以 "JNL-" 開頭
                  envelope.events().size() == 4

TC-F011-14  applyPosting_gateClosed_zeroEmission【v0.16 新增】
            Given: 2-line posting，EmitGate 關閉（follower / init）
            When:  applyPosting
            Then:  envelopes.get() == 0；events.get() == 0
                  CF_OUTBOX 也未新增 entry（persistApply 跳過 outbox 寫入）

TC-F011-15  envelopeJsonRoundTrip【v0.16 新增】
            Given: JournalEventEnvelope{ type="JOURNAL", journalId="JNL-T-1", events=[e1, e2] }
            When:  Jackson 序列化 → 反序列化
            Then:  兩次 type / journalId / events 內容完全一致
                  JSON 包含 "type":"JOURNAL" 字串

TC-F011-16  persistApply_writesOneEnvelopeEntry【v0.16 新增】
            Given: 2-line posting，RocksDB + outbox 開啟，EmitGate enabled
            When:  applyPosting + persistApply
            Then:  outboxStore.readPendingJournals(100) 返回 1 個 envelope
                  envelope.events() 包含 2 個 BalanceChangeEvent
                  CF_OUTBOX 中對應的 key 為 "outbox:journal:<journalId>"

TC-F011-17  asyncOutboxPublisher_publishesEnvelope【v0.16 新增】
            Given: CF_OUTBOX 中有一筆 envelope entry，EmitGate enabled
            When:  AsyncOutboxPublisher.scanAndPublish() 觸發
            Then:  KafkaEventPublisher.onPosting 被呼叫 1 次
                  KafkaEventPublisher.onEvent 沒有被呼叫（不再 per-event fallback）
```

---

## Module 12：性能 / 負載測試（NFR）

```
TC-NFR-01  posting_p95_under3ms_normalLoad
           Given: 500 並發，各自不同帳戶
           When:  執行 10,000 次 Posting
           Then:  P95 ≤ 3ms

TC-NFR-02  posting_hotspotAccount_p95_under3ms
           Given: 1000 並發，全部打 COMPANY_FX_ACC
           When:  執行 10,000 次 Posting
           Then:  P95 ≤ 3ms，無重複入帳，balance 精確

TC-NFR-03  balanceQuery_p95_under2ms
           Given: 10,000 QPS Balance Query
           When:  連續查詢 Active 帳戶 balance
           Then:  P95 ≤ 2ms

TC-NFR-04  idempotency_1000Retries_onlyOneJournalCreated
           Given: 相同 requestId
           When:  1000 次並發重試
           Then:  只有 1 筆 Journal 存在

TC-NFR-05  concurrentPosting_noNegativeBalance_noDoubleDebit
           Given: CLIENT balance=1000，1001 並發 DEBIT 1
           When:  全部執行
           Then:  1000 筆成功，1 筆 INSUFFICIENT_BALANCE，balance=0，無負數穿透
```

---

## Module 13：Account Queue（ADR-001 v0.2）

```
TC-QUEUE-01  singleAccount_serialization_processedInOrder
             Given: CLIENT_ACC_001 balance=10000, 50 concurrent DEBIT 1 via AccountQueueManager
             When:  全部請求完成
             Then:  balance=9950，所有請求串行處理，無並發衝突

TC-QUEUE-02  backpressure_exceedingMaxQueueSize_returnsFalse
             Given: MAX_QUEUE_SIZE=1000
             When:  提交 > 1000 請求到同一帳戶
             Then:  超過部分返回 false（背壓），queue depth ≤ 1000

TC-QUEUE-03  multipleAccounts_independentQueues
             Given: ACC_A 和 ACC_B 各有獨立 queue
             When:  同時提交 100 CREDIT 到兩個帳戶
             Then:  兩者獨立並行處理，無錯誤

TC-QUEUE-04  submitAsync_completesFutureWithResult_onSuccess
             Given: AccountQueueManager 配置正常 commandHandler（返回 CommandResult.completed）
             When:  調用 submitAsync(accountId, cmd).get()
             Then:  Future 正常完成，返回 COMPLETED CommandResult，journalId 正確

TC-QUEUE-05  submitAsync_completesFutureExceptionally_onHandlerError
             Given: commandHandler 拋出 RuntimeException
             When:  調用 submitAsync(accountId, cmd).get()
             Then:  Future 以 ExecutionException 完成，cause 為原始異常
```

---

## Module 14：Account Queue + Outbox Integration

```
TC-QUEUE-OBX-01  concurrentRequests_produceCorrectEvents
                 Given: CLIENT_ACC_001 + COMPANY_FX_ACC，20 並發 Posting（每筆 2 條 JournalLine）
                 When:  Account Queue serialized → StateMachine apply → Event publish
                 Then:  40 個 BalanceChangeEvent，CLIENT 端 accountSeq 嚴格遞增

TC-QUEUE-OBX-02  hotspotAccount_concurrentCredits_allSucceed
                 Given: COMPANY_FX_ACC hotspot，50 並發 CREDIT 各 10.00
                 When:  Account Queue 串行處理 COMPANY 帳戶
                 Then:  COMPANY balance = 100000 + 500 = 100500，100 個事件，無重複
```

---

## Module 15：SOFAJRaft Cluster

```
TC-RAFT-01  threeNodeCluster_leaderElectionAndLogReplication
            Given: 3 個 RaftNodeManager on 127.0.0.1:18081/2/3
            When:  start → wait leader → submit PostingCommand
            Then:  leader elected in < 1s，journal replicated to all 3 nodes

TC-RAFT-02  cluster_survivesFollowerRestart
            Given: 3-node cluster with elected leader
            When:  follower 關閉後重啟
            Then:  follower 重新加入 cluster，無錯誤
```

### 15.1 Apache Ratis Engine（ADR-003 — pluggable ConsensusEngine）

```
TC-RAFT-RATIS-01  posting_committedThroughRatisLog_mutatesBalance
                  Given: single-node embedded Ratis cluster wrapping LedgerStateMachine,
                         CLIENT_ACC_001 AVAILABLE_BALANCE=1000.00
                  When:  RatisNodeManager.submit(PostingCommand DEBIT 300)
                  Then:  result COMPLETED，balance=700.00，lastAppliedIndex > 0
                  (impl: RatisEngineIntegrationTest — passing, no docker)

TC-RAFT-RATIS-02  duplicateRequestId_isIdempotent
                  Given: same single-node Ratis cluster
                  When:  submit same PostingCommand (requestId) twice
                  Then:  both COMPLETED，balance debited once only (idempotencyStore hit)

TC-RAFT-RATIS-03  snapshotTakenAndReloaded_stateRestored  (harness-ready)
                  Given: Ratis node with N applied commands
                  When:  takeSnapshot() → restart → loadSnapshot via SimpleStateMachineStorage
                  Then:  balances + accountSeq restored from snapshot, no log-replay gap

TC-RAFT-RATIS-04  engineSelectable_viaConsensusEngineProperty
                  Given: CONSENSUS_ENGINE=ratis|jraft
                  When:  Spring context boot (LedgerConfig.raftNodeManager)
                  Then:  ConsensusEngine bean is RatisNodeManager | RaftNodeManager respectively

TC-NFR-OOM-01     leaderOOM_zeroCommittedLoss_cleanRecovery  (DR harness — scripts/oom-dr-test.sh)
                  Given: heap-throttled 3-node cluster (docker-compose.oom.yml), per engine
                  When:  sustained load drives leader OOM → ExitOnOutOfMemoryError → restart
                  Then:  every API-acked journalId still queryable (lost=0)，leader re-elected，
                         seeded-account balances identical across all 3 nodes (divergence=0)
```

---

## Module 16：Kafka Event Publishing（F-011/F-011b）

```
TC-KAFKA-01  posting_publishesBalanceChangeEvent_toKafka
             Given: Kafka broker (Testcontainers), CLIENT_ACC_001 balance=1000
             When:  StateMachine.applyPosting DEBIT 100 → KafkaEventPublisher flush
             Then:  Kafka consumer receives 1+ record with BALANCE_CHANGE, accountSeq field

TC-KAFKA-02  multiplePostings_produceSequentialAccountSeq
             Given: Kafka broker, CLIENT_ACC_001
             When:  5 sequential DEBIT 10 postings → publish to Kafka
             Then:  Kafka consumer receives 5 records with monotonically increasing accountSeq
```

---

## Module 17：Idempotency & Hotspot Account Concurrency（F-013）

### 17.1 Idempotency

```
TC-F013-01  duplicateRequestId_returnsCachedResult_noReExecution
            Given: Posting req-001 completed, journalId=JNL-001
            When:  重複發送相同 req-001
            Then:  返回原結果（journalId=JNL-001），State Machine 不重新 apply

TC-F013-02  duplicateRequestId_rejected_returnsOriginalErrors
            Given: Posting req-002 rejected（INSUFFICIENT_BALANCE）
            When:  重複發送 req-002
            Then:  返回原錯誤碼（INSUFFICIENT_BALANCE），不做餘額校驗

TC-F013-03  idempotencySurvivesLeaderFailover
            Given: Leader 完成 req-003 apply → idempotencyStore.put → Leader 宕機
            When:  新 Leader 選出後，Client 重試 req-003
            Then:  從 RocksDB 恢復的 idempotencyStore 命中 → 返回原結果

TC-F013-04  idempotencyEntry_expiredAfterTTL_treatedAsNewRequest
            Given: idempotencyStore TTL=1s，req-004 completed → 等待 2s
            When:  重試 req-004
            Then:  idempotencyStore 已淘汰 → 視為新請求，重新執行帳務變動

TC-F013-05  concurrentDuplicateRequestIds_onlyOneExecuted
            Given: 兩個线程同時發送 req-005
            When:  同時到達 Account Queue
            Then:  只生成 1 筆 Journal，另一個返回快取結果
```

### 17.2 Hotspot Account Concurrency

```
TC-F013-06  hotspotAccount_1000ConcurrentPostings_noBalanceInconsistency
            Given: COMPANY_FX_ACC balance=1,000,000，1000 並發 DEBIT 1
            When:  1000 並發 Posting（不同 CLIENT_ACC → 同一 COMPANY_FX_ACC）
            Then:  最終 COMPANY_FX_ACC balance=0，無重複扣款，無負數

TC-F013-07  hotspotAccount_1000ConcurrentPostings_p95Under3ms
            Given: COMPANY_FX_ACC，1000 並發 RFQ Posting
            When:  測量 Posting P95 延遲
            Then:  P95 ≤ 3ms

TC-F013-08  accountQueueDepthExceedsMaxSize_returnsQueueFull
            Given: MAX_QUEUE_SIZE=10，queue("COMPANY_FX_ACC") 已有 10 個 pending
            When:  第 11 個請求到達
            Then:  返回 HTTP 429 QUEUE_FULL

TC-F013-09  multiAccountCoordination_deadlockPrevention_orderedTokenAcquisition
            Given: RFQ 涉及 CLIENT_ACC_001 + COMPANY_FX_ACC
            When:  兩個 RFQ 同時執行，反向帳戶順序
            Then:  無死鎖，兩者均成功（按 accountId 升序取 Token）

TC-F013-10  virtualThreadWorkerCrash_queueRecoversAndResumesConsumption
            Given: Account Queue Worker-3（COMPANY_FX_ACC）運行中
            When:  模擬 Worker thread 中斷/崩潰
            Then:  系統自動重建 Worker-3，queue 中 pending 請求繼續被消費

TC-F013-11  duplicateRejectedRequest_returnsSameErrorDetails
            Given: Posting req-001 rejected（ACCOUNT_NOT_FOUND，accountId=GHOST_ACC）
            When:  重複發送 req-001
            Then:  返回原錯誤碼與相同 errorDetails，不做餘額校驗

TC-F013-12  idempotencySurvivesRestart_errorDetailsPreserved
            Given: req-002 rejected 並已寫入 RocksDB idempotency CF（含 errorDetails）
            When:  重啟後重送 req-002
            Then:  從 RocksDB 恢復的 idempotencyStore 命中 → 返回相同 errorDetails
```


---

## Module 18：Java Client SDK（F-014）

### 18.1 Leader Discovery

```
TC-F014-01  leaderDiscovery_firstCall_queriesAnyEndpoint
            Given: SDK 初始化，無快取 Leader Hint，集群有 3 個端點
            When:  第一次呼叫 client.post(postingRequest)
            Then:  SDK 從任一端點查詢 GET /raft/leader
                   取得 leaderEndpoint 後正確路由 Posting 請求
                   請求成功返回 PostingResult

TC-F014-02  leaderDiscovery_cachedLeader_reusesHint
            Given: Leader Hint 已快取，TTL 未過期
            When:  連續 100 次呼叫 client.post()
            Then:  全部使用快取的 leaderEndpoint
                   不重複查詢 /raft/leader（查詢次數 = 0）

TC-F014-03  leaderDiscovery_leaderStepDown_refreshesHint
            Given: Leader Hint 指向 node-1，快取未過期
            When:  發送 Posting → node-1 返回 HTTP 503 NOT_LEADER
            Then:  SDK 標記快取失效
                   從剩餘端點查詢 /raft/leader 取得新 Leader
                   使用新 Leader endpoint 重試成功
```

### 18.2 Retry Strategy

```
TC-F014-04  retry_idempotentPost_failoverRetriesAndSucceeds
            Given: SDK maxRetries=3，Leader 在第一次請求後切換
            When:  client.post(postingRequest)
                  第 1 次發送 → HTTP 503（NOT_LEADER）
            Then:  SDK 刷新 Leader Hint
                   第 2 次重試（相同 requestId）→ HTTP 200
                   返回 PostingResult，retriesAttempted=1
                   Server 端僅收到 1 筆 Posting（冪等保證）

TC-F014-05  retry_nonIdempotentRead_failoverWithoutRetryBody
            Given: SDK 用於 queryBalance()，Leader 切換中
            When:  client.queryBalance(queryRequest)
                  第 1 次發送 → Connection Refused
            Then:  SDK 刷新 Leader Hint
                   第 2 次重試 GET 請求（無 request body，天然安全）
                   返回 BalanceQueryResult
                   不消耗 maxRetries 計數（讀取不限重試次數）
```

### 18.3 Configuration

```
TC-F014-06  config_allTimeoutsSettable
            Given: LedgerClientConfig.builder()
                   .connectTimeout(Duration.ofMillis(500))
                   .readTimeout(Duration.ofMillis(3000))
                   .leaderCacheTtl(Duration.ofSeconds(10))
                   .maxRetries(5)
            When:  config.build()
            Then:  config.connectTimeout == 500ms
                   config.readTimeout == 3000ms
                   config.leaderCacheTtl == 10s
                   config.maxRetries == 5
```

### 18.4 API Correctness

```
TC-F014-07  queryBalance_returnsCorrectBalance
            Given: CLIENT_ACC_001 AVAILABLE_BALANCE/USD = 700.00（State Machine 最新值）
                   SDK 已初始化並發現 Leader
            When:  client.queryBalance(new BalanceQueryRequest("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD"))
            Then:  BalanceQueryResult.amount == 700.00
                   dataSource == "STATE_MACHINE"
                   結果與直接 HTTP GET /ledger/accounts/CLIENT_ACC_001/balances 一致

TC-F014-08  post_unbalancedJournal_propagatesError
            Given: PostingRequest 中 DEBIT 100 + CREDIT 99（不平衡）
            When:  client.post(unbalancedRequest)
            Then:  拋出 LedgerClientException
                   errorCode == "JOURNAL_UNBALANCED"
                   httpStatusCode == 400
                   retriesAttempted == 0（業務錯誤不重試）
```

---

## Module 19：Projection MySQL View Layer v2（F-012 v2）

> 本模組涵蓋 projection MySQL schema v2 重構資料表之 idempotency、accountSeq guard、surrogate FK chain 及 Kafka 重播冪等測試。

### 19.1 ProjectionEventLog — Idempotency Guard

```
TC-PROJ-01  insertEvent_newEvent_succeeds
            Given: projection_event_log 為空，accountSeq=5 for ACC_001/AVAILABLE/USD
            When:  insertEvent("ACC_001", "AVAILABLE_BALANCE", "USD", 5, "JLL-001", "JNL-001", "evt-001", "APPLIED")
            Then:  INSERT 成功，status = "APPLIED"

TC-PROJ-02  insertEvent_duplicateEvent_throwsDuplicateKey
            Given: projection_event_log 已存在 (ACC_001, AVAILABLE, USD, seq=5)
            When:  insertEvent("ACC_001", "AVAILABLE_BALANCE", "USD", 5, "JLL-001", "JNL-001", "evt-001", "APPLIED")
            Then:  DuplicateKeyException（UK uk_event_seq 拒絕重複），不產生第二行
```

### 19.2 AccountBalance — accountSeq Guard

```
TC-PROJ-03  upsertBalance_higherSeq_appliesBalance
            Given: account_balance 存在 (ACC_001, AVAILABLE, USD, seq=3, amount=100.00)
            When:  upsertBalance(accountPk, "ACC_001", "AVAILABLE", "USD", 50.00, "CURRENT", seq=4, "JNL-002")
            Then:  amount = 50.00, account_seq = 4, last_journal_id = "JNL-002"

TC-PROJ-04  upsertBalance_lowerSeq_preservesExistingData
            Given: account_balance 存在 (ACC_001, AVAILABLE, USD, seq=5, amount=200.00)
            When:  upsertBalance(accountPk, "ACC_001", "AVAILABLE", "USD", 999.00, "CURRENT", seq=3, "JNL-stale")
            Then:  amount = 200.00（未被覆蓋）, account_seq = 5（未被遞減）

TC-PROJ-05  upsertBalance_sameSeq_idempotentNoChange
            Given: account_balance 存在 (ACC_001, AVAILABLE, USD, seq=5, amount=200.00)
            When:  upsertBalance(accountPk, "ACC_001", "AVAILABLE", "USD", 200.00, "CURRENT", seq=5, "JNL-001")
            Then:  amount = 200.00（不變）, account_seq = 5（不變）
```

### 19.3 ProjectionConsumer — Full Idempotent Flow

```
TC-PROJ-06  onBalanceChange_newEvent_fullFlowSucceeds
            Given: MySQL 乾淨，Kafka 事件 accountSeq=1 for ACC_001/AVAILABLE/USD
            When:  ProjectionConsumer.onBalanceChange(event)
            Then:  account 行存在、journal 行存在（journal_id UNIQUE OK）
                   account_balance 行存在（amount=postBalance, seq=1）
                   journal_line 行存在（journal_fk/account_fk/account_balance_fk 三個 surrogate FK 正確）
                   projection_event_log 行存在（status=APPLIED）

TC-PROJ-07  onBalanceChange_duplicateEvent_skippedWithNoSideEffect
            Given: TC-PROJ-06 已成功處理 seq=1
            When:  相同事件再次送達（相同 accountSeq=1）
            Then:  projection_event_log insert → DuplicateKeyException
                   account_balance 數據不變
                   journal_line 數據不變
                   無 exception 拋出至上層（graceful skip）

TC-PROJ-08  onBalanceChange_staleSeq_eventLoggedAsSkippedStale
            Given: account_balance 已更新至 seq=10（via prior events）
            When:  送達 seq=5 的事件（模擬 Kafka out-of-order）
            Then:  account_balance applyBalanceChange 返回 0 rows（seq guard）
                   projection_event_log 寫入 status="SKIPPED_STALE"
                   account_balance 數據不變（仍為 seq=10 的值）
```

### 19.4 Surrogate FK Chain Consistency

```
TC-PROJ-09  journalLine_insert_validSurrogateChain
            Given: journal id=100, account id=200, account_balance id=300
            When:  insertJournalLine(surrogate FKs: journalId=100, accountId=200, accountBalanceId=300,
                   business keys: journalJournalId="JNL-001", accountAccountId="ACC_001")
            Then:  journal_line 行成功插入
                   journal_journal_id = "JNL-001"（denormalized for direct query）
                   account_account_id = "ACC_001"（denormalized for direct query）
                   所有三個 surrogate FK JOIN 回正確父行

TC-PROJ-10  journalLine_missingSurrogateLogicallyAllowed
            Given: journal id=999（不存在於 journal 表，因無 FK constraint）
            When:  insertJournalLine(journalId=999, accountId=200, accountBalanceId=300, ...)
            Then:  INSERT 成功（MySQL 無 FK 檢查）
                   查詢時 LEFT JOIN journal 返回 NULL 欄位（資料完整但不影響 insert）
```

### 19.5 Projection Deadlock Safety

```
TC-PROJ-11  journalLineBatch_rowsSortedByAccountThenLineId
            Given: 一個 poll batch 包含 PendingRow：ACC-Z/JL-002、ACC-A/JL-003、ACC-A/JL-001、ACC-Z/JL-001
            When:  JournalFlushBuffer.toJournalLineRows(rows)
            Then:  返回結果按 (accountAccountId 升序, journalLineId 升序) 排列
                   → ACC-A/JL-001、ACC-A/JL-003、ACC-Z/JL-001、ACC-Z/JL-002

TC-PROJ-12  journalLineBatch_deadlockRetriesBeforeFailing
            Given: batch insert 前兩次拋出 MySQLTransactionRollbackException SQLState=40001，第三次成功
            When:  JournalFlushBuffer.flushRowsDirect(batch)
            Then:  最多重試 3 次，最終成功寫入；Kafka offset 不受影響
```

---

## Module 20：Config Abstraction（F-015）

### 20.1 ConfigService 介面與 SpringConfigService 實作

**前置條件**：ledger-restful 模組 clean compile 成功

**測試內容**：驗證 ConfigService 介面完整、SpringConfigService 正確讀取 @Value、LedgerConfig 所有 System.getenv() 替換為 ConfigService 注入

| ID | 測試內容 | Given | When | Then |
|----|---------|-------|------|------|
| TC-F015-01 | ConfigService 介面四個方法簽名正確 | — | 讀取 ConfigService.class | 介面包含 get(String,String)、getInt(String,int)、getLong(String,long)、getBool(String,boolean) |
| TC-F015-02 | SpringConfigService 正確讀取 @Value 含 yml 預設值 | application.yml 含 `ledger.rocksdb.path: /custom/path` | new SpringConfigService() → get("ledger.rocksdb.path", "default") | 返回 "/custom/path" |
| TC-F015-03 | SpringConfigService fallback 到 @Value 預設值 | application.yml 不含 `kafka.bootstrap.servers` | get("kafka.bootstrap.servers", "fallback") | 返回 "fallback" |
| TC-F015-04 | LedgerConfig 所有 System.getenv().getOrDefault() 已替換 | LedgerConfig.java | 全文 grep "System.getenv()" | 0 結果 |
| TC-F015-05 | LedgerConfig 注入 ConfigService | LedgerConfig 類 | @Autowired(required=false) ConfigService | Spring 容器成功啟動，ConfigService 不為 null |

---

## Module 21：Micrometer Metrics & Observability（F-016）

### 21.1 Metrics 暴露與標籤正確性

**前置條件**：ledger-restful 與 ledger-projection 模組啟動成功，Micrometer Prometheus Registry 已註冊

**測試內容**：驗證所有 `ledger.` 前綴指標正確暴露於 `/actuator/prometheus`，標籤值域符合規格

| ID | 測試內容 | Given | When | Then |
|----|---------|-------|------|------|
| TC-F016-01 | `/actuator/prometheus` 返回全部 `ledger.` 前綴指標 | 應用啟動完成 | GET `/actuator/prometheus` | 回應包含 ledger.posting.duration、ledger.balance.query.duration、ledger.outbox.pending、ledger.raft.is_leader、ledger.account.queue.depth、ledger.projection.seconds.since.last.event 等 |
| TC-F016-02 | `ledger.posting.duration` 含 outcome + businessEventType 兩個 tag | 提交一筆 RFQ_SETTLEMENT Posting | GET `/actuator/prometheus` | 找到 `ledger_posting_duration_seconds_count{outcome="COMPLETED",businessEventType="RFQ_SETTLEMENT"}` |
| TC-F016-03 | `ledger.balance.query.duration` 含 queryType (live/live-position/asof/batch) | 執行四種查詢 | GET `/actuator/prometheus` | 找到四種 queryType 對應的 `ledger_balance_query_duration_seconds_count` |
| TC-F016-04 | hotspot account gauges 預設註冊 4 個帳戶 | 應用啟動完成 | GET `/actuator/prometheus` | 找到 `ledger_account_queue_depth{accountId="STRESS-HOT-CO-001"}`、`COMPANY_FX_ACC`、`NOSTRO_USD`、`SUSPENSE_USD` |
| TC-F016-05 | Projection gauges 在 projection 節點獨立暴露 | ledger-projection 啟動 | GET `:8089/actuator/prometheus` | 回應包含 `ledger_projection_seconds_since_last_event`、`ledger_projection_events_processed`、`ledger_projection_balance_queue_depth`、`ledger_projection_balance_writes` |
| TC-F016-06 | Alert Rule `posting-latency-high` 在 P95 > 3ms 持續 2min 觸發 | 注入高延遲 Posting（sleep 100ms）持續 2min | Prometheus evaluation | Alert 狀態為 `firing`，severity=critical |
| TC-F016-07 | Alert Rule `raft-leader-election` 在 leader 切換時立即觸發 | 強制 kill Leader 節點 | Prometheus evaluation | Alert 狀態為 `firing`，severity=critical |
| TC-F016-08 | Histogram bucket 含 `le="0.003"` 供 P95 SLO 計算 | 應用啟動完成 | GET `/actuator/prometheus` | 找到 `ledger_posting_duration_seconds_bucket{le="0.003"}` |
| TC-F016-09 | `ledger.outbox.pending` 在 Outbox 積壓時 > 0 | 停止 Kafka consumer，持續寫入 Posting | GET `/actuator/prometheus` | `ledger_outbox_pending` > 0 |
| TC-F016-10 | `ledger.reconciliation.l1.unbalanced.total` 在 L1 發現不平衡時 +1 | 製造一筆借貸不平衡 Journal | 執行 L1 Reconciliation | `ledger_reconciliation_l1_unbalanced_total` 增加 1 |

---

## 測試執行順序建議（TDD Red-Green 順序）

```
Phase 1 — 基礎模型
  TC-F001-* → TC-F010-* → TC-F008-01~10（Balance 操作）

Phase 2 — 核心寫路徑
  TC-F002-01~07（Posting 基本）
  TC-F008-11~15（Reversal State Machine）
  TC-F004-01~07（Reversal API）
  TC-F003-01~08（Manual Adjustment）

Phase 2.5 — Position 支持【v0.3 新增】
  TC-F002-11~15（Posting position）
  TC-F008-27~30（State Machine position 驗證規則 V-13）
  TC-F005-07~10（Balance Query position）

Phase 3 — 讀路徑
  TC-F005-*（Balance Query）
  TC-F006-*（Journal Query）

Phase 3.5 — accountSeq【v0.2 新增】
  TC-F008-19~26（State Machine accountSeq）
  TC-F011-01~12（BalanceChangeEvent accountSeq + position + outbox callback deletion）
  TC-F011-13~17（JournalEventEnvelope 1-record-per-journal，v0.16 新增）

Phase 4 — 對帳與帳期
  TC-F007-*（Reconciliation）
  TC-F009-*（Accounting Period）

Phase 5 — 持久性與性能
  TC-ROCKS-*（RocksDB 持久性）
  TC-NFR-*（性能 / 負載）

Phase 6 — 並發安全
  TC-F002-08~09（並發 Posting）
  TC-NFR-04~05（冪等 + 並發）

Phase 6.5 — Idempotency & Hotspot Account【v0.4 新增】
  TC-F013-01~10（Idempotency + Hotspot Concurrency）

Phase 7 — Java Client SDK【v0.5 新增】
  TC-F014-01~08（Leader Discovery + Retry + Config + API Correctness）

Phase 8 — Projection MySQL View Layer v2【v0.6 新增】
  TC-PROJ-01~02（projection_event_log idempotency）
  TC-PROJ-03~05（account_balance accountSeq guard）
  TC-PROJ-06~08（ProjectionConsumer full idempotent flow）
  TC-PROJ-09~10（surrogate FK chain — consistency + no-FK tolerance）
  TC-PROJ-11~12（deadlock-safe batch ordering + bounded retry）

Phase 9 — Config Abstraction【v0.10 新增】
  TC-F015-01~05（ConfigService + SpringConfigService + LedgerConfig refactor）

Phase 10 — Micrometer Metrics & Observability【v0.11 新增】
  TC-F016-01~10（Metrics expose + tag correctness + alert firing + histogram bucket + projection gauges）

Phase 11 — SnowflakeIdGenerator Clock Drift Tolerance【v0.13 新增】
  TC-SNOW-01~04（monotonic ID + sequence rollover + ≤5ms backward wait + >5ms backward reject）

---

## Module 22：SnowflakeIdGenerator 時鐘回退容錯

### 22.1 Monotonic ID Generation
- **TC-SNOW-01**：連續調用 nextId() 產生嚴格遞增 ID
  - Given：`workerId=1`，時鐘正常遞增
  - When：連續呼叫 `nextId()` 三次
  - Then：`id1 < id2 < id3`

### 22.2 Sequence Rollover Within Same Millisecond
- **TC-SNOW-02**：同一毫秒內序列號耗盡後自動進位到下一毫秒
  - Given：`workerId=1`，固定毫秒戳 `T`
  - When：連續產生 5000 個 ID（超過 4096 序列上限）
  - Then：所有 ID 嚴格遞增，無重複，無死鎖

### 22.3 Small Backwards Clock Drift Tolerance（≤5 ms）
- **TC-SNOW-03**：NTP 微調導致時鐘回退 ≤5ms 時等待而非拋錯
  - Given：`lastTimestamp = T`，模擬時鐘回退 3ms
  - When：呼叫 `nextId()`
  - Then：Busy-wait 直到時鐘回到 T，成功產生 `id > lastId`，不拋例外

### 22.4 Large Backwards Clock Drift Rejection（>5 ms）
- **TC-SNOW-04**：時鐘回退超過 5ms 仍應拒絕產生 ID
  - Given：`lastTimestamp = T`，模擬時鐘回退 10ms
  - When：呼叫 `nextId()`
  - Then：拋出 `IllegalStateException`，訊息包含 "Clock moved backwards"
```
