package com.changeops.changeservice.api.controller;

import com.changeops.changeservice.api.dto.CreateChangeRequest;
import com.changeops.changeservice.api.dto.CreateChangeResponse;
import com.changeops.changeservice.api.dto.ChangeDetailDto;
import com.changeops.changeservice.api.dto.ChangeDto;
import com.changeops.changeservice.api.dto.ChangeEventDto;
import com.changeops.changeservice.api.dto.ChangeStatsDto;
import com.changeops.changeservice.application.port.in.CreateChangeUseCase;
import com.changeops.changeservice.application.port.in.GetChangeEventsUseCase;
import com.changeops.changeservice.application.port.in.GetChangeStatsUseCase;
import com.changeops.changeservice.application.port.in.GetChangeUseCase;
import com.changeops.changeservice.application.port.in.ListChangesUseCase;
import com.changeops.changeservice.domain.valueobject.ChangeStatus;
import io.micrometer.core.annotation.Timed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/changes")
@Tag(name = "Changes", description = "Change Management API")
@Timed(value = "api_request_duration", description = "API request duration")
public class ChangeController {

    private final CreateChangeUseCase createChangeUseCase;
    private final ListChangesUseCase listChangesUseCase;
    private final GetChangeUseCase getChangeUseCase;
    private final GetChangeEventsUseCase getChangeEventsUseCase;
    private final GetChangeStatsUseCase getChangeStatsUseCase;
    private final Environment environment;

    public ChangeController(
            CreateChangeUseCase createChangeUseCase,
            ListChangesUseCase listChangesUseCase,
            GetChangeUseCase getChangeUseCase,
            GetChangeEventsUseCase getChangeEventsUseCase,
            GetChangeStatsUseCase getChangeStatsUseCase,
            Environment environment) {
        this.createChangeUseCase = createChangeUseCase;
        this.listChangesUseCase = listChangesUseCase;
        this.getChangeUseCase = getChangeUseCase;
        this.getChangeEventsUseCase = getChangeEventsUseCase;
        this.getChangeStatsUseCase = getChangeStatsUseCase;
        this.environment = environment;
    }

    @PostMapping
    @Operation(summary = "Create a new change request")
    public ResponseEntity<CreateChangeResponse> create(
            @Valid @RequestBody CreateChangeRequest request,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @AuthenticationPrincipal Jwt jwt) {

        String requestedBy = resolveRequestedBy(userId, jwt, request.requestedBy());

        CreateChangeUseCase.Result result = createChangeUseCase.execute(
                new CreateChangeUseCase.Command(
                        request.title(),
                        request.description(),
                        request.componentId(),
                        requestedBy,
                        request.scheduledAt()));

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(result.changeId())
                .toUri();

        return ResponseEntity.created(location).body(new CreateChangeResponse(
                result.changeId(),
                result.status(),
                result.correlationId(),
                result.createdAt()));
    }

    @GetMapping
    @Operation(summary = "List changes with optional filters")
    public ResponseEntity<Page<ChangeDto>> list(
            @RequestParam(required = false) ChangeStatus status,
            @RequestParam(required = false) String componentId,
            @RequestParam(required = false) Instant since,
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {

        Page<ListChangesUseCase.Result> results = listChangesUseCase.execute(
                new ListChangesUseCase.Query(status, componentId, since), pageable);

        return ResponseEntity.ok(results.map(r -> new ChangeDto(
                r.changeId(), r.title(), r.componentId(),
                r.status(), r.correlationId(), r.createdAt(), r.updatedAt())));
    }

    @GetMapping("/stats")
    @Operation(summary = "Get change counts grouped by status")
    public ResponseEntity<ChangeStatsDto> stats(
            @RequestParam(required = false) Instant since) {

        GetChangeStatsUseCase.Result result = getChangeStatsUseCase.execute(since);
        return ResponseEntity.ok(new ChangeStatsDto(
                result.total(), result.prepared(), result.completed(), result.failed()));
    }

    @GetMapping("/{changeId}")
    @Operation(summary = "Get a change by ID")
    public ResponseEntity<ChangeDetailDto> getById(@PathVariable UUID changeId) {
        GetChangeUseCase.Result r = getChangeUseCase.execute(changeId);
        return ResponseEntity.ok(new ChangeDetailDto(
                r.changeId(), r.title(), r.description(), r.componentId(),
                r.requestedBy(), r.status(), r.correlationId(),
                r.scheduledAt(), r.createdAt(), r.updatedAt()));
    }

    @GetMapping("/{changeId}/events")
    @Operation(summary = "Get the event timeline for a change")
    public ResponseEntity<List<ChangeEventDto>> getEvents(@PathVariable UUID changeId) {
        List<GetChangeEventsUseCase.Result> events = getChangeEventsUseCase.execute(changeId);
        return ResponseEntity.ok(events.stream()
                .map(e -> new ChangeEventDto(e.eventId(), e.changeId(),
                        e.eventType(), e.payload(), e.occurredAt()))
                .toList());
    }

    private String resolveRequestedBy(String userId, Jwt jwt, String fallback) {
        if (jwt != null && jwt.getSubject() != null) return jwt.getSubject();
        if (environment.acceptsProfiles(Profiles.of("local")) && userId != null) return userId;
        return fallback;
    }
}
