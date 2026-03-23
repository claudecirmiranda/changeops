package com.changeops.changeservice.infrastructure.kafka;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IntegrationEvent(
        String eventType,
        String version,
        UUID correlationId,
        Instant occurredAt,
        Object payload
) {}
