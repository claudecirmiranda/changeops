package com.changeops.deployorchestrator.infrastructure.kafka;

import com.changeops.deployorchestrator.domain.event.DeployFinishedEvent;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.ContainerProperties;
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

    // ── Consumer ──────────────────────────────────────────────────────────────

    @Bean
    public ConsumerFactory<String, DeployFinishedEvent> deployEventConsumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.changeops.*");
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, DeployFinishedEvent.class.getName());
        props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        return new DefaultKafkaConsumerFactory<>(props);
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

    // ── Producer ──────────────────────────────────────────────────────────────

    @Bean
    public ProducerFactory<String, IntegrationEvent> resultProducerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, IntegrationEvent> resultKafkaTemplate() {
        return new KafkaTemplate<>(resultProducerFactory());
    }

    // ── Topics ────────────────────────────────────────────────────────────────

    @Bean
    public NewTopic deployFinishedTopic() {
        return TopicBuilder.name(deployFinishedTopic).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic changeResultTopic() {
        return TopicBuilder.name(changeResultTopic).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic changeResultDltTopic() {
        return TopicBuilder.name(changeResultTopic + "-dlt").partitions(1).replicas(1).build();
    }
}
