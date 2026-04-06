package com.changeops.changeservice.api.dto;

import com.changeops.changeservice.domain.valueobject.ChangeStatus;

import java.util.List;
import java.util.Map;

public record ChangeListResponse(
        List<ChangeDto> content,
        long totalElements,
        int totalPages,
        int number,
        int size,
        boolean first,
        boolean last,
        Map<ChangeStatus, Long> statusSummary
) {}
