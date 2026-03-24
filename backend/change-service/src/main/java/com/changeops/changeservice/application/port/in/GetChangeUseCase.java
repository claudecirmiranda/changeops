package com.changeops.changeservice.application.port.in;

import java.time.Instant;
import java.util.UUID;

public interface GetChangeUseCase {

    Result execute(UUID changeId);

    record Result(
            UUID changeId,
            String title,
            String description,
            String componentId,
            String requestedBy,
            String status,
            UUID correlationId,
            Instant scheduledAt,
            Instant createdAt,
            Instant updatedAt
    ) {}
}
