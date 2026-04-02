package com.changeops.deployorchestrator.application;

import com.changeops.deployorchestrator.application.port.in.ProcessDeployResultUseCase;
import com.changeops.deployorchestrator.application.port.out.IdempotencyPort;
import com.changeops.deployorchestrator.application.port.out.UpdateChangeStatusPort;
import com.changeops.deployorchestrator.application.service.ProcessDeployResultService;
import com.changeops.deployorchestrator.application.service.PostDeployChecklistService;
import com.changeops.deployorchestrator.application.port.out.PublishResultEventPort;
import com.changeops.deployorchestrator.domain.event.DeployFinishedEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import com.changeops.deployorchestrator.domain.exception.ChangeNotFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.changeops.deployorchestrator.application.port.out.SaveChangeEventPort;

@ExtendWith(MockitoExtension.class)
class ProcessDeployResultServiceTest {

    private SimpleMeterRegistry meterRegistry;

    @Mock IdempotencyPort idempotencyPort;
    @Mock UpdateChangeStatusPort updateChangeStatusPort;
    @Mock PublishResultEventPort publishResultEventPort;
    @Mock SaveChangeEventPort saveChangeEventPort; // ← adicionar


    ProcessDeployResultUseCase service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new ProcessDeployResultService(
                idempotencyPort,
                new PostDeployChecklistService(),
                updateChangeStatusPort,
                publishResultEventPort,
                saveChangeEventPort,          // ← adicionar
                meterRegistry);
        when(updateChangeStatusPort.existsByChangeId(any())).thenReturn(true);
    }

    @Test
    void shouldMarkCompleted_andPublishEvent_whenDeploySucceeds() {
        DeployFinishedEvent event = buildEvent("SUCCESS");
        when(idempotencyPort.tryMarkAsProcessed(eq(event.payload().deployId()), anyString())).thenReturn(true);

        service.execute(event);

        verify(updateChangeStatusPort).markCompleted(event.payload().changeId());
        verify(publishResultEventPort).publish(argThat(r -> r.isSuccess()));
    }

    @Test
    void shouldMarkFailed_andPublishEvent_whenDeployFails() {
        DeployFinishedEvent event = buildEvent("FAILURE");
        when(idempotencyPort.tryMarkAsProcessed(eq(event.payload().deployId()), anyString())).thenReturn(true);

        service.execute(event);

        verify(updateChangeStatusPort).markFailed(event.payload().changeId());
        verify(publishResultEventPort).publish(argThat(r -> !r.isSuccess() && r.getFailureReason() != null));
    }

    @Test
    void shouldIncludeFailureReason_inPublishedEvent_whenChecklistFails() {
        // Verifies that failureReason is populated even when ChangeResult is already FAILURE
        // (regression guard for withChecklistFailure removing the SUCCESS guard)
        DeployFinishedEvent event = buildEvent("FAILURE");
        when(idempotencyPort.tryMarkAsProcessed(eq(event.payload().deployId()), anyString())).thenReturn(true);

        service.execute(event);

        verify(publishResultEventPort).publish(argThat(r ->
                !r.isSuccess() && r.getFailureReason() != null && !r.getFailureReason().isBlank()));
    }

    @Test
    void shouldDiscardEvent_whenDeployIdAlreadyProcessed() {
        DeployFinishedEvent event = buildEvent("SUCCESS");
        when(idempotencyPort.tryMarkAsProcessed(eq(event.payload().deployId()), anyString())).thenReturn(false);

        service.execute(event);

        verify(updateChangeStatusPort, never()).markCompleted(any());
        verify(updateChangeStatusPort, never()).markFailed(any());
        verifyNoInteractions(publishResultEventPort);
    }

    @Test
    void shouldNotMarkCompleted_whenSameEventDeliveredTwice() {
        DeployFinishedEvent event = buildEvent("SUCCESS");

        // First delivery — not yet processed
        when(idempotencyPort.tryMarkAsProcessed(eq(event.payload().deployId()), anyString()))
                .thenReturn(true)   // first call
                .thenReturn(false); // second call

        service.execute(event);
        service.execute(event);

        // markCompleted should only be called once
        verify(updateChangeStatusPort, times(1)).markCompleted(event.payload().changeId());
    }

    @Test
    void shouldSaveChangeEvent_whenDeploySucceeds() {
        DeployFinishedEvent event = buildEvent("SUCCESS");
        when(idempotencyPort.tryMarkAsProcessed(eq(event.payload().deployId()), anyString())).thenReturn(true);

        service.execute(event);

        verify(saveChangeEventPort).save(
                eq(event.payload().changeId()),
                eq("ChangeCompletedEvent"),
                anyString(),
                any(Instant.class));
    }

    @Test
    void shouldIncrementChangesCompletedCounter_whenDeploySucceeds() {
        DeployFinishedEvent event = buildEvent("SUCCESS");
        when(idempotencyPort.tryMarkAsProcessed(eq(event.payload().deployId()), anyString())).thenReturn(true);

        service.execute(event);

        assertThat(meterRegistry.counter("changes_completed_total").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("changes_failed_total").count()).isEqualTo(0.0);
    }

    @Test
    void shouldIncrementChangesFailedCounter_whenDeployFails() {
        DeployFinishedEvent event = buildEvent("FAILURE");
        when(idempotencyPort.tryMarkAsProcessed(eq(event.payload().deployId()), anyString())).thenReturn(true);

        service.execute(event);

        assertThat(meterRegistry.counter("changes_failed_total").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("changes_completed_total").count()).isEqualTo(0.0);
    }

    @Test
    void shouldThrowRetryableException_whenChangeIdNotFound() {
        DeployFinishedEvent event = buildEvent("SUCCESS");
        when(updateChangeStatusPort.existsByChangeId(event.payload().changeId())).thenReturn(false);

        assertThatThrownBy(() -> service.execute(event))
                .isInstanceOf(ChangeNotFoundException.class)
                .hasMessageContaining(event.payload().changeId().toString());

        verifyNoInteractions(idempotencyPort);
        verify(updateChangeStatusPort, never()).markCompleted(any());
        verify(updateChangeStatusPort, never()).markFailed(any());
        verifyNoInteractions(publishResultEventPort);
    }

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
