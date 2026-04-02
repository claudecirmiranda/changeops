package com.changeops.deployorchestrator.application.port.out;

import java.util.UUID;

public interface UpdateChangeStatusPort {
    void markCompleted(UUID changeId);
    void markFailed(UUID changeId);
    boolean existsByChangeId(UUID changeId);
}
