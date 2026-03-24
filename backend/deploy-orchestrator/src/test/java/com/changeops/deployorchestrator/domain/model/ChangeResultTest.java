package com.changeops.deployorchestrator.domain.model;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ChangeResultTest {

    @Test
    void shouldBeSuccess_whenCreatedWithTrueFlag() {
        var result = ChangeResult.from(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), true);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResult()).isEqualTo(ChangeResult.DeployResult.SUCCESS);
        assertThat(result.getFailureReason()).isNull();
        assertThat(result.getFinishedAt()).isNull();
    }

    @Test
    void shouldBeFailure_whenCreatedWithFalseFlag() {
        var result = ChangeResult.from(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), false);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getResult()).isEqualTo(ChangeResult.DeployResult.FAILURE);
    }

    @Test
    void shouldSetFailureReasonAndResult_onWithChecklistFailure() {
        var result = ChangeResult.from(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), true);

        result.withChecklistFailure("smoke-test failed");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getResult()).isEqualTo(ChangeResult.DeployResult.FAILURE);
        assertThat(result.getFailureReason()).isEqualTo("smoke-test failed");
    }

    @Test
    void shouldSetFailureReason_evenWhenAlreadyFailed() {
        var result = ChangeResult.from(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), false);

        result.withChecklistFailure("error-rate exceeded");

        assertThat(result.getFailureReason()).isEqualTo("error-rate exceeded");
    }

    @Test
    void shouldSetFinishedAt_onMarkFinished() {
        var result = ChangeResult.from(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), true);
        assertThat(result.getFinishedAt()).isNull();

        result.markFinished();

        assertThat(result.getFinishedAt()).isNotNull();
    }
}
