package com.changeops.deployorchestrator.domain.event;

import java.time.Instant;
import java.util.UUID;

public record DeployFinishedEvent(
        String eventType,
        String version,
        UUID correlationId,
        Instant occurredAt,
        Payload payload
) {
    public record Payload(
            UUID deployId,
            UUID changeId,
            String result,
            Instant executedAt
    ) {}

    public boolean isSuccess() {
        return "SUCCESS".equalsIgnoreCase(payload().result());
    }
}
