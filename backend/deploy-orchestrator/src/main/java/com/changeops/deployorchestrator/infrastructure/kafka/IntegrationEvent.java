package com.changeops.deployorchestrator.infrastructure.kafka;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record IntegrationEvent(
        String eventType,
        String version,
        UUID correlationId,
        Instant occurredAt,
        Object payload
) {
    public record ChangeCompletedPayload(
            UUID changeId,
            UUID deployId,
            Instant completedAt) {}

    public record ChangeFailedPayload(
            UUID changeId,
            UUID deployId,
            String reason,
            Instant failedAt) {}
}
