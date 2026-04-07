package com.changeops.changeservice.application.service;

import com.changeops.changeservice.application.port.in.CreateChangeUseCase;
import com.changeops.changeservice.application.port.out.PublishEventPort;
import com.changeops.changeservice.application.port.out.SaveChangeEventPort;
import com.changeops.changeservice.application.port.out.SaveChangePort;
import com.changeops.changeservice.domain.model.Change;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests simulating service unavailability scenarios during change creation.
 *
 * <p>Flow under test:
 * {@code Change.create() → saveChangePort.save() → publishEventPort.publish()
 * → saveChangeEventPort.save() [non-fatal] → counter.increment()}
 *
 * <p>Because {@code CreateChangeService.execute()} is {@code @Transactional},
 * a {@code RuntimeException} thrown from either the database save or the Kafka
 * publish will cause Spring to roll back the DB write in production. In this
 * unit test there is no Spring context, so interactions are validated
 * independently. Refer to the Transactional Outbox roadmap item (Phase 2) for
 * the long-term fix to the DB-commit / Kafka-publish coupling.
 */
@SuppressWarnings("null")
@ExtendWith(MockitoExtension.class)
class CreateChangeServiceUnavailabilityTest {

    @Mock
    SaveChangePort saveChangePort;

    @Mock
    PublishEventPort publishEventPort;

    @Mock
    SaveChangeEventPort saveChangeEventPort;

    SimpleMeterRegistry meterRegistry;
    CreateChangeService service;

    private static final UUID CORRELATION_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new CreateChangeService(
                saveChangePort, publishEventPort, saveChangeEventPort,
                new ObjectMapper().registerModule(new JavaTimeModule()), meterRegistry);
    }

    // ─── Database unavailability ──────────────────────────────────────────────

    @Test
    void shouldPropagateException_whenDatabaseSaveFails() {
        when(saveChangePort.save(any(Change.class)))
                .thenThrow(new RuntimeException("DB connection lost"));

        assertThatThrownBy(() -> service.execute(buildCommand()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("DB connection lost");
    }

    @Test
    void shouldNotIncrementCounter_whenDatabaseSaveFails() {
        when(saveChangePort.save(any(Change.class)))
                .thenThrow(new RuntimeException("DB connection lost"));

        assertThatThrownBy(() -> service.execute(buildCommand()));

        Counter counter = meterRegistry.find("changes_created_total").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(0.0);
    }

    @Test
    void shouldNotPublishOrPersistTimeline_whenDatabaseSaveFails() {
        when(saveChangePort.save(any(Change.class)))
                .thenThrow(new RuntimeException("DB connection lost"));

        assertThatThrownBy(() -> service.execute(buildCommand()));

        verifyNoInteractions(publishEventPort);
        verifyNoInteractions(saveChangeEventPort);
    }

    @Test
    void shouldInvokeSave_beforeKafkaPublishAttempt_whenKafkaFails() {
        // Documents execution order: DB save is called before Kafka publish.
        // In production, @Transactional rolls back the DB write when Kafka throws,
        // since both operations share the same transaction boundary.
        // The Transactional Outbox Pattern (Phase 2) will decouple them.
        when(saveChangePort.save(any(Change.class))).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new RuntimeException("Kafka broker unreachable"))
                .when(publishEventPort).publish(any());

        assertThatThrownBy(() -> service.execute(buildCommand()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Kafka broker unreachable");

        verify(saveChangePort).save(any(Change.class));
        verifyNoMoreInteractions(saveChangeEventPort);
    }

    // ─── Kafka unavailability ─────────────────────────────────────────────────

    @Test
    void shouldPropagateException_whenKafkaPublishFails() {
        when(saveChangePort.save(any(Change.class))).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new RuntimeException("Failed to publish event to Kafka"))
                .when(publishEventPort).publish(any());

        assertThatThrownBy(() -> service.execute(buildCommand()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Kafka");
    }

    @Test
    void shouldNotIncrementCounter_whenKafkaPublishFails() {
        when(saveChangePort.save(any(Change.class))).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new RuntimeException("Failed to publish event to Kafka"))
                .when(publishEventPort).publish(any());

        assertThatThrownBy(() -> service.execute(buildCommand()));

        Counter counter = meterRegistry.find("changes_created_total").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(0.0);
    }

    // ─── Timeline persistence failure (non-fatal by design) ──────────────────

    @Test
    void shouldIncrementCounter_whenTimelineFails_butSaveAndPublishSucceed() {
        // Save and Kafka succeed; timeline persistence fails.
        // The failure is swallowed (non-fatal), so the counter is incremented normally.
        when(saveChangePort.save(any(Change.class))).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new RuntimeException("Timeline DB write failed"))
                .when(saveChangeEventPort).save(any(), anyString(), anyString(), any());

        assertThatCode(() -> service.execute(buildCommand())).doesNotThrowAnyException();

        Counter counter = meterRegistry.find("changes_created_total").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);

        Counter failuresCounter = meterRegistry.find("timeline_persistence_failures_total").counter();
        assertThat(failuresCounter).isNotNull();
        assertThat(failuresCounter.count()).isEqualTo(1.0);
    }

    @Test
    void shouldStillPublishKafkaEvent_whenTimelinePersistenceFails() {
        // Timeline failure must NOT block the Kafka publish. The publish() call
        // happens before persistEventToTimeline() inside the forEach lambda:
        //   publishEventPort.publish(event);          <- first
        //   persistEventToTimeline(saved, event);     <- second (non-fatal catch)
        when(saveChangePort.save(any(Change.class))).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new RuntimeException("Timeline DB write failed"))
                .when(saveChangeEventPort).save(any(), anyString(), anyString(), any());

        service.execute(buildCommand());

        verify(publishEventPort).publish(any());
    }

    // ─── Helper ───────────────────────────────────────────────────────────────

    private CreateChangeUseCase.Command buildCommand() {
        return new CreateChangeUseCase.Command(
                "Deploy unavailability-test-svc", "Unavailability test description",
                "unavailability-svc", "user-test-01",
                Instant.now().plus(1, ChronoUnit.DAYS),
                CORRELATION_ID);
    }
}
