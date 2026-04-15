package com.changeops.changeservice.application.service;

import com.changeops.changeservice.application.port.in.GetChangeEventsUseCase;
import com.changeops.changeservice.application.port.in.GetChangeUseCase;
import com.changeops.changeservice.application.port.in.ListChangesUseCase;
import com.changeops.changeservice.application.port.out.ChangeExistsPort;
import com.changeops.changeservice.application.port.out.LoadChangeEventsPort;
import com.changeops.changeservice.application.port.out.LoadChangesPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests simulating database unavailability for the read-side query services:
 * {@code GetChangeService}, {@code ListChangesService}, and {@code GetChangeEventsService}.
 *
 * <p>All tests verify that port exceptions propagate to the caller without being swallowed,
 * allowing the REST layer's {@code GlobalExceptionHandler} to map them to 500 responses.
 */
@ExtendWith(MockitoExtension.class)
class QueryServicesUnavailabilityTest {

    @Mock
    LoadChangesPort loadChangesPort;

    @Mock
    ChangeExistsPort changeExistsPort;

    @Mock
    LoadChangeEventsPort loadChangeEventsPort;

    GetChangeService getChangeService;
    ListChangesService listChangesService;
    GetChangeEventsService getChangeEventsService;

    @BeforeEach
    void setUp() {
        getChangeService = new GetChangeService(loadChangesPort);
        listChangesService = new ListChangesService(loadChangesPort);
        getChangeEventsService = new GetChangeEventsService(changeExistsPort, loadChangeEventsPort);
    }

    // ─── GetChangeService ─────────────────────────────────────────────────────

    @Test
    void shouldPropagateException_whenDatabaseUnavailable_onGetChange() {
        UUID changeId = UUID.randomUUID();
        when(loadChangesPort.findById(changeId))
                .thenThrow(new RuntimeException("DB connection unavailable"));

        GetChangeUseCase getChangeUseCase = getChangeService;
        assertThatThrownBy(() -> getChangeUseCase.execute(changeId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("DB connection unavailable");
    }

    // ─── ListChangesService ───────────────────────────────────────────────────

    @Test
    void shouldPropagateException_whenDatabaseUnavailable_onListChanges() {
        Pageable pageable = PageRequest.of(0, 20);
        when(loadChangesPort.findAll(any(), any(), any(Pageable.class)))
                .thenThrow(new RuntimeException("DB connection unavailable"));

        assertThatThrownBy(() -> listChangesService.execute(new ListChangesUseCase.Query(null, null), pageable))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("DB connection unavailable");
    }

    @Test
    void shouldPropagateException_whenCountGroupedByStatusFails() {
        // findAll() succeeds but countGroupedByStatus() fails (e.g., aggregate query timeout).
        Pageable pageable = PageRequest.of(0, 20);
        when(loadChangesPort.findAll(any(), any(), any(Pageable.class)))
                .thenReturn(org.springframework.data.domain.Page.empty(pageable));
        when(loadChangesPort.countGroupedByStatus())
                .thenThrow(new RuntimeException("Query timeout on status aggregation"));

        assertThatThrownBy(() -> listChangesService.execute(new ListChangesUseCase.Query(null, null), pageable))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Query timeout on status aggregation");
    }

    // ─── GetChangeEventsService ───────────────────────────────────────────────

    @Test
    void shouldPropagateException_whenChangeExistsCheckFails() {
        UUID changeId = UUID.randomUUID();
        when(changeExistsPort.existsById(changeId))
                .thenThrow(new RuntimeException("DB connection unavailable"));

        GetChangeEventsUseCase getChangeEventsUseCase = getChangeEventsService;
        assertThatThrownBy(() -> getChangeEventsUseCase.execute(changeId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("DB connection unavailable");
    }

    @Test
    void shouldPropagateException_whenDatabaseUnavailable_onGetChangeEvents() {
        UUID changeId = UUID.randomUUID();
        when(changeExistsPort.existsById(changeId)).thenReturn(true);
        when(loadChangeEventsPort.findByChangeId(changeId))
                .thenThrow(new RuntimeException("DB connection unavailable"));

        GetChangeEventsUseCase getChangeEventsUseCase = getChangeEventsService;
        assertThatThrownBy(() -> getChangeEventsUseCase.execute(changeId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("DB connection unavailable");
    }
}
