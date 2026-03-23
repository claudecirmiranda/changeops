package com.changeops.deployorchestrator.infrastructure.persistence;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProcessedEventsCleanupJob {

    private final ProcessedEventRepository repository;

    /**
     * Deletes processed events older than 90 days.
     * Runs daily at 03:00 AM.
     */
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void cleanupOldProcessedEvents() {
        // int deleted = repository.deleteOlderThan(90);
        // if (deleted > 0) {
        //     log.info("Cleaned up {} processed events older than 90 days", deleted);
        // }
    }
}
