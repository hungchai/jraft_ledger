package com.tomma8.ledger.domain.event;

@FunctionalInterface
public interface LedgerEventListener {
    void onEvent(BalanceChangeEvent event);

    default void onAccountCreated(AccountCreatedEvent event) {
        // no-op by default — implementations can override
    }
}
