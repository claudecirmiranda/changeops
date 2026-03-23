package com.changeops.changeservice.api.dto;

import java.time.Instant;
import java.util.UUID;

public record CreateChangeResponse(
        UUID changeId,
        String status,
        UUID correlationId,
        Instant createdAt
) {}
