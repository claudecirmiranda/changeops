package com.changeops.changeservice.application.port.in;

import java.time.Instant;
import java.util.UUID;

public interface CreateChangeUseCase {

    Result execute(Command command);

    record Command(
            String title,
            String description,
            String componentId,
            String requestedBy,
            Instant scheduledAt
    ) {}

    record Result(
            UUID changeId,
            String status,
            UUID correlationId,
            Instant createdAt
    ) {}
}
