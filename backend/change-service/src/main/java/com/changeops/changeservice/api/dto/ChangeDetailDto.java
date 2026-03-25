package com.changeops.changeservice.api.dto;

import com.changeops.changeservice.domain.valueobject.ChangeStatus;

import java.time.Instant;
import java.util.UUID;

public record ChangeDetailDto(
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
