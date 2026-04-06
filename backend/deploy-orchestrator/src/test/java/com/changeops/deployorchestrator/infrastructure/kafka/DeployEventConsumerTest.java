package com.changeops.deployorchestrator.infrastructure.kafka;

import com.changeops.deployorchestrator.application.port.in.ProcessDeployResultUseCase;
import com.changeops.deployorchestrator.domain.exception.InvalidOrchestratorStateException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class DeployEventConsumerTest {

    @Mock
    ProcessDeployResultUseCase processDeployResultUseCase;

    private SimpleMeterRegistry registry;
    private DeployEventConsumer consumer;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        consumer = new DeployEventConsumer(processDeployResultUseCase, objectMapper, registry);
    }

    @Test
    void shouldIncrementEventsFailedAndDltCounters_whenDltHandlerIsCalled() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "changeops.deploy.finished-dlt", 0, 0L,
                UUID.randomUUID().toString(),
                "{\"eventType\":\"DeployFinishedEvent\"}"
        );

        consumer.onDlt(record);

        assertThat(registry.counter("events_failed_total", "consumer", "deploy-orchestrator").count())
                .isEqualTo(1.0);
        assertThat(registry.counter("events_dlt_total", "consumer", "deploy-orchestrator").count())
                .isEqualTo(1.0);
    }

    @Test
    void shouldIncrementEventsRetriesCounter_whenTopicIsRetryTopic() {
        ConsumerRecord<String, String> record = buildRecord("changeops.deploy.finished-retry-0");

        consumer.onDeployFinished(record, "changeops.deploy.finished-retry-0", 0L);

        assertThat(registry.counter("events_retries_total", "consumer", "deploy-orchestrator").count())
                .isEqualTo(1.0);
    }

    @Test
    void shouldNotIncrementEventsRetriesCounter_whenTopicIsOriginalTopic() {
        ConsumerRecord<String, String> record = buildRecord("changeops.deploy.finished");

        consumer.onDeployFinished(record, "changeops.deploy.finished", 0L);

        assertThat(registry.counter("events_retries_total", "consumer", "deploy-orchestrator").count())
                .isEqualTo(0.0);
    }

    @Test
    void shouldIncrementRetriesForEachRetryTopic() {
        consumer.onDeployFinished(buildRecord("changeops.deploy.finished-retry-0"),
                "changeops.deploy.finished-retry-0", 0L);
        consumer.onDeployFinished(buildRecord("changeops.deploy.finished-retry-1"),
                "changeops.deploy.finished-retry-1", 1L);
        consumer.onDeployFinished(buildRecord("changeops.deploy.finished-retry-2"),
                "changeops.deploy.finished-retry-2", 2L);

        assertThat(registry.counter("events_retries_total", "consumer", "deploy-orchestrator").count())
                .isEqualTo(3.0);
    }

    @Test
    void shouldThrowInvalidOrchestratorStateException_whenEventIsNull() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "changeops.deploy.finished", 0, 0L,
                UUID.randomUUID().toString(), null);

        assertThatThrownBy(() -> consumer.onDeployFinished(record, "changeops.deploy.finished", 0L))
                .isInstanceOf(InvalidOrchestratorStateException.class)
                .hasMessageContaining("Null or empty payload");
    }

    @Test
    void shouldIncrementDltCounters_whenDltHandlerReceivesNullEvent() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
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
        String jsonWithNullPayload = "{\"eventType\":\"DeployFinishedEvent\",\"version\":\"1.0\","
                + "\"correlationId\":\"" + UUID.randomUUID() + "\","
                + "\"occurredAt\":\"2026-03-23T11:42:00Z\",\"payload\":null}";
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "changeops.deploy.finished", 0, 0L,
                UUID.randomUUID().toString(), jsonWithNullPayload);

        assertThatThrownBy(() -> consumer.onDeployFinished(record, "changeops.deploy.finished", 0L))
                .isInstanceOf(InvalidOrchestratorStateException.class)
                .hasMessageContaining("Deserialization failed or invalid payload");
    }

    @Test
    void shouldHandleByteArrayPayload_inDltHandler() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "changeops.deploy.finished-dlt", 0, 0L,
                UUID.randomUUID().toString(),
                "{\"eventType\":\"DeployFinishedEvent\"}");

        consumer.onDlt(record);

        assertThat(registry.counter("events_dlt_total", "consumer", "deploy-orchestrator").count())
                .isEqualTo(1.0);
        assertThat(registry.counter("events_failed_total", "consumer", "deploy-orchestrator").count())
                .isEqualTo(1.0);
    }

    @Test
    void shouldIncrementCounters_whenDltPayloadExceedsMaxLength() {
        String longPayload = "x".repeat(DeployEventConsumer.MAX_DLT_PAYLOAD_LOG_LENGTH + 100);
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
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
        String json = "{\"eventType\":\"DeployFinishedEvent\",\"version\":\"1.0\","
                + "\"correlationId\":\"" + UUID.randomUUID() + "\","
                + "\"occurredAt\":\"2026-03-23T11:42:00Z\","
                + "\"payload\":{\"deployId\":null,\"changeId\":\"" + UUID.randomUUID() + "\","
                + "\"result\":\"SUCCESS\",\"executedAt\":\"2026-03-23T11:42:00Z\"}}";
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "changeops.deploy.finished", 0, 0L,
                UUID.randomUUID().toString(), json);

        assertThatThrownBy(() -> consumer.onDeployFinished(record, "changeops.deploy.finished", 0L))
                .isInstanceOf(InvalidOrchestratorStateException.class);
    }

    @Test
    void shouldThrowInvalidOrchestratorStateException_whenPayloadChangeIdIsNull() {
        String json = "{\"eventType\":\"DeployFinishedEvent\",\"version\":\"1.0\","
                + "\"correlationId\":\"" + UUID.randomUUID() + "\","
                + "\"occurredAt\":\"2026-03-23T11:42:00Z\","
                + "\"payload\":{\"deployId\":\"" + UUID.randomUUID() + "\",\"changeId\":null,"
                + "\"result\":\"SUCCESS\",\"executedAt\":\"2026-03-23T11:42:00Z\"}}";
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "changeops.deploy.finished", 0, 0L,
                UUID.randomUUID().toString(), json);

        assertThatThrownBy(() -> consumer.onDeployFinished(record, "changeops.deploy.finished", 0L))
                .isInstanceOf(InvalidOrchestratorStateException.class);
    }

    @Test
    void shouldThrowInvalidOrchestratorStateException_whenResultFieldIsNull() {
        String json = "{\"eventType\":\"DeployFinishedEvent\",\"version\":\"1.0\","
                + "\"correlationId\":\"" + UUID.randomUUID() + "\","
                + "\"occurredAt\":\"2026-03-23T11:42:00Z\","
                + "\"payload\":{\"deployId\":\"" + UUID.randomUUID() + "\","
                + "\"changeId\":\"" + UUID.randomUUID() + "\","
                + "\"result\":null,\"executedAt\":\"2026-03-23T11:42:00Z\"}}";
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "changeops.deploy.finished", 0, 0L,
                UUID.randomUUID().toString(), json);

        assertThatThrownBy(() -> consumer.onDeployFinished(record, "changeops.deploy.finished", 0L))
                .isInstanceOf(InvalidOrchestratorStateException.class);
    }

    @Test
    void shouldIncrementDltCounters_whenDltHandlerReceivesEmptyJsonString() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
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
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "changeops.deploy.finished-dlt", 0, 0L,
                UUID.randomUUID().toString(), "hello world");

        consumer.onDlt(record);

        assertThat(registry.counter("events_dlt_total", "consumer", "deploy-orchestrator").count())
                .isEqualTo(1.0);
    }

    // ─── Poison pill (CT-13B / CT-32 equivalents) ─────────────────────────────

    @Test
    void shouldThrowInvalidOrchestratorStateException_whenJsonContainsInvalidUUID() {
        String malformedJson = "{\"eventType\":\"DeployFinishedEvent\",\"version\":\"1.0\","
                + "\"correlationId\":\"f10f409d-2eee-4053-82f2-80fac03fd65b\","
                + "\"occurredAt\":\"2026-03-23T11:42:00Z\","
                + "\"payload\":{\"deployId\":\"" + UUID.randomUUID() + "\","
                + "\"changeId\":\"INVALID-NOT-A-UUID\","
                + "\"result\":\"SUCCESS\",\"executedAt\":\"2026-03-23T11:42:00Z\"}}";
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "changeops.deploy.finished", 0, 0L,
                UUID.randomUUID().toString(), malformedJson);

        assertThatThrownBy(() -> consumer.onDeployFinished(record, "changeops.deploy.finished", 0L))
                .isInstanceOf(InvalidOrchestratorStateException.class)
                .hasMessageContaining("Failed to parse");
    }

    @Test
    void shouldThrowInvalidOrchestratorStateException_whenValueIsPlainText() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "changeops.deploy.finished", 0, 0L,
                UUID.randomUUID().toString(), "hello world not json");

        assertThatThrownBy(() -> consumer.onDeployFinished(record, "changeops.deploy.finished", 0L))
                .isInstanceOf(InvalidOrchestratorStateException.class)
                .hasMessageContaining("Failed to parse");
    }

    @Test
    void shouldThrowInvalidOrchestratorStateException_whenValueIsBlank() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "changeops.deploy.finished", 0, 0L,
                UUID.randomUUID().toString(), "   ");

        assertThatThrownBy(() -> consumer.onDeployFinished(record, "changeops.deploy.finished", 0L))
                .isInstanceOf(InvalidOrchestratorStateException.class)
                .hasMessageContaining("Null or empty payload");
    }

    private ConsumerRecord<String, String> buildRecord(String topic) {
        UUID deployId = UUID.randomUUID();
        UUID changeId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        String json = "{\"eventType\":\"DeployFinishedEvent\",\"version\":\"1.0\","
                + "\"correlationId\":\"" + correlationId + "\","
                + "\"occurredAt\":\"2026-03-23T11:42:00Z\","
                + "\"payload\":{\"deployId\":\"" + deployId + "\","
                + "\"changeId\":\"" + changeId + "\","
                + "\"result\":\"SUCCESS\",\"executedAt\":\"2026-03-23T11:42:00Z\"}}";
        return new ConsumerRecord<>(
                topic, 0, 0L,
                UUID.randomUUID().toString(), json);
    }
}
