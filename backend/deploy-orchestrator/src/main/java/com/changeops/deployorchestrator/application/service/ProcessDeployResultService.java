package com.changeops.deployorchestrator.application.service;

import com.changeops.deployorchestrator.application.port.in.ProcessDeployResultUseCase;
import com.changeops.deployorchestrator.application.port.out.IdempotencyPort;
import com.changeops.deployorchestrator.application.port.out.PublishResultEventPort;
import com.changeops.deployorchestrator.application.port.out.SaveChangeEventPort;
import com.changeops.deployorchestrator.application.port.out.UpdateChangeStatusPort;
import com.changeops.deployorchestrator.domain.event.DeployFinishedEvent;
import com.changeops.deployorchestrator.domain.model.ChangeResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;

import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class ProcessDeployResultService implements ProcessDeployResultUseCase {

    private final IdempotencyPort idempotencyPort;
    private final PostDeployChecklistService checklistService;
    private final UpdateChangeStatusPort updateChangeStatusPort;
    private final PublishResultEventPort publishResultEventPort;
    private final SaveChangeEventPort saveChangeEventPort; // ← aqui, junto aos outros campos
    private final Counter eventsConsumedCounter;
    private final Counter eventsFailedCounter;
    private final Counter eventsDiscardedCounter;
    private final Counter changesCompletedCounter;
    private final Counter changesFailedCounter;
    private final Timer orchestrationTimer;

    public ProcessDeployResultService(
            IdempotencyPort idempotencyPort,
            PostDeployChecklistService checklistService,
            UpdateChangeStatusPort updateChangeStatusPort,
            PublishResultEventPort publishResultEventPort,
            SaveChangeEventPort saveChangeEventPort, // ← adicionar no construtor
            MeterRegistry meterRegistry) {
        this.idempotencyPort = idempotencyPort;
        this.checklistService = checklistService;
        this.updateChangeStatusPort = updateChangeStatusPort;
        this.publishResultEventPort = publishResultEventPort;
        this.saveChangeEventPort = saveChangeEventPort; // ← atribuir
        this.eventsConsumedCounter = Counter.builder("events_consumed_total")
                .tag("type", "DeployFinishedEvent")
                .description("Total DeployFinishedEvents consumed")
                .register(meterRegistry);
        this.eventsFailedCounter = Counter.builder("events_failed_total")
                .tag("consumer", "deploy-orchestrator")
                .description("Total events that failed processing")
                .register(meterRegistry);
        this.eventsDiscardedCounter = Counter.builder("events_discarded_total")
                .tag("reason", "duplicate")
                .description("Total events discarded (e.g. duplicates)")
                .register(meterRegistry);
        this.changesCompletedCounter = Counter.builder("changes_completed_total")
                .description("Total changes transitioned to COMPLETED")
                .register(meterRegistry);
        this.changesFailedCounter = Counter.builder("changes_failed_total")
                .description("Total changes transitioned to FAILED")
                .register(meterRegistry);
        this.orchestrationTimer = Timer.builder("orchestration_duration_seconds")
                .description("Time to process a DeployFinishedEvent end-to-end")
                .publishPercentileHistogram()
                .register(meterRegistry);
    }

    @Override
    @Transactional
    public void execute(DeployFinishedEvent event) {
        var payload = event.payload();

        MDC.put("correlation_id", event.correlationId() != null
                ? event.correlationId().toString() : "unknown");
        MDC.put("change_id", payload.changeId().toString());
        MDC.put("deploy_id", payload.deployId().toString());

        Timer.Sample timerSample = Timer.start();
        try {
            log.info("DeployFinishedEvent received: deployId={}, changeId={}, result={}",
                    payload.deployId(), payload.changeId(), payload.result());

            // ── Step 1: Atomic idempotency check + mark ──────────────
            if (!idempotencyPort.tryMarkAsProcessed(payload.deployId(), "deploy-orchestrator")) {
                eventsDiscardedCounter.increment();
                log.warn("Event already processed, discarding: deployId={}", payload.deployId());
                return;
            }

            // ── Step 2: Post-deploy checklist ──────────────────────────
            PostDeployChecklistService.ChecklistResult checklist =
                    checklistService.execute(
                            payload.changeId(),
                            payload.deployId(),
                            event.isSuccess());

            // ── Step 3: Build result ────────────────────────────────────
            ChangeResult changeResult = ChangeResult.from(
                    payload.changeId(),
                    payload.deployId(),
                    event.correlationId(),
                    event.isSuccess() && checklist.allPassed());

            if (!checklist.allPassed() && checklist.failureReason() != null) {
                changeResult.withChecklistFailure(checklist.failureReason());
            }

            // ── Step 4: Update change status + save event ──────────────
            if (changeResult.isSuccess()) {
                updateChangeStatusPort.markCompleted(payload.changeId());
                changesCompletedCounter.increment();
                log.info("Change status updated to COMPLETED: changeId={}", payload.changeId());
                saveChangeEventPort.save(
                        payload.changeId(),
                        "ChangeCompletedEvent",
                        String.format("{\"changeId\":\"%s\",\"deployId\":\"%s\"}",
                                payload.changeId(), payload.deployId()),
                        Instant.now());
            } else {
                updateChangeStatusPort.markFailed(payload.changeId());
                changesFailedCounter.increment();
                log.info("Change status updated to FAILED: changeId={}, reason={}",
                        payload.changeId(), changeResult.getFailureReason());
                saveChangeEventPort.save(
                        payload.changeId(),
                        "ChangeFailedEvent",
                        String.format("{\"changeId\":\"%s\",\"deployId\":\"%s\",\"reason\":\"%s\"}",
                                payload.changeId(), payload.deployId(),
                                changeResult.getFailureReason()),
                        Instant.now());
            }

            // ── Step 5: Idempotency already marked in Step 1 ─────────

            // ── Step 6: Publish result event ───────────────────────────
            changeResult.markFinished();
            publishResultEventPort.publish(changeResult);

            eventsConsumedCounter.increment();

        } catch (Exception e) {
            eventsFailedCounter.increment();
            log.error("Error processing DeployFinishedEvent: deployId={}, changeId={}",
                    payload.deployId(), payload.changeId(), e);
            throw e;
        } finally {
            timerSample.stop(orchestrationTimer);
            MDC.remove("deploy_id");
            MDC.remove("change_id");
        }
    }
}
