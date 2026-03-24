package com.changeops.changeservice.api.dto;

import java.time.Instant;
import java.util.UUID;

public record ChangeDetailDto(
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
