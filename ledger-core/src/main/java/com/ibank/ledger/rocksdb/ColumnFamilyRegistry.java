package com.ibank.ledger.rocksdb;

public final class ColumnFamilyRegistry {

    private ColumnFamilyRegistry() {}

    public static final String CF_JOURNAL = "journal";
    public static final String CF_JOURNAL_LINE = "journal_line";
    public static final String CF_BALANCE = "balance";
    public static final String CF_IDEMPOTENCY = "idempotency";
    public static final String CF_ACCOUNT_META = "account_meta";
    public static final String CF_BALANCE_TYPE = "balance_type";
    public static final String CF_SM_SNAPSHOT = "sm_snapshot";

    // Default column family (RocksDB default)
    public static final String CF_DEFAULT = "default";

    public static String[] allColumnFamilies() {
        return new String[]{
                CF_DEFAULT, CF_JOURNAL, CF_JOURNAL_LINE, CF_BALANCE,
                CF_IDEMPOTENCY, CF_ACCOUNT_META, CF_BALANCE_TYPE, CF_SM_SNAPSHOT
        };
    }
}
