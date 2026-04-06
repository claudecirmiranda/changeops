package com.changeops.changeservice.application.port.out;

import com.changeops.changeservice.domain.model.Change;
import com.changeops.changeservice.domain.valueobject.ChangeStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface LoadChangesPort {
    Optional<Change> findById(UUID changeId);
    Page<Change> findAll(ChangeStatus status, String componentId, Pageable pageable);
    Map<ChangeStatus, Long> countGroupedByStatus();
}
