package com.changeops.deployorchestrator.infrastructure.kafka;

import com.changeops.deployorchestrator.application.port.out.PublishResultEventPort;
import com.changeops.deployorchestrator.domain.model.ChangeResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Slf4j
@Component
public class KafkaResultPublisherAdapter implements PublishResultEventPort {

    private final KafkaTemplate<String, IntegrationEvent> kafkaTemplate;
    private final String changeResultTopic;
    private final String dlqTopic;
    private final Counter eventsPublishedCounter;

    public KafkaResultPublisherAdapter(
            KafkaTemplate<String, IntegrationEvent> kafkaTemplate,
            @Value("${changeops.kafka.topics.change-result}") String changeResultTopic,
            @Value("${changeops.kafka.topics.dlq}") String dlqTopic,
            MeterRegistry meterRegistry) {
        this.kafkaTemplate = kafkaTemplate;
        this.changeResultTopic = changeResultTopic;
        this.dlqTopic = dlqTopic;
        this.eventsPublishedCounter = Counter.builder("events_published_total")
                .description("Total result events published")
                .register(meterRegistry);
    }

    @Override
    public void publish(ChangeResult result) {
        IntegrationEvent envelope = buildEnvelope(result);
        String key = result.getChangeId().toString();

        kafkaTemplate.send(changeResultTopic, key, envelope)
                .whenComplete((sendResult, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish result event after retries — sending to DLQ: " +
                                        "changeId={}, correlationId={}",
                                result.getChangeId(), result.getCorrelationId(), ex);
                        sendToDlq(key, envelope);
                    } else {
                        eventsPublishedCounter.increment();
                        log.info("Result event published: eventType={}, changeId={}, correlationId={}",
                                envelope.eventType(), result.getChangeId(), result.getCorrelationId());
                    }
                });
    }

    private void sendToDlq(String key, IntegrationEvent envelope) {
        kafkaTemplate.send(dlqTopic, key, envelope)
                .whenComplete((r, ex) -> {
                    if (ex != null) {
                        log.error("CRITICAL: Failed to send event to DLQ — manual intervention required: key={}", key, ex);
                    } else {
                        log.error("Event sent to DLQ after max retries: key={}, eventType={}", key, envelope.eventType());
                    }
                });
    }

    private IntegrationEvent buildEnvelope(ChangeResult result) {
        if (result.isSuccess()) {
            return new IntegrationEvent(
                    "ChangeCompletedEvent", "1.0",
                    result.getCorrelationId(), Instant.now(),
                    new IntegrationEvent.ChangeCompletedPayload(
                            result.getChangeId(), result.getDeployId(), result.getFinishedAt()));
        } else {
            return new IntegrationEvent(
                    "ChangeFailedEvent", "1.0",
                    result.getCorrelationId(), Instant.now(),
                    new IntegrationEvent.ChangeFailedPayload(
                            result.getChangeId(), result.getDeployId(),
                            result.getFailureReason(), result.getFinishedAt()));
        }
    }
}
