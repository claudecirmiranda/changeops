package com.changeops.changeservice.api.dto;

public record ChangeStatsDto(long total, long prepared, long completed, long failed) {}
