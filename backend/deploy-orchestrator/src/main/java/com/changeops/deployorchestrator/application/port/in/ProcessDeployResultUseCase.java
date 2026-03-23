package com.changeops.deployorchestrator.application.port.in;

import com.changeops.deployorchestrator.domain.event.DeployFinishedEvent;

public interface ProcessDeployResultUseCase {
    void execute(DeployFinishedEvent event);
}
