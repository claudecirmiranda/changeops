// ListChangesService.java
package com.changeops.changeservice.application.service;

import com.changeops.changeservice.application.port.in.ListChangesUseCase;
import com.changeops.changeservice.application.port.out.LoadChangesPort;
import com.changeops.changeservice.domain.model.Change;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ListChangesService implements ListChangesUseCase {

    private final LoadChangesPort loadChangesPort;

    @Override
    @Transactional(readOnly = true)
    public Page<ListChangesUseCase.Result> execute(ListChangesUseCase.Query query, Pageable pageable) {
        log.debug("Listing changes: status={}, componentId={}", query.status(), query.componentId());
        return loadChangesPort.findAll(query.status(), query.componentId(), pageable)
                .map(this::toResult);
    }

    private ListChangesUseCase.Result toResult(Change change) {
        return new ListChangesUseCase.Result(
                change.getChangeId(),
                change.getTitle(),
                change.getComponentId(),
                change.getStatus().name(),
                change.getCorrelationId(),
                change.getCreatedAt(),
                change.getUpdatedAt());
    }
}