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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ListChangesServiceTest {

    @Mock
    LoadChangesPort loadChangesPort;

    ListChangesService service;

    @BeforeEach
    void setUp() {
        service = new ListChangesService(loadChangesPort);
    }

    @Test
    void shouldDelegateToPort_andMapResults() {
        Change change = Change.create("Deploy v1", "desc", "svc-a", "user-1",
                Instant.now().plus(1, ChronoUnit.DAYS));
        change.pullDomainEvents();
        Pageable pageable = PageRequest.of(0, 10);
        when(loadChangesPort.findAll(null, null, pageable))
                .thenReturn(new PageImpl<>(Objects.requireNonNull(List.of(change))));

        Page<ListChangesUseCase.Result> results =
                service.execute(new ListChangesUseCase.Query(null, null), pageable);

        assertThat(results.getContent()).hasSize(1);
        ListChangesUseCase.Result r = results.getContent().get(0);
        assertThat(r.changeId()).isEqualTo(change.getChangeId());
        assertThat(r.title()).isEqualTo("Deploy v1");
        assertThat(r.status()).isEqualTo(ChangeStatus.PREPARED);
        verify(loadChangesPort).findAll(null, null, pageable);
    }

    @Test
    void shouldReturnEmptyPage_whenNoChanges() {
        Pageable pageable = PageRequest.of(0, 10);
        when(loadChangesPort.findAll(any(), any(), eq(pageable)))
                .thenReturn(new PageImpl<>(Objects.requireNonNull(Collections.emptyList())));

        Page<ListChangesUseCase.Result> results =
                service.execute(new ListChangesUseCase.Query(null, null), pageable);

        assertThat(results.getContent()).isEmpty();
        assertThat(results.getTotalElements()).isZero();
    }

    @Test
    void shouldPassFilters_toPort() {
        Pageable pageable = PageRequest.of(0, 5);
        when(loadChangesPort.findAll(eq(ChangeStatus.PREPARED), eq("payment-service"), eq(pageable)))
                .thenReturn(new PageImpl<>(Objects.requireNonNull(Collections.emptyList())));

        service.execute(new ListChangesUseCase.Query(ChangeStatus.PREPARED, "payment-service"), pageable);

        verify(loadChangesPort).findAll(ChangeStatus.PREPARED, "payment-service", pageable);
    }
}
