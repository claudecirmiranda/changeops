package com.changeops.changeservice.domain.event;

import java.time.Instant;

public interface DomainEvent {
    Instant occurredAt();
}
