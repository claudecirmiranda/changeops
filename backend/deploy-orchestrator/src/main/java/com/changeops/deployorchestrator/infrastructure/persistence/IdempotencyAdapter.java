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
    @SuppressWarnings("null")
    public void markAsProcessed(UUID eventId, String serviceName) {
        UUID requiredEventId = Objects.requireNonNull(eventId, "eventId must not be null");
        String requiredServiceName = Objects.requireNonNull(serviceName, "serviceName must not be null");

        try {
            repository.save(ProcessedEventEntity.builder()
                    .eventId(requiredEventId)
                    .processedAt(Instant.now())
                    .serviceName(requiredServiceName)
                    .build());
            log.debug("Event marked as processed: eventId={}", requiredEventId);
        } catch (DataIntegrityViolationException e) {
            log.warn("Concurrent idempotency conflict for eventId={} — already processed", requiredEventId);
        }
    }

    @Override
    public boolean tryMarkAsProcessed(UUID eventId, String serviceName) {
        UUID requiredEventId = Objects.requireNonNull(eventId, "eventId must not be null");
        String requiredServiceName = Objects.requireNonNull(serviceName, "serviceName must not be null");

        int inserted = repository.insertIfAbsent(requiredEventId, requiredServiceName);
        if (inserted == 0) {
            log.debug("Event already processed (atomic check): eventId={}", requiredEventId);
            return false;
        }
        log.debug("Event atomically marked as processed: eventId={}", requiredEventId);
        return true;
    }
}
