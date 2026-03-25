package com.changeops.changeservice.api.dto;

import com.changeops.changeservice.domain.valueobject.ChangeStatus;

import java.time.Instant;
import java.util.UUID;

public record CreateChangeResponse(
        UUID changeId,
        ChangeStatus status,
        UUID correlationId,
        Instant createdAt
) {}
