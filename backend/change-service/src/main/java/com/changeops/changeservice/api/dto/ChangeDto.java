package com.changeops.changeservice.api.dto;

import java.time.Instant;
import java.util.UUID;

public record ChangeDto(
        UUID changeId,
        String title,
        String componentId,
        String status,
        UUID correlationId,
        Instant createdAt,
        Instant updatedAt
) {}
