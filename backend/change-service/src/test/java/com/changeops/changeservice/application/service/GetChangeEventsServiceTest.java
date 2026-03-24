package com.changeops.changeservice.application.service;

import com.changeops.changeservice.application.port.in.GetChangeEventsUseCase;
import com.changeops.changeservice.application.port.out.ChangeExistsPort;
import com.changeops.changeservice.application.port.out.LoadChangeEventsPort;
import com.changeops.changeservice.domain.exception.ChangeNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetChangeEventsServiceTest {

    @Mock
    ChangeExistsPort changeExistsPort;

    @Mock
    LoadChangeEventsPort loadChangeEventsPort;

    GetChangeEventsService service;

    @BeforeEach
    void setUp() {
        service = new GetChangeEventsService(changeExistsPort, loadChangeEventsPort);
    }

    @Test
    void shouldReturnEvents_whenChangeExists() {
        UUID changeId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Instant now = Instant.now();

        when(changeExistsPort.existsById(changeId)).thenReturn(true);
        when(loadChangeEventsPort.findByChangeId(changeId)).thenReturn(List.of(
                new LoadChangeEventsPort.ChangeEventResult(
                        eventId, changeId, "ChangePreparedEvent", "{}", now)));

        List<GetChangeEventsUseCase.Result> results = service.execute(changeId);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).eventId()).isEqualTo(eventId);
        assertThat(results.get(0).changeId()).isEqualTo(changeId);
        assertThat(results.get(0).eventType()).isEqualTo("ChangePreparedEvent");
        verify(changeExistsPort).existsById(changeId);
        verify(loadChangeEventsPort).findByChangeId(changeId);
    }

    @Test
    void shouldThrowChangeNotFound_whenChangeDoesNotExist() {
        UUID changeId = UUID.randomUUID();
        when(changeExistsPort.existsById(changeId)).thenReturn(false);

        assertThatThrownBy(() -> service.execute(changeId))
                .isInstanceOf(ChangeNotFoundException.class)
                .hasMessageContaining(changeId.toString());
    }

    @Test
    void shouldReturnEmptyList_whenNoEvents() {
        UUID changeId = UUID.randomUUID();
        when(changeExistsPort.existsById(changeId)).thenReturn(true);
        when(loadChangeEventsPort.findByChangeId(changeId)).thenReturn(Collections.emptyList());

        List<GetChangeEventsUseCase.Result> results = service.execute(changeId);

        assertThat(results).isEmpty();
    }
}
