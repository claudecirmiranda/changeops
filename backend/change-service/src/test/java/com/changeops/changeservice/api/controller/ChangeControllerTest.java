package com.changeops.changeservice.api.controller;

import com.changeops.changeservice.application.port.in.CreateChangeUseCase;
import com.changeops.changeservice.application.port.in.GetChangeEventsUseCase;
import com.changeops.changeservice.application.port.in.GetChangeUseCase;
import com.changeops.changeservice.application.port.in.ListChangesUseCase;
import com.changeops.changeservice.domain.exception.ChangeNotFoundException;
import com.changeops.changeservice.domain.exception.InvalidChangeStateException;
import com.changeops.changeservice.domain.valueobject.ChangeStatus;
import com.changeops.changeservice.infrastructure.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChangeController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("local")
@SuppressWarnings("null")
class ChangeControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    CreateChangeUseCase createChangeUseCase;

    @MockBean
    ListChangesUseCase listChangesUseCase;

    @MockBean
    GetChangeUseCase getChangeUseCase;

    @MockBean
    GetChangeEventsUseCase getChangeEventsUseCase;

    // ─── POST /api/v1/changes ────────────────────────────────────────────────

    @Test
    void create_shouldReturn201WithLocationAndBody() throws Exception {
        UUID changeId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        Instant createdAt = Instant.now();

        when(createChangeUseCase.execute(any()))
                .thenReturn(new CreateChangeUseCase.Result(changeId, ChangeStatus.PREPARED, correlationId, createdAt));

        String body = """
                {
                  "title": "Deploy payment-service v3.0",
                  "description": "Version upgrade",
                  "componentId": "payment-service",
                  "requestedBy": "user-001",
                  "scheduledAt": "%s"
                }""".formatted(Instant.now().plus(2, ChronoUnit.DAYS));

        mockMvc.perform(post("/api/v1/changes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString(changeId.toString())))
                .andExpect(jsonPath("$.changeId").value(changeId.toString()))
                .andExpect(jsonPath("$.status").value("PREPARED"))
                .andExpect(jsonPath("$.correlationId").value(correlationId.toString()));
    }

    @Test
    void create_shouldUseXUserIdHeader_whenLocalProfileAndNoJwt() throws Exception {
        UUID changeId = UUID.randomUUID();
        when(createChangeUseCase.execute(any()))
                .thenReturn(new CreateChangeUseCase.Result(changeId, ChangeStatus.PREPARED, UUID.randomUUID(), Instant.now()));

        String body = """
                {
                  "title": "Deploy v1",
                  "componentId": "svc-a",
                  "requestedBy": "fallback-user",
                  "scheduledAt": "%s"
                }""".formatted(Instant.now().plus(1, ChronoUnit.DAYS));

        mockMvc.perform(post("/api/v1/changes")
                        .header("X-User-Id", "header-user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
    }

    // ─── GET /api/v1/changes ─────────────────────────────────────────────────

    @Test
    void list_shouldReturn200WithPage() throws Exception {
        UUID changeId = UUID.randomUUID();
        ListChangesUseCase.Result result = new ListChangesUseCase.Result(
                changeId, "Deploy v1", "svc-a", ChangeStatus.PREPARED,
                UUID.randomUUID(), Instant.now(), Instant.now());

        when(listChangesUseCase.execute(any(), any()))
                .thenReturn(new PageImpl<>(List.of(result), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/changes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].changeId").value(changeId.toString()))
                .andExpect(jsonPath("$.content[0].title").value("Deploy v1"));
    }

    @Test
    void list_shouldForwardStatusFilterParam() throws Exception {
        when(listChangesUseCase.execute(any(), any()))
                .thenReturn(new PageImpl<>(List.<ListChangesUseCase.Result>of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get("/api/v1/changes")
                        .param("status", "PREPARED")
                        .param("componentId", "svc-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)));
    }

    // ─── GET /api/v1/changes/{changeId} ──────────────────────────────────────

    @Test
    void getById_shouldReturn200WithDetail() throws Exception {
        UUID changeId = UUID.randomUUID();
        GetChangeUseCase.Result result = new GetChangeUseCase.Result(
                changeId, "Deploy v1", "Version upgrade",
                "svc-a", "user-001", ChangeStatus.PREPARED, UUID.randomUUID(),
                Instant.now().plus(1, ChronoUnit.DAYS), Instant.now(), Instant.now());

        when(getChangeUseCase.execute(changeId)).thenReturn(result);

        mockMvc.perform(get("/api/v1/changes/" + changeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changeId").value(changeId.toString()))
                .andExpect(jsonPath("$.title").value("Deploy v1"))
                .andExpect(jsonPath("$.componentId").value("svc-a"));
    }

    // ─── GET /api/v1/changes/{changeId}/events ────────────────────────────────

    @Test
    void getEvents_shouldReturn200WithEventList() throws Exception {
        UUID changeId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        GetChangeEventsUseCase.Result event = new GetChangeEventsUseCase.Result(
                eventId, changeId, "CHANGE_PREPARED", "{}", Instant.now());

        when(getChangeEventsUseCase.execute(changeId)).thenReturn(List.of(event));

        mockMvc.perform(get("/api/v1/changes/" + changeId + "/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].eventId").value(eventId.toString()))
                .andExpect(jsonPath("$[0].eventType").value("CHANGE_PREPARED"));
    }

    @Test
    void getEvents_shouldReturn200WithEmptyList() throws Exception {
        UUID changeId = UUID.randomUUID();
        when(getChangeEventsUseCase.execute(changeId)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/changes/" + changeId + "/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    // ─── GlobalExceptionHandler ───────────────────────────────────────────────

    @Test
    void create_shouldReturn400WithProblemDetail_whenValidationFails() throws Exception {
        String invalidBody = """
                {
                  "description": "Missing required fields"
                }""";

        mockMvc.perform(post("/api/v1/changes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Error"))
                .andExpect(jsonPath("$.fields").exists());
    }

    @Test
    void getById_shouldReturn404_whenChangeNotFound() throws Exception {
        UUID changeId = UUID.randomUUID();
        when(getChangeUseCase.execute(changeId))
                .thenThrow(new ChangeNotFoundException(changeId));

        mockMvc.perform(get("/api/v1/changes/" + changeId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Resource Not Found"))
                .andExpect(jsonPath("$.detail").value(containsString(changeId.toString())));
    }

    @Test
    void getById_shouldReturn409_whenInvalidStateTransition() throws Exception {
        UUID changeId = UUID.randomUUID();
        when(getChangeUseCase.execute(changeId))
                .thenThrow(new InvalidChangeStateException("Cannot cancel a completed change"));

        mockMvc.perform(get("/api/v1/changes/" + changeId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Invalid State Transition"));
    }

    @Test
    void getById_shouldReturn500_whenUnexpectedErrorOccurs() throws Exception {
        UUID changeId = UUID.randomUUID();
        when(getChangeUseCase.execute(changeId))
                .thenThrow(new RuntimeException("Database connection lost"));

        mockMvc.perform(get("/api/v1/changes/" + changeId))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.title").value("Internal Server Error"));
    }

    @Test
    void getById_shouldReturn400_whenChangeIdIsInvalidUuid() throws Exception {
        mockMvc.perform(get("/api/v1/changes/7e213233-77d6-4727-91d8-c4d563939d83aaa"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid Parameter"))
                .andExpect(jsonPath("$.detail").value(containsString("changeId")))
                .andExpect(jsonPath("$.detail").value(containsString("UUID")));
    }

    @Test
    void getEvents_shouldReturn400_whenChangeIdIsInvalidUuid() throws Exception {
        mockMvc.perform(get("/api/v1/changes/not-a-valid-uuid/events"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid Parameter"))
                .andExpect(jsonPath("$.detail").value(containsString("changeId")))
                .andExpect(jsonPath("$.detail").value(containsString("UUID")));
    }

    @Test
    void create_shouldReturn400_whenBodyIsMalformedJson() throws Exception {
        mockMvc.perform(post("/api/v1/changes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ this is not valid json }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Bad Request"));
    }
}
