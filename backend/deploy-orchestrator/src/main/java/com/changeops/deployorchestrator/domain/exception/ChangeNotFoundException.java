package com.changeops.deployorchestrator.domain.exception;

/**
 * Thrown when a referenced changeId does not exist in the database.
 * This is a retryable condition: the change record may not yet be visible
 * due to eventual consistency or transient database unavailability.
 * Unlike {@link InvalidOrchestratorStateException}, this exception is NOT
 * in the {@code @RetryableTopic} exclude list — the consumer will retry
 * with exponential backoff before routing to the DLT.
 */
public class ChangeNotFoundException extends RuntimeException {
    public ChangeNotFoundException(String message) {
        super(message);
    }
}
