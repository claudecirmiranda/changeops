package com.changeops.deployorchestrator.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ChangeEventJpaRepository extends JpaRepository<ChangeEventEntity, UUID> {
}
