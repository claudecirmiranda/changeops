package com.changeops.deployorchestrator.application.port.out;

import com.changeops.deployorchestrator.domain.model.ChangeResult;

public interface PublishResultEventPort {
    void publish(ChangeResult result);
}
