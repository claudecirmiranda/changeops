package com.changeops.deployorchestrator.infrastructure.persistence;

import com.changeops.deployorchestrator.application.port.out.UpdateChangeStatusPort;
import com.changeops.deployorchestrator.domain.exception.InvalidOrchestratorStateException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class UpdateChangeStatusAdapter implements UpdateChangeStatusPort {

    private final ChangeStatusJpaRepository repository;

    @Override
    public void markCompleted(UUID changeId) {
        int updated = repository.markCompleted(changeId);
        if (updated == 0) {
            throw new InvalidOrchestratorStateException(
                "Cannot mark COMPLETED: change not found or already in a terminal state. changeId=" + changeId);
        }
    }

    @Override
    public void markFailed(UUID changeId) {
        int updated = repository.markFailed(changeId);
        if (updated == 0) {
            throw new InvalidOrchestratorStateException(
                "Cannot mark FAILED: change not found or already in a terminal state. changeId=" + changeId);
        }
    }
}
