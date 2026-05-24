package com.tomma8.ledger.client;

public class LedgerClientException extends RuntimeException {

    public static final String NOT_LEADER = "NOT_LEADER";
    public static final String NO_LEADER_AVAILABLE = "NO_LEADER_AVAILABLE";
    public static final String CLUSTER_UNAVAILABLE = "CLUSTER_UNAVAILABLE";
    public static final String MAX_RETRIES_EXCEEDED = "MAX_RETRIES_EXCEEDED";
    public static final String TIMEOUT = "TIMEOUT";
    public static final String IO_ERROR = "IO_ERROR";
    public static final String SERIALIZATION_ERROR = "SERIALIZATION_ERROR";

    private final String errorCode;

    public LedgerClientException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public LedgerClientException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
