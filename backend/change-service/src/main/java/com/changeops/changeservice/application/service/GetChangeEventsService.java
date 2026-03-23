package com.changeops.changeservice.application.service;

import com.changeops.changeservice.application.port.in.GetChangeEventsUseCase;
import com.changeops.changeservice.domain.exception.ChangeNotFoundException;
import com.changeops.changeservice.infrastructure.persistence.repository.ChangeEventJpaRepository;
import com.changeops.changeservice.infrastructure.persistence.repository.ChangeJpaRepository;
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

    private final ChangeJpaRepository changeJpaRepository;
    private final ChangeEventJpaRepository changeEventJpaRepository;

    @Override
    @Transactional(readOnly = true)
    public List<Result> execute(UUID changeId) {
        log.debug("Getting timeline for changeId={}", changeId);
        if (!changeJpaRepository.existsById(changeId)) {
            throw new ChangeNotFoundException(changeId);
        }
        return changeEventJpaRepository.findByChangeIdOrderByOccurredAtAsc(changeId)
                .stream()
                .map(e -> new Result(e.getEventId(), e.getChangeId(),
                        e.getEventType(), e.getPayload(), e.getOccurredAt()))
                .toList();
    }
}
