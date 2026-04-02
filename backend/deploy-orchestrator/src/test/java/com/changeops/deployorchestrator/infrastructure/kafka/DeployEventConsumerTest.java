package com.changeops.deployorchestrator.infrastructure.kafka;

import com.changeops.deployorchestrator.application.port.in.ProcessDeployResultUseCase;
import com.changeops.deployorchestrator.domain.event.DeployFinishedEvent;
import com.changeops.deployorchestrator.domain.exception.InvalidOrchestratorStateException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class DeployEventConsumerTest {

    @Mock
    ProcessDeployResultUseCase processDeployResultUseCase;

    private SimpleMeterRegistry registry;
    private DeployEventConsumer consumer;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        consumer = new DeployEventConsumer(processDeployResultUseCase, registry);
    }

    @Test
    void shouldIncrementEventsFailedAndDltCounters_whenDltHandlerIsCalled() {
        ConsumerRecord<String, Object> record = new ConsumerRecord<>(
                "changeops.deploy.finished-dlt", 0, 0L,
                UUID.randomUUID().toString(),
                "{\"eventType\":\"DeployFinishedEvent\"}"  // String — caminho feliz
        );

        consumer.onDlt(record);

        assertThat(registry.counter("events_failed_total", "consumer", "deploy-orchestrator").count())
                .isEqualTo(1.0);
        assertThat(registry.counter("events_dlt_total", "consumer", "deploy-orchestrator").count())
                .isEqualTo(1.0);
    }

    @Test
    void shouldIncrementEventsRetriesCounter_whenTopicIsRetryTopic() {
        ConsumerRecord<String, DeployFinishedEvent> record = buildRecord("changeops.deploy.finished-retry-0");

        consumer.onDeployFinished(record, "changeops.deploy.finished-retry-0", 0L);

        assertThat(registry.counter("events_retries_total", "consumer", "deploy-orchestrator").count())
                .isEqualTo(1.0);
    }

    @Test
    void shouldNotIncrementEventsRetriesCounter_whenTopicIsOriginalTopic() {
        ConsumerRecord<String, DeployFinishedEvent> record = buildRecord("changeops.deploy.finished");

        consumer.onDeployFinished(record, "changeops.deploy.finished", 0L);

        assertThat(registry.counter("events_retries_total", "consumer", "deploy-orchestrator").count())
                .isEqualTo(0.0);
    }

    @Test
    void shouldIncrementRetriesForEachRetryTopic() {
        consumer.onDeployFinished(buildRecord("changeops.deploy.finished-retry-0"), "changeops.deploy.finished-retry-0", 0L);
        consumer.onDeployFinished(buildRecord("changeops.deploy.finished-retry-1"), "changeops.deploy.finished-retry-1", 1L);
        consumer.onDeployFinished(buildRecord("changeops.deploy.finished-retry-2"), "changeops.deploy.finished-retry-2", 2L);

        assertThat(registry.counter("events_retries_total", "consumer", "deploy-orchestrator").count())
                .isEqualTo(3.0);
    }

    @Test
    void shouldThrowInvalidOrchestratorStateException_whenEventIsNull() {
        ConsumerRecord<String, DeployFinishedEvent> record = new ConsumerRecord<>(
                "changeops.deploy.finished", 0, 0L,
                UUID.randomUUID().toString(), null);

        assertThatThrownBy(() -> consumer.onDeployFinished(record, "changeops.deploy.finished", 0L))
                .isInstanceOf(InvalidOrchestratorStateException.class)
                .hasMessageContaining("Deserialization failed or invalid payload");
    }

    @Test
    void shouldIncrementDltCounters_whenDltHandlerReceivesNullEvent() {
        ConsumerRecord<String, Object> record = new ConsumerRecord<>(
                "changeops.deploy.finished-dlt", 0, 0L,
                UUID.randomUUID().toString(), null);

        consumer.onDlt(record);

        assertThat(registry.counter("events_failed_total", "consumer", "deploy-orchestrator").count())
                .isEqualTo(1.0);
        assertThat(registry.counter("events_dlt_total", "consumer", "deploy-orchestrator").count())
                .isEqualTo(1.0);
    }

    @Test
    void shouldThrowInvalidOrchestratorStateException_whenEventPayloadIsNull() {
        DeployFinishedEvent eventWithNullPayload = new DeployFinishedEvent(
                "DeployFinishedEvent", "1.0", UUID.randomUUID(), Instant.now(), null);
        ConsumerRecord<String, DeployFinishedEvent> record = new ConsumerRecord<>(
                "changeops.deploy.finished", 0, 0L,
                UUID.randomUUID().toString(), eventWithNullPayload);

        assertThatThrownBy(() -> consumer.onDeployFinished(record, "changeops.deploy.finished", 0L))
                .isInstanceOf(InvalidOrchestratorStateException.class)
                .hasMessageContaining("Deserialization failed or invalid payload");
    }

    @Test
    void shouldHandleByteArrayPayload_inDltHandler() {
        byte[] rawPayload = "{\"eventType\":\"DeployFinishedEvent\"}".getBytes(StandardCharsets.UTF_8);
        ConsumerRecord<String, Object> record = new ConsumerRecord<>(
                "changeops.deploy.finished-dlt", 0, 0L,
                UUID.randomUUID().toString(), rawPayload);

        consumer.onDlt(record);

        assertThat(registry.counter("events_dlt_total", "consumer", "deploy-orchestrator").count())
                .isEqualTo(1.0);
        assertThat(registry.counter("events_failed_total", "consumer", "deploy-orchestrator").count())
                .isEqualTo(1.0);
    }

    @Test
    void shouldIncrementCounters_whenDltPayloadExceedsMaxLength() {
        String longPayload = "x".repeat(DeployEventConsumer.MAX_DLT_PAYLOAD_LOG_LENGTH + 100);
        ConsumerRecord<String, Object> record = new ConsumerRecord<>(
                "changeops.deploy.finished-dlt", 0, 0L,
                UUID.randomUUID().toString(), longPayload);

        consumer.onDlt(record);

        assertThat(registry.counter("events_dlt_total", "consumer", "deploy-orchestrator").count())
                .isEqualTo(1.0);
        assertThat(registry.counter("events_failed_total", "consumer", "deploy-orchestrator").count())
                .isEqualTo(1.0);
    }

    // ─── Edge-Case: Malformed / missing payload fields ────────────────────────

    @Test
    void shouldThrowInvalidOrchestratorStateException_whenPayloadDeployIdIsNull() {
        DeployFinishedEvent eventWithNullDeployId = new DeployFinishedEvent(
                "DeployFinishedEvent", "1.0", UUID.randomUUID(), Instant.now(),
                new DeployFinishedEvent.Payload(
                        null,              // deployId = null
                        UUID.randomUUID(),
                        "SUCCESS",
                        Instant.now()));
        ConsumerRecord<String, DeployFinishedEvent> record = new ConsumerRecord<>(
                "changeops.deploy.finished", 0, 0L,
                UUID.randomUUID().toString(), eventWithNullDeployId);

        // A null deployId would cause a NullPointerException in the service —
        // the consumer must treat it as a deserialization failure and route to DLT
        // by throwing InvalidOrchestratorStateException (no-retry annotation).
        assertThatThrownBy(() -> consumer.onDeployFinished(record, "changeops.deploy.finished", 0L))
                .isInstanceOf(InvalidOrchestratorStateException.class);
    }

    @Test
    void shouldThrowInvalidOrchestratorStateException_whenPayloadChangeIdIsNull() {
        DeployFinishedEvent eventWithNullChangeId = new DeployFinishedEvent(
                "DeployFinishedEvent", "1.0", UUID.randomUUID(), Instant.now(),
                new DeployFinishedEvent.Payload(
                        UUID.randomUUID(),
                        null,              // changeId = null
                        "SUCCESS",
                        Instant.now()));
        ConsumerRecord<String, DeployFinishedEvent> record = new ConsumerRecord<>(
                "changeops.deploy.finished", 0, 0L,
                UUID.randomUUID().toString(), eventWithNullChangeId);

        assertThatThrownBy(() -> consumer.onDeployFinished(record, "changeops.deploy.finished", 0L))
                .isInstanceOf(InvalidOrchestratorStateException.class);
    }

    @Test
    void shouldThrowInvalidOrchestratorStateException_whenResultFieldIsNull() {
        DeployFinishedEvent eventWithNullResult = new DeployFinishedEvent(
                "DeployFinishedEvent", "1.0", UUID.randomUUID(), Instant.now(),
                new DeployFinishedEvent.Payload(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null,             // result = null
                        Instant.now()));
        ConsumerRecord<String, DeployFinishedEvent> record = new ConsumerRecord<>(
                "changeops.deploy.finished", 0, 0L,
                UUID.randomUUID().toString(), eventWithNullResult);

        assertThatThrownBy(() -> consumer.onDeployFinished(record, "changeops.deploy.finished", 0L))
                .isInstanceOf(InvalidOrchestratorStateException.class);
    }

    @Test
    void shouldIncrementDltCounters_whenDltHandlerReceivesEmptyJsonString() {
        ConsumerRecord<String, Object> record = new ConsumerRecord<>(
                "changeops.deploy.finished-dlt", 0, 0L,
                UUID.randomUUID().toString(), "{}");

        consumer.onDlt(record);

        assertThat(registry.counter("events_dlt_total", "consumer", "deploy-orchestrator").count())
                .isEqualTo(1.0);
        assertThat(registry.counter("events_failed_total", "consumer", "deploy-orchestrator").count())
                .isEqualTo(1.0);
    }

    @Test
    void shouldIncrementDltCounters_whenDltHandlerReceivesPlainTextPayload() {
        ConsumerRecord<String, Object> record = new ConsumerRecord<>(
                "changeops.deploy.finished-dlt", 0, 0L,
                UUID.randomUUID().toString(), "hello world");

        consumer.onDlt(record);

        assertThat(registry.counter("events_dlt_total", "consumer", "deploy-orchestrator").count())
                .isEqualTo(1.0);
    }

    private ConsumerRecord<String, DeployFinishedEvent> buildRecord(String topic) {
        return new ConsumerRecord<>(
                topic, 0, 0L,
                UUID.randomUUID().toString(),
                new DeployFinishedEvent(
                        "DeployFinishedEvent", "1.0",
                        UUID.randomUUID(), Instant.now(),
                        new DeployFinishedEvent.Payload(
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                "SUCCESS",
                                Instant.now())));
    }
}
