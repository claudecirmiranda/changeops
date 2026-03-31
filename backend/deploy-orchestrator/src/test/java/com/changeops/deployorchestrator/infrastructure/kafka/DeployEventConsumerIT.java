package com.changeops.deployorchestrator.infrastructure.kafka;

import com.changeops.deployorchestrator.domain.event.DeployFinishedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@SuppressWarnings("null")
class DeployEventConsumerIT {

    @Container
    static PostgreSQLContainer<?> postgres = createPostgresContainer();

    @Container
    static KafkaContainer kafka = createKafkaContainer();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("changeops.kafka.topics.deploy-finished", () -> "changeops.deploy.finished");
        registry.add("changeops.kafka.topics.change-result", () -> "changeops.change.result");
        registry.add("changeops.kafka.topics.dlq", () -> "changeops.events.dlq");
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ObjectMapper objectMapper;

    private KafkaTemplate<String, DeployFinishedEvent> kafkaTemplate;

    @BeforeEach
    void setUp() {
        Map<String, Object> producerProps = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class,
                JsonSerializer.ADD_TYPE_INFO_HEADERS, false
        );
        kafkaTemplate = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(producerProps));

        jdbcTemplate.execute("DELETE FROM change_events");
        jdbcTemplate.execute("DELETE FROM processed_events");
        jdbcTemplate.execute("DELETE FROM changes");
    }

    @Test
    void shouldMarkCompleted_whenDeploySucceeds() {
        UUID changeId = insertPreparedChange();
        UUID deployId = UUID.randomUUID();

        publishDeployEvent(deployId, changeId, "SUCCESS");

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            String status = jdbcTemplate.queryForObject(
                    "SELECT status FROM changes WHERE change_id = ?",
                    String.class, changeId);
            assertThat(status).isEqualTo("COMPLETED");
        });
    }

    @Test
    void shouldMarkFailed_whenDeployFails() {
        UUID changeId = insertPreparedChange();
        UUID deployId = UUID.randomUUID();

        publishDeployEvent(deployId, changeId, "FAILURE");

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            String status = jdbcTemplate.queryForObject(
                    "SELECT status FROM changes WHERE change_id = ?",
                    String.class, changeId);
            assertThat(status).isEqualTo("FAILED");
        });
    }

    @Test
    void shouldBeIdempotent_whenSameEventDeliveredTwice() {
        UUID changeId = insertPreparedChange();
        UUID deployId = UUID.randomUUID();

        publishDeployEvent(deployId, changeId, "SUCCESS");

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            String status = jdbcTemplate.queryForObject(
                    "SELECT status FROM changes WHERE change_id = ?",
                    String.class, changeId);
            assertThat(status).isEqualTo("COMPLETED");
        });

        // Send same event again
        publishDeployEvent(deployId, changeId, "SUCCESS");

        // Wait a bit and verify still COMPLETED (no error, idempotent)
        await().during(2, TimeUnit.SECONDS).atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM processed_events WHERE event_id = ?",
                    Integer.class, deployId);
            assertThat(count).isEqualTo(1);
        });
    }

    @Test
    void shouldSaveChangeEvent_whenDeploySucceeds() {
        UUID changeId = insertPreparedChange();
        UUID deployId = UUID.randomUUID();

        publishDeployEvent(deployId, changeId, "SUCCESS");

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM change_events WHERE change_id = ? AND event_type = 'ChangeCompletedEvent'",
                    Integer.class, changeId);
            assertThat(count).isGreaterThanOrEqualTo(1);
        });
    }

    @Test
    void shouldSendToDlt_whenProcessingFailsAfterRetries() {
        // Use a non-existent changeId to force a processing failure
        UUID nonExistentChangeId = UUID.randomUUID();
        UUID deployId = UUID.randomUUID();

        Map<String, Object> consumerProps = new HashMap<>();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "test-dlt-verifier-" + UUID.randomUUID());
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        publishDeployEvent(deployId, nonExistentChangeId, "SUCCESS");

        List<ConsumerRecord<String, String>> dltRecords = new ArrayList<>();
        try (Consumer<String, String> dltConsumer =
                     new DefaultKafkaConsumerFactory<String, String>(consumerProps).createConsumer()) {
            dltConsumer.subscribe(Collections.singletonList("changeops.deploy.finished-dlt"));

            // After retries are exhausted the event must land in the DLT topic
            await().atMost(60, TimeUnit.SECONDS).pollInterval(2, TimeUnit.SECONDS).untilAsserted(() -> {
                dltConsumer.poll(Duration.ofMillis(500)).forEach(dltRecords::add);
                assertThat(dltRecords).isNotEmpty();
            });
        }

        // The change record must not have been created (non-existent changeId)
        Integer changeCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM changes WHERE change_id = ?",
                Integer.class, nonExistentChangeId);
        assertThat(changeCount).isZero();
    }

    private UUID insertPreparedChange() {
        UUID changeId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO changes (change_id, title, description, component_id, requested_by,
                                     scheduled_at, status, correlation_id, created_at, updated_at)
                VALUES (?, 'Test Change', 'desc', 'test-svc', 'user-test',
                        NOW() + INTERVAL '1 day', 'PREPARED', ?, NOW(), NOW())
                """, changeId, correlationId);
        return changeId;
    }

    private void publishDeployEvent(UUID deployId, UUID changeId, String result) {
        DeployFinishedEvent event = new DeployFinishedEvent(
                "DeployFinishedEvent", "1.0",
                UUID.randomUUID(), Instant.now(),
                new DeployFinishedEvent.Payload(deployId, changeId, result, Instant.now()));

        kafkaTemplate.send(new ProducerRecord<>(
                "changeops.deploy.finished", changeId.toString(), event));
        kafkaTemplate.flush();
    }

    @SuppressWarnings("all")
    private static PostgreSQLContainer<?> createPostgresContainer() {
        return new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("changeops_test")
                .withUsername("test")
                .withPassword("test")
                .withInitScript("init-test-schema.sql");
    }

    @SuppressWarnings("all")
    private static KafkaContainer createKafkaContainer() {
        return new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));
    }
}
