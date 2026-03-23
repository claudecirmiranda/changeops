package com.changeops.deployorchestrator.infrastructure.persistence;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "change_events")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChangeEventEntity {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "change_id", nullable = false)
    private UUID changeId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    private String payload;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
}