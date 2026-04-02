package com.changeops.deployorchestrator.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface ChangeStatusJpaRepository extends JpaRepository<ChangeStatusEntity, UUID> {

    @Modifying
    @Query("UPDATE ChangeStatusEntity c SET c.status = 'COMPLETED', c.updatedAt = CURRENT_TIMESTAMP WHERE c.changeId = :changeId")
    int markCompleted(@Param("changeId") UUID changeId);

    @Modifying
    @Query("UPDATE ChangeStatusEntity c SET c.status = 'FAILED', c.updatedAt = CURRENT_TIMESTAMP WHERE c.changeId = :changeId")
    int markFailed(@Param("changeId") UUID changeId);

    boolean existsByChangeId(UUID changeId);
}
