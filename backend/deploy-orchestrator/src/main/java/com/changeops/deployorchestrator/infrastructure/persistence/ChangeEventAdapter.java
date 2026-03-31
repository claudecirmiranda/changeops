// infrastructure/persistence/ChangeEventAdapter.java
package com.changeops.deployorchestrator.infrastructure.persistence;

import com.changeops.deployorchestrator.application.port.out.SaveChangeEventPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ChangeEventAdapter implements SaveChangeEventPort {

    private final ChangeEventJpaRepository repository;

    @Override
    @SuppressWarnings("null")
    public void save(UUID changeId, String eventType, String payload, Instant occurredAt) {
        ChangeEventEntity event = ChangeEventEntity.builder()
                .eventId(UUID.randomUUID())
                .changeId(Objects.requireNonNull(changeId, "changeId must not be null"))
                .eventType(Objects.requireNonNull(eventType, "eventType must not be null"))
                .payload(Objects.requireNonNull(payload, "payload must not be null"))
                .occurredAt(Objects.requireNonNull(occurredAt, "occurredAt must not be null"))
                .build();
        repository.save(event);
    }
}
