package com.changeops.changeservice.application.port.in;

import com.changeops.changeservice.domain.valueobject.ChangeStatus;

import java.time.Instant;
import java.util.UUID;

public interface CreateChangeUseCase {

    Result execute(Command command);

    record Command(
            String title,
            String description,
            String componentId,
            String requestedBy,
            Instant scheduledAt,
            UUID correlationId
    ) {}

    record Result(
            UUID changeId,
            ChangeStatus status,
            UUID correlationId,
            Instant createdAt
    ) {}
}
