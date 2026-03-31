package com.changeops.deployorchestrator.infrastructure.kafka;

import com.changeops.deployorchestrator.application.port.out.PublishResultEventPort;
import com.changeops.deployorchestrator.domain.model.ChangeResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Component
public class KafkaResultPublisherAdapter implements PublishResultEventPort {

    private final KafkaTemplate<String, IntegrationEvent> kafkaTemplate;
    private final String changeResultTopic;
    private final String dlqTopic;
    private final MeterRegistry meterRegistry;
    private final Map<String, Counter> counterCache = new ConcurrentHashMap<>();

    public KafkaResultPublisherAdapter(
            KafkaTemplate<String, IntegrationEvent> kafkaTemplate,
            @Value("${changeops.kafka.topics.change-result}") String changeResultTopic,
            @Value("${changeops.kafka.topics.dlq}") String dlqTopic,
            MeterRegistry meterRegistry) {
        this.kafkaTemplate = Objects.requireNonNull(kafkaTemplate, "kafkaTemplate must not be null");
        this.changeResultTopic = Objects.requireNonNull(changeResultTopic, "changeResultTopic must not be null");
        this.dlqTopic = Objects.requireNonNull(dlqTopic, "dlqTopic must not be null");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
    }

    @Override
    public void publish(ChangeResult result) {
        ChangeResult requiredResult = Objects.requireNonNull(result, "result must not be null");
        String key = Objects.requireNonNull(requiredResult.getChangeId(), "changeId must not be null").toString();
        IntegrationEvent envelope = buildEnvelope(requiredResult);

        try {
            SendResult<String, IntegrationEvent> sendResult =
                    kafkaTemplate.send(
                            Objects.requireNonNull(changeResultTopic, "changeResultTopic must not be null"),
                            Objects.requireNonNull(key, "key must not be null"),
                            envelope).get(10, TimeUnit.SECONDS);
            getOrCreateCounter(envelope.eventType()).increment();
            log.info("Result event published: eventType={}, changeId={}, correlationId={}, topic={}, partition={}, offset={}",
                    envelope.eventType(), requiredResult.getChangeId(), requiredResult.getCorrelationId(),
                    sendResult.getRecordMetadata().topic(),
                    sendResult.getRecordMetadata().partition(),
                    sendResult.getRecordMetadata().offset());
        } catch (ExecutionException e) {
            log.error("Failed to publish result event — sending to DLQ: changeId={}, correlationId={}",
                    requiredResult.getChangeId(), requiredResult.getCorrelationId(), e.getCause());
            sendToDlq(key, envelope);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted publishing result event — sending to DLQ: changeId={}",
                    requiredResult.getChangeId());
            sendToDlq(key, envelope);
        } catch (TimeoutException e) {
            log.error("Timeout publishing result event — sending to DLQ: changeId={}",
                    requiredResult.getChangeId());
            sendToDlq(key, envelope);
        }
    }

    private void sendToDlq(String key, IntegrationEvent envelope) {
        kafkaTemplate.send(
                        Objects.requireNonNull(dlqTopic, "dlqTopic must not be null"),
                        Objects.requireNonNull(key, "key must not be null"),
                        envelope)
                .whenComplete((r, ex) -> {
                    if (ex != null) {
                        log.error("CRITICAL: Failed to send event to DLQ — manual intervention required: key={}", key, ex);
                    } else {
                        log.error("Event sent to DLQ after max retries: key={}, eventType={}", key, envelope.eventType());
                    }
                });
    }

    private Counter getOrCreateCounter(String eventType) {
        return counterCache.computeIfAbsent(Objects.requireNonNull(eventType, "eventType must not be null"), type ->
                Counter.builder("events_published_total")
                        .tag("type", type)
                        .description("Total number of events published to Kafka")
                        .register(meterRegistry));
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
