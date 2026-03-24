package com.changeops.changeservice.application.port.out;

import java.time.Instant;
import java.util.UUID;

public interface SaveChangeEventPort {
    void save(UUID changeId, String eventType, String payload, Instant occurredAt);
}
