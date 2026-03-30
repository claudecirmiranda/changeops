package com.changeops.deployorchestrator.infrastructure.persistence;

import com.changeops.deployorchestrator.application.port.out.IdempotencyPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotencyAdapter implements IdempotencyPort {

    private final ProcessedEventRepository repository;

    @Override
    public boolean isAlreadyProcessed(UUID eventId) {
        return repository.existsById(Objects.requireNonNull(eventId));
    }

    @Override
    public void markAsProcessed(UUID eventId, String serviceName) {
        try {
            repository.save(Objects.requireNonNull(ProcessedEventEntity.builder()
                    .eventId(eventId)
                    .processedAt(Instant.now())
                    .serviceName(serviceName)
                    .build()));
            log.debug("Event marked as processed: eventId={}", eventId);
        } catch (DataIntegrityViolationException e) {
            log.warn("Concurrent idempotency conflict for eventId={} — already processed", eventId);
        }
    }

    @Override
    public boolean tryMarkAsProcessed(UUID eventId, String serviceName) {
        int inserted = repository.insertIfAbsent(eventId, serviceName);
        if (inserted == 0) {
            log.debug("Event already processed (atomic check): eventId={}", eventId);
            return false;
        }
        log.debug("Event atomically marked as processed: eventId={}", eventId);
        return true;
    }
}
