package com.changeops.changeservice.application.port.out;

import java.util.UUID;

public interface ChangeExistsPort {
    boolean existsById(UUID changeId);
}
