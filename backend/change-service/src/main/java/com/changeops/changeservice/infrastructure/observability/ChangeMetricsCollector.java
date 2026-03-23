package com.changeops.changeservice.infrastructure.observability;

import com.changeops.changeservice.domain.valueobject.ChangeStatus;
import com.changeops.changeservice.infrastructure.persistence.repository.ChangeJpaRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ChangeMetricsCollector implements MeterBinder {

    private final ChangeJpaRepository changeJpaRepository;

    @Override
    public void bindTo(MeterRegistry registry) {
        for (ChangeStatus status : ChangeStatus.values()) {
            Gauge.builder("changes_by_status_total", () ->
                            changeJpaRepository.countByStatus(status))
                    .tag("status", status.name())
                    .description("Number of changes by status")
                    .register(registry);
        }
    }
}
