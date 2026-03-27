package com.changeops.changeservice.infrastructure.observability;

import com.changeops.changeservice.domain.valueobject.ChangeStatus;
import com.changeops.changeservice.infrastructure.persistence.repository.ChangeJpaRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Component
@RequiredArgsConstructor
public class ChangeMetricsCollector implements MeterBinder {

    private final ChangeJpaRepository changeJpaRepository;
    private final Map<ChangeStatus, AtomicLong> counts = new EnumMap<>(ChangeStatus.class);

    @Override
    public void bindTo(MeterRegistry registry) {
        for (ChangeStatus status : ChangeStatus.values()) {
            AtomicLong counter = new AtomicLong(0);
            counts.put(status, counter);
            Gauge.builder("changes_by_status", counter, AtomicLong::get)
                    .tag("status", status.name())
                    .description("Number of changes by status")
                    .register(registry);
        }
        refreshCounts();
    }

    @Scheduled(fixedDelay = 5_000)
    public void refreshCounts() {
        for (ChangeStatus status : ChangeStatus.values()) {
            counts.get(status).set(changeJpaRepository.countByStatus(status));
        }
    }
}
