package com.tomma8.ledger.domain.event;

public interface LedgerEventListener {
    void onEvent(BalanceChangeEvent event);

    default void onAccountCreated(AccountCreatedEvent event) {
        // no-op by default — implementations can override
    }

    /**
     * Bundled per-journal emission. Replaces per-line {@link #onEvent}
     * calls on the hot path so a multi-line posting produces one Kafka
     * record instead of N. Default is to fall back to per-event emission
     * (preserves existing tests / custom listeners).
     */
    default void onPosting(JournalEventEnvelope envelope) {
        for (BalanceChangeEvent event : envelope.events()) {
            onEvent(event);
        }
    }
}
