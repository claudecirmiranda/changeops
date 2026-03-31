package com.changeops.changeservice.application.service;

import com.changeops.changeservice.application.port.in.GetChangeStatsUseCase;
import com.changeops.changeservice.application.port.out.LoadChangesPort;
import com.changeops.changeservice.domain.valueobject.ChangeStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class GetChangeStatsService implements GetChangeStatsUseCase {

    private final LoadChangesPort loadChangesPort;

    @Override
    @Transactional(readOnly = true)
    public Result execute(Instant since) {
        log.debug("Getting change stats since={}", since);
        Map<ChangeStatus, Long> counts = loadChangesPort.countByStatusFiltered(since);
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        return new Result(
                total,
                counts.getOrDefault(ChangeStatus.PREPARED, 0L),
                counts.getOrDefault(ChangeStatus.COMPLETED, 0L),
                counts.getOrDefault(ChangeStatus.FAILED, 0L));
    }
}
