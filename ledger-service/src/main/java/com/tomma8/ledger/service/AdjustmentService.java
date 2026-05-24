package com.tomma8.ledger.service;

import com.tomma8.ledger.domain.command.AdjustmentCommand;
import com.tomma8.ledger.domain.command.CommandResult;
import com.tomma8.ledger.domain.command.PostingCommand;
import com.tomma8.ledger.domain.exception.*;
import com.tomma8.ledger.domain.model.*;
import com.tomma8.ledger.statemachine.LedgerStateMachine;

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
        CommandResult cached = approveResults.get(approveRequestId);
        if (cached != null) {
            return cached;
        }
        PostingCommand cmd = validateDraftForApproval(draftId, checkerId);
        AdjustmentCommand adjCmd = new AdjustmentCommand(
                cmd.requestId(), cmd.businessEventType(), cmd.businessEventRef(),
                cmd.valueDate(), cmd.legs(), "MANUAL_ADJUSTMENT", draftId);
        CommandResult result = stateMachine.applyAdjustment(adjCmd);
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
            // Each leg's amount is applied to all its lines
            for (var line : leg.lines()) {
                if (line.entryType() == EntryType.DEBIT) {
                    debitTotal = debitTotal.add(leg.amount());
                } else {
                    creditTotal = creditTotal.add(leg.amount());
                }
            }
        }
        if (debitTotal.compareTo(creditTotal) != 0) {
            throw new IllegalArgumentException("JOURNAL_UNBALANCED: debit=" + debitTotal + " credit=" + creditTotal);
        }
    }
}
