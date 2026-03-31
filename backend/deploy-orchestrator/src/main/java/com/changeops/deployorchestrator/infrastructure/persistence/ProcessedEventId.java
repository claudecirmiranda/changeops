package com.changeops.deployorchestrator.infrastructure.persistence;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProcessedEventId implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private UUID eventId;

    private String serviceName;
}
