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
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("null")
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
        String key = Objects.requireNonNull(result.getChangeId().toString());

        RecordMetadata metadata = new RecordMetadata(
                new TopicPartition(CHANGE_RESULT_TOPIC, 0), 0L, 0, 0L, 0, 0);
        SendResult<String, IntegrationEvent> sendResult =
                new SendResult<>(new ProducerRecord<>(CHANGE_RESULT_TOPIC, null), metadata);
        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(sendResult));

        adapter.publish(result);

        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<IntegrationEvent> eventCaptor = ArgumentCaptor.forClass(IntegrationEvent.class);
        verify(kafkaTemplate).send(topicCaptor.capture(), keyCaptor.capture(), eventCaptor.capture());
        assertThat(topicCaptor.getValue()).isEqualTo(CHANGE_RESULT_TOPIC);
        assertThat(keyCaptor.getValue()).isEqualTo(key);
        assertThat(eventCaptor.getValue().eventType()).isEqualTo("ChangeCompletedEvent");
        double count = meterRegistry.counter("events_published_total", "type", "ChangeCompletedEvent").count();
        assertThat(count).isEqualTo(1.0);
    }

    @Test
    void whenKafkaPublishFails_shouldFallbackToDlqTopic() {
        ChangeResult result = ChangeResult.from(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), false);
        result.markFinished();
        String key = Objects.requireNonNull(result.getChangeId().toString());

        CompletableFuture<SendResult<String, IntegrationEvent>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Kafka unavailable"));
        RecordMetadata dlqMetadata = new RecordMetadata(
                new TopicPartition(DLQ_TOPIC, 0), 0L, 0, 0L, 0, 0);
        SendResult<String, IntegrationEvent> dlqSendResult =
                new SendResult<>(new ProducerRecord<>(DLQ_TOPIC, null), dlqMetadata);

        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenAnswer(invocation -> CHANGE_RESULT_TOPIC.equals(invocation.getArgument(0, String.class))
                        ? failedFuture
                        : CompletableFuture.completedFuture(dlqSendResult));

        adapter.publish(result);

        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<IntegrationEvent> eventCaptor = ArgumentCaptor.forClass(IntegrationEvent.class);
        verify(kafkaTemplate, times(2)).send(topicCaptor.capture(), keyCaptor.capture(), eventCaptor.capture());
        assertThat(topicCaptor.getAllValues()).containsExactly(CHANGE_RESULT_TOPIC, DLQ_TOPIC);
        assertThat(keyCaptor.getAllValues()).containsExactly(key, key);
        assertThat(eventCaptor.getAllValues().get(1).eventType()).isEqualTo("ChangeFailedEvent");
    }
}
