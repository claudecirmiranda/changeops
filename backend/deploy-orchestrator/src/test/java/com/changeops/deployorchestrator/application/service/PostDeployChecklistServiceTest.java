package com.changeops.deployorchestrator.application.service;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PostDeployChecklistServiceTest {

    private final PostDeployChecklistService service = new PostDeployChecklistService();

    @Test
    void shouldPassAllChecks_whenDeploySucceeded() {
        var result = service.execute(UUID.randomUUID(), UUID.randomUUID(), true);

        assertThat(result.allPassed()).isTrue();
        assertThat(result.failureReason()).isNull();
        assertThat(result.items()).allMatch(PostDeployChecklistService.CheckItem::passed);
    }

    @Test
    void shouldFailAllChecks_andSetFailureReason_whenDeployFailed() {
        var result = service.execute(UUID.randomUUID(), UUID.randomUUID(), false);

        assertThat(result.allPassed()).isFalse();
        assertThat(result.failureReason()).isNotNull().isNotBlank();
        assertThat(result.items()).noneMatch(PostDeployChecklistService.CheckItem::passed);
    }

    @Test
    void shouldReturn4CheckItems() {
        var result = service.execute(UUID.randomUUID(), UUID.randomUUID(), true);

        assertThat(result.items()).hasSize(4);
    }

    @Test
    void shouldProvideIndividualFailureMessages_whenChecksFail() {
        var result = service.execute(UUID.randomUUID(), UUID.randomUUID(), false);

        assertThat(result.items())
                .allMatch(item -> item.failureMessage() != null && !item.failureMessage().isBlank());
    }
}
