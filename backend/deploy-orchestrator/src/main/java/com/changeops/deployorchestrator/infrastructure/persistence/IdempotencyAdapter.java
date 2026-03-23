package com.changeops.deployorchestrator.infrastructure.persistence;

import com.changeops.deployorchestrator.application.port.out.IdempotencyPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotencyAdapter implements IdempotencyPort {

    private final ProcessedEventRepository repository;

    @Override
    public boolean isAlreadyProcessed(UUID eventId) {
        return repository.existsById(eventId);
    }

    @Override
    public void markAsProcessed(UUID eventId, String serviceName) {
        try {
            repository.save(ProcessedEventEntity.builder()
                    .eventId(eventId)
                    .processedAt(Instant.now())
                    .serviceName(serviceName)
                    .build());
            log.debug("Event marked as processed: eventId={}", eventId);
        } catch (DataIntegrityViolationException e) {
            // Race condition: another instance already processed this event
            log.warn("Concurrent idempotency conflict for eventId={} — already processed", eventId);
        }
    }
}
