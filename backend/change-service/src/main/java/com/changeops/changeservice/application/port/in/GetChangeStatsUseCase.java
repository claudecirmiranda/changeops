package com.changeops.changeservice.application.port.in;

import com.changeops.changeservice.domain.valueobject.ChangeStatus;

import java.time.Instant;
import java.util.Map;

public interface GetChangeStatsUseCase {

    Result execute(Instant since);

    record Result(long total, long prepared, long completed, long failed) {}
}
