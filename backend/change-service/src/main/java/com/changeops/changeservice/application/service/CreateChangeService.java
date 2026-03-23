package com.changeops.changeservice.application.service;

import com.changeops.changeservice.application.port.in.CreateChangeUseCase;
import com.changeops.changeservice.application.port.out.PublishEventPort;
import com.changeops.changeservice.application.port.out.SaveChangeEventPort;
import com.changeops.changeservice.application.port.out.SaveChangePort;
import com.changeops.changeservice.domain.event.DomainEvent;
import com.changeops.changeservice.domain.model.Change;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
public class CreateChangeService implements CreateChangeUseCase {

    private final SaveChangePort saveChangePort;
    private final PublishEventPort publishEventPort;
    private final SaveChangeEventPort saveChangeEventPort;
    private final ObjectMapper objectMapper;
    private final Counter changesCreatedCounter;

    public CreateChangeService(
            SaveChangePort saveChangePort,
            PublishEventPort publishEventPort,
            SaveChangeEventPort saveChangeEventPort,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        this.saveChangePort = saveChangePort;
        this.publishEventPort = publishEventPort;
        this.saveChangeEventPort = saveChangeEventPort;
        this.objectMapper = objectMapper;
        this.changesCreatedCounter = Counter.builder("changes_created_total")
                .description("Total changes created")
                .register(meterRegistry);
    }

    @Override
    @Transactional
    public Result execute(Command command) {
        log.info("Creating change: title={}, componentId={}, requestedBy={}",
                command.title(), command.componentId(), command.requestedBy());

        Change change = Change.create(
                command.title(),
                command.description(),
                command.componentId(),
                command.requestedBy(),
                command.scheduledAt());

        Change saved = saveChangePort.save(change);

        List<DomainEvent> events = saved.pullDomainEvents();
        events.forEach(event -> {
            publishEventPort.publish(event);
            persistEventToTimeline(saved, event);
        });

        changesCreatedCounter.increment();

        log.info("Change created: changeId={}, correlationId={}, status={}",
                saved.getChangeId(), saved.getCorrelationId(), saved.getStatus());

        return new Result(
                saved.getChangeId(),
                saved.getStatus().name(),
                saved.getCorrelationId(),
                saved.getCreatedAt());
    }

    private void persistEventToTimeline(Change change, DomainEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            saveChangeEventPort.save(
                    change.getChangeId(),
                    event.getClass().getSimpleName(),
                    payload,
                    event.occurredAt());
        } catch (Exception e) {
            log.warn("Failed to persist event to timeline: changeId={}", change.getChangeId(), e);
        }
    }
}
