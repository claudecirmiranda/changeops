// ListChangesService.java
package com.changeops.changeservice.application.service;

import com.changeops.changeservice.application.port.in.ListChangesUseCase;
import com.changeops.changeservice.application.port.out.LoadChangesPort;
import com.changeops.changeservice.domain.model.Change;
import com.changeops.changeservice.domain.valueobject.ChangeStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ListChangesService implements ListChangesUseCase {

    private final LoadChangesPort loadChangesPort;

    @Override
    @Transactional(readOnly = true)
    public ListChangesUseCase.PageResult execute(ListChangesUseCase.Query query, Pageable pageable) {
        log.debug("Listing changes: status={}, componentId={}", query.status(), query.componentId());
        Page<ListChangesUseCase.Result> page = loadChangesPort.findAll(query.status(), query.componentId(), pageable)
                .map(this::toResult);
        Map<ChangeStatus, Long> statusSummary = loadChangesPort.countGroupedByStatus();
        return new ListChangesUseCase.PageResult(page, statusSummary);
    }

    private ListChangesUseCase.Result toResult(Change change) {
        return new ListChangesUseCase.Result(
                change.getChangeId(),
                change.getTitle(),
                change.getComponentId(),
                change.getStatus(),
                change.getCorrelationId(),
                change.getCreatedAt(),
                change.getUpdatedAt());
    }
}
