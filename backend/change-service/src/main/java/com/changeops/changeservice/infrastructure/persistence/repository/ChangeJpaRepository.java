package com.changeops.changeservice.infrastructure.persistence.repository;

import com.changeops.changeservice.domain.valueobject.ChangeStatus;
import com.changeops.changeservice.infrastructure.persistence.entity.ChangeEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface ChangeJpaRepository extends JpaRepository<ChangeEntity, UUID> {

    long countByStatus(ChangeStatus status);

    @Query("""
            SELECT c FROM ChangeEntity c
            WHERE (:status IS NULL OR c.status = :status)
              AND (:componentId IS NULL OR c.componentId = :componentId)
            ORDER BY c.createdAt DESC
            """)
    Page<ChangeEntity> findAllFiltered(
            @Param("status") ChangeStatus status,
            @Param("componentId") String componentId,
            Pageable pageable);
}
