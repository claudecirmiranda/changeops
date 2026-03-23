package com.changeops.changeservice.infrastructure.persistence.repository;

import com.changeops.changeservice.infrastructure.persistence.entity.ChangeEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ChangeEventJpaRepository extends JpaRepository<ChangeEventEntity, UUID> {
    List<ChangeEventEntity> findByChangeIdOrderByOccurredAtAsc(UUID changeId);
}
