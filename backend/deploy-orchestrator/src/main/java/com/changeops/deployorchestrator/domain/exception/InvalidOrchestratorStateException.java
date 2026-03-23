package com.changeops.deployorchestrator.domain.exception;

public class InvalidOrchestratorStateException extends RuntimeException {
    public InvalidOrchestratorStateException(String message) {
        super(message);
    }
}
