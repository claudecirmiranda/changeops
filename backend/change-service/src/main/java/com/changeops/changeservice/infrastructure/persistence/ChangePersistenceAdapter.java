package com.changeops.changeservice.infrastructure.persistence;

import com.changeops.changeservice.application.port.out.ChangeExistsPort;
import com.changeops.changeservice.application.port.out.LoadChangeEventsPort;
import com.changeops.changeservice.application.port.out.LoadChangesPort;
import com.changeops.changeservice.application.port.out.SaveChangeEventPort;
import com.changeops.changeservice.application.port.out.SaveChangePort;
import com.changeops.changeservice.domain.model.Change;
import com.changeops.changeservice.domain.valueobject.ChangeStatus;
import com.changeops.changeservice.infrastructure.persistence.entity.ChangeEntity;
import com.changeops.changeservice.infrastructure.persistence.entity.ChangeEventEntity;
import com.changeops.changeservice.infrastructure.persistence.repository.ChangeEventJpaRepository;
import com.changeops.changeservice.infrastructure.persistence.repository.ChangeJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ChangePersistenceAdapter
        implements SaveChangePort, LoadChangesPort, SaveChangeEventPort,
                   ChangeExistsPort, LoadChangeEventsPort {

    private final ChangeJpaRepository changeJpaRepository;
    private final ChangeEventJpaRepository changeEventJpaRepository;

    @Override
    @SuppressWarnings("null")
    public Change save(Change change) {
        ChangeEntity entity = toEntity(change);
        ChangeEntity saved = Objects.requireNonNull(changeJpaRepository.save(entity));
        return toDomain(saved);
    }

    @Override
    public Optional<Change> findById(UUID changeId) {
        return changeJpaRepository.findById(Objects.requireNonNull(changeId)).map(this::toDomain);
    }

    @Override
    public Page<Change> findAll(ChangeStatus status, String componentId, Pageable pageable) {
        return changeJpaRepository.findAllFiltered(status, componentId, pageable)
                .map(this::toDomain);
    }

    @Override
    public void save(UUID changeId, String eventType, String payload, Instant occurredAt) {
        ChangeEventEntity event = ChangeEventEntity.builder()
                .eventId(UUID.randomUUID())
                .changeId(changeId)
                .eventType(eventType)
                .payload(payload)
                .occurredAt(occurredAt)
                .build();
        changeEventJpaRepository.save(Objects.requireNonNull(event));
    }

    private ChangeEntity toEntity(Change c) {
        return ChangeEntity.builder()
                .changeId(c.getChangeId())
                .title(c.getTitle())
                .description(c.getDescription())
                .componentId(c.getComponentId())
                .requestedBy(c.getRequestedBy())
                .scheduledAt(c.getScheduledAt())
                .status(c.getStatus())
                .correlationId(c.getCorrelationId())
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .build();
    }

    private Change toDomain(ChangeEntity e) {
        return Change.reconstitute(
                e.getChangeId(), e.getTitle(), e.getDescription(),
                e.getComponentId(), e.getRequestedBy(), e.getScheduledAt(),
                e.getStatus(), e.getCorrelationId(),
                e.getCreatedAt(), e.getUpdatedAt());
    }

    @Override
    public boolean existsById(UUID changeId) {
        return changeJpaRepository.existsById(Objects.requireNonNull(changeId));
    }

    @Override
    public List<LoadChangeEventsPort.ChangeEventResult> findByChangeId(UUID changeId) {
        return changeEventJpaRepository.findByChangeIdOrderByOccurredAtAsc(Objects.requireNonNull(changeId))
                .stream()
                .map(e -> new LoadChangeEventsPort.ChangeEventResult(
                        e.getEventId(), e.getChangeId(),
                        e.getEventType(), e.getPayload(), e.getOccurredAt()))
                .toList();
    }
}
