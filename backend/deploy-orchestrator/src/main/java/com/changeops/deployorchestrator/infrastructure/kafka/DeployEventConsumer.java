package com.changeops.deployorchestrator.infrastructure.kafka;

import com.changeops.deployorchestrator.application.port.in.ProcessDeployResultUseCase;
import com.changeops.deployorchestrator.domain.event.DeployFinishedEvent;
import com.changeops.deployorchestrator.domain.exception.InvalidOrchestratorStateException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class DeployEventConsumer {

    static final int MAX_DLT_PAYLOAD_LOG_LENGTH = 500;

    private final ProcessDeployResultUseCase processDeployResultUseCase;
    private final Counter dltCounter;
    private final Counter eventsFailedCounter;
    private final Counter eventsRetriesCounter;

    public DeployEventConsumer(
            ProcessDeployResultUseCase processDeployResultUseCase,
            MeterRegistry meterRegistry) {
        this.processDeployResultUseCase = processDeployResultUseCase;
        this.dltCounter = Counter.builder("events_dlt_total")
                .tag("consumer", "deploy-orchestrator")
                .description("Total events sent to DLT after max retries")
                .register(meterRegistry);
        this.eventsFailedCounter = Counter.builder("events_failed_total")
                .tag("consumer", "deploy-orchestrator")
                .description("Total events that permanently failed processing")
                .register(meterRegistry);
        this.eventsRetriesCounter = Counter.builder("events_retries_total")
                .tag("consumer", "deploy-orchestrator")
                .description("Total retry hops (incremented each time an event is picked up from a retry topic)")
                .register(meterRegistry);
    }

    @RetryableTopic(
            attempts = "4",
            backoff = @Backoff(delay = 500, multiplier = 2.0, maxDelay = 10_000),
            autoCreateTopics = "true",
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
            dltStrategy = DltStrategy.FAIL_ON_ERROR,
            dltTopicSuffix = "-dlt",
            exclude = {InvalidOrchestratorStateException.class}
    )
    @KafkaListener(
            topics = "${changeops.kafka.topics.deploy-finished}",
            groupId = "${changeops.kafka.consumer.group-id}",
            containerFactory = "deployEventListenerContainerFactory"
    )
    public void onDeployFinished(
            ConsumerRecord<String, DeployFinishedEvent> record,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.OFFSET) long offset) {

        DeployFinishedEvent event = record.value();

        if (event == null || event.payload() == null) {
            log.error("Deserialization failed or invalid payload — sending to DLT: topic={}, offset={}, key={}",
                    topic, offset, record.key());
            throw new InvalidOrchestratorStateException(
                    "Deserialization failed or invalid payload. topic=" + topic + ", offset=" + offset);
        }

        if (topic.contains("-retry-")) {
            eventsRetriesCounter.increment();
        }

        MDC.put("correlation_id", event.correlationId() != null
                ? event.correlationId().toString() : "unknown");
        MDC.put("deploy_id", event.payload().deployId().toString());

        try {
            log.info("Received DeployFinishedEvent: topic={}, offset={}, deployId={}, result={}",
                    topic, offset, event.payload().deployId(), event.payload().result());

            processDeployResultUseCase.execute(event);

        } catch (Exception e) {
            log.error("Error processing DeployFinishedEvent — will retry: deployId={}, attempt topic={}",
                    event.payload().deployId(), topic, e);
            throw e;
        } finally {
            MDC.remove("correlation_id");
            MDC.remove("deploy_id");
        }
    }

    @DltHandler
    public void onDlt(ConsumerRecord<String, Object> record) {
        String topic = record.topic();
        String safePayload = toSafePayload(record.value());
        log.error("Event routed to DLT: key={}, topic={}, offset={}, payload={}",
                record.key(), topic, record.offset(), safePayload);
        dltCounter.increment();
        eventsFailedCounter.increment();
    }

    /**
     * Converts a DLT record value to a safe, log-friendly string.
     *
     * Payloads that reach the DLT are often malformed or unexpectedly large.
     * Logging the full content could saturate structured logs (Grafana/ELK), expose
     * sensitive data, or cause memory pressure when the JSON encoder serializes a very
     * long string. MAX_DLT_PAYLOAD_LOG_LENGTH limits the logged portion to a safe and
     * inspectable size; the full message remains available in Kafka UI for diagnostics.
     *
     * When the value is a byte array, the truncation is applied before creating the
     * String to avoid allocating the entire byte array in memory.
     */
    private String toSafePayload(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof byte[]) {
            byte[] bytes = (byte[]) value;
            int length = Math.min(bytes.length, MAX_DLT_PAYLOAD_LOG_LENGTH);
            String base = new String(bytes, 0, length, StandardCharsets.UTF_8);
            return bytes.length > length ? base + "...[truncated]" : base;
        }
        String payload = String.valueOf(value);
        return payload.length() > MAX_DLT_PAYLOAD_LOG_LENGTH
                ? payload.substring(0, MAX_DLT_PAYLOAD_LOG_LENGTH) + "...[truncated]"
                : payload;
    }
}
