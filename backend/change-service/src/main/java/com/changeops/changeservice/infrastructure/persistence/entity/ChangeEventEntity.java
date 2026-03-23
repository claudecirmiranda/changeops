package com.changeops.changeservice.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "change_events", indexes = {
        @Index(name = "idx_change_events_change_id", columnList = "change_id"),
        @Index(name = "idx_change_events_occurred_at", columnList = "occurred_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChangeEventEntity {

    @Id
    @Column(name = "event_id", updatable = false, nullable = false)
    private UUID eventId;

    @Column(name = "change_id", nullable = false)
    private UUID changeId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "payload", nullable = false, columnDefinition = "JSONB")
    private String payload;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
}
