package com.changeops.changeservice.domain;

import com.changeops.changeservice.domain.event.ChangePreparedEvent;
import com.changeops.changeservice.domain.event.DomainEvent;
import com.changeops.changeservice.domain.exception.InvalidChangeStateException;
import com.changeops.changeservice.domain.model.Change;
import com.changeops.changeservice.domain.valueobject.ChangeStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class ChangeTest {

    @Test
    void create_shouldSetPreparedStatus_andGenerateCorrelationId() {
        Change change = createValidChange();

        assertThat(change.getStatus()).isEqualTo(ChangeStatus.PREPARED);
        assertThat(change.getChangeId()).isNotNull();
        assertThat(change.getCorrelationId()).isNotNull();
        assertThat(change.getCreatedAt()).isNotNull();
    }

    @Test
    void create_shouldRaiseDomainEvent() {
        Change change = createValidChange();
        List<DomainEvent> events = change.pullDomainEvents();

        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(ChangePreparedEvent.class);

        ChangePreparedEvent event = (ChangePreparedEvent) events.get(0);
        assertThat(event.changeId()).isEqualTo(change.getChangeId());
        assertThat(event.correlationId()).isEqualTo(change.getCorrelationId());
        assertThat(event.componentId()).isEqualTo("payment-service");
    }

    @Test
    void pullDomainEvents_shouldClearEventsAfterPull() {
        Change change = createValidChange();
        change.pullDomainEvents();

        assertThat(change.pullDomainEvents()).isEmpty();
    }

    @Test
    void complete_shouldTransitionToCompleted_whenPrepared() {
        Change change = createValidChange();
        change.pullDomainEvents();
        change.complete();

        assertThat(change.getStatus()).isEqualTo(ChangeStatus.COMPLETED);
        assertThat(change.getUpdatedAt()).isAfterOrEqualTo(change.getCreatedAt());
    }

    @Test
    void fail_shouldTransitionToFailed_whenPrepared() {
        Change change = createValidChange();
        change.pullDomainEvents();
        change.fail();

        assertThat(change.getStatus()).isEqualTo(ChangeStatus.FAILED);
    }

    @Test
    void complete_shouldThrow_whenNotPrepared() {
        Change change = createValidChange();
        change.pullDomainEvents();
        change.complete();

        assertThatThrownBy(change::complete)
                .isInstanceOf(InvalidChangeStateException.class)
                .hasMessageContaining("COMPLETED");
    }

    @Test
    void fail_shouldThrow_whenAlreadyFailed() {
        Change change = createValidChange();
        change.pullDomainEvents();
        change.fail();

        assertThatThrownBy(change::fail)
                .isInstanceOf(InvalidChangeStateException.class);
    }

    private Change createValidChange() {
        return Change.create(
                "Deploy payment-service",
                "Version upgrade",
                "payment-service",
                "user-001",
                Instant.now().plus(2, ChronoUnit.DAYS));
    }
}
