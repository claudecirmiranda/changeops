package com.changeops.deployorchestrator.infrastructure.kafka;

import com.changeops.deployorchestrator.domain.model.ChangeResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KafkaResultPublisherAdapterTest {

    private static final String CHANGE_RESULT_TOPIC = "change-result";
    private static final String DLQ_TOPIC = "change-result-dlq";

    @Mock
    private KafkaTemplate<String, IntegrationEvent> kafkaTemplate;

    private SimpleMeterRegistry meterRegistry;
    private KafkaResultPublisherAdapter adapter;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        adapter = new KafkaResultPublisherAdapter(kafkaTemplate, CHANGE_RESULT_TOPIC, DLQ_TOPIC, meterRegistry);
    }

    @Test
    void whenKafkaPublishSucceeds_shouldIncrementCounter() {
        ChangeResult result = ChangeResult.from(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), true);
        result.markFinished();

        RecordMetadata metadata = new RecordMetadata(
                new TopicPartition(CHANGE_RESULT_TOPIC, 0), 0L, 0, 0L, 0, 0);
        SendResult<String, IntegrationEvent> sendResult =
                new SendResult<>(new ProducerRecord<>(CHANGE_RESULT_TOPIC, null), metadata);
        when(kafkaTemplate.send(eq(CHANGE_RESULT_TOPIC), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(sendResult));

        adapter.publish(result);

        double count = meterRegistry.counter("events_published_total", "type", "ChangeCompletedEvent").count();
        assertThat(count).isEqualTo(1.0);
    }

    @Test
    void whenKafkaPublishFails_shouldFallbackToDlqTopic() {
        ChangeResult result = ChangeResult.from(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), false);
        result.markFinished();

        CompletableFuture<SendResult<String, IntegrationEvent>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Kafka unavailable"));

        when(kafkaTemplate.send(eq(CHANGE_RESULT_TOPIC), any(), any()))
                .thenReturn(failedFuture);
        when(kafkaTemplate.send(eq(DLQ_TOPIC), any(), any()))
                .thenReturn(CompletableFuture.<SendResult<String, IntegrationEvent>>completedFuture(null));

        adapter.publish(result);

        ArgumentCaptor<IntegrationEvent> captor = ArgumentCaptor.forClass(IntegrationEvent.class);
        verify(kafkaTemplate).send(eq(DLQ_TOPIC), any(String.class), captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo("ChangeFailedEvent");
    }
}
