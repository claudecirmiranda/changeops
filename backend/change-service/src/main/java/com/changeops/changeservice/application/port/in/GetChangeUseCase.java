package com.changeops.changeservice.application.port.in;

import com.changeops.changeservice.domain.valueobject.ChangeStatus;

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
            ChangeStatus status,
            UUID correlationId,
            Instant scheduledAt,
            Instant createdAt,
            Instant updatedAt
    ) {}
}
