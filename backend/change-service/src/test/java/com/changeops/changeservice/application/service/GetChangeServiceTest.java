package com.changeops.changeservice.application.service;

import com.changeops.changeservice.application.port.in.GetChangeUseCase;
import com.changeops.changeservice.application.port.out.LoadChangesPort;
import com.changeops.changeservice.domain.exception.ChangeNotFoundException;
import com.changeops.changeservice.domain.model.Change;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetChangeServiceTest {

    @Mock
    LoadChangesPort loadChangesPort;

    GetChangeService service;

    @BeforeEach
    void setUp() {
        service = new GetChangeService(loadChangesPort);
    }

    @Test
    void shouldReturnResult_whenChangeExists() {
        Change change = Change.create(
                "Deploy payment-service",
                "Version upgrade",
                "payment-service",
                "user-001",
                Instant.now().plus(2, ChronoUnit.DAYS));
        change.pullDomainEvents();

        when(loadChangesPort.findById(change.getChangeId()))
                .thenReturn(Optional.of(change));

        GetChangeUseCase.Result result = service.execute(change.getChangeId());

        assertThat(result.changeId()).isEqualTo(change.getChangeId());
        assertThat(result.title()).isEqualTo("Deploy payment-service");
        assertThat(result.description()).isEqualTo("Version upgrade");
        assertThat(result.componentId()).isEqualTo("payment-service");
        assertThat(result.requestedBy()).isEqualTo("user-001");
        assertThat(result.status()).isEqualTo("PREPARED");
        assertThat(result.correlationId()).isEqualTo(change.getCorrelationId());
        assertThat(result.scheduledAt()).isEqualTo(change.getScheduledAt());
        assertThat(result.createdAt()).isEqualTo(change.getCreatedAt());

        verify(loadChangesPort).findById(change.getChangeId());
    }

    @Test
    void shouldThrowChangeNotFound_whenChangeDoesNotExist() {
        UUID changeId = UUID.randomUUID();
        when(loadChangesPort.findById(changeId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.execute(changeId))
                .isInstanceOf(ChangeNotFoundException.class)
                .hasMessageContaining(changeId.toString());
    }
}
