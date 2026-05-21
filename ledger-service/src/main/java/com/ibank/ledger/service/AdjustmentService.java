package com.ibank.ledger.service;

import com.ibank.ledger.domain.command.CommandResult;
import com.ibank.ledger.domain.command.PostingCommand;
import com.ibank.ledger.domain.exception.*;
import com.ibank.ledger.domain.model.*;
import com.ibank.ledger.statemachine.LedgerStateMachine;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * F-003 Manual Adjustment with Maker-Checker.
 */
public class AdjustmentService {

    private final LedgerStateMachine stateMachine;
    private final ConcurrentHashMap<String, AdjustmentDraft> drafts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CommandResult> approveResults = new ConcurrentHashMap<>();

    public AdjustmentService(LedgerStateMachine stateMachine) {
        this.stateMachine = stateMachine;
    }

    public AdjustmentDraft createDraft(PostingCommand command, String makerId) {
        // Validate journal balance
        validateBalanced(command);

        String draftId = "DRAFT-" + UUID.randomUUID().toString().substring(0, 8);
        AdjustmentDraft draft = new AdjustmentDraft(
                draftId, command, makerId, DraftStatus.PENDING_APPROVAL,
                Instant.now(), Instant.now().plusSeconds(86400)); // 24h
        drafts.put(draftId, draft);
        return draft;
    }

    public PostingCommand validateDraftForApproval(String draftId, String checkerId) {
        CommandResult cached = approveResults.get(draftId);
        if (cached != null && cached.isCompleted()) {
            throw new IllegalArgumentException("Draft already approved: " + draftId);
        }

        AdjustmentDraft draft = drafts.get(draftId);
        if (draft == null) {
            throw new IllegalArgumentException("Draft not found: " + draftId);
        }
        if (draft.makerId().equals(checkerId)) {
            throw new MakerCheckerSamePersonException();
        }
        if (draft.isExpired()) {
            throw new DraftExpiredException(draftId);
        }
        if (draft.status() != DraftStatus.PENDING_APPROVAL) {
            throw new DraftNotPendingException(draftId);
        }
        return draft.command();
    }

    public void recordApproveResult(String draftId, String approveRequestId, CommandResult result) {
        if (result.isCompleted()) {
            drafts.computeIfPresent(draftId, (k, d) -> d.approve());
        }
        approveResults.put(approveRequestId, result);
    }

    public CommandResult approveDraft(String draftId, String checkerId, String approveRequestId) {
        PostingCommand cmd = validateDraftForApproval(draftId, checkerId);
        CommandResult result = stateMachine.applyPosting(cmd);
        recordApproveResult(draftId, approveRequestId, result);
        return result;
    }

    public void rejectDraft(String draftId, String checkerId, String reason) {
        AdjustmentDraft draft = drafts.get(draftId);
        if (draft == null) {
            throw new IllegalArgumentException("Draft not found: " + draftId);
        }
        if (draft.makerId().equals(checkerId)) {
            throw new MakerCheckerSamePersonException();
        }
        drafts.put(draftId, draft.reject());
    }

    public AdjustmentDraft getDraft(String draftId) {
        return drafts.get(draftId);
    }

    void addDraftForTest(AdjustmentDraft draft) {
        drafts.put(draft.draftId(), draft);
    }

    private void validateBalanced(PostingCommand cmd) {
        BigDecimal debitTotal = BigDecimal.ZERO;
        BigDecimal creditTotal = BigDecimal.ZERO;
        for (var leg : cmd.legs()) {
            for (var line : leg.lines()) {
                if (line.entryType() == EntryType.DEBIT) {
                    debitTotal = debitTotal.add(line.amount());
                } else {
                    creditTotal = creditTotal.add(line.amount());
                }
            }
        }
        if (debitTotal.compareTo(creditTotal) != 0) {
            throw new IllegalArgumentException("JOURNAL_UNBALANCED: debit=" + debitTotal + " credit=" + creditTotal);
        }
    }
}
