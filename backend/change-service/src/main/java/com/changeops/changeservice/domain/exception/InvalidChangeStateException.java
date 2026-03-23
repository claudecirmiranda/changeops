package com.changeops.changeservice.domain.exception;

public class InvalidChangeStateException extends RuntimeException {
    public InvalidChangeStateException(String message) {
        super(message);
    }
}
