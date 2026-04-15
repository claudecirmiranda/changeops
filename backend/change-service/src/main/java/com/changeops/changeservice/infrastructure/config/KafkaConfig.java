package com.changeops.changeservice.infrastructure.config;

import com.changeops.changeservice.infrastructure.kafka.IntegrationEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

@Configuration
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${changeops.kafka.topics.change-prepared}")
    private String changePreparedTopic;

    @Value("${changeops.kafka.default-replication-factor:1}")
    private int replicationFactor;

    @Value("${changeops.kafka.producer.close-timeout-seconds:30}")
    private int producerCloseTimeoutSeconds;

    private final ObjectMapper objectMapper;

    public KafkaConfig(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Bean
    public ProducerFactory<String, IntegrationEvent> producerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 1);
        JsonSerializer<IntegrationEvent> valueSerializer = new JsonSerializer<>(objectMapper);
        valueSerializer.setAddTypeInfo(false);
        DefaultKafkaProducerFactory<String, IntegrationEvent> factory =
                new DefaultKafkaProducerFactory<>(props, new StringSerializer(), valueSerializer);
        factory.setPhysicalCloseTimeout(producerCloseTimeoutSeconds);
        return factory;
    }

    @Bean
    public KafkaTemplate<String, IntegrationEvent> kafkaTemplate() {
        return new KafkaTemplate<>(Objects.requireNonNull(producerFactory()));
    }

    @Bean
    public NewTopic changePreparedTopic() {
        return TopicBuilder.name(Objects.requireNonNull(changePreparedTopic))
                .partitions(3)
                .replicas(replicationFactor)
                .build();
    }
}
