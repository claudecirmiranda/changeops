package com.changeops.changeservice.application.service;

import com.changeops.changeservice.application.port.in.GetChangeEventsUseCase;
import com.changeops.changeservice.application.port.out.ChangeExistsPort;
import com.changeops.changeservice.application.port.out.LoadChangeEventsPort;
import com.changeops.changeservice.domain.exception.ChangeNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class GetChangeEventsService implements GetChangeEventsUseCase {

    private final ChangeExistsPort changeExistsPort;
    private final LoadChangeEventsPort loadChangeEventsPort;

    @Override
    @Transactional(readOnly = true)
    public List<Result> execute(UUID changeId) {
        log.debug("Getting timeline for changeId={}", changeId);
        if (!changeExistsPort.existsById(changeId)) {
            throw new ChangeNotFoundException(changeId);
        }
        return loadChangeEventsPort.findByChangeId(changeId)
                .stream()
                .map(e -> new Result(e.eventId(), e.changeId(),
                        e.eventType(), e.payload(), e.occurredAt()))
                .toList();
    }
}
