package com.changeops.changeservice.api.dto;

import com.changeops.changeservice.domain.valueobject.ChangeStatus;

import java.time.Instant;
import java.util.UUID;

public record ChangeDto(
        UUID changeId,
        String title,
        String componentId,
        ChangeStatus status,
        UUID correlationId,
        Instant createdAt,
        Instant updatedAt
) {}
