package com.changeops.deployorchestrator.infrastructure.kafka;

import com.changeops.deployorchestrator.domain.event.DeployFinishedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
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
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

@Configuration
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

    @Bean
    public ConsumerFactory<String, DeployFinishedEvent> deployEventConsumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        JsonDeserializer<DeployFinishedEvent> jsonDeserializer =
                new JsonDeserializer<>(DeployFinishedEvent.class, objectMapper, false);
        jsonDeserializer.addTrustedPackages("com.changeops.*");

        ErrorHandlingDeserializer<DeployFinishedEvent> errorHandlingDeserializer =
                new ErrorHandlingDeserializer<>(jsonDeserializer);

        return new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), errorHandlingDeserializer);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, DeployFinishedEvent>
    deployEventListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, DeployFinishedEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(deployEventConsumerFactory());
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.setConcurrency(3);
        return factory;
    }

    // ── DLT Producer (Primary) ────────────────────────────────────────────────
    //
    // Marcado como @Primary para que o @RetryableTopic use este template
    // ao publicar no DLT — ByteArraySerializer preserva os bytes brutos
    // da mensagem original sem re-serializar como Base64.

    @Bean
    public ProducerFactory<String, byte[]> dltProducerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        return new DefaultKafkaProducerFactory<>(
                props, new StringSerializer(), new ByteArraySerializer());
    }

    @Primary
    @Bean
    public KafkaTemplate<String, byte[]> dltKafkaTemplate() {
        return new KafkaTemplate<>(dltProducerFactory());
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

