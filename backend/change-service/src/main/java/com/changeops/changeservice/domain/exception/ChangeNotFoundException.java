package com.changeops.changeservice.domain.exception;

import java.util.UUID;

public class ChangeNotFoundException extends RuntimeException {
    public ChangeNotFoundException(UUID changeId) {
        super("Change not found: " + changeId);
    }
}
