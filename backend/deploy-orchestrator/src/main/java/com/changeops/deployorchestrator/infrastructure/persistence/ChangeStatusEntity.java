package com.changeops.deployorchestrator.infrastructure.persistence;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "changes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChangeStatusEntity {

    @Id
    @Column(name = "change_id", updatable = false, nullable = false)
    private UUID changeId;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
