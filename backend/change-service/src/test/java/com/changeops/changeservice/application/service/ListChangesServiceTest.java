package com.changeops.changeservice.application.service;

import com.changeops.changeservice.application.port.in.ListChangesUseCase;
import com.changeops.changeservice.application.port.out.LoadChangesPort;
import com.changeops.changeservice.domain.model.Change;
import com.changeops.changeservice.domain.valueobject.ChangeStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("null")
class ListChangesServiceTest {

    @Mock
    LoadChangesPort loadChangesPort;

    ListChangesService service;

    private static final UUID TEST_CORRELATION_ID = UUID.randomUUID();
    
    @BeforeEach
    void setUp() {
        service = new ListChangesService(loadChangesPort);
    }

    @Test
    void shouldDelegateToPort_andMapResults() {
        Change change = Change.create("Deploy v1", "desc", "svc-a", "user-1",
                Instant.now().plus(1, ChronoUnit.DAYS),TEST_CORRELATION_ID);
        change.pullDomainEvents();
        Pageable pageable = PageRequest.of(0, 10);
        when(loadChangesPort.findAll(null, null, pageable))
                .thenReturn(new PageImpl<>(List.of(change)));
        when(loadChangesPort.countGroupedByStatus()).thenReturn(buildEmptySummary());

        ListChangesUseCase.PageResult pageResult =
                service.execute(new ListChangesUseCase.Query(null, null), pageable);

        assertThat(pageResult.page().getContent()).hasSize(1);
        ListChangesUseCase.Result r = pageResult.page().getContent().get(0);
        assertThat(r.changeId()).isEqualTo(change.getChangeId());
        assertThat(r.title()).isEqualTo("Deploy v1");
        assertThat(r.status()).isEqualTo(ChangeStatus.PREPARED);
        verify(loadChangesPort).findAll(null, null, pageable);
    }

    @Test
    void shouldReturnEmptyPage_whenNoChanges() {
        Pageable pageable = PageRequest.of(0, 10);
        when(loadChangesPort.findAll(any(), any(), eq(pageable)))
                .thenReturn(new PageImpl<>(Collections.emptyList()));
        when(loadChangesPort.countGroupedByStatus()).thenReturn(buildEmptySummary());

        ListChangesUseCase.PageResult pageResult =
                service.execute(new ListChangesUseCase.Query(null, null), pageable);

        assertThat(pageResult.page().getContent()).isEmpty();
        assertThat(pageResult.page().getTotalElements()).isZero();
    }

    @Test
    void shouldPassFilters_toPort() {
        Pageable pageable = PageRequest.of(0, 5);
        when(loadChangesPort.findAll(eq(ChangeStatus.PREPARED), eq("payment-service"), eq(pageable)))
                .thenReturn(new PageImpl<>(Collections.emptyList()));
        when(loadChangesPort.countGroupedByStatus()).thenReturn(buildEmptySummary());

        service.execute(new ListChangesUseCase.Query(ChangeStatus.PREPARED, "payment-service"), pageable);

        verify(loadChangesPort).findAll(ChangeStatus.PREPARED, "payment-service", pageable);
    }

    @Test
    void shouldReturnStatusSummary_fromPort() {
        Pageable pageable = PageRequest.of(0, 10);
        when(loadChangesPort.findAll(null, null, pageable))
                .thenReturn(new PageImpl<>(Collections.emptyList()));
        Map<ChangeStatus, Long> expectedSummary = buildEmptySummary();
        expectedSummary.put(ChangeStatus.PREPARED, 5L);
        expectedSummary.put(ChangeStatus.COMPLETED, 3L);
        when(loadChangesPort.countGroupedByStatus()).thenReturn(expectedSummary);

        ListChangesUseCase.PageResult pageResult =
                service.execute(new ListChangesUseCase.Query(null, null), pageable);

        assertThat(pageResult.statusSummary()).containsEntry(ChangeStatus.PREPARED, 5L);
        assertThat(pageResult.statusSummary()).containsEntry(ChangeStatus.COMPLETED, 3L);
    }

    private Map<ChangeStatus, Long> buildEmptySummary() {
        Map<ChangeStatus, Long> summary = new EnumMap<>(ChangeStatus.class);
        Arrays.stream(ChangeStatus.values()).forEach(s -> summary.put(s, 0L));
        return summary;
    }
}
