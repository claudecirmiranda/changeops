package com.changeops.changeservice.domain.model;

import com.changeops.changeservice.domain.event.ChangePreparedEvent;
import com.changeops.changeservice.domain.exception.InvalidChangeStateException;
import com.changeops.changeservice.domain.valueobject.ChangeStatus;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Getter
@Builder(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Change {

    private final UUID changeId;
    private final String title;
    private final String description;
    private final String componentId;
    private final String requestedBy;
    private final Instant scheduledAt;
    private ChangeStatus status;
    private final UUID correlationId;
    private final Instant createdAt;
    private Instant updatedAt;

    @Builder.Default
    private final List<Object> domainEvents = new ArrayList<>();

    public static Change create(
            String title,
            String description,
            String componentId,
            String requestedBy,
            Instant scheduledAt) {

        UUID changeId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        Instant now = Instant.now();

        Change change = Change.builder()
                .changeId(changeId)
                .title(title)
                .description(description)
                .componentId(componentId)
                .requestedBy(requestedBy)
                .scheduledAt(scheduledAt)
                .status(ChangeStatus.PREPARED)
                .correlationId(correlationId)
                .createdAt(now)
                .updatedAt(now)
                .build();

        change.domainEvents.add(new ChangePreparedEvent(
                changeId, componentId, requestedBy, scheduledAt, correlationId, now));

        return change;
    }

    public static Change fromEntity(com.changeops.changeservice.infrastructure.persistence.entity.ChangeEntity e) {
        return Change.builder()
                .changeId(e.getChangeId())
                .title(e.getTitle())
                .description(e.getDescription())
                .componentId(e.getComponentId())
                .requestedBy(e.getRequestedBy())
                .scheduledAt(e.getScheduledAt())
                .status(e.getStatus())
                .correlationId(e.getCorrelationId())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }

    public void complete() {
        if (this.status != ChangeStatus.PREPARED) {
            throw new InvalidChangeStateException(
                    "Cannot complete change in status: " + this.status);
        }
        this.status = ChangeStatus.COMPLETED;
        this.updatedAt = Instant.now();
    }

    public void fail() {
        if (this.status != ChangeStatus.PREPARED) {
            throw new InvalidChangeStateException(
                    "Cannot fail change in status: " + this.status);
        }
        this.status = ChangeStatus.FAILED;
        this.updatedAt = Instant.now();
    }

    public List<Object> pullDomainEvents() {
        List<Object> events = Collections.unmodifiableList(new ArrayList<>(this.domainEvents));
        this.domainEvents.clear();
        return events;
    }
}
