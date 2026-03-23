package com.changeops.changeservice.infrastructure.kafka;

import com.changeops.changeservice.application.port.out.PublishEventPort;
import com.changeops.changeservice.domain.event.ChangePreparedEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Component
public class KafkaEventPublisherAdapter implements PublishEventPort {

    private final KafkaTemplate<String, IntegrationEvent> kafkaTemplate;
    private final String changePreparedTopic;
    private final Counter eventsPublishedCounter;

    public KafkaEventPublisherAdapter(
            KafkaTemplate<String, IntegrationEvent> kafkaTemplate,
            @Value("${changeops.kafka.topics.change-prepared}") String changePreparedTopic,
            MeterRegistry meterRegistry) {
        this.kafkaTemplate = kafkaTemplate;
        this.changePreparedTopic = changePreparedTopic;
        this.eventsPublishedCounter = Counter.builder("events_published_total")
                .description("Total integration events published")
                .register(meterRegistry);
    }

    @Override
    public void publish(Object domainEvent) {
        if (domainEvent instanceof ChangePreparedEvent event) {
            publishChangePrepared(event);
        } else {
            log.warn("No publisher found for event type: {}", domainEvent.getClass().getSimpleName());
        }
    }

    private void publishChangePrepared(ChangePreparedEvent event) {
        IntegrationEvent envelope = IntegrationEvent.builder()
                .eventType("ChangePreparedEvent")
                .version("1.0")
                .correlationId(event.correlationId())
                .occurredAt(Instant.now())
                .payload(new ChangePreparedPayload(
                        event.changeId(),
                        event.componentId(),
                        event.requestedBy(),
                        event.scheduledAt()))
                .build();

        CompletableFuture<SendResult<String, IntegrationEvent>> future =
                kafkaTemplate.send(changePreparedTopic, event.changeId().toString(), envelope);

        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Failed to publish ChangePreparedEvent: correlationId={}, changeId={}",
                        event.correlationId(), event.changeId(), ex);
            } else {
                eventsPublishedCounter.increment();
                log.info("ChangePreparedEvent published: correlationId={}, changeId={}, partition={}, offset={}",
                        event.correlationId(), event.changeId(),
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
            }
        });
    }

    public record ChangePreparedPayload(
            java.util.UUID changeId,
            String componentId,
            String requestedBy,
            java.time.Instant scheduledAt) {}
}
