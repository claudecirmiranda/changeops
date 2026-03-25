package com.changeops.deployorchestrator.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

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
