package com.changeops.deployorchestrator.infrastructure.kafka;

import com.changeops.deployorchestrator.application.port.out.PublishResultEventPort;
import com.changeops.deployorchestrator.domain.model.ChangeResult;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.time.Instant;
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

    public KafkaResultPublisherAdapter(
            KafkaTemplate<String, IntegrationEvent> kafkaTemplate,
            @Value("${changeops.kafka.topics.change-result}") String changeResultTopic,
            @Value("${changeops.kafka.topics.dlq}") String dlqTopic,
            MeterRegistry meterRegistry) {
        this.kafkaTemplate = kafkaTemplate;
        this.changeResultTopic = changeResultTopic;
        this.dlqTopic = dlqTopic;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void publish(ChangeResult result) {
        IntegrationEvent envelope = buildEnvelope(result);
        String key = result.getChangeId().toString();

        try {
            SendResult<String, IntegrationEvent> sendResult =
                    kafkaTemplate.send(changeResultTopic, key, envelope).get(10, TimeUnit.SECONDS);
            meterRegistry.counter("events_published_total", "type", envelope.eventType()).increment();
            log.info("Result event published: eventType={}, changeId={}, correlationId={}",
                    envelope.eventType(), result.getChangeId(), result.getCorrelationId());
        } catch (ExecutionException e) {
            log.error("Failed to publish result event — sending to DLQ: changeId={}, correlationId={}",
                    result.getChangeId(), result.getCorrelationId(), e.getCause());
            sendToDlq(key, envelope);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted publishing result event — sending to DLQ: changeId={}",
                    result.getChangeId());
            sendToDlq(key, envelope);
        } catch (TimeoutException e) {
            log.error("Timeout publishing result event — sending to DLQ: changeId={}",
                    result.getChangeId());
            sendToDlq(key, envelope);
        }
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
