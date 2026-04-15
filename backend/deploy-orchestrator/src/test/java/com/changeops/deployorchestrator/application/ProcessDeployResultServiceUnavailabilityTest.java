package com.changeops.deployorchestrator.application;

import com.changeops.deployorchestrator.application.port.in.ProcessDeployResultUseCase;
import com.changeops.deployorchestrator.application.port.out.IdempotencyPort;
import com.changeops.deployorchestrator.application.port.out.PublishResultEventPort;
import com.changeops.deployorchestrator.application.port.out.SaveChangeEventPort;
import com.changeops.deployorchestrator.application.port.out.UpdateChangeStatusPort;
import com.changeops.deployorchestrator.application.service.PostDeployChecklistService;
import com.changeops.deployorchestrator.application.service.ProcessDeployResultService;
import com.changeops.deployorchestrator.domain.event.DeployFinishedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests simulating service unavailability scenarios for
 * {@code ProcessDeployResultService} (Flow 2).
 *
 * <p>Each test targets a different failure point in the orchestration pipeline:
 * exists-check → idempotency → status update → timeline save (non-fatal)
 * → publish → counters.
 *
 * <p>When a {@code RuntimeException} propagates out of {@code execute()}, it is
 * re-thrown by the outer catch block, which allows {@code @RetryableTopic} to
 * schedule retries and ultimately route to the DLT when exhausted.
 */
@ExtendWith(MockitoExtension.class)
class ProcessDeployResultServiceUnavailabilityTest {

    private SimpleMeterRegistry meterRegistry;

    @Mock
    IdempotencyPort idempotencyPort;

    @Mock
    UpdateChangeStatusPort updateChangeStatusPort;

    @Mock
    PublishResultEventPort publishResultEventPort;

    @Mock
    SaveChangeEventPort saveChangeEventPort;

    ProcessDeployResultUseCase service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new ProcessDeployResultService(
                idempotencyPort,
                new PostDeployChecklistService(),
                updateChangeStatusPort,
                publishResultEventPort,
                saveChangeEventPort,
                meterRegistry,
                new ObjectMapper());
    }

    // ─── DB unavailable at pre-condition check (existsByChangeId) ────────────

    @Test
    void shouldPropagateException_andNotCallIdempotency_whenExistsCheckFails() {
        when(updateChangeStatusPort.existsByChangeId(any()))
                .thenThrow(new RuntimeException("DB connection unavailable"));

        assertThatThrownBy(() -> service.execute(buildEvent("SUCCESS")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("DB connection unavailable");

        verifyNoInteractions(idempotencyPort);
        verify(updateChangeStatusPort, never()).markCompleted(any());
        verify(updateChangeStatusPort, never()).markFailed(any());
        verifyNoInteractions(publishResultEventPort);
    }

    @Test
    void shouldNotIncrementAnyCounter_whenExistsCheckFails() {
        when(updateChangeStatusPort.existsByChangeId(any()))
                .thenThrow(new RuntimeException("DB connection unavailable"));

        assertThatThrownBy(() -> service.execute(buildEvent("SUCCESS")));

        assertThat(meterRegistry.counter("events_consumed_total", "type", "DeployFinishedEvent").count())
                .isEqualTo(0.0);
        assertThat(meterRegistry.counter("changes_completed_total").count()).isEqualTo(0.0);
        assertThat(meterRegistry.counter("events_discarded_total", "reason", "duplicate").count())
                .isEqualTo(0.0);
    }

    // ─── DB unavailable at idempotency check ─────────────────────────────────

    @Test
    void shouldPropagateException_whenDatabaseThrows_onIdempotencyCheck() {
        when(updateChangeStatusPort.existsByChangeId(any())).thenReturn(true);
        when(idempotencyPort.tryMarkAsProcessed(any(), anyString()))
                .thenThrow(new RuntimeException("DB connection unavailable during idempotency write"));

        assertThatThrownBy(() -> service.execute(buildEvent("SUCCESS")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("idempotency");

        verify(updateChangeStatusPort, never()).markCompleted(any());
        verify(updateChangeStatusPort, never()).markFailed(any());
        verifyNoInteractions(publishResultEventPort);
    }

    // ─── DB unavailable at status update ─────────────────────────────────────

    @Test
    void shouldPropagateException_whenDatabaseThrows_onMarkCompleted() {
        when(updateChangeStatusPort.existsByChangeId(any())).thenReturn(true);
        when(idempotencyPort.tryMarkAsProcessed(any(), anyString())).thenReturn(true);
        doThrow(new RuntimeException("DB connection unavailable on update"))
                .when(updateChangeStatusPort).markCompleted(any());

        assertThatThrownBy(() -> service.execute(buildEvent("SUCCESS")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("DB connection unavailable on update");

        verifyNoInteractions(publishResultEventPort);
    }

    @Test
    void shouldPropagateException_whenDatabaseThrows_onMarkFailed() {
        when(updateChangeStatusPort.existsByChangeId(any())).thenReturn(true);
        when(idempotencyPort.tryMarkAsProcessed(any(), anyString())).thenReturn(true);
        doThrow(new RuntimeException("DB connection unavailable on update"))
                .when(updateChangeStatusPort).markFailed(any());

        assertThatThrownBy(() -> service.execute(buildEvent("FAILURE")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("DB connection unavailable on update");

        verifyNoInteractions(publishResultEventPort);
    }

    @Test
    void shouldNotIncrementCompletedCounter_whenMarkCompletedFails() {
        when(updateChangeStatusPort.existsByChangeId(any())).thenReturn(true);
        when(idempotencyPort.tryMarkAsProcessed(any(), anyString())).thenReturn(true);
        doThrow(new RuntimeException("DB write failure"))
                .when(updateChangeStatusPort).markCompleted(any());

        assertThatThrownBy(() -> service.execute(buildEvent("SUCCESS")));

        assertThat(meterRegistry.counter("changes_completed_total").count()).isEqualTo(0.0);
        assertThat(meterRegistry.counter("events_consumed_total", "type", "DeployFinishedEvent").count())
                .isEqualTo(0.0);
    }

    @Test
    void shouldNotPublishEvent_whenStatusUpdateFails() {
        when(updateChangeStatusPort.existsByChangeId(any())).thenReturn(true);
        when(idempotencyPort.tryMarkAsProcessed(any(), anyString())).thenReturn(true);
        doThrow(new RuntimeException("DB write failure"))
                .when(updateChangeStatusPort).markCompleted(any());

        assertThatThrownBy(() -> service.execute(buildEvent("SUCCESS")));

        verifyNoInteractions(publishResultEventPort);
    }

    // ─── Publish failure (documents current behavior: exception propagates) ──

    @Test
    void shouldNotIncrementCounters_whenPublishFails() {
        // KafkaResultPublisherAdapter swallows publish exceptions internally (DLQ fallback).
        // However, if the adapter is replaced or re-throws in future, this test documents
        // that counters at step 7 (after publish) will not be incremented.
        // The outer catch block in ProcessDeployResultService re-throws any exception.
        when(updateChangeStatusPort.existsByChangeId(any())).thenReturn(true);
        when(idempotencyPort.tryMarkAsProcessed(any(), anyString())).thenReturn(true);
        doThrow(new RuntimeException("Kafka publish failure"))
                .when(publishResultEventPort).publish(any());

        try {
            service.execute(buildEvent("SUCCESS"));
        } catch (RuntimeException ex) {
            assertThat(ex.getMessage()).contains("Kafka publish failure");
        }

        // Counters at step 7 are never reached because publish throws
        assertThat(meterRegistry.counter("changes_completed_total").count()).isEqualTo(0.0);
        assertThat(meterRegistry.counter("events_consumed_total", "type", "DeployFinishedEvent").count())
                .isEqualTo(0.0);
    }

    @Test
    void shouldHaveInvokedMarkCompleted_beforePublishFails() {
        // Documents that the status update commits (port is called) before the publish attempt.
        // In production with @Transactional, a publish failure will roll back the status update.
        // Transactional Outbox Pattern (Phase 2) will decouple DB commit from Kafka publish.
        when(updateChangeStatusPort.existsByChangeId(any())).thenReturn(true);
        when(idempotencyPort.tryMarkAsProcessed(any(), anyString())).thenReturn(true);

        DeployFinishedEvent event = buildEvent("SUCCESS");
        doThrow(new RuntimeException("Kafka publish failure"))
                .when(publishResultEventPort).publish(any());

        try {
            service.execute(event);
        } catch (RuntimeException ignored) {
            // exception accepted — the focus is on port interaction order
        }

        verify(updateChangeStatusPort).markCompleted(event.payload().changeId());
    }

    // ─── Helper ───────────────────────────────────────────────────────────────

    private DeployFinishedEvent buildEvent(String result) {
        UUID correlationId = UUID.randomUUID();
        return new DeployFinishedEvent(
                "DeployFinishedEvent", "1.0",
                correlationId, Instant.now(),
                new DeployFinishedEvent.Payload(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        result,
                        Instant.now()));
    }
}
