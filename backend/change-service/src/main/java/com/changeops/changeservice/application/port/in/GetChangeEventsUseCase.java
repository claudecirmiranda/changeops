package com.changeops.changeservice.application.port.in;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface GetChangeEventsUseCase {

    List<Result> execute(UUID changeId);

    record Result(
            UUID eventId,
            UUID changeId,
            String eventType,
            String payload,
            Instant occurredAt
    ) {}
}
