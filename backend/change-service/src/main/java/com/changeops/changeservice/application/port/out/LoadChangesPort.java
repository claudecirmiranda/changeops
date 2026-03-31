package com.changeops.changeservice.application.port.out;

import com.changeops.changeservice.domain.model.Change;
import com.changeops.changeservice.domain.valueobject.ChangeStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface LoadChangesPort {
    Optional<Change> findById(UUID changeId);
    Page<Change> findAll(ChangeStatus status, String componentId, Instant since, Pageable pageable);
    Map<ChangeStatus, Long> countByStatusFiltered(Instant since);
}
