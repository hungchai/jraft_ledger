package com.ibank.ledger.domain.event;

@FunctionalInterface
public interface LedgerEventListener {
    void onEvent(BalanceChangeEvent event);
}
