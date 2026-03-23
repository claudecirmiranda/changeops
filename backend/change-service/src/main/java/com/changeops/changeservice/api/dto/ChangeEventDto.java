package com.changeops.changeservice.api.dto;

import java.time.Instant;
import java.util.UUID;

public record ChangeEventDto(
        UUID eventId,
        UUID changeId,
        String eventType,
        String payload,
        Instant occurredAt
) {}
