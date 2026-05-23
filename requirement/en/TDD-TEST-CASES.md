# TDD Test Cases — Next-Gen Internal Ledger Platform

**Version**: v0.4
**Date**: 2026-05-23
**Method**: Test-Driven Development (Red → Green → Refactor)
**Framework**: JUnit 5 + Mockito + AssertJ + Testcontainers (MySQL) + RocksDB embedded

> **v0.4 Change Summary**: Added Module 17 (F-013 Idempotency & Hotspot Account Concurrency), added TC-F013-01~10. TDD execution plan supplemented with Phase 6.5.
>
> **v0.3 Change Summary**: Added position field support (AccountBalanceKey composite key extension). Added TC-F002-11~15 (Posting position), TC-F005-07~10 (Balance Query position), TC-F008-27~30 (State Machine position validation rule V-13). Updated existing test cases to reflect new key format (accountId, balanceType, currency, position).
>
> **v0.2 Change Summary**: Added Module 3 Section 3.4 (TC-F008-19 ~ TC-F008-26, accountSeq State Machine), Module 11 Section 11.1 (TC-F011-01 ~ TC-F011-07, BalanceChangeEvent accountSeq), TDD execution plan supplemented with Phase 3.5.

---

## Test Layering Strategy

```
┌─────────────────────────────────────┐
│         E2E / Integration Tests      │  Few, covering key business flows
├─────────────────────────────────────┤
│         Service / UseCase Tests      │  Medium, covering business rules
├─────────────────────────────────────┤
│         Unit Tests                   │  Many, covering all core logic
└─────────────────────────────────────┘
```

Principles:
- Unit Test coverage target: ≥ 90%
- Each test case tests only one thing
- Test name format: `[methodName]_[scenario]_[expectedResult]`
- All tests must run in CI environment without external dependencies (embedded RocksDB / Testcontainers)

---

## Module 1: Balance Type Registry (F-001)

### 1.1 BalanceTypeConfigService

```
TC-F001-01  getConfig_existingActiveType_returnsConfig
            Given: AVAILABLE_BALANCE already in Registry, status=ACTIVE
            When:  getConfig("AVAILABLE_BALANCE")
            Then:  returns correct config, allowNegative=false

TC-F001-02  getConfig_nonExistentType_throwsBalanceTypeNotFoundException
            Given: "UNKNOWN_TYPE" not in Registry
            When:  getConfig("UNKNOWN_TYPE")
            Then:  throws BalanceTypeNotFoundException

TC-F001-03  getConfig_inactiveType_throwsBalanceTypeInactiveException
            Given: "OLD_TYPE" status=INACTIVE
            When:  getConfig("OLD_TYPE")
            Then:  throws BalanceTypeInactiveException

TC-F001-04  registerType_newType_successfullyRegistered
            Given: "BROKERAGE_BALANCE" does not exist
            When:  registerType(BalanceTypeConfig{code="BROKERAGE_BALANCE", allowNegative=false})
            Then:  type exists in Registry, status=ACTIVE, configVersion=1

TC-F001-05  registerType_duplicateCode_throwsDuplicateBalanceTypeException
            Given: "AVAILABLE_BALANCE" already exists
            When:  registerType(BalanceTypeConfig{code="AVAILABLE_BALANCE"})
            Then:  throws DuplicateBalanceTypeException

TC-F001-06  registerType_tradeAheadBalance_allowNegativeTrue_signConventionNormalDebit
            Given: new type TRADE_AHEAD_BALANCE, allowNegative=true, negativeSemantics=PRE_AUTHORIZED
            When:  registerType(...)
            Then:  config.allowNegative=true, config.negativeSemantics=PRE_AUTHORIZED

TC-F001-07  updateConfig_existingType_configVersionIncremented
            Given: "AVAILABLE_BALANCE" configVersion=1
            When:  updateConfig("AVAILABLE_BALANCE", newConfig)
            Then:  configVersion=2, change record saved

TC-F001-08  deactivateType_existingType_statusBecomesInactive
            Given: "OLD_TYPE" status=ACTIVE
            When:  deactivateType("OLD_TYPE")
            Then:  status=INACTIVE, subsequent getConfig throws BalanceTypeInactiveException
```

---

## Module 2: Account Management (F-010)

### 2.1 AccountService

```
TC-F010-01  createAccount_validInput_accountCreatedInStateMachine
            Given: accountId="CLIENT_ACC_001", type=CLIENT, ownerId="CUST-001"
            When:  createAccount(...)
            Then:  account exists in State Machine, status=ACTIVE

TC-F010-02  createAccount_duplicateAccountId_throwsAccountAlreadyExistsException
            Given: "CLIENT_ACC_001" already exists
            When:  createAccount(accountId="CLIENT_ACC_001")
            Then:  throws AccountAlreadyExistsException

TC-F010-03  createAccount_clientTypeWithoutOwnerId_throwsMissingOwnerIdException
            Given: type=CLIENT, ownerId=null
            When:  createAccount(...)
            Then:  throws MissingOwnerIdException

TC-F010-04  createAccount_withBalanceInitializations_balancesInitializedToZero
            Given: initialize AVAILABLE_BALANCE/USD + AVAILABLE_BALANCE/HKD
            When:  createAccount(...)
            Then:  both keys exist in balanceStore, amount=0

TC-F010-05  createAccount_withUnknownBalanceType_throwsBalanceTypeNotFoundException
            Given: balanceInitializations contains "UNKNOWN_TYPE"
            When:  createAccount(...)
            Then:  throws BalanceTypeNotFoundException

TC-F010-06  freezeAccount_activeAccount_statusBecomeFrozen
            Given: "CLIENT_ACC_001" status=ACTIVE
            When:  freezeAccount("CLIENT_ACC_001")
            Then:  status=FROZEN in State Machine

TC-F010-07  unfreezeAccount_frozenAccount_statusBecomeActive
            Given: "CLIENT_ACC_001" status=FROZEN
            When:  unfreezeAccount("CLIENT_ACC_001")
            Then:  status=ACTIVE

TC-F010-08  closeAccount_withNonZeroBalance_throwsAccountHasNonZeroBalanceException
            Given: "CLIENT_ACC_001" AVAILABLE_BALANCE/USD = 100.00
            When:  closeAccount("CLIENT_ACC_001")
            Then:  throws AccountHasNonZeroBalanceException

TC-F010-09  closeAccount_withAllZeroBalances_statusBecomeClosed
            Given: all balances = 0
            When:  closeAccount("CLIENT_ACC_001")
            Then:  status=CLOSED

TC-F010-10  closeAccount_closedAccount_cannotBeUnfrozen
            Given: status=CLOSED
            When:  unfreezeAccount("CLIENT_ACC_001")
            Then:  throws AccountClosedException

TC-F010-11  addBalanceType_existingAccount_newBalanceInitializedToZero
            Given: "CLIENT_ACC_001" exists, no BROKERAGE_BALANCE/USD
            When:  addBalanceType("CLIENT_ACC_001", "BROKERAGE_BALANCE", "USD")
            Then:  new key exists in balanceStore, amount=0
```

---

## Module 3: State Machine (F-008)

### 3.1 LedgerStateMachine — Balance Operations

```
TC-F008-01  applyPosting_singleDebit_balanceDecreased
            Given: CLIENT_ACC_001 AVAILABLE_BALANCE/USD = 1000.00
            When:  apply PostingCommand{ DEBIT 300.00 }
            Then:  balance = 700.00, stateVersion incremented

TC-F008-02  applyPosting_singleCredit_balanceIncreased
            Given: CLIENT_ACC_001 AVAILABLE_BALANCE/USD = 1000.00
            When:  apply PostingCommand{ CREDIT 500.00 }
            Then:  balance = 1500.00

TC-F008-03  applyPosting_debitExceedsBalance_allowNegativeFalse_commandRejected
            Given: balance = 100.00, allowNegative=false
            When:  apply PostingCommand{ DEBIT 200.00 }
            Then:  CommandResult.status=REJECTED, errorCode=INSUFFICIENT_BALANCE
                   balance remains 100.00 unchanged

TC-F008-04  applyPosting_tradeAheadBalance_allowNegativeTrue_negativeBalanceAllowed
            Given: TRADE_AHEAD_BALANCE/USD = 0.00, allowNegative=true
            When:  apply PostingCommand{ DEBIT 50000.00 }
            Then:  balance = -50000.00, CommandResult.status=COMPLETED

TC-F008-05  applyPosting_tradeAheadBalance_creditAboveZero_commandRejected
            Given: TRADE_AHEAD_BALANCE/USD = -10000.00, allowNegative=true
            When:  apply PostingCommand{ CREDIT 20000.00 } (result would be > 0)
            Then:  CommandResult.status=REJECTED, errorCode=CREDIT_EXCEEDS_LIMIT

TC-F008-06  applyPosting_multiAccount_atomicUpdate
            Given: CLIENT_ACC_001 USD = 1000.00, COMPANY_FX_ACC USD = 5000.00
            When:  apply PostingCommand{ CLIENT DEBIT 800 + COMPANY CREDIT 800 }
            Then:  CLIENT = 200.00, COMPANY = 5800.00, both updated simultaneously

TC-F008-07  applyPosting_frozenAccount_commandRejected
            Given: CLIENT_ACC_001 status=FROZEN
            When:  apply PostingCommand{ DEBIT 100.00 }
            Then:  CommandResult.status=REJECTED, errorCode=ACCOUNT_FROZEN

TC-F008-08  applyPosting_idempotency_sameRequestId_returnsSameResult
            Given: requestId="req-001" already successfully executed, balance=700.00
            When:  apply same requestId again
            Then:  returns original CommandResult, balance remains 700.00 (no duplicate deduction)

TC-F008-09  applyPosting_journalUnbalanced_commandRejected
            Given: legs total DEBIT ≠ total CREDIT
            When:  apply PostingCommand
            Then:  CommandResult.status=REJECTED, errorCode=JOURNAL_UNBALANCED

TC-F008-10  applyPosting_generateJournalLine_balanceBeforeAndAfterCorrect
            Given: CLIENT balance = 1000.00
            When:  apply DEBIT 300.00
            Then:  JournalLine.balanceBefore=1000.00, balanceAfter=700.00
```

### 3.2 LedgerStateMachine — Reversal

```
TC-F008-11  applyReversal_confirmedJournal_balanceReverted
            Given: original Journal DEBIT 300.00 already executed, balance=700.00
            When:  apply ReversalCommand{ originalJournalId }
            Then:  balance=1000.00, original Journal status=REVERSED

TC-F008-12  applyReversal_alreadyReversedJournal_commandRejected
            Given: original Journal status=REVERSED
            When:  apply ReversalCommand
            Then:  CommandResult.status=REJECTED, errorCode=JOURNAL_ALREADY_REVERSED

TC-F008-13  applyReversal_reversalJournal_commandRejected
            Given: journalType=REVERSAL Journal
            When:  apply ReversalCommand
            Then:  CommandResult.status=REJECTED, errorCode=CANNOT_REVERSE_REVERSAL

TC-F008-14  applyReversal_noBalanceCheck_executesEvenIfInsufficientBalance
            Given: original Journal CREDIT 1000.00, but account balance has been consumed to 0 by other transactions
            When:  apply ReversalCommand (DEBIT 1000.00 back, balance would go negative)
            Then:  CommandResult.status=COMPLETED, balance=-1000.00 (negative allowed)

TC-F008-15  applyReversal_crossPeriod_markedCorrectly
            Given: original Journal valueDate in closed accounting period
            When:  apply ReversalCommand
            Then:  Reversal Journal crossPeriod=true
```

### 3.3 LedgerStateMachine — Snapshot & Replay

```
TC-F008-16  takeSnapshot_allBalancesSerializedAndRestored
            Given: 5 accounts with different balances
            When:  takeSnapshot() → clear State Machine → restoreFromSnapshot()
            Then:  all balances completely consistent, stateVersion consistent

TC-F008-17  replayFromLog_afterSnapshot_balanceCorrect
            Given: Snapshot at index=100, index 101-110 has 10 PostingCommands
            When:  restore Snapshot + replay log 101-110
            Then:  final balance equals result of directly executing 110 entries

TC-F008-18  inactiveAccount_evictedFromMemory_reloadedFromRocksDB
            Given: account evicted after 24 hours without transactions
            When:  apply PostingCommand to that account
            Then:  warm-up from RocksDB, balance correct, continue execution
```

### 3.4 LedgerStateMachine — accountSeq [v0.2 New]

```
TC-F008-19  applyPosting_firstEver_accountSeqStartsAtOne
            Given: CLIENT_ACC_001 AVAILABLE_BALANCE/USD has no transactions yet (accountSeq does not exist)
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
                   (Reversal also increments seq, because it is a balance change)

TC-F008-22  applyAdjustment_accountSeqIncremented
            Given: CLIENT_ACC_001 AVAILABLE_BALANCE/USD accountSeq == 20
            When:  apply AdjustmentCommand
            Then:  BalanceEntry.accountSeq == 21

TC-F008-23  applyPosting_differentBalanceType_seqIndependent
            Given: CLIENT_ACC_001 AVAILABLE_BALANCE/USD  accountSeq == 5
                   CLIENT_ACC_001 TRADE_AHEAD_BALANCE/USD accountSeq == 3
            When:  apply PostingCommand updating both balance types simultaneously
            Then:  AVAILABLE_BALANCE/USD  accountSeq == 6
                   TRADE_AHEAD_BALANCE/USD accountSeq == 4
                   (seq for different keys are independent)

TC-F008-24  takeSnapshot_accountSeqSerializedAndRestored
            Given: CLIENT_ACC_001 AVAILABLE_BALANCE/USD accountSeq == 42
            When:  takeSnapshot() → restoreFromSnapshot()
            Then:  BalanceEntry.accountSeq == 42
                   (Snapshot must serialize accountSeq, must not lose it)

TC-F008-25  replayFromLog_afterSnapshot_accountSeqContinues
            Given: Snapshot at index=100: accountSeq == 42
                   Raft Log index 101-105: 5 PostingCommands
            When:  restore Snapshot → replay log 101-105
            Then:  accountSeq == 47 (42 + 5)

TC-F008-26  restartNode_accountSeqResumesFromRocksDB
            Given: CLIENT_ACC_001 accountSeq == 99, already written to RocksDB CF_BALANCE
                   simulate JVM crash (no Snapshot)
            When:  restart, replay from Raft Log
            Then:  accountSeq recovered from RocksDB, next event accountSeq == 100
                   must not reset to 0 or 1
```

### 3.5 LedgerStateMachine — Position Validation Rule V-13 [v0.3 New]

```
TC-F008-27  applyPosting_lockedPositionDebitToNegative_rejected
            Given: AVAILABLE_BALANCE/USD/LOCKED = 50.00, allowNegative=false
            When:  apply PostingCommand{ position=LOCKED, DEBIT 100.00 }
            Then:  CommandResult.status=REJECTED, errorCode=INSUFFICIENT_BALANCE
                   balance remains 50.00 unchanged
                   (Validation rule V-13: LOCKED position cannot be negative)

TC-F008-28  applyPosting_frozenPositionDebitToNegative_rejected
            Given: AVAILABLE_BALANCE/USD/FROZEN = 30.00, allowNegative=false
            When:  apply PostingCommand{ position=FROZEN, DEBIT 50.00 }
            Then:  CommandResult.status=REJECTED, errorCode=INSUFFICIENT_BALANCE
                   balance remains 30.00 unchanged
                   (Validation rule V-13: FROZEN position cannot be negative)

TC-F008-29  applyPosting_currentPositionDebitToNegative_allowNegativeFalse_rejected
            Given: AVAILABLE_BALANCE/USD/CURRENT = 100.00, allowNegative=false
            When:  apply PostingCommand{ position=CURRENT, DEBIT 200.00 }
            Then:  CommandResult.status=REJECTED, errorCode=INSUFFICIENT_BALANCE
                   (CURRENT position follows allowNegative config)

TC-F008-30  applyPosting_positionInJournalLine_correctlyRecorded
            Given: CLIENT_ACC_001 AVAILABLE_BALANCE/USD/CURRENT = 1000.00
            When:  apply PostingCommand{ position=LOCKED, DEBIT 100.00 }
            Then:  JournalLine.position=LOCKED
                   AccountBalanceKey = (CLIENT_ACC_001, AVAILABLE_BALANCE, USD, LOCKED)
                   balance updated correctly
```

---

## Module 4: Posting API (F-002)

### 4.1 PostingService

```
TC-F002-01  post_validSingleLeg_returnsCompletedResult
            Given: valid PostingRequest, CLIENT DEBIT + COMPANY CREDIT, each 800 USD
            When:  post(request)
            Then:  PostingResult.status=COMPLETED, journalId not empty

TC-F002-02  post_insufficientBalance_returnsRejectedResult
            Given: CLIENT balance=100, request DEBIT 500
            When:  post(request)
            Then:  PostingResult.status=REJECTED, errorCode=INSUFFICIENT_BALANCE

TC-F002-03  post_unknownAccount_returnsRejectedResult
            Given: accountId="GHOST_ACC" does not exist
            When:  post(request)
            Then:  PostingResult.status=REJECTED, errorCode=ACCOUNT_NOT_FOUND

TC-F002-04  post_sameRequestIdTwice_idempotentResult
            Given: requestId="req-abc" first attempt succeeded
            When:  second post(same request)
            Then:  returns same PostingResult as first, balance not changed again

TC-F002-05  post_unbalancedJournal_returnsBadRequest
            Given: DEBIT 100 + CREDIT 99 (unbalanced)
            When:  post(request)
            Then:  HTTP 400 / errorCode=JOURNAL_UNBALANCED

TC-F002-06  post_rfqScenario_twoAccounts_atomicUpdate
            Given: CLIENT_ACC_001 USD=1000, COMPANY_FX_ACC USD=5000
            When:  post(CLIENT DEBIT 800 + COMPANY CREDIT 800)
            Then:  CLIENT=200, COMPANY=5800, Journal contains 2 JournalLines

TC-F002-07  post_frozenAccount_returnsRejectedResult
            Given: CLIENT_ACC_001 status=FROZEN
            When:  post(request to CLIENT_ACC_001)
            Then:  PostingResult.status=REJECTED, errorCode=ACCOUNT_FROZEN

TC-F002-08  post_concurrentSameAccount_noDoubleDebit
            Given: CLIENT balance=1000, 1000 concurrent requests each DEBIT 1 (total 1000)
            When:  all executed
            Then:  balance=0, exactly 1000 Journals, no duplicates

TC-F002-09  post_hotspotCompanyAccount_1000Concurrent_noDuplicate
            Given: COMPANY_FX_ACC, 1000 concurrent different CLIENT CREDIT incoming
            When:  all executed
            Then:  all Journals unique, COMPANY balance exactly equals sum of all CREDITs

TC-F002-10  post_inactiveBalanceType_returnsBadRequest
            Given: balanceType="OLD_TYPE" status=INACTIVE
            When:  post(request)
            Then:  HTTP 400 / errorCode=BALANCE_TYPE_NOT_FOUND

TC-F002-11  post_withPositionCurrent_defaultPositionUsed
            Given: PostingRequest.Line does not specify position
            When:  post(request)
            Then:  JournalLine.position=CURRENT, AccountBalanceKey uses position=CURRENT

TC-F002-12  post_withPositionLocked_lockedBalanceUpdated
            Given: CLIENT_ACC_001 AVAILABLE_BALANCE/USD/LOCKED = 500.00
            When:  post(request with position=LOCKED, DEBIT 100)
            Then:  LOCKED balance = 400.00, CURRENT balance unchanged

TC-F002-13  post_lockedPositionDebitToNegative_returnsRejected
            Given: AVAILABLE_BALANCE/USD/LOCKED = 50.00, allowNegative=false
            When:  post(request with position=LOCKED, DEBIT 100)
            Then:  PostingResult.status=REJECTED, errorCode=INSUFFICIENT_BALANCE
                   (Validation rule V-13: LOCKED position cannot be negative)

TC-F002-14  post_frozenPositionDebitToNegative_returnsRejected
            Given: AVAILABLE_BALANCE/USD/FROZEN = 30.00, allowNegative=false
            When:  post(request with position=FROZEN, DEBIT 50)
            Then:  PostingResult.status=REJECTED, errorCode=INSUFFICIENT_BALANCE
                   (Validation rule V-13: FROZEN position cannot be negative)

TC-F002-15  post_multiPositionSameAccount_independentBalances
            Given: CLIENT_ACC_001 has AVAILABLE_BALANCE/USD/CURRENT=1000,
                   AVAILABLE_BALANCE/USD/LOCKED=200, AVAILABLE_BALANCE/USD/FROZEN=50
            When:  post(simultaneously DEBIT CURRENT 100 + CREDIT LOCKED 50)
            Then:  CURRENT=900, LOCKED=250, FROZEN=50 (all independent)
```

---

## Module 5: Reversal API (F-004)

```
TC-F004-01  reverse_confirmedJournal_reversalJournalCreated
            Given: original Journal status=CONFIRMED
            When:  reverse(originalJournalId, request)
            Then:  ReversalResult.status=COMPLETED, reversalJournalId not empty
                   original Journal status=REVERSED

TC-F004-02  reverse_alreadyReversedJournal_returnsRejected
            Given: original Journal status=REVERSED
            When:  reverse(originalJournalId)
            Then:  ReversalResult.status=REJECTED, errorCode=JOURNAL_ALREADY_REVERSED

TC-F004-03  reverse_reversalJournal_returnsRejected
            Given: journalType=REVERSAL
            When:  reverse(reversalJournalId)
            Then:  ReversalResult.status=REJECTED, errorCode=CANNOT_REVERSE_REVERSAL

TC-F004-04  reverse_sameRequestIdTwice_idempotent
            Given: requestId="rev-001" first attempt succeeded
            When:  second reverse(same requestId)
            Then:  returns same ReversalResult as first, no duplicate reversal

TC-F004-05  reverse_crossPeriodJournal_markedCrossPeriod
            Given: original Journal valueDate in closed accounting period
            When:  reverse(...)
            Then:  ReversalResult.crossPeriod=true

TC-F004-06  reverse_mirrorsOriginalLines_debitCreditSwapped
            Given: original Journal: CLIENT DEBIT 800 + COMPANY CREDIT 800
            When:  reverse(...)
            Then:  Reversal Journal: CLIENT CREDIT 800 + COMPANY DEBIT 800
                   amounts exactly the same, directions completely opposite

TC-F004-07  reverse_insufficientBalance_stillExecutes
            Given: original Journal CREDIT 1000 (CLIENT received money), but CLIENT already transferred out, balance=0
            When:  reverse (CLIENT DEBIT 1000 back, balance would go negative)
            Then:  ReversalResult.status=COMPLETED, balance=-1000 (no balance check)
```

---

## Module 6: Manual Adjustment (F-003)

```
TC-F003-01  createDraft_validInput_draftCreatedWithPendingStatus
            Given: valid AdjustmentDraftRequest
            When:  createDraft(request)
            Then:  Draft exists, status=PENDING_APPROVAL, not posted

TC-F003-02  createDraft_unbalancedLegs_returnsBadRequest
            Given: DEBIT 100 + CREDIT 99
            When:  createDraft(request)
            Then:  HTTP 400 / errorCode=JOURNAL_UNBALANCED

TC-F003-03  approveDraft_validChecker_adjustmentPosted
            Given: Draft status=PENDING_APPROVAL, balance=1000
            When:  approveDraft(draftId, checkerId="checker-001")
            Then:  Draft status=EXECUTED, Journal posted, balance changed

TC-F003-04  approveDraft_samePersonAsMaker_throwsMakerCheckerSamePersonException
            Given: makerId="ops-001"
            When:  approveDraft(draftId, checkerId="ops-001")
            Then:  throws MakerCheckerSamePersonException, Draft not executed

TC-F003-05  approveDraft_expiredDraft_throwsDraftExpiredException
            Given: Draft expiresAt already expired
            When:  approveDraft(draftId)
            Then:  throws DraftExpiredException

TC-F003-06  approveDraft_alreadyExecutedDraft_throwsDraftNotPendingException
            Given: Draft status=EXECUTED
            When:  approveDraft(draftId)
            Then:  throws DraftNotPendingException

TC-F003-07  rejectDraft_validChecker_draftStatusRejected
            Given: Draft status=PENDING_APPROVAL
            When:  rejectDraft(draftId, checkerId, rejectReason)
            Then:  Draft status=REJECTED, not posted

TC-F003-08  approveDraft_idempotent_sameRequestIdTwice_notDoublePosted
            Given: approveRequestId="appr-001" first attempt succeeded and posted
            When:  second approveDraft(same requestId)
            Then:  returns original result, balance not changed again
```

---

## Module 7: Balance Query (F-005)

```
TC-F005-01  getBalance_activeAccount_returnsCurrentBalance
            Given: CLIENT_ACC_001 AVAILABLE_BALANCE/USD = 700.00 (latest State Machine value)
            When:  getBalance("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD")
            Then:  amount=700.00, dataSource=STATE_MACHINE

TC-F005-02  getBalance_afterPosting_immediatelyReflectsNewBalance
            Given: balance=1000.00, execute DEBIT 300
            When:  immediately getBalance (same request cycle)
            Then:  amount=700.00 (no delay, strongly consistent)

TC-F005-03  getBalance_tradeAheadNegativeBalance_returnsNegativeValue
            Given: TRADE_AHEAD_BALANCE/USD = -45000.00
            When:  getBalance(...)
            Then:  amount=-45000.00, allowNegative=true

TC-F005-04  getBalance_unknownAccount_throwsAccountNotFoundException
            Given: accountId does not exist
            When:  getBalance(...)
            Then:  throws AccountNotFoundException

TC-F005-05  getBatchBalances_multipleAccounts_allReturnedCorrectly
            Given: 200 accounts with different balances
            When:  getBatchBalances([200 keys])
            Then:  returns 200 correct balances, dataSource=STATE_MACHINE

TC-F005-06  getAsOfBalance_historicalSnapshot_returnsSnapshotBalance
            Given: EOD Snapshot at 2026-05-15, CLIENT balance=500.00
                   today balance=700.00
            When:  getAsOfBalance("CLIENT_ACC_001", asOf="2026-05-15")
            Then:  amount=500.00, dataSource=EOD_SNAPSHOT

TC-F005-07  getBalance_withPosition_returnsPositionBalance
            Given: CLIENT_ACC_001 AVAILABLE_BALANCE/USD/CURRENT=1000,
                   AVAILABLE_BALANCE/USD/LOCKED=200
            When:  getBalance("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD", position=LOCKED)
            Then:  amount=200.00, position=LOCKED

TC-F005-08  getBalance_defaultPosition_returnsCurrentBalance
            Given: CLIENT_ACC_001 AVAILABLE_BALANCE/USD/CURRENT=1000,
                   AVAILABLE_BALANCE/USD/LOCKED=200
            When:  getBalance("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD") (position not specified)
            Then:  amount=1000.00, position=CURRENT (default)

TC-F005-09  getBalance_allPositions_returnsPositionsMap
            Given: CLIENT_ACC_001 AVAILABLE_BALANCE/USD has CURRENT=1000, LOCKED=200, FROZEN=50
            When:  getBalance("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD", includeAllPositions=true)
            Then:  BalanceQueryResult.positions = {CURRENT: 1000, LOCKED: 200, FROZEN: 50}

TC-F005-10  getBatchBalances_withPositionKeys_allReturnedCorrectly
            Given: 3 accounts each have CURRENT/LOCKED/FROZEN position balance
            When:  getBatchBalances([3 AccountBalanceKey with different positions])
            Then:  returns 3 correct balances, each includes position info
```

---

## Module 8: Journal Query (F-006)

```
TC-F006-01  getJournal_existingJournalId_returnsJournalWithLines
            Given: JNL-001 already synced to MySQL View Layer
            When:  getJournal("JNL-001")
            Then:  returns Journal and all JournalLines, dataSource=VIEW_LAYER

TC-F006-02  getJournalsByAccount_withFilters_returnsPagedResults
            Given: CLIENT_ACC_001 has 1250 Journals
            When:  getJournals(accountId="CLIENT_ACC_001", page=0, size=50)
            Then:  returns 50 rows, totalCount=1250

TC-F006-03  getJournalsByBusinessEventRef_rfqId_returnsAllRelatedJournals
            Given: RFQ-001 has original Journal + Reversal Journal
            When:  getJournals(businessEventRef="RFQ-001")
            Then:  returns 2 rows, including NORMAL + REVERSAL types

TC-F006-04  getJournalChain_originalJournal_returnsFullChain
            Given: original → Reversal → Rebook three Journals
            When:  getChain(originalJournalId)
            Then:  chain contains 3 rows, relationships correctly labeled

TC-F006-05  getJournalsByRequestId_confirmsIdempotency
            Given: requestId="req-abc" corresponds to JNL-001
            When:  getJournals(requestId="req-abc")
            Then:  returns JNL-001
```

---

## Module 9: Reconciliation (F-007)

```
TC-F007-01  runL1Reconciliation_allJournalsBalanced_noDiscrepancies
            Given: 100 Journals, all debit-credit balanced
            When:  runL1Reconciliation(date)
            Then:  Report.l1Summary.unbalancedJournals=0

TC-F007-02  runL1Reconciliation_unbalancedJournal_discrepancyDetected
            Given: 1 Journal manually modified to be unbalanced (injected test)
            When:  runL1Reconciliation(date)
            Then:  Report.l1Summary.unbalancedJournals=1, Case created

TC-F007-03  runL1Reconciliation_balanceMismatch_detectedAndCaseCreated
            Given: State Machine balance ≠ MySQL balance (injected inconsistency)
            When:  runL1Reconciliation(date)
            Then:  balanceConsistencyPassed=false, Case created

TC-F007-04  runL2Reconciliation_subAccountsSumMatchControl_noCases
            Given: 10 CLIENT accounts USD each 100, CONTROL_CLIENT_USD = 1000
            When:  runL2Reconciliation(rule="RFQ-USD-CONTROL")
            Then:  rulesPassed=1, no Case

TC-F007-05  runL2Reconciliation_subAccountsSumMismatch_caseCreated
            Given: 10 CLIENT accounts USD each 100 (total 1000), CONTROL = 999 (off by 1)
                   tolerance=0.01
            When:  runL2Reconciliation(rule)
            Then:  rulesFailed=1, Case created, discrepancyAmount=1.00

TC-F007-06  runL3Reconciliation_externalFileMatched_allMatched
            Given: 100 external settlements, 100 internal Journals, keys fully match
            When:  runL3Reconciliation(externalFile)
            Then:  matched=100, internalOnly=0, externalOnly=0

TC-F007-07  runL3Reconciliation_internalOnly_caseCreated
            Given: internally has 1 Journal, external settlement file does not
            When:  runL3Reconciliation(externalFile)
            Then:  internalOnly=1, Case created, type=INTERNAL_ONLY

TC-F007-08  runL3Reconciliation_amountMismatch_caseCreated
            Given: same externalRef, internal 800.00, external 810.00
            When:  runL3Reconciliation(externalFile)
            Then:  amountMismatch=1, Case created, discrepancyAmount=10.00
```

---

## Module 10: Accounting Period / EOD (F-009)

```
TC-F009-01  closeperiod_openPeriod_eodTasksExecutedInOrder
            Given: accounting period 2026-05-16 status=OPEN
            When:  triggerEOD("2026-05-16")
            Then:  EOD steps executed in order (Snapshot → Recon → Snapshot → Report → CLOSED)

TC-F009-02  postDuringClosing_returnsperiodClosedError
            Given: accounting period status=CLOSING
            When:  post(PostingRequest)
            Then:  PostingResult.status=REJECTED, errorCode=PERIOD_CLOSED

TC-F009-03  postToClosedPeriod_returnsperiodClosedError
            Given: accounting period status=CLOSED
            When:  post(PostingRequest, valueDate in closed accounting period)
            Then:  PostingResult.status=REJECTED, errorCode=PERIOD_CLOSED

TC-F009-04  reverseInClosedPeriod_allowedWithCrossPeriodFlag
            Given: original Journal valueDate in CLOSED accounting period
            When:  reverse(...)
            Then:  ReversalResult.status=COMPLETED, crossPeriod=true

TC-F009-05  eodBalanceSnapshot_matchesStateMachine
            Given: EOD execution completed
            When:  compare EOD Snapshot balance vs State Machine balance (same time point)
            Then:  all account balances completely consistent, difference is 0
```

---

## Module 11: RocksDB Durability Tests

```
TC-ROCKS-01  writeBatch_atomic_journalAndBalanceConsistentAfterCrash
             Given: simulate JVM crash during WriteBatch write (injected)
             When:  read from RocksDB after restart
             Then:  journal and balance either both exist or both do not exist (atomicity)

TC-ROCKS-02  walReplay_afterCleanRestart_balanceRecovered
             Given: execute 100 PostingCommands, normal shutdown
             When:  restart, replay from WAL + Snapshot
             Then:  all balances exactly consistent with before shutdown

TC-ROCKS-03  columnFamilyIsolation_writeToOneCF_notAffectOthers
             Given: write one entry to CF_JOURNAL
             When:  read CF_BALANCE
             Then:  CF_BALANCE unaffected, keys do not conflict
```

### 11.1 BalanceChangeEvent — accountSeq + position [v0.2 New, v0.3 Updated]

```
TC-F011-01  publishEvent_firstPosting_accountSeqIsOne
            Given: CLIENT_ACC_001 AVAILABLE_BALANCE/USD/CURRENT first posting
            When:  State Machine apply → publish BalanceChangeEvent (version 1.2)
            Then:  event.accountSeq == 1
                   event.prevAccountSeq == 0 (represents no predecessor)
                   event.position == CURRENT

TC-F011-02  publishEvent_subsequentPosting_accountSeqIncremented
            Given: previous event accountSeq == 41
            When:  next Posting apply → publish
            Then:  event.accountSeq == 42
                   event.prevAccountSeq == 41

TC-F011-03  publishEvent_reversal_accountSeqIncremented
            Given: previous event accountSeq == 10
            When:  Reversal apply → publish
            Then:  event.accountSeq == 11
                   event.prevAccountSeq == 10

TC-F011-04  consumer_detectsGap_whenSeqNotConsecutive
            Given: Consumer already received accountSeq == 100
            When:  next received event accountSeq == 102 (prevAccountSeq == 101)
                   but 101 never arrived
            Then:  Consumer determines gap (prevAccountSeq 102 ≠ previous accountSeq 100 + 1), triggers alert

TC-F011-05  consumer_noDuplicateAlert_whenIdempotentRetry
            Given: Outbox at-least-once resends same event
                   event.accountSeq == 50, event.idempotencyKey same
            When:  Consumer receives duplicate event
            Then:  Consumer deduplicates by idempotencyKey, does not misjudge as gap
                   (duplicate event has same accountSeq, not a new seq)

TC-F011-06  restartNode_outboxResend_accountSeqUnchanged
            Given: State Machine apply at accountSeq == 77, written to Outbox
                   node restarts, Outbox resends
            When:  Consumer receives resent event
            Then:  event.accountSeq still 77, consistent with original send
                   (seq determined at apply time, Outbox resend does not change value)

TC-F011-07  multiBalanceType_samePosting_seqIndependentPerKey
            Given: one RFQ Posting simultaneously updates:
                   CLIENT_ACC_001 AVAILABLE_BALANCE/USD/CURRENT (previous seq=10)
                   CLIENT_ACC_001 TRADE_AHEAD_BALANCE/USD/CURRENT (previous seq=5)
            When:  publish two BalanceChangeEvents
            Then:  AVAILABLE_BALANCE event.accountSeq == 11
                   TRADE_AHEAD_BALANCE event.accountSeq == 6
                   (seq for two keys increment independently, no mutual impact)

TC-F011-08  publishEvent_withPosition_eventContainsPosition [v0.3 New]
            Given: CLIENT_ACC_001 AVAILABLE_BALANCE/USD/LOCKED = 500.00
            When:  Posting apply with position=LOCKED → publish
            Then:  event.position == LOCKED
                   event.accountBalanceKey == (CLIENT_ACC_001, AVAILABLE_BALANCE, USD, LOCKED)

TC-F011-09  multiPosition_sameAccount_independentSeqPerPosition [v0.3 New]
            Given: CLIENT_ACC_001 AVAILABLE_BALANCE/USD has:
                   CURRENT position accountSeq == 10
                   LOCKED position accountSeq == 5
            When:  same Posting updates CURRENT + LOCKED respectively
            Then:  CURRENT event.accountSeq == 11
                   LOCKED event.accountSeq == 6
                   (seq for different positions increment independently, no mutual impact)
```

---

## Module 12: Performance / Load Tests (NFR)

```
TC-NFR-01  posting_p95_under3ms_normalLoad
           Given: 500 concurrent, each different account
           When:  execute 10,000 Postings
           Then:  P95 ≤ 3ms

TC-NFR-02  posting_hotspotAccount_p95_under3ms
           Given: 1000 concurrent, all targeting COMPANY_FX_ACC
           When:  execute 10,000 Postings
           Then:  P95 ≤ 3ms, no duplicate posting, balance exact

TC-NFR-03  balanceQuery_p95_under2ms
           Given: 10,000 QPS Balance Query
           When:  continuously query Active account balances
           Then:  P95 ≤ 2ms

TC-NFR-04  idempotency_1000Retries_onlyOneJournalCreated
           Given: same requestId
           When:  1000 concurrent retries
           Then:  only 1 Journal exists

TC-NFR-05  concurrentPosting_noNegativeBalance_noDoubleDebit
           Given: CLIENT balance=1000, 1001 concurrent DEBIT 1
           When:  all executed
           Then:  1000 succeeded, 1 INSUFFICIENT_BALANCE, balance=0, no negative penetration
```

---

## Module 13: Account Queue (ADR-001 v0.2)

```
TC-QUEUE-01  singleAccount_serialization_processedInOrder
             Given: CLIENT_ACC_001 balance=10000, 50 concurrent DEBIT 1 via AccountQueueManager
             When:  all requests completed
             Then:  balance=9950, all requests processed serially, no concurrency conflicts

TC-QUEUE-02  backpressure_exceedingMaxQueueSize_returnsFalse
             Given: MAX_QUEUE_SIZE=1000
             When:  submit > 1000 requests to same account
             Then:  excess returns false (backpressure), queue depth ≤ 1000

TC-QUEUE-03  multipleAccounts_independentQueues
             Given: ACC_A and ACC_B each have independent queue
             When:  simultaneously submit 100 CREDIT to both accounts
             Then:  both process independently in parallel, no errors
```

---

## Module 14: Account Queue + Outbox Integration

```
TC-QUEUE-OBX-01  concurrentRequests_produceCorrectEvents
                 Given: CLIENT_ACC_001 + COMPANY_FX_ACC, 20 concurrent Postings (each 2 JournalLines)
                 When:  Account Queue serialized → StateMachine apply → Event publish
                 Then:  40 BalanceChangeEvents, CLIENT side accountSeq strictly increasing

TC-QUEUE-OBX-02  hotspotAccount_concurrentCredits_allSucceed
                 Given: COMPANY_FX_ACC hotspot, 50 concurrent CREDIT each 10.00
                 When:  Account Queue serially processes COMPANY account
                 Then:  COMPANY balance = 100000 + 500 = 100500, 100 events, no duplicates
```

---

## Module 15: SOFAJRaft Cluster

```
TC-RAFT-01  threeNodeCluster_leaderElectionAndLogReplication
            Given: 3 RaftNodeManager on 127.0.0.1:18081/2/3
            When:  start → wait leader → submit PostingCommand
            Then:  leader elected in < 1s, journal replicated to all 3 nodes

TC-RAFT-02  cluster_survivesFollowerRestart
            Given: 3-node cluster with elected leader
            When:  follower shutdown then restart
            Then:  follower rejoins cluster, no errors
```

---

## Module 16: Kafka Event Publishing (F-011/F-011b)

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

## Module 17: Idempotency & Hotspot Account Concurrency (F-013)

### 17.1 Idempotency

```
TC-F013-01  duplicateRequestId_returnsCachedResult_noReExecution
            Given: Posting req-001 completed, journalId=JNL-001
            When:  retry same req-001
            Then:  returns original result (journalId=JNL-001), State Machine does not re-apply

TC-F013-02  duplicateRequestId_rejected_returnsOriginalErrors
            Given: Posting req-002 rejected (INSUFFICIENT_BALANCE)
            When:  retry req-002
            Then:  returns original error code (INSUFFICIENT_BALANCE), no balance re-validation

TC-F013-03  idempotencySurvivesLeaderFailover
            Given: Leader completes req-003 apply → idempotencyStore.put → Leader crashes
            When:  new Leader elected, Client retries req-003
            Then:  idempotencyStore recovered from RocksDB hits → returns original result

TC-F013-04  idempotencyEntry_expiredAfterTTL_treatedAsNewRequest
            Given: idempotencyStore TTL=1s, req-004 completed → wait 2s
            When:  retry req-004
            Then:  idempotencyStore evicted → treated as new request, re-executes ledger mutation

TC-F013-05  concurrentDuplicateRequestIds_onlyOneExecuted
            Given: two threads simultaneously send req-005
            When:  arrive at Account Queue at the same time
            Then:  only 1 Journal generated, the other returns cached result
```

### 17.2 Hotspot Account Concurrency

```
TC-F013-06  hotspotAccount_1000ConcurrentPostings_noBalanceInconsistency
            Given: COMPANY_FX_ACC balance=1,000,000, 1000 concurrent DEBIT 1
            When:  1000 concurrent Postings (different CLIENT_ACC → same COMPANY_FX_ACC)
            Then:  final COMPANY_FX_ACC balance=0, no duplicate deduction, no negative

TC-F013-07  hotspotAccount_1000ConcurrentPostings_p95Under3ms
            Given: COMPANY_FX_ACC, 1000 concurrent RFQ Postings
            When:  measure Posting P95 latency
            Then:  P95 ≤ 3ms

TC-F013-08  accountQueueDepthExceedsMaxSize_returnsQueueFull
            Given: MAX_QUEUE_SIZE=10, queue("COMPANY_FX_ACC") already has 10 pending
            When:  11th request arrives
            Then:  returns HTTP 429 QUEUE_FULL

TC-F013-09  multiAccountCoordination_deadlockPrevention_orderedTokenAcquisition
            Given: RFQ involves CLIENT_ACC_001 + COMPANY_FX_ACC
            When:  two RFQs execute simultaneously with reversed account order
            Then:  no deadlock, both succeed (tokens acquired in accountId ascending order)

TC-F013-10  virtualThreadWorkerCrash_queueRecoversAndResumesConsumption
            Given: Account Queue Worker-3 (COMPANY_FX_ACC) running
            When:  simulate Worker thread interrupt/crash
            Then:  system auto-rebuilds Worker-3, pending requests in queue continue to be consumed
```

---

## Recommended Test Execution Order (TDD Red-Green Order)

```
Phase 1 — Foundation Models
  TC-F001-* → TC-F010-* → TC-F008-01~10 (Balance operations)

Phase 2 — Core Write Path
  TC-F002-01~07 (Posting basics)
  TC-F008-11~15 (Reversal State Machine)
  TC-F004-01~07 (Reversal API)
  TC-F003-01~08 (Manual Adjustment)

Phase 2.5 — Position support [v0.3 New]
  TC-F002-11~15 (Posting position)
  TC-F008-27~30 (State Machine position validation rule V-13)
  TC-F005-07~10 (Balance Query position)

Phase 3 — Read Path
  TC-F005-* (Balance Query)
  TC-F006-* (Journal Query)

Phase 3.5 — accountSeq [v0.2 New]
  TC-F008-19~26 (State Machine accountSeq)
  TC-F011-01~09 (BalanceChangeEvent accountSeq + position)

Phase 4 — Reconciliation and Accounting Period
  TC-F007-* (Reconciliation)
  TC-F009-* (Accounting Period)

Phase 5 — Durability and Performance
  TC-ROCKS-* (RocksDB durability)
  TC-NFR-* (Performance / Load)

Phase 6 — Concurrency Safety
  TC-F002-08~09 (Concurrent Posting)
  TC-NFR-04~05 (Idempotency + Concurrency)

Phase 6.5 — Idempotency & Hotspot Account [v0.4 New]
  TC-F013-01~10 (Idempotency + Hotspot Concurrency)
```
