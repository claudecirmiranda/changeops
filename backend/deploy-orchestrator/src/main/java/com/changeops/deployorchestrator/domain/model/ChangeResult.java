package com.changeops.deployorchestrator.domain.model;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ChangeResult {

    private final UUID changeId;
    private final UUID deployId;
    private final UUID correlationId;
    private DeployResult result;
    private String failureReason;
    private final Instant startedAt;
    private Instant finishedAt;

    public enum DeployResult { SUCCESS, FAILURE }

    public static ChangeResult from(UUID changeId, UUID deployId,
                                    UUID correlationId, boolean success) {
        return ChangeResult.builder()
                .changeId(changeId)
                .deployId(deployId)
                .correlationId(correlationId)
                .result(success ? DeployResult.SUCCESS : DeployResult.FAILURE)
                .startedAt(Instant.now())
                .build();
    }

    public void withChecklistFailure(String reason) {
        this.result = DeployResult.FAILURE;
        this.failureReason = reason;
    }

    public void markFinished() {
        this.finishedAt = Instant.now();
    }

    public boolean isSuccess() {
        return result == DeployResult.SUCCESS;
    }
}
