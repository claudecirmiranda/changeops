package com.changeops.changeservice.api.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record CreateChangeRequest(
        @NotBlank(message = "title is required")
        @Size(max = 255, message = "title must not exceed 255 characters")
        String title,

        @Size(max = 2000, message = "description must not exceed 2000 characters")
        String description,

        @NotBlank(message = "componentId is required")
        @Size(max = 100, message = "componentId must not exceed 100 characters")
        @Pattern(regexp = "^[a-zA-Z0-9][a-zA-Z0-9_.\\-]{0,99}$",
                message = "componentId must start with alphanumeric and contain only letters, digits, dots, hyphens, or underscores")
        String componentId,

        @NotBlank(message = "requestedBy is required")
        @Size(max = 100)
        String requestedBy,

        @NotNull(message = "scheduledAt is required")
        @Future(message = "scheduledAt must be a future date")
        Instant scheduledAt
) {}
