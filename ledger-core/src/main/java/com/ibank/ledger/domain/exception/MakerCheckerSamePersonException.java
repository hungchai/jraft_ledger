package com.ibank.ledger.domain.exception;

public class MakerCheckerSamePersonException extends RuntimeException {
    public MakerCheckerSamePersonException() {
        super("Maker and Checker cannot be the same person");
    }
}
