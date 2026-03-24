package com.changeops.changeservice.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface LoadChangeEventsPort {

    List<ChangeEventResult> findByChangeId(UUID changeId);

    record ChangeEventResult(
            UUID eventId,
            UUID changeId,
            String eventType,
            String payload,
            Instant occurredAt
    ) {}
}
