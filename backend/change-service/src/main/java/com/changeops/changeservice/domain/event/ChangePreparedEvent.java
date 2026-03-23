package com.changeops.changeservice.domain.event;

import java.time.Instant;
import java.util.UUID;

public record ChangePreparedEvent(
        UUID changeId,
        String componentId,
        String requestedBy,
        Instant scheduledAt,
        UUID correlationId,
        Instant occurredAt
) {}
