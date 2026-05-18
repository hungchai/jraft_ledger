package com.ibank.ledger.rocksdb;

import com.ibank.ledger.domain.model.AccountBalanceKey;

public final class RocksDBKeySerializer {

    private static final String SEPARATOR = "#";

    private RocksDBKeySerializer() {}

    // CF_BALANCE: account_id#balance_type#currency
    public static byte[] balanceKey(String accountId, String balanceType, String currency) {
        return (accountId + SEPARATOR + balanceType + SEPARATOR + currency).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public static byte[] balanceKey(AccountBalanceKey key) {
        return balanceKey(key.accountId(), key.balanceType(), key.currency());
    }

    // CF_BALANCE prefix scan: account_id#
    public static byte[] balancePrefix(String accountId) {
        return (accountId + SEPARATOR).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    // CF_JOURNAL: journal_id
    public static byte[] journalKey(String journalId) {
        return journalId.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    // CF_JOURNAL_LINE: journal_id#journal_line_id
    public static byte[] journalLineKey(String journalId, String journalLineId) {
        return (journalId + SEPARATOR + journalLineId).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    // CF_JOURNAL_LINE prefix scan: journal_id#
    public static byte[] journalLinePrefix(String journalId) {
        return (journalId + SEPARATOR).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    // CF_IDEMPOTENCY: request_id
    public static byte[] idempotencyKey(String requestId) {
        return requestId.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    // CF_ACCOUNT_META: account_id
    public static byte[] accountMetaKey(String accountId) {
        return accountId.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    // CF_BALANCE_TYPE: type_code
    public static byte[] balanceTypeKey(String typeCode) {
        return typeCode.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
