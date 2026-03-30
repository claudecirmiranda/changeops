package com.changeops.deployorchestrator.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEventEntity, ProcessedEventId> {

    @Modifying
    @Query(value = "INSERT INTO processed_events (event_id, processed_at, service_name) " +
            "VALUES (:eventId, NOW(), :serviceName) ON CONFLICT (event_id, service_name) DO NOTHING",
            nativeQuery = true)
    int insertIfAbsent(@Param("eventId") UUID eventId, @Param("serviceName") String serviceName);

    @Modifying
    @Query(value = "DELETE FROM processed_events WHERE processed_at < NOW() - CAST(:days || ' days' AS INTERVAL)",
            nativeQuery = true)
    int deleteOlderThan(@Param("days") int days);
}
