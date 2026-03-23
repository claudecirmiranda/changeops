// infrastructure/persistence/ChangeEventAdapter.java
package com.changeops.deployorchestrator.infrastructure.persistence;

import com.changeops.deployorchestrator.application.port.out.SaveChangeEventPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ChangeEventAdapter implements SaveChangeEventPort {

    private final ChangeEventJpaRepository repository;

    @Override
    public void save(UUID changeId, String eventType, String payload, Instant occurredAt) {
        repository.save(ChangeEventEntity.builder()
            .eventId(UUID.randomUUID())
            .changeId(changeId)
            .eventType(eventType)
            .payload(payload)
            .occurredAt(occurredAt)
            .build());
    }
}