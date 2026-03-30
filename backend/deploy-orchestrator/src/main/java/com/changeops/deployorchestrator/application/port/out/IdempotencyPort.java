package com.changeops.deployorchestrator.application.port.out;

import java.util.UUID;

public interface IdempotencyPort {
    boolean isAlreadyProcessed(UUID eventId, String serviceName);
    void markAsProcessed(UUID eventId, String serviceName);

    /**
     * Atomically checks and marks an event as processed.
     * @return true if the event was newly marked (first time), false if already processed.
     */
    boolean tryMarkAsProcessed(UUID eventId, String serviceName);
}
