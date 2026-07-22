package com.tomma8.ledger.domain.model;

/**
 * Centralized error code enum for Ledger Platform.
 * Single source of truth matching requirement validation rules.
 * 
 * @see requirement/en/LEDGER-PLATFORM-FULL-REQUIREMENTS.md
 */
public enum LedgerErrorCode {
    
    // ── Pre-validation (Network Layer, V-01 ~ V-05) ─────────────────
    INVALID_REQUEST_ID("V-01: requestId format invalid"),
    LEGS_EMPTY("V-02: legs contains at least one"),
    BALANCE_TYPE_NOT_FOUND("V-03: balanceType not found in Registry or INACTIVE"),
    INVALID_AMOUNT("V-04: amount must be > 0"),
    JOURNAL_UNBALANCED("V-05: DEBIT total != CREDIT total per currency"),
    INVALID_LEG_AMOUNT("Leg amount must be positive"),
    
    // ── Business Validation (State Machine, V-08 ~ V-13) ─────────────
    ACCOUNT_NOT_FOUND("V-08: accountId does not exist"),
    BALANCE_NOT_INITIALIZED("V-09: balanceType + position + currency not initialized"),
    INSUFFICIENT_BALANCE("V-10: DEBIT would push balance below 0 (allowNegative=false)"),
    CREDIT_EXCEEDS_LIMIT("V-11: CREDIT would push balance above 0 (allowNegative=true)"),
    ACCOUNT_FROZEN("V-12: account status is FROZEN"),
    POSITION_BALANCE_FLOOR_BREACH("V-13: LOCKED/FROZEN position cannot go negative"),
    
    // ── Account Management ───────────────────────────────────────────
    ACCOUNT_ALREADY_EXISTS("Account already exists with different type/owner"),
    
    // ── Reversal (V-05 ~ V-07) ───────────────────────────────────────
    JOURNAL_NOT_FOUND("V-05: originalJournalId does not exist"),
    JOURNAL_ALREADY_REVERSED("V-06: journal status is already REVERSED"),
    CANNOT_REVERSE_REVERSAL("V-07: cannot reverse a REVERSAL journal"),
    
    // ── Maker-Checker (F-003) ────────────────────────────────────────
    MAKER_CHECKER_SAME_PERSON("V-05: checkerId must differ from makerId"),
    DRAFT_EXPIRED("V-06: draft has expired"),
    DRAFT_NOT_PENDING("V-07: draft status is not PENDING_APPROVAL"),
    INVALID_ADJUSTMENT_TYPE("V-04: adjustmentType invalid"),
    
    // ── Seed Posting ─────────────────────────────────────────────────
    SEED_NOT_ALLOWED_FOR_CLIENT("Seed posting not allowed for CLIENT accounts"),
    SEED_NOT_ALLOWED_FOR_CONTROL("Seed posting not allowed for CONTROL accounts"),
    
    // ── Infrastructure ───────────────────────────────────────────────
    NOT_LEADER("Current node is not Raft leader"),
    QUEUE_FULL("Account queue depth exceeds MAX_QUEUE_SIZE"),
    PERIOD_CLOSED("Accounting period is CLOSING or CLOSED"),
    RAFT_APPLY_ERROR("Raft state machine apply encountered an unexpected error");
    
    private final String description;
    
    LedgerErrorCode(String description) {
        this.description = description;
    }
    
    public String description() {
        return description;
    }
}
