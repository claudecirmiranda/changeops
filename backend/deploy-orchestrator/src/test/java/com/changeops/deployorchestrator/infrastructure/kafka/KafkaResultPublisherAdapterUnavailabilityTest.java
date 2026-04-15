package com.changeops.deployorchestrator.infrastructure.kafka;

import com.changeops.deployorchestrator.domain.model.ChangeResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests covering Kafka unavailability scenarios for {@code KafkaResultPublisherAdapter}.
 *
 * <p>Complements {@code KafkaResultPublisherAdapterTest} (which covers the ExecutionException
 * path and DLQ fallback basics) by adding:
 * <ul>
 *   <li>TimeoutException from {@code future.get(10, TimeUnit.SECONDS)}</li>
 *   <li>InterruptedException and its interrupt-flag restoration contract</li>
 *   <li>Double failure: both main topic and DLQ unavailable simultaneously</li>
 * </ul>
 *
 * <p>All scenarios verify that {@code publish()} never throws to the caller — the
 * adapter must be fault-tolerant and fall back gracefully to logging when both
 * channels fail.
 */
@SuppressWarnings("null")
@ExtendWith(MockitoExtension.class)
class KafkaResultPublisherAdapterUnavailabilityTest {

    private static final String CHANGE_RESULT_TOPIC = "change-result";
    private static final String DLQ_TOPIC = "change-result-dlq";

    @Mock
    KafkaTemplate<String, IntegrationEvent> kafkaTemplate;

    SimpleMeterRegistry meterRegistry;
    KafkaResultPublisherAdapter adapter;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        adapter = new KafkaResultPublisherAdapter(kafkaTemplate, CHANGE_RESULT_TOPIC, DLQ_TOPIC, meterRegistry);
    }

    // ─── TimeoutException (future.get times out) ──────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void shouldFallbackToDlq_andNotIncrementCounter_whenKafkaTimesOut() throws Exception {
        ChangeResult result = ChangeResult.from(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), true);
        result.markFinished();

        CompletableFuture<SendResult<String, IntegrationEvent>> timeoutFuture = org.mockito.Mockito.mock(CompletableFuture.class);
        when(timeoutFuture.get(anyLong(), any())).thenThrow(new TimeoutException("Kafka broker did not respond in 10s"));

        RecordMetadata dlqMetadata = new RecordMetadata(new TopicPartition(DLQ_TOPIC, 0), 0L, 0, 0L, 0, 0);
        SendResult<String, IntegrationEvent> dlqSendResult =
                new SendResult<>(new ProducerRecord<>(DLQ_TOPIC, null), dlqMetadata);

        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenAnswer(invocation -> CHANGE_RESULT_TOPIC.equals(invocation.getArgument(0, String.class))
                        ? timeoutFuture
                        : CompletableFuture.completedFuture(dlqSendResult));

        assertThatCode(() -> adapter.publish(result)).doesNotThrowAnyException();

        verify(kafkaTemplate, times(2)).send(anyString(), anyString(), any());
        assertThat(meterRegistry.find("events_published_total").counters()).isEmpty();
    }

    // ─── InterruptedException (thread interrupted during Kafka send) ──────────

    @Test
    @SuppressWarnings("unchecked")
    void shouldFallbackToDlq_andRestoreInterruptFlag_whenKafkaInterrupted() throws Exception {
        ChangeResult result = ChangeResult.from(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), false);
        result.markFinished();

        CompletableFuture<SendResult<String, IntegrationEvent>> interruptFuture = org.mockito.Mockito.mock(CompletableFuture.class);
        when(interruptFuture.get(anyLong(), any())).thenThrow(new InterruptedException("Thread was interrupted"));

        RecordMetadata dlqMetadata = new RecordMetadata(new TopicPartition(DLQ_TOPIC, 0), 0L, 0, 0L, 0, 0);
        SendResult<String, IntegrationEvent> dlqSendResult =
                new SendResult<>(new ProducerRecord<>(DLQ_TOPIC, null), dlqMetadata);

        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenAnswer(invocation -> CHANGE_RESULT_TOPIC.equals(invocation.getArgument(0, String.class))
                        ? interruptFuture
                        : CompletableFuture.completedFuture(dlqSendResult));

        // Ensure the interrupt flag is clear before the call
        Thread.interrupted();

        adapter.publish(result);

        // The adapter must restore the interrupt flag via Thread.currentThread().interrupt()
        // so that callers can detect the interruption and act accordingly.
        assertThat(Thread.interrupted())
                .as("Interrupt flag must be restored after catching InterruptedException")
                .isTrue();

        verify(kafkaTemplate, times(2)).send(anyString(), anyString(), any());
        assertThat(meterRegistry.find("events_published_total").counters()).isEmpty();
    }

    // ─── Double failure: main topic AND DLQ both unavailable ─────────────────

    @Test
    void shouldNotThrow_whenBothMainTopicAndDlqFail() {
        ChangeResult result = ChangeResult.from(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), true);
        result.markFinished();

        CompletableFuture<SendResult<String, IntegrationEvent>> mainTopicFailed = new CompletableFuture<>();
        mainTopicFailed.completeExceptionally(new RuntimeException("Main topic down"));

        CompletableFuture<SendResult<String, IntegrationEvent>> dlqFailed = new CompletableFuture<>();
        dlqFailed.completeExceptionally(new RuntimeException("DLQ also down — manual intervention required"));

        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenAnswer(invocation -> CHANGE_RESULT_TOPIC.equals(invocation.getArgument(0, String.class))
                        ? mainTopicFailed
                        : dlqFailed);

        // Must not throw — the CRITICAL DLQ failure is logged but never propagated
        assertThatCode(() -> adapter.publish(result)).doesNotThrowAnyException();

        // Both topics (main + DLQ) must have been attempted
        verify(kafkaTemplate, times(2)).send(anyString(), anyString(), any());

        // Counter must NOT be incremented when publish failed
        assertThat(meterRegistry.find("events_published_total").counters()).isEmpty();
    }
}
