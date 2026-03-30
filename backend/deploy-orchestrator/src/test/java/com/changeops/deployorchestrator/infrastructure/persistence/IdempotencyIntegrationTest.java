package com.changeops.deployorchestrator.infrastructure.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@ActiveProfiles("test")
class IdempotencyIntegrationTest {

    @SuppressWarnings("resource")
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("deploy_orchestrator_test")
            .withUsername("test")
            .withPassword("test")
            .withInitScript("init-test-schema.sql");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    IdempotencyAdapter idempotency;

    @BeforeEach
    void setUp() {
        // Limpeza de dados de teste
        jdbcTemplate.execute("DELETE FROM processed_events");
        jdbcTemplate.execute("DELETE FROM changes");
    }

    @Test
    @DisplayName("Deve marcar evento como processado na primeira tentativa")
    void shouldMarkEventAsProcessed_onFirstAttempt() {
        // Given: um novo eventId
        UUID eventId = UUID.randomUUID();
        String consumerName = "deploy-orchestrator";

        // When: tenta marcar como processado pela primeira vez
        boolean result = idempotency.tryMarkAsProcessed(eventId, consumerName);

        // Then: retorna true (evento processado)
        assertThat(result).isTrue();

        // And: evento registrado na tabela
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM processed_events WHERE event_id = ? AND service_name = ?",
                Integer.class,
                eventId,
                consumerName
        );
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("Deve descartar evento duplicado sem processar novamente")
    void shouldDiscardDuplicateEventWithoutReprocessing() {
        // Given: evento já processado
        UUID eventId = UUID.randomUUID();
        String consumerName = "deploy-orchestrator";
        idempotency.markAsProcessed(eventId, consumerName);

        // When: tenta processar novamente
        boolean result = idempotency.tryMarkAsProcessed(eventId, consumerName);

        // Then: rejeita duplicata
        assertThat(result).isFalse();

        // And: estado do banco inalterado (apenas 1 registro)
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM processed_events WHERE event_id = ?",
                Integer.class,
                eventId
        );
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("Deve permitir eventos diferentes com o mesmo consumer")
    void shouldAllowDifferentEventsForSameConsumer() {
        // Given: dois eventIds distintos
        UUID eventId1 = UUID.randomUUID();
        UUID eventId2 = UUID.randomUUID();
        String consumerName = "deploy-orchestrator";

        // When: marca ambos como processados
        boolean result1 = idempotency.tryMarkAsProcessed(eventId1, consumerName);
        boolean result2 = idempotency.tryMarkAsProcessed(eventId2, consumerName);

        // Then: ambos são aceitos
        assertThat(result1).isTrue();
        assertThat(result2).isTrue();

        // And: dois registros distintos na tabela
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM processed_events WHERE service_name = ?",
                Integer.class,
                consumerName
        );
        assertThat(count).isEqualTo(2);
    }

    @Test
    @DisplayName("Deve rejeitar mesmo eventId de consumer diferente (PK em event_id)")
    void shouldRejectSameEventIdFromDifferentConsumer() {
        // Given: mesmo eventId, consumers diferentes
        UUID eventId = UUID.randomUUID();
        String consumer1 = "deploy-orchestrator";
        String consumer2 = "audit-logger";

        // When: primeiro consumer processa
        boolean result1 = idempotency.tryMarkAsProcessed(eventId, consumer1);
        // When: segundo consumer tenta processar o mesmo eventId
        boolean result2 = idempotency.tryMarkAsProcessed(eventId, consumer2);

        // Then: primeiro aceito, segundo rejeitado (PK é apenas event_id)
        assertThat(result1).isTrue();
        assertThat(result2).isFalse();

        // And: apenas 1 registro no banco
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM processed_events WHERE event_id = ?",
                Integer.class,
                eventId
        );
        assertThat(count).isEqualTo(1);
    }
}