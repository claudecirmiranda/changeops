package com.changeops.deployorchestrator.infrastructure.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

@Configuration
@SuppressWarnings("null")
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${changeops.kafka.consumer.group-id}")
    private String groupId;

    @Value("${changeops.kafka.topics.deploy-finished}")
    private String deployFinishedTopic;

    @Value("${changeops.kafka.topics.change-result}")
    private String changeResultTopic;

    @Value("${changeops.kafka.default-replication-factor:1}")
    private int replicationFactor;

    private final ObjectMapper objectMapper;

    public KafkaConfig(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ── Consumer ──────────────────────────────────────────────────────────────
    //
    // Uses StringDeserializer so that deserialization NEVER fails — even for
    // poison pills (malformed JSON, invalid UUIDs, plain text).  The listener
    // parses the String → DeployFinishedEvent manually via ObjectMapper;
    // parse failures throw InvalidOrchestratorStateException which is in the
    // @RetryableTopic exclude list and routes straight to DLT.
    //
    // This also fixes the DLT consumer: because retries/DLT records are
    // re-published as Strings (via StringSerializer in the default producer),
    // the DLT consumer can always deserialize them and invoke the @DltHandler.

    @Bean
    public ConsumerFactory<String, String> deployEventConsumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer());
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String>
    deployEventListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(deployEventConsumerFactory());
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.setConcurrency(3);
        return factory;
    }

    // ── Producer Padrão (Primary — usado pelo @RetryableTopic para publicar retries/DLT) ──────────
    //
    // Uses StringSerializer so that retry/DLT records (which are Strings from
    // StringDeserializer) pass through without re-encoding.  This avoids the
    // base64-double-encoding problem that occurred when JsonSerializer<Object>
    // serialized raw byte[] values from DeadLetterPublishingRecoverer.

    @Bean
    public ProducerFactory<String, String> defaultProducerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        return new DefaultKafkaProducerFactory<>(props, new StringSerializer(), new StringSerializer());
    }

    @Primary
    @Bean
    public KafkaTemplate<String, String> defaultKafkaTemplate() {
        return new KafkaTemplate<>(defaultProducerFactory());
    }

    // ── Result Producer ───────────────────────────────────────────────────────

    @Bean
    public ProducerFactory<String, IntegrationEvent> resultProducerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        JsonSerializer<IntegrationEvent> valueSerializer = new JsonSerializer<>(objectMapper);
        valueSerializer.setAddTypeInfo(false);
        return new DefaultKafkaProducerFactory<>(props, new StringSerializer(), valueSerializer);
    }

    @Bean
    public KafkaTemplate<String, IntegrationEvent> resultKafkaTemplate() {
        return new KafkaTemplate<>(resultProducerFactory());
    }

    // ── Topics ────────────────────────────────────────────────────────────────

    @Bean
    public NewTopic deployFinishedTopic() {
        return TopicBuilder.name(deployFinishedTopic)
                .partitions(3)
                .replicas(replicationFactor)
                .build();
    }

    @Bean
    public NewTopic deployFinishedDltTopic() {
        return TopicBuilder.name(deployFinishedTopic + "-dlt")
                .partitions(1)
                .replicas(replicationFactor)
                .build();
    }

    @Bean
    public NewTopic changeResultTopic() {
        return TopicBuilder.name(changeResultTopic)
                .partitions(3)
                .replicas(replicationFactor)
                .build();
    }

    @Bean
    public NewTopic changeResultDltTopic() {
        return TopicBuilder.name(changeResultTopic + "-dlt")
                .partitions(1)
                .replicas(replicationFactor)
                .build();
    }
}

