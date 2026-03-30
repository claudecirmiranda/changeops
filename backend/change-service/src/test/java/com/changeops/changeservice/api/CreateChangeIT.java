package com.changeops.changeservice.api;

import com.changeops.changeservice.api.dto.CreateChangeRequest;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc(addFilters = false)
@Testcontainers
@ActiveProfiles("test")
class CreateChangeIT {

    private static final PostgresHolder POSTGRES = new PostgresHolder();
    private static final KafkaHolder KAFKA = new KafkaHolder();

    @Container
    static PostgreSQLContainer<?> postgres = POSTGRES.container();

    @Container
    static KafkaContainer kafka = KAFKA.container();

    @org.springframework.test.context.DynamicPropertySource
    static void configureProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("changeops.kafka.topics.change-prepared", () -> "changeops.change.prepared");
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void shouldCreate_whenPayloadIsValid_thenReturn201AndPublishEvent() throws Exception {
        CreateChangeRequest request = new CreateChangeRequest(
                "Deploy payment-service v3.0",
                "Major version upgrade",
                "payment-service",
                "user-test-001",
                Instant.now().plus(2, ChronoUnit.DAYS));

        String responseBody = mockMvc.perform(post("/api/v1/changes")
                        .contentType(Objects.requireNonNull(MediaType.APPLICATION_JSON))
                        .content(Objects.requireNonNull(objectMapper.writeValueAsString(request)))
                        .header("X-User-Id", "user-test-001"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.changeId").isNotEmpty())
                .andExpect(jsonPath("$.status").value("PREPARED"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        // verify event published to Kafka
        var parsed = objectMapper.readTree(responseBody);
        String changeId = parsed.get("changeId").asText();

        ConsumerRecords<String, String> records = consumeFromKafka(
                "changeops.change.prepared", changeId);

        assertThat(records.count()).isGreaterThanOrEqualTo(1);
        var record = records.iterator().next();
        var event = objectMapper.readTree(record.value());
        assertThat(event.get("eventType").asText()).isEqualTo("ChangePreparedEvent");
        assertThat(event.get("correlationId").asText())
                .isEqualTo(parsed.get("correlationId").asText());
    }

    @Test
    void shouldReturn400_whenTitleIsMissing() throws Exception {
        String invalidPayload = """
                {
                  "description": "Missing title",
                  "componentId": "payment-service",
                  "requestedBy": "user-001",
                  "scheduledAt": "2099-01-01T00:00:00Z"
                }""";

        mockMvc.perform(post("/api/v1/changes")
                        .contentType(Objects.requireNonNull(MediaType.APPLICATION_JSON))
                        .content(invalidPayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.title").isNotEmpty());
    }

    @Test
    void shouldReturn400_whenComponentIdIsMissing() throws Exception {
        String invalidPayload = """
                {
                  "title": "Some change",
                  "requestedBy": "user-001",
                  "scheduledAt": "2099-01-01T00:00:00Z"
                }""";

        mockMvc.perform(post("/api/v1/changes")
                        .contentType(Objects.requireNonNull(MediaType.APPLICATION_JSON))
                        .content(invalidPayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.componentId").isNotEmpty());
    }

    @Test
    void shouldReturn400_whenComponentIdHasInvalidFormat() throws Exception {
        String invalidPayload = """
                {
                  "title": "Some change",
                  "componentId": "?#@invalid",
                  "requestedBy": "user-001",
                  "scheduledAt": "2099-01-01T00:00:00Z"
                }""";

        mockMvc.perform(post("/api/v1/changes")
                        .contentType(Objects.requireNonNull(MediaType.APPLICATION_JSON))
                        .content(invalidPayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.componentId").isNotEmpty());
    }

    @Test
    void shouldListChanges_andReturnPaginatedResults() throws Exception {
        mockMvc.perform(get("/api/v1/changes")
                        .param("size", "10")
                        .param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.pageable").exists());
    }

    private ConsumerRecords<String, String> consumeFromKafka(String topic, String expectedKey) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-consumer-" + System.currentTimeMillis());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            return consumer.poll(Duration.ofSeconds(10));
        }
    }

    @SuppressWarnings("all")
    private static final class PostgresHolder {
        private final PostgreSQLContainer<?> container = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("changeops_test")
                .withUsername("test")
                .withPassword("test");

        private PostgreSQLContainer<?> container() {
            return container;
        }
    }

    @SuppressWarnings("all")
    private static final class KafkaHolder {
        private final KafkaContainer container = new KafkaContainer(
                DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

        private KafkaContainer container() {
            return container;
        }
    }
}
