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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.changeops.deployorchestrator.application.port.out.SaveChangeEventPort;

@ExtendWith(MockitoExtension.class)
class ProcessDeployResultServiceTest {

    @Mock IdempotencyPort idempotencyPort;
    @Mock UpdateChangeStatusPort updateChangeStatusPort;
    @Mock PublishResultEventPort publishResultEventPort;
    @Mock SaveChangeEventPort saveChangeEventPort; // ← adicionar


    ProcessDeployResultUseCase service;

    @BeforeEach
    void setUp() {
        service = new ProcessDeployResultService(
                idempotencyPort,
                new PostDeployChecklistService(),
                updateChangeStatusPort,
                publishResultEventPort,
                saveChangeEventPort,          // ← adicionar
                new SimpleMeterRegistry());
    }

    @Test
    void shouldMarkCompleted_andPublishEvent_whenDeploySucceeds() {
        DeployFinishedEvent event = buildEvent("SUCCESS");
        when(idempotencyPort.isAlreadyProcessed(event.payload().deployId())).thenReturn(false);

        service.execute(event);

        verify(updateChangeStatusPort).markCompleted(event.payload().changeId());
        verify(idempotencyPort).markAsProcessed(eq(event.payload().deployId()), anyString());
        verify(publishResultEventPort).publish(argThat(r -> r.isSuccess()));
    }

    @Test
    void shouldMarkFailed_andPublishEvent_whenDeployFails() {
        DeployFinishedEvent event = buildEvent("FAILURE");
        when(idempotencyPort.isAlreadyProcessed(event.payload().deployId())).thenReturn(false);

        service.execute(event);

        verify(updateChangeStatusPort).markFailed(event.payload().changeId());
        verify(idempotencyPort).markAsProcessed(eq(event.payload().deployId()), anyString());
        verify(publishResultEventPort).publish(argThat(r -> !r.isSuccess()));
    }

    @Test
    void shouldDiscardEvent_whenDeployIdAlreadyProcessed() {
        DeployFinishedEvent event = buildEvent("SUCCESS");
        when(idempotencyPort.isAlreadyProcessed(event.payload().deployId())).thenReturn(true);

        service.execute(event);

        verifyNoInteractions(updateChangeStatusPort);
        verifyNoInteractions(publishResultEventPort);
        verify(idempotencyPort, never()).markAsProcessed(any(), any());
    }

    @Test
    void shouldNotMarkCompleted_whenSameEventDeliveredTwice() {
        DeployFinishedEvent event = buildEvent("SUCCESS");

        // First delivery
        when(idempotencyPort.isAlreadyProcessed(event.payload().deployId())).thenReturn(false);
        service.execute(event);

        // Second delivery — simulate already processed
        when(idempotencyPort.isAlreadyProcessed(event.payload().deployId())).thenReturn(true);
        service.execute(event);

        // markCompleted should only be called once
        verify(updateChangeStatusPort, times(1)).markCompleted(event.payload().changeId());
    }

    @Test
    void shouldSaveChangeEvent_whenDeploySucceeds() {
        DeployFinishedEvent event = buildEvent("SUCCESS");
        when(idempotencyPort.isAlreadyProcessed(event.payload().deployId())).thenReturn(false);

        service.execute(event);

        verify(saveChangeEventPort).save(
                eq(event.payload().changeId()),
                eq("ChangeCompletedEvent"),
                anyString(),
                any(Instant.class));
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
