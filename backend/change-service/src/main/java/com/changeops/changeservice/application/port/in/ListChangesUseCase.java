package com.changeops.changeservice.application.port.in;

import com.changeops.changeservice.domain.valueobject.ChangeStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public interface ListChangesUseCase {

    PageResult execute(Query query, Pageable pageable);

    record Query(ChangeStatus status, String componentId) {}

    record Result(
            UUID changeId,
            String title,
            String componentId,
            ChangeStatus status,
            UUID correlationId,
            Instant createdAt,
            Instant updatedAt
    ) {}

    record PageResult(Page<Result> page, Map<ChangeStatus, Long> statusSummary) {}
}
