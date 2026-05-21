# TDD Test Cases — Next-Gen Internal Ledger Platform

**Version**: v0.2
**Date**: 2026-05-18
**Method**: Test-Driven Development (Red → Green → Refactor)
**Framework**: JUnit 5 + Mockito + AssertJ + Testcontainers (MySQL) + RocksDB embedded

> **v0.2 Change Summary**: Added Module 3 Section 3.4 (TC-F008-19 ~ TC-F008-26, accountSeq State Machine), Module 11 Section 11.1 (TC-F011-01 ~ TC-F011-07, BalanceChangeEvent accountSeq), TDD execution plan supplemented with Phase 3.5.

---

```
┌─────────────────────────────────────┐
│         E2E
├─────────────────────────────────────┤
│         Service
├─────────────────────────────────────┤
└─────────────────────────────────────┘
```

## Test Layering Strategy

```
┌─────────────────────────────────────┐
│ E2E / Integration Tests │ Few, covering key business flows
├─────────────────────────────────────┤
│ Service / UseCase Tests │ Medium, covering business rules
├─────────────────────────────────────┤
│ Unit Tests │ Many, covering all core logic
└─────────────────────────────────────┘
```

Principles:
- Unit Test coverage target: ≥ 90%
- Each test case tests only one thing
- Test name format: `[MethodName]_[Scenario]_[ExpectedResult]`
- All tests must run in CI environment without external dependencies (embedded RocksDB / Testcontainers)

---

## Module 1

### 1.1 BalanceTypeConfigService

```
TC-F001-01 getConfig_existingActiveType_returnsConfig
            When: getConfig("AVAILABLE_BALANCE")

TC-F001-02 getConfig_nonExistentType_throwsBalanceTypeNotFoundException
            When: getConfig("UNKNOWN_TYPE")

TC-F001-03 getConfig_inactiveType_throwsBalanceTypeInactiveException
            Given: "OLD_TYPE" status=INACTIVE
            When: getConfig("OLD_TYPE")

TC-F001-04 registerType_newType_successfullyRegistered
            When: registerType(BalanceTypeConfig{code="BROKERAGE_BALANCE", allowNegative=false})

TC-F001-05 registerType_duplicateCode_throwsDuplicateBalanceTypeException
            When: registerType(BalanceTypeConfig{code="AVAILABLE_BALANCE"})

TC-F001-06 registerType_tradeAheadBalance_allowNegativeTrue_signConventionNormalDebit
            When: registerType(...)

TC-F001-07 updateConfig_existingType_configVersionIncremented
            Given: "AVAILABLE_BALANCE" configVersion=1
            When: updateConfig("AVAILABLE_BALANCE", newConfig)

TC-F001-08 deactivateType_existingType_statusBecomesInactive
            Given: "OLD_TYPE" status=ACTIVE
            When: deactivateType("OLD_TYPE")
```

### 1.1 BalanceTypeConfigService

```
TC-F001-01 getConfig_existingActiveType_returnsConfig
            Given: AVAILABLE_BALANCE exists in Registry with status=ACTIVE
            When: getConfig("AVAILABLE_BALANCE")
            Then: Returns correct config with allowNegative=false

TC-F001-02 getConfig_nonExistentType_throwsBalanceTypeNotFoundException
            Given: "UNKNOWN_TYPE" does not exist in Registry
            When: getConfig("UNKNOWN_TYPE")
            Then: Throws BalanceTypeNotFoundException

TC-F001-03 getConfig_inactiveType_throwsBalanceTypeInactiveException
            Given: "OLD_TYPE" has status=INACTIVE
            When: getConfig("OLD_TYPE")
            Then: Throws BalanceTypeInactiveException

TC-F001-04 registerType_newType_successfullyRegistered
            Given: "BROKERAGE_BALANCE" does not exist
            When: registerType(BalanceTypeConfig{code="BROKERAGE_BALANCE", allowNegative=false})
            Then: Registry contains this type with status=ACTIVE and configVersion=1

TC-F001-05 registerType_duplicateCode_throwsDuplicateBalanceTypeException
            Given: "AVAILABLE_BALANCE" already exists
            When: registerType(BalanceTypeConfig{code="AVAILABLE_BALANCE"})
            Then: Throws DuplicateBalanceTypeException

TC-F001-06 registerType_tradeAheadBalance_allowNegativeTrue_signConventionNormalDebit
            Given: New type TRADE_AHEAD_BALANCE with allowNegative=true and negativeSemantics=PRE_AUTHORIZED
            When: registerType(...)
            Then: config.allowNegative=true and config.negativeSemantics=PRE_AUTHORIZED

TC-F001-07 updateConfig_existingType_configVersionIncremented
            Given: "AVAILABLE_BALANCE" has configVersion=1
            When: updateConfig("AVAILABLE_BALANCE", newConfig)
            Then: configVersion=2 and change record is saved

TC-F001-08 deactivateType_existingType_statusBecomesInactive
            Given: "OLD_TYPE" has status=ACTIVE
            When: deactivateType("OLD_TYPE")
            Then: status=INACTIVE; subsequent getConfig throws BalanceTypeInactiveException
```

---

## Module 2

### 2.1 AccountService

```
TC-F010-01 createAccount_validInput_accountCreatedInStateMachine
            When: createAccount(...)

TC-F010-02 createAccount_duplicateAccountId_throwsAccountAlreadyExistsException
            When: createAccount(accountId="CLIENT_ACC_001")

TC-F010-03 createAccount_clientTypeWithoutOwnerId_throwsMissingOwnerIdException
            When: createAccount(...)

TC-F010-04 createAccount_withBalanceInitializations_balancesInitializedToZero
            When: createAccount(...)

TC-F010-05 createAccount_withUnknownBalanceType_throwsBalanceTypeNotFoundException
            When: createAccount(...)

TC-F010-06 freezeAccount_activeAccount_statusBecomeFrozen
            Given: "CLIENT_ACC_001" status=ACTIVE
            When: freezeAccount("CLIENT_ACC_001")

TC-F010-07 unfreezeAccount_frozenAccount_statusBecomeActive
            Given: "CLIENT_ACC_001" status=FROZEN
            When: unfreezeAccount("CLIENT_ACC_001")
            Then: status=ACTIVE

TC-F010-08 closeAccount_withNonZeroBalance_throwsAccountHasNonZeroBalanceException
            Given: "CLIENT_ACC_001" AVAILABLE_BALANCE/USD = 100.00
            When: closeAccount("CLIENT_ACC_001")

TC-F010-09 closeAccount_withAllZeroBalances_statusBecomeClosed
            When: closeAccount("CLIENT_ACC_001")
            Then: status=CLOSED

TC-F010-10 closeAccount_closedAccount_cannotBeUnfrozen
            Given: status=CLOSED
            When: unfreezeAccount("CLIENT_ACC_001")

TC-F010-11 addBalanceType_existingAccount_newBalanceInitializedToZero
            When: addBalanceType("CLIENT_ACC_001", "BROKERAGE_BALANCE", "USD")
```

### 2.1 AccountService

```
TC-F010-01 createAccount_validInput_accountCreatedInStateMachine
            Given: accountId="CLIENT_ACC_001", type=CLIENT, ownerId="CUST-001"
            When: createAccount(...)
            Then: Account exists in State Machine with status=ACTIVE

TC-F010-02 createAccount_duplicateAccountId_throwsAccountAlreadyExistsException
            Given: "CLIENT_ACC_001" already exists
            When: createAccount(accountId="CLIENT_ACC_001")
            Then: Throws AccountAlreadyExistsException

TC-F010-03 createAccount_clientTypeWithoutOwnerId_throwsMissingOwnerIdException
            Given: type=CLIENT with ownerId=null
            When: createAccount(...)
            Then: Throws MissingOwnerIdException

TC-F010-04 createAccount_withBalanceInitializations_balancesInitializedToZero
            Given: Initialize AVAILABLE_BALANCE/USD + AVAILABLE_BALANCE/HKD
            When: createAccount(...)
            Then: Both keys exist in balanceStore with amount=0

TC-F010-05 createAccount_withUnknownBalanceType_throwsBalanceTypeNotFoundException
            Given: balanceInitializations contains "UNKNOWN_TYPE"
            When: createAccount(...)
            Then: Throws BalanceTypeNotFoundException

TC-F010-06 freezeAccount_activeAccount_statusBecomeFrozen
            Given: "CLIENT_ACC_001" has status=ACTIVE
            When: freezeAccount("CLIENT_ACC_001")
            Then: Status becomes FROZEN in State Machine

TC-F010-07 unfreezeAccount_frozenAccount_statusBecomeActive
            Given: "CLIENT_ACC_001" has status=FROZEN
            When: unfreezeAccount("CLIENT_ACC_001")
            Then: status=ACTIVE

TC-F010-08 closeAccount_withNonZeroBalance_throwsAccountHasNonZeroBalanceException
            Given: "CLIENT_ACC_001" AVAILABLE_BALANCE/USD = 100.00
            When: closeAccount("CLIENT_ACC_001")
            Then: Throws AccountHasNonZeroBalanceException

TC-F010-09 closeAccount_withAllZeroBalances_statusBecomeClosed
            Given: All balances = 0
            When: closeAccount("CLIENT_ACC_001")
            Then: status=CLOSED

TC-F010-10 closeAccount_closedAccount_cannotBeUnfrozen
            Given: status=CLOSED
            When: unfreezeAccount("CLIENT_ACC_001")
            Then: Throws AccountClosedException

TC-F010-11 addBalanceType_existingAccount_newBalanceInitializedToZero
            Given: "CLIENT_ACC_001" exists without BROKERAGE_BALANCE/USD
            When: addBalanceType("CLIENT_ACC_001", "BROKERAGE_BALANCE", "USD")
            Then: New key exists in balanceStore with amount=0
```

---

## Module 3

### 3.1 LedgerStateMachine

```
TC-F008-01 applyPosting_singleDebit_balanceDecreased
            Given: CLIENT_ACC_001 AVAILABLE_BALANCE/USD = 1000.00
            When: apply PostingCommand{ DEBIT 300.00 }

TC-F008-02 applyPosting_singleCredit_balanceIncreased
            Given: CLIENT_ACC_001 AVAILABLE_BALANCE/USD = 1000.00
            When: apply PostingCommand{ CREDIT 500.00 }
            Then: balance = 1500.00

TC-F008-03 applyPosting_debitExceedsBalance_allowNegativeFalse_commandRejected
            When: apply PostingCommand{ DEBIT 200.00 }

TC-F008-04 applyPosting_tradeAheadBalance_allowNegativeTrue_negativeBalanceAllowed
            When: apply PostingCommand{ DEBIT 50000.00 }

TC-F008-05 applyPosting_tradeAheadBalance_creditAboveZero_commandRejected
            When: apply PostingCommand{ CREDIT 20000.00 }

TC-F008-06 applyPosting_multiAccount_atomicUpdate
            When: apply PostingCommand{ CLIENT DEBIT 800 + COMPANY CREDIT 800 }

TC-F008-07 applyPosting_frozenAccount_commandRejected
            Given: CLIENT_ACC_001 status=FROZEN
            When: apply PostingCommand{ DEBIT 100.00 }

TC-F008-08 applyPosting_idempotency_sameRequestId_returnsSameResult

TC-F008-09 applyPosting_journalUnbalanced_commandRejected
            When: apply PostingCommand

TC-F008-10 applyPosting_generateJournalLine_balanceBeforeAndAfterCorrect
            Given: CLIENT balance = 1000.00
            When: apply DEBIT 300.00
```

### 3.1 LedgerStateMachine — Balance Operations

```
TC-F008-01 applyPosting_singleDebit_balanceDecreased
            Given: CLIENT_ACC_001 AVAILABLE_BALANCE/USD = 1000.00
            When: apply PostingCommand{ DEBIT 300.00 }
            Then: balance = 700.00 and stateVersion incremented

TC-F008-02 applyPosting_singleCredit_balanceIncreased
            Given: CLIENT_ACC_001 AVAILABLE_BALANCE/USD = 1000.00
            When: apply PostingCommand{ CREDIT 500.00 }
            Then: balance = 1500.00

TC-F008-03 applyPosting_debitExceedsBalance_allowNegativeFalse_commandRejected
            Given: balance = 100.00 with allowNegative=false
            When: apply PostingCommand{ DEBIT 200.00 }
            Then: CommandResult.status=REJECTED with errorCode=INSUFFICIENT_BALANCE
                   and balance remains 100.00 unchanged

TC-F008-04 applyPosting_tradeAheadBalance_allowNegativeTrue_negativeBalanceAllowed
            Given: TRADE_AHEAD_BALANCE/USD = 0.00 with allowNegative=true
            When: apply PostingCommand{ DEBIT 50000.00 }
            Then: balance = -50000.00 and CommandResult.status=COMPLETED

TC-F008-05 applyPosting_tradeAheadBalance_creditAboveZero_commandRejected
            Given: TRADE_AHEAD_BALANCE/USD = -10000.00 with allowNegative=true
            When: apply PostingCommand{ CREDIT 20000.00 } (would result in > 0)
            Then: CommandResult.status=REJECTED with errorCode=CREDIT_EXCEEDS_LIMIT

TC-F008-06 applyPosting_multiAccount_atomicUpdate
            Given: CLIENT_ACC_001 USD = 1000.00 and COMPANY_FX_ACC USD = 5000.00
            When: apply PostingCommand{ CLIENT DEBIT 800 + COMPANY CREDIT 800 }
            Then: CLIENT = 200.00 and COMPANY = 5800.00, both updated atomically

TC-F008-07 applyPosting_frozenAccount_commandRejected
            Given: CLIENT_ACC_001 has status=FROZEN
            When: apply PostingCommand{ DEBIT 100.00 }
            Then: CommandResult.status=REJECTED with errorCode=ACCOUNT_FROZEN

TC-F008-08 applyPosting_idempotency_sameRequestId_returnsSameResult
            Given: requestId="req-001" already executed successfully with balance=700.00
            When: apply same requestId again
            Then: Returns original CommandResult with balance still 700.00 (no duplicate deduction)

TC-F008-09 applyPosting_journalUnbalanced_commandRejected
            Given: legs have DEBIT total ≠ CREDIT total
            When: apply PostingCommand
            Then: CommandResult.status=REJECTED with errorCode=JOURNAL_UNBALANCED

TC-F008-10 applyPosting_generateJournalLine_balanceBeforeAndAfterCorrect
            Given: CLIENT balance = 1000.00
            When: apply DEBIT 300.00
            Then: JournalLine.balanceBefore=1000.00 and balanceAfter=700.00
```

### 3.2 LedgerStateMachine — Reversal

```
TC-F008-11 applyReversal_confirmedJournal_balanceReverted
            When: apply ReversalCommand{ originalJournalId }

TC-F008-12 applyReversal_alreadyReversedJournal_commandRejected
            When: apply ReversalCommand

TC-F008-13 applyReversal_reversalJournal_commandRejected
            When: apply ReversalCommand

TC-F008-14 applyReversal_noBalanceCheck_executesEvenIfInsufficientBalance
            When: apply ReversalCommand

TC-F008-15 applyReversal_crossPeriod_markedCorrectly
            When: apply ReversalCommand
            Then: Reversal Journal crossPeriod=true
```

### 3.2 LedgerStateMachine — Reversal

```
TC-F008-11 applyReversal_confirmedJournal_balanceReverted
            Given: Original Journal DEBIT 300.00 already executed with balance=700.00
            When: apply ReversalCommand{ originalJournalId }
            Then: balance=1000.00 and original Journal status=REVERSED

TC-F008-12 applyReversal_alreadyReversedJournal_commandRejected
            Given: Original Journal has status=REVERSED
            When: apply ReversalCommand
            Then: CommandResult.status=REJECTED with errorCode=JOURNAL_ALREADY_REVERSED

TC-F008-13 applyReversal_reversalJournal_commandRejected
            Given: Journal with journalType=REVERSAL
            When: apply ReversalCommand
            Then: CommandResult.status=REJECTED with errorCode=CANNOT_REVERSE_REVERSAL

TC-F008-14 applyReversal_noBalanceCheck_executesEvenIfInsufficientBalance
            Given: Original Journal CREDIT 1000.00, but account balance has been consumed by other transactions to 0
            When: apply ReversalCommand (DEBIT 1000.00 back; balance would go negative)
            Then: CommandResult.status=COMPLETED with balance=-1000.00 (negative allowed)

TC-F008-15 applyReversal_crossPeriod_markedCorrectly
            Given: Original Journal has valueDate in a closed accounting period
            When: apply ReversalCommand
            Then: Reversal Journal has crossPeriod=true
```

### 3.3 LedgerStateMachine — Snapshot & Replay

```
TC-F008-16 takeSnapshot_allBalancesSerializedAndRestored

TC-F008-17 replayFromLog_afterSnapshot_balanceCorrect
            When: restore Snapshot + replay log 101-110

TC-F008-18 inactiveAccount_evictedFromMemory_reloadedFromRocksDB
```

### 3.3 LedgerStateMachine — Snapshot & Replay

```
TC-F008-16 takeSnapshot_allBalancesSerializedAndRestored
            Given: 5 accounts each with different balances
            When: takeSnapshot() → clear State Machine → restoreFromSnapshot()
            Then: All balances are exactly the same and stateVersion is consistent

TC-F008-17 replayFromLog_afterSnapshot_balanceCorrect
            Given: Snapshot at index=100, indexes 101-110 have 10 PostingCommands
            When: restore Snapshot + replay log 101-110
            Then: Final balance equals result of directly executing 110 postings

TC-F008-18 inactiveAccount_evictedFromMemory_reloadedFromRocksDB
            Given: Account has no transactions for 24 hours and has been evicted
            When: apply PostingCommand to that account
            Then: Warmed up from RocksDB with correct balance and execution continues
```

### 3.4 LedgerStateMachine

```
TC-F008-19 applyPosting_firstEver_accountSeqStartsAtOne
            When: apply PostingCommand{ DEBIT 100.00 }
            Then: BalanceEntry.accountSeq == 1

TC-F008-20 applyPosting_subsequentPosting_accountSeqIncremented
            Given: CLIENT_ACC_001 AVAILABLE_BALANCE/USD accountSeq == 5
            When: apply PostingCommand{ DEBIT 100.00 }
            Then: BalanceEntry.accountSeq == 6

TC-F008-21 applyReversal_accountSeqIncremented
            Given: CLIENT_ACC_001 AVAILABLE_BALANCE/USD accountSeq == 10
            When: apply ReversalCommand
            Then: BalanceEntry.accountSeq == 11

TC-F008-22 applyAdjustment_accountSeqIncremented
            Given: CLIENT_ACC_001 AVAILABLE_BALANCE/USD accountSeq == 20
            When: apply AdjustmentCommand
            Then: BalanceEntry.accountSeq == 21

TC-F008-23 applyPosting_differentBalanceType_seqIndependent
            Given: CLIENT_ACC_001 AVAILABLE_BALANCE/USD accountSeq == 5
                   CLIENT_ACC_001 TRADE_AHEAD_BALANCE/USD accountSeq == 3
            Then: AVAILABLE_BALANCE/USD accountSeq == 6
                   TRADE_AHEAD_BALANCE/USD accountSeq == 4

TC-F008-24 takeSnapshot_accountSeqSerializedAndRestored
            Given: CLIENT_ACC_001 AVAILABLE_BALANCE/USD accountSeq == 42
            When: takeSnapshot() → restoreFromSnapshot()
            Then: BalanceEntry.accountSeq == 42

TC-F008-25 replayFromLog_afterSnapshot_accountSeqContinues
                   Raft Log index 101-105
            When: restore Snapshot → replay log 101-105
            Then: accountSeq == 47

TC-F008-26 restartNode_accountSeqResumesFromRocksDB
```

### 3.4 LedgerStateMachine

```
TC-F008-19 applyPosting_firstEver_accountSeqStartsAtOne
            Given: CLIENT_ACC_001 AVAILABLE_BALANCE/USD has no transactions yet (accountSeq does not exist)
            When: apply PostingCommand{ DEBIT 100.00 }
            Then: BalanceEntry.accountSeq == 1

TC-F008-20 applyPosting_subsequentPosting_accountSeqIncremented
            Given: CLIENT_ACC_001 AVAILABLE_BALANCE/USD accountSeq == 5
            When: apply PostingCommand{ DEBIT 100.00 }
            Then: BalanceEntry.accountSeq == 6

TC-F008-21 applyReversal_accountSeqIncremented
            Given: CLIENT_ACC_001 AVAILABLE_BALANCE/USD accountSeq == 10
            When: apply ReversalCommand
            Then: BalanceEntry.accountSeq == 11
                   (Reversal also increments seq because it is a balance change)

TC-F008-22 applyAdjustment_accountSeqIncremented
            Given: CLIENT_ACC_001 AVAILABLE_BALANCE/USD accountSeq == 20
            When: apply AdjustmentCommand
            Then: BalanceEntry.accountSeq == 21

TC-F008-23 applyPosting_differentBalanceType_seqIndependent
            Given: CLIENT_ACC_001 AVAILABLE_BALANCE/USD accountSeq == 5
                   CLIENT_ACC_001 TRADE_AHEAD_BALANCE/USD accountSeq == 3
            When: apply PostingCommand updating both balance types simultaneously
            Then: AVAILABLE_BALANCE/USD accountSeq == 6
                   TRADE_AHEAD_BALANCE/USD accountSeq == 4
                   (seqs for different keys are independent of each other)

TC-F008-24 takeSnapshot_accountSeqSerializedAndRestored
            Given: CLIENT_ACC_001 AVAILABLE_BALANCE/USD accountSeq == 42
            When: takeSnapshot() → restoreFromSnapshot()
            Then: BalanceEntry.accountSeq == 42
                   (Snapshot must serialize accountSeq; it must not be lost)

TC-F008-25 replayFromLog_afterSnapshot_accountSeqContinues
            Given: Snapshot at index=100: accountSeq == 42
                   Raft Log index 101-105: 5 PostingCommands
            When: restore Snapshot → replay log 101-105
            Then: accountSeq == 47 (42 + 5)

TC-F008-26 restartNode_accountSeqResumesFromRocksDB
            Given: CLIENT_ACC_001 accountSeq == 99, written to RocksDB CF_BALANCE
                   Simulating JVM crash (no Snapshot)
            When: Restart and replay from Raft Log
            Then: accountSeq recovers from RocksDB; next event accountSeq == 100
                   Must not reset to 0 or 1
```

---

## Module 4

### 4.1 PostingService

```
TC-F002-01 post_validSingleLeg_returnsCompletedResult
            When: post(request)

TC-F002-02 post_insufficientBalance_returnsRejectedResult
            When: post(request)

TC-F002-03 post_unknownAccount_returnsRejectedResult
            When: post(request)

TC-F002-04 post_sameRequestIdTwice_idempotentResult

TC-F002-05 post_unbalancedJournal_returnsBadRequest
            Given: DEBIT 100 + CREDIT 99
            When: post(request)
            Then: HTTP 400 / errorCode=JOURNAL_UNBALANCED

TC-F002-06 post_rfqScenario_twoAccounts_atomicUpdate
            When: post(CLIENT DEBIT 800 + COMPANY CREDIT 800)

TC-F002-07 post_frozenAccount_returnsRejectedResult
            Given: CLIENT_ACC_001 status=FROZEN
            When: post(request to CLIENT_ACC_001)

TC-F002-08 post_concurrentSameAccount_noDoubleDebit

TC-F002-09 post_hotspotCompanyAccount_1000Concurrent_noDuplicate

TC-F002-10 post_inactiveBalanceType_returnsBadRequest
            Given: balanceType="OLD_TYPE" status=INACTIVE
            When: post(request)
            Then: HTTP 400 / errorCode=BALANCE_TYPE_NOT_FOUND
```

### 4.1 PostingService

```
TC-F002-01 post_validSingleLeg_returnsCompletedResult
            Given: Valid PostingRequest with CLIENT DEBIT + COMPANY CREDIT, 800 USD each
            When: post(request)
            Then: PostingResult.status=COMPLETED and journalId is not empty

TC-F002-02 post_insufficientBalance_returnsRejectedResult
            Given: CLIENT balance=100 with DEBIT 500 requested
            When: post(request)
            Then: PostingResult.status=REJECTED with errorCode=INSUFFICIENT_BALANCE

TC-F002-03 post_unknownAccount_returnsRejectedResult
            Given: accountId="GHOST_ACC" does not exist
            When: post(request)
            Then: PostingResult.status=REJECTED with errorCode=ACCOUNT_NOT_FOUND

TC-F002-04 post_sameRequestIdTwice_idempotentResult
            Given: requestId="req-abc" succeeds first time
            When: second post(same request)
            Then: Returns same PostingResult as first time; balance not changed again

TC-F002-05 post_unbalancedJournal_returnsBadRequest
            Given: DEBIT 100 + CREDIT 99 (unbalanced)
            When: post(request)
            Then: HTTP 400 with errorCode=JOURNAL_UNBALANCED

TC-F002-06 post_rfqScenario_twoAccounts_atomicUpdate
            Given: CLIENT_ACC_001 USD=1000 and COMPANY_FX_ACC USD=5000
            When: post(CLIENT DEBIT 800 + COMPANY CREDIT 800)
            Then: CLIENT=200, COMPANY=5800, and Journal contains 2 JournalLines

TC-F002-07 post_frozenAccount_returnsRejectedResult
            Given: CLIENT_ACC_001 has status=FROZEN
            When: post(request to CLIENT_ACC_001)
            Then: PostingResult.status=REJECTED with errorCode=ACCOUNT_FROZEN

TC-F002-08 post_concurrentSameAccount_noDoubleDebit
            Given: CLIENT balance=1000 with 1000 concurrent requests each DEBIT 1 (total 1000)
            When: All execute
            Then: balance=0, exactly 1000 Journals, no duplicates

TC-F002-09 post_hotspotCompanyAccount_1000Concurrent_noDuplicate
            Given: COMPANY_FX_ACC with 1000 concurrent different CLIENT CREDITs coming in
            When: All execute
            Then: All Journals are unique; COMPANY balance equals exactly the sum of all CREDITs

TC-F002-10 post_inactiveBalanceType_returnsBadRequest
            Given: balanceType="OLD_TYPE" has status=INACTIVE
            When: post(request)
            Then: HTTP 400 with errorCode=BALANCE_TYPE_NOT_FOUND
```

---

## Module 5

```
TC-F004-01 reverse_confirmedJournal_reversalJournalCreated
            When: reverse(originalJournalId, request)

TC-F004-02 reverse_alreadyReversedJournal_returnsRejected
            When: reverse(originalJournalId)

TC-F004-03 reverse_reversalJournal_returnsRejected
            Given: journalType=REVERSAL
            When: reverse(reversalJournalId)

TC-F004-04 reverse_sameRequestIdTwice_idempotent

TC-F004-05 reverse_crossPeriodJournal_markedCrossPeriod
            When: reverse(...)
            Then: ReversalResult.crossPeriod=true

TC-F004-06 reverse_mirrorsOriginalLines_debitCreditSwapped
            CLIENT DEBIT 800 + COMPANY CREDIT 800
            When: reverse(...)

TC-F004-07 reverse_insufficientBalance_stillExecutes
            When: reverse
```

```
TC-F004-01 reverse_confirmedJournal_reversalJournalCreated
            Given: Original Journal has status=CONFIRMED
            When: reverse(originalJournalId, request)
            Then: ReversalResult.status=COMPLETED and reversalJournalId is not empty
                   Original Journal status=REVERSED

TC-F004-02 reverse_alreadyReversedJournal_returnsRejected
            Given: Original Journal has status=REVERSED
            When: reverse(originalJournalId)
            Then: ReversalResult.status=REJECTED with errorCode=JOURNAL_ALREADY_REVERSED

TC-F004-03 reverse_reversalJournal_returnsRejected
            Given: journalType=REVERSAL
            When: reverse(reversalJournalId)
            Then: ReversalResult.status=REJECTED with errorCode=CANNOT_REVERSE_REVERSAL

TC-F004-04 reverse_sameRequestIdTwice_idempotent
            Given: requestId="rev-001" succeeds first time
            When: second reverse(same requestId)
            Then: Returns same ReversalResult as first time; no duplicate reversal

TC-F004-05 reverse_crossPeriodJournal_markedCrossPeriod
            Given: Original Journal has valueDate in a closed accounting period
            When: reverse(...)
            Then: ReversalResult.crossPeriod=true

TC-F004-06 reverse_mirrorsOriginalLines_debitCreditSwapped
            Given: Original Journal: CLIENT DEBIT 800 + COMPANY CREDIT 800
            When: reverse(...)
            Then: Reversal Journal: CLIENT CREDIT 800 + COMPANY DEBIT 800
                   Amounts are exactly the same; directions are completely opposite

TC-F004-07 reverse_insufficientBalance_stillExecutes
            Given: Original Journal CREDIT 1000 (CLIENT received money), but CLIENT has transferred it out with balance=0
            When: reverse (CLIENT DEBIT 1000 back; balance would go negative)
            Then: ReversalResult.status=COMPLETED with balance=-1000 (no balance validation performed)
```

---

## Module 6

```
TC-F003-01 createDraft_validInput_draftCreatedWithPendingStatus
            When: createDraft(request)

TC-F003-02 createDraft_unbalancedLegs_returnsBadRequest
            Given: DEBIT 100 + CREDIT 99
            When: createDraft(request)
            Then: HTTP 400 / errorCode=JOURNAL_UNBALANCED

TC-F003-03 approveDraft_validChecker_adjustmentPosted
            When: approveDraft(draftId, checkerId="checker-001")

TC-F003-04 approveDraft_samePersonAsMaker_throwsMakerCheckerSamePersonException
            Given: makerId="ops-001"
            When: approveDraft(draftId, checkerId="ops-001")

TC-F003-05 approveDraft_expiredDraft_throwsDraftExpiredException
            When: approveDraft(draftId)

TC-F003-06 approveDraft_alreadyExecutedDraft_throwsDraftNotPendingException
            Given: Draft status=EXECUTED
            When: approveDraft(draftId)

TC-F003-07 rejectDraft_validChecker_draftStatusRejected
            Given: Draft status=PENDING_APPROVAL
            When: rejectDraft(draftId, checkerId, rejectReason)

TC-F003-08 approveDraft_idempotent_sameRequestIdTwice_notDoublePosted
```

```
TC-F003-01 createDraft_validInput_draftCreatedWithPendingStatus
            Given: Valid AdjustmentDraftRequest
            When: createDraft(request)
            Then: Draft exists with status=PENDING_APPROVAL and not posted

TC-F003-02 createDraft_unbalancedLegs_returnsBadRequest
            Given: DEBIT 100 + CREDIT 99
            When: createDraft(request)
            Then: HTTP 400 with errorCode=JOURNAL_UNBALANCED

TC-F003-03 approveDraft_validChecker_adjustmentPosted
            Given: Draft status=PENDING_APPROVAL with balance=1000
            When: approveDraft(draftId, checkerId="checker-001")
            Then: Draft status=EXECUTED and Journal posted with balance change

TC-F003-04 approveDraft_samePersonAsMaker_throwsMakerCheckerSamePersonException
            Given: makerId="ops-001"
            When: approveDraft(draftId, checkerId="ops-001")
            Then: Throws MakerCheckerSamePersonException and Draft is not executed

TC-F003-05 approveDraft_expiredDraft_throwsDraftExpiredException
            Given: Draft expiresAt has expired
            When: approveDraft(draftId)
            Then: Throws DraftExpiredException

TC-F003-06 approveDraft_alreadyExecutedDraft_throwsDraftNotPendingException
            Given: Draft status=EXECUTED
            When: approveDraft(draftId)
            Then: Throws DraftNotPendingException

TC-F003-07 rejectDraft_validChecker_draftStatusRejected
            Given: Draft status=PENDING_APPROVAL
            When: rejectDraft(draftId, checkerId, rejectReason)
            Then: Draft status=REJECTED and not posted

TC-F003-08 approveDraft_idempotent_sameRequestIdTwice_notDoublePosted
            Given: approveRequestId="appr-001" first time successfully posted
            When: second approveDraft(same requestId)
            Then: Returns original result and balance is not changed again
```

---

## Module 7

```
TC-F005-01 getBalance_activeAccount_returnsCurrentBalance
            Given: CLIENT_ACC_001 AVAILABLE_BALANCE/USD = 700.00
            When: getBalance("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD")

TC-F005-02 getBalance_afterPosting_immediatelyReflectsNewBalance
            Then: amount=700.00

TC-F005-03 getBalance_tradeAheadNegativeBalance_returnsNegativeValue
            Given: TRADE_AHEAD_BALANCE/USD = -45000.00
            When: getBalance(...)

TC-F005-04 getBalance_unknownAccount_throwsAccountNotFoundException
            When: getBalance(...)

TC-F005-05 getBatchBalances_multipleAccounts_allReturnedCorrectly
            When: getBatchBalances

TC-F005-06 getAsOfBalance_historicalSnapshot_returnsSnapshotBalance
            When: getAsOfBalance("CLIENT_ACC_001", asOf="2026-05-15")
```

```
TC-F005-01 getBalance_activeAccount_returnsCurrentBalance
            Given: CLIENT_ACC_001 AVAILABLE_BALANCE/USD = 700.00 (State Machine latest value)
            When: getBalance("CLIENT_ACC_001", "AVAILABLE_BALANCE", "USD")
            Then: amount=700.00 and dataSource=STATE_MACHINE

TC-F005-02 getBalance_afterPosting_immediatelyReflectsNewBalance
            Given: balance=1000.00 and DEBIT 300 executed
            When: getBalance immediately (same request cycle)
            Then: amount=700.00 (no delay, strongly consistent)

TC-F005-03 getBalance_tradeAheadNegativeBalance_returnsNegativeValue
            Given: TRADE_AHEAD_BALANCE/USD = -45000.00
            When: getBalance(...)
            Then: amount=-45000.00 and allowNegative=true

TC-F005-04 getBalance_unknownAccount_throwsAccountNotFoundException
            Given: accountId does not exist
            When: getBalance(...)
            Then: Throws AccountNotFoundException

TC-F005-05 getBatchBalances_multipleAccounts_allReturnedCorrectly
            Given: 200 accounts each with different balance
            When: getBatchBalances([200 keys])
            Then: Returns 200 correct balances with dataSource=STATE_MACHINE

TC-F005-06 getAsOfBalance_historicalSnapshot_returnsSnapshotBalance
            Given: EOD Snapshot at 2026-05-15 with CLIENT balance=500.00
                   Today's balance=700.00
            When: getAsOfBalance("CLIENT_ACC_001", asOf="2026-05-15")
            Then: amount=500.00 and dataSource=EOD_SNAPSHOT
```

---

## Module 8

```
TC-F006-01 getJournal_existingJournalId_returnsJournalWithLines
            When: getJournal("JNL-001")

TC-F006-02 getJournalsByAccount_withFilters_returnsPagedResults
            When: getJournals(accountId="CLIENT_ACC_001", page=0, size=50)

TC-F006-03 getJournalsByBusinessEventRef_rfqId_returnsAllRelatedJournals
            When: getJournals(businessEventRef="RFQ-001")

TC-F006-04 getJournalChain_originalJournal_returnsFullChain
            When: getChain(originalJournalId)

TC-F006-05 getJournalsByRequestId_confirmsIdempotency
            When: getJournals(requestId="req-abc")
```

```
TC-F006-01 getJournal_existingJournalId_returnsJournalWithLines
            Given: JNL-001 has been synced to MySQL View Layer
            When: getJournal("JNL-001")
            Then: Returns Journal and all JournalLines with dataSource=VIEW_LAYER

TC-F006-02 getJournalsByAccount_withFilters_returnsPagedResults
            Given: CLIENT_ACC_001 has 1250 Journals
            When: getJournals(accountId="CLIENT_ACC_001", page=0, size=50)
            Then: Returns 50 records with totalCount=1250

TC-F006-03 getJournalsByBusinessEventRef_rfqId_returnsAllRelatedJournals
            Given: RFQ-001 has original Journal + Reversal Journal
            When: getJournals(businessEventRef="RFQ-001")
            Then: Returns 2 records including NORMAL + REVERSAL types

TC-F006-04 getJournalChain_originalJournal_returnsFullChain
            Given: Original → Reversal → Rebook three Journals
            When: getChain(originalJournalId)
            Then: Chain contains 3 records with relationships correctly marked

TC-F006-05 getJournalsByRequestId_confirmsIdempotency
            Given: requestId="req-abc" corresponds to JNL-001
            When: getJournals(requestId="req-abc")
            Then: Returns JNL-001
```

---

## Module 9

```
TC-F007-01 runL1Reconciliation_allJournalsBalanced_noDiscrepancies
            When: runL1Reconciliation(date)
            Then: Report.l1Summary.unbalancedJournals=0

TC-F007-02 runL1Reconciliation_unbalancedJournal_discrepancyDetected
            When: runL1Reconciliation(date)

TC-F007-03 runL1Reconciliation_balanceMismatch_detectedAndCaseCreated
            Given: State Machine balance ≠ MySQL balance
            When: runL1Reconciliation(date)

TC-F007-04 runL2Reconciliation_subAccountsSumMatchControl_noCases
            When: runL2Reconciliation(rule="RFQ-USD-CONTROL")

TC-F007-05 runL2Reconciliation_subAccountsSumMismatch_caseCreated
                   tolerance=0.01
            When: runL2Reconciliation(rule)

TC-F007-06 runL3Reconciliation_externalFileMatched_allMatched
            When: runL3Reconciliation(externalFile)

TC-F007-07 runL3Reconciliation_internalOnly_caseCreated
            When: runL3Reconciliation(externalFile)

TC-F007-08 runL3Reconciliation_amountMismatch_caseCreated
            When: runL3Reconciliation(externalFile)
```

```
TC-F007-01 runL1Reconciliation_allJournalsBalanced_noDiscrepancies
            Given: 100 Journals, all balanced
            When: runL1Reconciliation(date)
            Then: Report.l1Summary.unbalancedJournals=0

TC-F007-02 runL1Reconciliation_unbalancedJournal_discrepancyDetected
            Given: 1 Journal artificially modified to be unbalanced (injected test)
            When: runL1Reconciliation(date)
            Then: Report.l1Summary.unbalancedJournals=1 and Case created

TC-F007-03 runL1Reconciliation_balanceMismatch_detectedAndCaseCreated
            Given: State Machine balance ≠ MySQL balance (injected inconsistency)
            When: runL1Reconciliation(date)
            Then: balanceConsistencyPassed=false and Case created

TC-F007-04 runL2Reconciliation_subAccountsSumMatchControl_noCases
            Given: 10 CLIENT accounts USD each 100, CONTROL_CLIENT_USD = 1000
            When: runL2Reconciliation(rule="RFQ-USD-CONTROL")
            Then: rulesPassed=1 and no Case

TC-F007-05 runL2Reconciliation_subAccountsSumMismatch_caseCreated
            Given: 10 CLIENT accounts USD each 100 (total 1000), CONTROL = 999 (difference 1)
                   tolerance=0.01
            When: runL2Reconciliation(rule)
            Then: rulesFailed=1 and Case created with discrepancyAmount=1.00

TC-F007-06 runL3Reconciliation_externalFileMatched_allMatched
            Given: 100 external settlements, 100 internal Journals, keys exactly match
            When: runL3Reconciliation(externalFile)
            Then: matched=100, internalOnly=0, externalOnly=0

TC-F007-07 runL3Reconciliation_internalOnly_caseCreated
            Given: 1 internal Journal without match in external settlement file
            When: runL3Reconciliation(externalFile)
            Then: internalOnly=1 and Case created with type=INTERNAL_ONLY

TC-F007-08 runL3Reconciliation_amountMismatch_caseCreated
            Given: Same externalRef with internal 800.00 and external 810.00
            When: runL3Reconciliation(externalFile)
            Then: amountMismatch=1 and Case created with discrepancyAmount=10.00
```

---

## Module 10

```
TC-F009-01 closeperiod_openPeriod_eodTasksExecutedInOrder
            When: triggerEOD("2026-05-16")

TC-F009-02 postDuringClosing_returnsperiodClosedError
            When: post(PostingRequest)

TC-F009-03 postToClosedPeriod_returnsperiodClosedError
            When: post

TC-F009-04 reverseInClosedPeriod_allowedWithCrossPeriodFlag
            When: reverse(...)

TC-F009-05 eodBalanceSnapshot_matchesStateMachine
```

```
TC-F009-01 closePeriod_openPeriod_eodTasksExecutedInOrder
            Given: Period 2026-05-16 status=OPEN
            When: triggerEOD("2026-05-16")
            Then: EOD steps execute in order (Snapshot → Recon → Snapshot → Report → CLOSED)

TC-F009-02 postDuringClosing_returnsPeriodClosedError
            Given: Period status=CLOSING
            When: post(PostingRequest)
            Then: PostingResult.status=REJECTED with errorCode=PERIOD_CLOSED

TC-F009-03 postToClosedPeriod_returnsPeriodClosedError
            Given: Period status=CLOSED
            When: post(PostingRequest with valueDate in closed period)
            Then: PostingResult.status=REJECTED with errorCode=PERIOD_CLOSED

TC-F009-04 reverseInClosedPeriod_allowedWithCrossPeriodFlag
            Given: Original Journal valueDate in CLOSED period
            When: reverse(...)
            Then: ReversalResult.status=COMPLETED with crossPeriod=true

TC-F009-05 eodBalanceSnapshot_matchesStateMachine
            Given: EOD execution completed
            When: Compare EOD Snapshot balance vs State Machine balance (same point in time)
            Then: All account balances are exactly the same with difference 0
```

---

## Module 11

```
TC-ROCKS-01 writeBatch_atomic_journalAndBalanceConsistentAfterCrash

TC-ROCKS-02 walReplay_afterCleanRestart_balanceRecovered

TC-ROCKS-03 columnFamilyIsolation_writeToOneCF_notAffectOthers
```

```
TC-ROCKS-01 writeBatch_atomic_journalAndBalanceConsistentAfterCrash
             Given: Simulate JVM crash during WriteBatch write (injection)
             When: Read from RocksDB after restart
             Then: Both journal and balance either exist together or not at all (atomicity)

TC-ROCKS-02 walReplay_afterCleanRestart_balanceRecovered
             Given: Execute 100 PostingCommands, shutdown normally
             When: Restart and replay from WAL + Snapshot
             Then: All balances are exactly the same as before shutdown

TC-ROCKS-03 columnFamilyIsolation_writeToOneCF_notAffectOthers
             Given: Write one record to CF_JOURNAL
             When: Read CF_BALANCE
             Then: CF_BALANCE is unaffected and keys do not conflict
```

### 11.1 BalanceChangeEvent

```
TC-F011-01 publishEvent_firstPosting_accountSeqIsOne
            When: State Machine apply → publish BalanceChangeEvent
            Then: event.accountSeq == 1
                   event.prevAccountSeq == 0

TC-F011-02 publishEvent_subsequentPosting_accountSeqIncremented
            Then: event.accountSeq == 42
                   event.prevAccountSeq == 41

TC-F011-03 publishEvent_reversal_accountSeqIncremented
            When: Reversal apply → publish
            Then: event.accountSeq == 11
                   event.prevAccountSeq == 10

TC-F011-04 consumer_detectsGap_whenSeqNotConsecutive

TC-F011-05 consumer_noDuplicateAlert_whenIdempotentRetry

TC-F011-06 restartNode_outboxResend_accountSeqUnchanged

TC-F011-07 multiBalanceType_samePosting_seqIndependentPerKey

                   CLIENT_ACC_001 AVAILABLE_BALANCE/USD
                   CLIENT_ACC_001 TRADE_AHEAD_BALANCE/USD
            Then: AVAILABLE_BALANCE event.accountSeq == 11
                   TRADE_AHEAD_BALANCE event.accountSeq == 6

```

### 11.1 BalanceChangeEvent

```
TC-F011-01 publishEvent_firstPosting_accountSeqIsOne
            Given: CLIENT_ACC_001 AVAILABLE_BALANCE/USD first posting
            When: State Machine apply → publish BalanceChangeEvent
            Then: event.accountSeq == 1
                   event.prevAccountSeq == 0 (represents no predecessor)

TC-F011-02 publishEvent_subsequentPosting_accountSeqIncremented
            Given: Previous event has accountSeq == 41
            When: Next Posting apply → publish
            Then: event.accountSeq == 42
                   event.prevAccountSeq == 41

TC-F011-03 publishEvent_reversal_accountSeqIncremented
            Given: Previous event has accountSeq == 10
            When: Reversal apply → publish
            Then: event.accountSeq == 11
                   event.prevAccountSeq == 10

TC-F011-04 consumer_detectsGap_whenSeqNotConsecutive
            Given: Consumer has received accountSeq == 100
            When: Next received event has accountSeq == 102 (prevAccountSeq == 101)
                   but 101 never arrived
            Then: Consumer detects gap (prevAccountSeq 102 ≠ previous accountSeq 100 + 1) and triggers alarm

TC-F011-05 consumer_noDuplicateAlert_whenIdempotentRetry
            Given: Outbox at-least-once resends the same event
                   event.accountSeq == 50 with same event.idempotencyKey
            When: Consumer receives duplicate event
            Then: Consumer deduplicates by idempotencyKey and does not falsely detect gap
                   (duplicate event has same accountSeq, not a new seq)

TC-F011-06 restartNode_outboxResend_accountSeqUnchanged
            Given: At State Machine apply accountSeq == 77, written to Outbox
                   Node restarts and Outbox resends
            When: Consumer receives resent event
            Then: event.accountSeq is still 77, consistent with original send
                   (seq is determined at apply time; Outbox resend does not change value)

TC-F011-07 multiBalanceType_samePosting_seqIndependentPerKey
            Given: One RFQ Posting simultaneously updates:
                   CLIENT_ACC_001 AVAILABLE_BALANCE/USD (previous seq=10)
                   CLIENT_ACC_001 TRADE_AHEAD_BALANCE/USD (previous seq=5)
            When: Publish two BalanceChangeEvents
            Then: AVAILABLE_BALANCE event.accountSeq == 11
                   TRADE_AHEAD_BALANCE event.accountSeq == 6
                   (seqs for the two keys increment independently and do not affect each other)
```

---

## Module 12

```
TC-NFR-01 posting_p95_under3ms_normalLoad
           Then: P95 ≤ 3ms

TC-NFR-02 posting_hotspotAccount_p95_under3ms

TC-NFR-03 balanceQuery_p95_under2ms
           Given: 10,000 QPS Balance Query
           Then: P95 ≤ 2ms

TC-NFR-04 idempotency_1000Retries_onlyOneJournalCreated

TC-NFR-05 concurrentPosting_noNegativeBalance_noDoubleDebit
```

```
TC-NFR-01 posting_p95_under3ms_normalLoad
           Given: 500 concurrent, each to different accounts
           When: Execute 10,000 Postings
           Then: P95 ≤ 3ms

TC-NFR-02 posting_hotspotAccount_p95_under3ms
           Given: 1000 concurrent, all hitting COMPANY_FX_ACC
           When: Execute 10,000 Postings
           Then: P95 ≤ 3ms with no duplicate postings and precise balance

TC-NFR-03 balanceQuery_p95_under2ms
           Given: 10,000 QPS Balance Query
           When: Continuously query active account balances
           Then: P95 ≤ 2ms

TC-NFR-04 idempotency_1000Retries_onlyOneJournalCreated
           Given: Same requestId
           When: 1000 concurrent retries
           Then: Only 1 Journal exists

TC-NFR-05 concurrentPosting_noNegativeBalance_noDoubleDebit
           Given: CLIENT balance=1000 with 1001 concurrent DEBIT 1
           When: All execute
```

---

## Module 13

```
TC-QUEUE-01 singleAccount_serialization_processedInOrder
             Given: CLIENT_ACC_001 balance=10000, 50 concurrent DEBIT 1 via AccountQueueManager

TC-QUEUE-02 backpressure_exceedingMaxQueueSize_returnsFalse
             Given: MAX_QUEUE_SIZE=1000

TC-QUEUE-03 multipleAccounts_independentQueues
```

```
TC-QUEUE-01 singleAccount_serialization_processedInOrder
             Given: CLIENT_ACC_001 balance=10000 with 50 concurrent DEBIT 1 via AccountQueueManager
             When: All requests complete
             Then: balance=9950, all requests processed serially with no concurrency conflicts

TC-QUEUE-02 backpressure_exceedingMaxQueueSize_returnsFalse
             Given: MAX_QUEUE_SIZE=1000
             When: Submit > 1000 requests to the same account
             Then: Excess returns false (backpressure) with queue depth ≤ 1000

TC-QUEUE-03 multipleAccounts_independentQueues
             Given: ACC_A and ACC_B each have independent queues
             When: Submit 100 CREDITs to both accounts simultaneously
             Then: Both process independently in parallel with no errors
```

---

```
TC-QUEUE-OBX-01 concurrentRequests_produceCorrectEvents
                 When: Account Queue serialized → StateMachine apply → Event publish

TC-QUEUE-OBX-02 hotspotAccount_concurrentCredits_allSucceed
```

```
TC-QUEUE-OBX-01 concurrentRequests_produceCorrectEvents
                 Given: CLIENT_ACC_001 + COMPANY_FX_ACC with 20 concurrent Postings (2 JournalLines each)
                 When: Account Queue serialized → StateMachine apply → Event publish
                 Then: 40 BalanceChangeEvents with CLIENT side accountSeq strictly increasing

TC-QUEUE-OBX-02 hotspotAccount_concurrentCredits_allSucceed
                 Given: COMPANY_FX_ACC hotspot with 50 concurrent CREDITs of 10.00 each
                 When: Account Queue processes COMPANY account serially
                 Then: COMPANY balance = 100000 + 500 = 100500, 100 events with no duplicates
```

---

```
TC-RAFT-01 threeNodeCluster_leaderElectionAndLogReplication
            When: start → wait leader → submit PostingCommand

TC-RAFT-02 cluster_survivesFollowerRestart
            Given: 3-node cluster with elected leader
```

```
TC-RAFT-01 threeNodeCluster_leaderElectionAndLogReplication
            Given: 3 RaftNodeManagers on 127.0.0.1:18081/2/3
            When: start → wait for leader → submit PostingCommand
            Then: Leader elected in < 1s and journal replicated to all 3 nodes

TC-RAFT-02 cluster_survivesFollowerRestart
            Given: 3-node cluster with elected leader
            When: Follower shuts down and restarts
            Then: Follower rejoins cluster with no errors
```

---

## Module 16

```
TC-KAFKA-01 posting_publishesBalanceChangeEvent_toKafka
             Given: Kafka broker (Testcontainers), CLIENT_ACC_001 balance=1000
             When: StateMachine.applyPosting DEBIT 100 → KafkaEventPublisher flush
             Then: Kafka consumer receives 1+ record with BALANCE_CHANGE, accountSeq field

TC-KAFKA-02 multiplePostings_produceSequentialAccountSeq
             Given: Kafka broker, CLIENT_ACC_001
             When: 5 sequential DEBIT 10 postings → publish to Kafka
             Then: Kafka consumer receives 5 records with monotonically increasing accountSeq
```

```
TC-KAFKA-01 posting_publishesBalanceChangeEvent_toKafka
             Given: Kafka broker (Testcontainers), CLIENT_ACC_001 balance=1000
             When: StateMachine.applyPosting DEBIT 100 → KafkaEventPublisher flush
             Then: Kafka consumer receives 1+ record with BALANCE_CHANGE and accountSeq field

TC-KAFKA-02 multiplePostings_produceSequentialAccountSeq
             Given: Kafka broker, CLIENT_ACC_001
             When: 5 sequential DEBIT 10 postings → publish to Kafka
             Then: Kafka consumer receives 5 records with monotonically increasing accountSeq
```

---

```
Phase 1
  TC-F001-* → TC-F010-* → TC-F008-01~10

Phase 2
  TC-F002-01~07
  TC-F008-11~15
  TC-F004-01~07
  TC-F003-01~08

Phase 3
  TC-F005-*
  TC-F006-*

Phase 3.5
  TC-F008-19~26
  TC-F011-01~07

Phase 4
  TC-F007-*
  TC-F009-*

Phase 5
  TC-ROCKS-*
  TC-NFR-*

Phase 6
  TC-F002-08~09
  TC-NFR-04~05
```

## Recommended Test Execution Order (TDD Red-Green Order)

```
Phase 1 — Foundation Model
  TC-F001-* → TC-F010-* → TC-F008-01~10 (Balance Operations)

Phase 2 — Core Write Path
  TC-F002-01~07 (Posting Basics)
  TC-F008-11~15 (Reversal State Machine)
  TC-F004-01~07 (Reversal API)
  TC-F003-01~08 (Manual Adjustment)

Phase 3 — Read Path
  TC-F005-* (Balance Query)
  TC-F006-* (Journal Query)

Phase 3.5
  TC-F008-19~26 (State Machine accountSeq)
  TC-F011-01~07 (BalanceChangeEvent accountSeq)

Phase 4 — Reconciliation and Period
  TC-F007-* (Reconciliation)
  TC-F009-* (Accounting Period)

Phase 5 — Persistence and Performance
  TC-ROCKS-* (RocksDB Persistence)
  TC-NFR-* (Performance / Load)

Phase 6 — Concurrency Safety
  TC-F002-08~09 (Concurrent Posting)
  TC-NFR-04~05 (Idempotency + Concurrency)
```
