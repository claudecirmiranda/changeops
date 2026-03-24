package com.changeops.changeservice.application.port.out;

import com.changeops.changeservice.domain.model.Change;

public interface SaveChangePort {
    Change save(Change change);
}
