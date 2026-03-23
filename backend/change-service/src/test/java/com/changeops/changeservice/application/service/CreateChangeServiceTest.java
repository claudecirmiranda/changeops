package com.changeops.changeservice.application.service;

import com.changeops.changeservice.application.port.in.CreateChangeUseCase;
import com.changeops.changeservice.application.port.out.PublishEventPort;
import com.changeops.changeservice.application.port.out.SaveChangeEventPort;
import com.changeops.changeservice.application.port.out.SaveChangePort;
import com.changeops.changeservice.domain.event.ChangePreparedEvent;
import com.changeops.changeservice.domain.model.Change;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CreateChangeServiceTest {

    @Mock
    SaveChangePort saveChangePort;

    @Mock
    PublishEventPort publishEventPort;

    @Mock
    SaveChangeEventPort saveChangeEventPort;

    MeterRegistry meterRegistry;

    CreateChangeService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new CreateChangeService(
                saveChangePort, publishEventPort, saveChangeEventPort,
                new ObjectMapper(), meterRegistry);
    }

    @Test
    void shouldSaveChange_andReturnResult() {
        when(saveChangePort.save(any(Change.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateChangeUseCase.Command command = new CreateChangeUseCase.Command(
                "Deploy payment-service v3.0", "Major version upgrade",
                "payment-service", "user-001",
                Instant.now().plus(2, ChronoUnit.DAYS));

        CreateChangeUseCase.Result result = service.execute(command);

        assertThat(result.changeId()).isNotNull();
        assertThat(result.status()).isEqualTo("PREPARED");
        assertThat(result.correlationId()).isNotNull();
        assertThat(result.createdAt()).isNotNull();

        verify(saveChangePort).save(any(Change.class));
    }

    @Test
    void shouldPublishChangePreparedEvent() {
        when(saveChangePort.save(any(Change.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateChangeUseCase.Command command = new CreateChangeUseCase.Command(
                "Deploy v2", "desc", "svc-a", "user-1",
                Instant.now().plus(1, ChronoUnit.DAYS));

        service.execute(command);

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(publishEventPort).publish(eventCaptor.capture());

        Object published = eventCaptor.getValue();
        assertThat(published).isInstanceOf(ChangePreparedEvent.class);
        ChangePreparedEvent event = (ChangePreparedEvent) published;
        assertThat(event.componentId()).isEqualTo("svc-a");
        assertThat(event.requestedBy()).isEqualTo("user-1");
    }

    @Test
    void shouldPersistEventToTimeline() {
        when(saveChangePort.save(any(Change.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateChangeUseCase.Command command = new CreateChangeUseCase.Command(
                "Deploy v3", "desc", "order-service", "user-2",
                Instant.now().plus(1, ChronoUnit.DAYS));

        service.execute(command);

        verify(saveChangeEventPort).save(
                any(UUID.class),
                eq("ChangePreparedEvent"),
                anyString(),
                any(Instant.class));
    }

    @Test
    void shouldIncrementCounter() {
        when(saveChangePort.save(any(Change.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateChangeUseCase.Command command = new CreateChangeUseCase.Command(
                "Deploy v4", null, "billing-service", "user-3",
                Instant.now().plus(1, ChronoUnit.DAYS));

        service.execute(command);

        Counter counter = meterRegistry.find("changes_created_total").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void shouldNotFailWhenTimelinePersistenceFails() {
        when(saveChangePort.save(any(Change.class))).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new RuntimeException("DB error"))
                .when(saveChangeEventPort).save(any(), anyString(), anyString(), any());

        CreateChangeUseCase.Command command = new CreateChangeUseCase.Command(
                "Deploy v5", null, "auth-service", "user-4",
                Instant.now().plus(1, ChronoUnit.DAYS));

        // Should not throw — timeline persistence failure is non-fatal
        CreateChangeUseCase.Result result = service.execute(command);
        assertThat(result.changeId()).isNotNull();

        verify(publishEventPort).publish(any());
    }
}
