package com.changeops.changeservice.application.service;

import com.changeops.changeservice.application.port.in.GetChangeUseCase;
import com.changeops.changeservice.application.port.out.LoadChangesPort;
import com.changeops.changeservice.domain.exception.ChangeNotFoundException;
import com.changeops.changeservice.domain.model.Change;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class GetChangeService implements GetChangeUseCase {

    private final LoadChangesPort loadChangesPort;

    @Override
    public Result execute(UUID changeId) {
        Change change = loadChangesPort.findById(changeId)
                .orElseThrow(() -> new ChangeNotFoundException(changeId));

        return new Result(
                change.getChangeId(),
                change.getTitle(),
                change.getDescription(),
                change.getComponentId(),
                change.getRequestedBy(),
                change.getStatus(),
                change.getCorrelationId(),
                change.getScheduledAt(),
                change.getCreatedAt(),
                change.getUpdatedAt());
    }
}
