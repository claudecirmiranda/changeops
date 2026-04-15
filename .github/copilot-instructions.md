# ChangeOps Dashboard — Guidelines for Consistent Changes

## Language Policy

> **Everything must be in English** — all class names, method names, variable names, comments, log messages, test descriptions, API field names, Kafka event field names, UI labels, button text, form field labels, error messages shown in the frontend, validation feedback, status descriptions, notification text, and any copy visible in the browser.
>
> **Documentation files** keep their current language — if a doc is already in Portuguese it stays in Portuguese; if in English it stays in English.

---

## Project Overview

**ChangeOps Dashboard** is a Proof of Concept (POC) for technical change management in high-frequency enterprise deployment environments. The solution implements:

- **Flow 1:** Change creation via REST API with domain event publishing  
- **Flow 2:** Idempotent consumption of deploy results with orchestration and post-execution validations  

Both flows are decoupled via **Kafka** and correlated end-to-end by a unique `correlation_id`.

**Scope (RFP - Block II):** See the document PROJETO_COMPLETO.md for full scope. Included: EDA, idempotency, retry+DLQ, structured logs, Prometheus+Grafana, React frontend, contracts (OpenAPI/AsyncAPI), Docker Compose, CI pipelines. Excluded: real integration with Jira/ServiceNow, real production deploy execution (simulated), full ITSM.

---

## Architecture — Hexagonal + Event-Driven (EDA)

### Architectural Principles (ADR-005)

Both backend services follow **Hexagonal Architecture (Ports & Adapters)**:

```
api/              → REST adapters (controllers, DTOs, filters)
application/      → Use cases (services + ports/in + ports/out)
domain/           → Pure Java (models, events, exceptions, value objects)
infrastructure/   → Adapters out (JPA, Kafka, security, observability)
```

**Critical Property:** The `domain/` layer is independent of Spring, JPA, and Kafka. Never add:
- `@Entity`, `@Repository`, `@Component`, `@ConfigurationProperties` 
- Imports from `javax.persistence`, `org.springframework`, `org.apache.kafka`

Domain is testable in isolation → mock the ports only.

### Package Dependency Rules

```
api/         → (depends on) → application/
application/ → (depends on) → domain/
infrastructure/ → (adapts)  → application/ (via ports/out)
domain/      — NO external dependencies
```

| Layer | Allowed imports | Forbidden imports |
|-------|----------------|-------------------|
| `domain/` | Pure Java, project-internal records/interfaces | `javax.persistence`, `jakarta.persistence`, `org.springframework`, `org.apache.kafka`, any external framework |
| `application/` | Domain, Java standard library, Spring `@Transactional` (demarcation only) | JPA entities, Kafka producers, HTTP clients |
| `api/` | Application ports, Spring Web, Validation, Security | Domain JPA entities, Kafka producers |
| `infrastructure/` | All of the above, JPA, Kafka, Micrometer | Direct domain mutation (must go through application ports) |

Never add any of the following to the `domain/` package:
- `@Entity`, `@Table`, `@Column`, `@Id`, `@GeneratedValue`
- `@Repository`, `@Service`, `@Component`, `@Bean`, `@Configuration`, `@ConfigurationProperties`
- `import org.springframework.*`, `import javax.persistence.*`, `import jakarta.persistence.*`, `import org.apache.kafka.*`

### ArchUnit Enforcement

The `deploy-orchestrator` contains an `HexagonalArchitectureTest` that validates layer dependencies. This test:
- Must not be suppressed or deleted
- Must cover all four layers
- Must be executed on every CI run (never tagged `@Disabled`)

### Event-Driven Architecture (ADR-003: Domain ↔ Integration Event Separation)

**Domain Event** (internal, pure):
```java
public record ChangePreparedEvent(
    UUID changeId,
    String componentId,
    String requestedBy,
    Instant scheduledAt
) implements DomainEvent {}
```

**Integration Event** (envelope, transportable):
```json
{
  "eventType": "ChangePreparedEvent",
  "version": "1.0",
  "correlationId": "uuid",
  "occurredAt": "ISO-8601",
  "payload": { /* domain event */ }
}
```

Always map domain event → integration event envelope **before** publishing to Kafka (never publish the domain event directly).

### Correlation ID Propagation

Verify the full chain is intact for any change touching request handling or Kafka consumers:

1. `CorrelationIdFilter` generates UUID and stores in MDC (`correlation_id`).
2. Controller passes `correlationId` into the domain via the command/request object.
3. Domain event carries `correlationId`.
4. `IntegrationEvent` envelope carries `correlationId` at the top level.
5. Consumer reads `correlationId` from the envelope and populates MDC before any log statement.

### Retry and DLT Strategy

New `@KafkaListener` methods that perform stateful processing must be annotated with `@RetryableTopic`:

- **Minimum attempts:** 4 (1 original + 3 retries)
- **Backoff:** exponential (start 500 ms, multiplier 2.0, max 10 s)
- **On exhaustion:** forward to DLT (suffix `-dlt`)
- **Poison pill exceptions** (e.g., deserialization errors) must be in the `exclude` list to skip retries and go directly to DLT.

Do not:
- Catch and silently swallow exceptions without rethrowing or forwarding to DLT.
- Use `@RetryableTopic` without an `exclude` list for deserialization errors.
- Omit a `@DltHandler` that logs the dead-lettered event.

---

## Backend Conventions

### Stack

- **Java:** 17 LTS (baseline for Spring Boot 3.2.3+)  
- **Spring Boot:** 3.2.3  
- **Spring Kafka:** Consumer/producer with idempotent producer config  
- **JPA/Flyway:** Migrations tracked by Flyway, shared schema tables: `changes`, `change_events`, `processed_events`  
- **Observability:** Logback JSON (logstash-logback-encoder), Micrometer + Prometheus  
- **Security:** Spring Security (OAuth2 Resource Server ready, currently mocked with `X-User-Id` header on `local` profile)  
- **Rate Limiting:** Bucket4j (100 req/min per IP on `POST /api/v1/changes`)

### Code — Style & Quality

**Checkstyle (both services):**
- Max line length: **150 characters** (pragmatic for Spring annotations)
- No star imports (`import java.util.*` ❌)  
- No unused imports
- Newline at end of file (UTF-8)
- Consistent whitespace (empty blocks allowed for Spring/Lombok proxies)

Run: `make lint-backend` → checks spotless + checkstyle

Any checkstyle violation is a **blocker**. The only acceptable suppressions are autogenerated code with a `// CHECKSTYLE:OFF` / `// CHECKSTYLE:ON` region comment citing the file and reason. Never suppress an entire file.

**JaCoCo Coverage (excluding infrastructure):**
- `change-service`: 80% line coverage (excluding `api/*.java` and `infrastructure/**`)  
- `deploy-orchestrator`: 70% line coverage (same exclusions)

Build fails if threshold is not met → Do not bypass with `@Generated` or suppression; optimize testable logic instead. Do not reduce coverage by extracting tested logic into `infrastructure/` just to fall under the exclusion threshold.

### Input Validation

- All request DTOs must be annotated with `@Valid` at the controller parameter.
- DTO fields must use JSR-380 annotations: `@NotNull`, `@NotBlank`, `@Size`, `@Pattern`, `@Future`, etc.
- Missing or invalid fields must return **400 Bad Request** with an RFC 7807 `ProblemDetail` body containing per-field errors.
- Never validate input manually with `if (field == null) throw new RuntimeException(...)` — use Bean Validation.

### Error Responses

- All error responses must follow **RFC 7807 ProblemDetail**: `title`, `detail`, `status`, `type`, `fields` (for validation errors).
- `GlobalExceptionHandler` must handle all unhandled exceptions — no raw 500 responses with stack traces in production bodies.

### Data Access

- **Never** concatenate strings to build JPQL or SQL queries. Use Spring Data JPA named queries, `@Query` with parameters, or `JpaRepository` methods.
- JPA entities (`@Entity`) belong exclusively in `infrastructure/persistence/entity/`.
- Domain models are mapped from/to JPA entities in the persistence adapter — never expose JPA entities through the application or API layers.

### Status Machine Enforcement

The `ChangeStatus` state machine transitions are driven exclusively by events. Never add a `PUT /changes/{id}/status` endpoint or any direct API mutation of status — status is an event-driven consequence, not a directly settable field.

### Rate Limiting Enforcement

- `POST /api/v1/changes` is protected by Bucket4j (100 req/min per IP).
- Never remove or weaken rate limiting on mutation endpoints.
- Any new mutation endpoint (`POST`, `PUT`, `DELETE`, `PATCH`) must include rate limiting.

### Null Safety — Java (`@SuppressWarnings("null")` Policy)

Spring and Mockito APIs are annotated with `@Nullable` return types that trigger IDE/static-analysis warnings even when the values are guaranteed non-null at runtime. This project follows a **layered suppression policy** to keep the code warning-free without hiding real bugs.

**Test classes (unit + integration):**
- Add `@SuppressWarnings("null")` at **class level** on every test class that uses Mockito `mock()` or Spring's `TestRestTemplate`/`WebTestClient`.
- Rationale: `mock(Foo.class)` returns `@Nullable T` but is never actually null. Suppressing at class level avoids dozens of per-line annotations.

**Infrastructure adapter classes (`infrastructure/` package):**
- Add `@SuppressWarnings("null")` at **class or method level** on adapters where Spring/JPA guarantees non-null at runtime (e.g., `@Value` field injection, JPA `save()`/`findById()` results, `KafkaTemplate` bean wiring).
- Rationale: these are false positives from static analysis — Spring fails fast on missing `@Value` properties; JPA `save()` never returns null.

**Production code (api/, application/, domain/):**
- **Never** suppress null warnings. Handle nullable returns explicitly with an `if (value == null) { value = "fallback"; }` reassignment block, ternary fallbacks, or null checks.
- **Important:** `Objects.requireNonNullElse()` is **not** sufficient — the IDE's flow analyzer does not recognize its return as `@NonNull`. Always use the `if (value == null)` reassignment pattern that the IDE can track.
- Examples: `String detail = ex.getMessage(); if (detail == null) { detail = "fallback"; }` — same pattern used throughout `GlobalExceptionHandler`.

**Domain layer (`domain/`):**
- **Never** add `@SuppressWarnings("null")` — domain must stay pure and independently testable.

### Backend Testing (See test-strategy.md)

**Pyramid:**
- **Unit** (60%): Pure domain + services with mocked ports (zero Spring context)
- **Integration** (30%): Testcontainers (PostgreSQL 16-alpine, Kafka 7.6.0), full stack
- **Event Consumers** (10%): Idempotency x2, status transitions, DLQ

**Tooling:** JUnit 5 + Mockito, Testcontainers, Awaitility (async), AssertJ

**Test Naming Conventions:**
- Unit tests: `*Test.java` (e.g., `ChangeTest`, `CreateChangeServiceTest`)
- Integration tests: `*IT.java` (e.g., `CreateChangeIT`, `DeployEventConsumerIT`)
- Tests that mix naming conventions will be excluded from the correct Surefire/Failsafe phase

**Integration Test Infrastructure:**
- Integration tests must use `@Testcontainers` + `@Container` — never hardcoded localhost ports or mocked Kafka.
- `@DynamicPropertySource` must configure datasource and Kafka bootstrap servers from container ports.
- Async assertions must use **Awaitility** — never `Thread.sleep()`.

**Mandatory Test Cases — Flow 1 (change-service):**

| # | Scenario | Expected result |
|---|----------|----------------|
| F1-1 | Valid `POST /api/v1/changes` | 201 + `Location` header + `ChangePreparedEvent` on Kafka topic |
| F1-2 | Missing required field | 400 + RFC 7807 ProblemDetail with per-field error |
| F1-3 | Invalid `componentId` | Error handled and returned (not 500) |

**Mandatory Test Cases — Flow 2 (deploy-orchestrator):**

| # | Scenario | Expected result |
|---|----------|----------------|
| F2-1 | `DeployFinishedEvent` with `result=SUCCESS`, all checks pass | Change status → `COMPLETED`, `ChangeCompletedEvent` published |
| F2-2 | `DeployFinishedEvent` with `result=FAILURE` | Change status → `FAILED`, `ChangeFailedEvent` published with `reason` |
| F2-3 | Same event delivered twice | State unchanged on second delivery; `events_discarded_total` incremented |
| F2-4 | Publish failure for result event | Retry triggered → DLT after exhaustion; `events_dlt_total` incremented |

**What Must NOT Be Done:**
- Do not add `@Disabled` to tests without an issue reference and a timeline.
- Do not use `@MockBean` in unit tests — use pure Mockito `@Mock` with `@InjectMocks`.

---

## Contracts & Event Versioning

### Contract-Driven Development

**REST API:** OpenAPI spec change-service.yml
- Endpoints: `POST /api/v1/changes`, `GET /api/v1/changes`, `GET /api/v1/changes/{changeId}`, `GET /api/v1/changes/{changeId}/events`
- Security: Bearer JWT (OAuth2)  
- Errors: RFC 7807 ProblemDetail (title, detail, status, type, fields)

**Kafka Events:** AsyncAPI spec events.yml
- Format: `IntegrationEvent` with `eventType`, `version`, `correlationId`, `occurredAt`, `payload`
- Topics: `changeops.change.prepared`, `changeops.deploy.finished`, `changeops.change.result`
- DLT: `-dlt` suffix (auto-created by @RetryableTopic)
- DLQ: `changeops.events.dlq` (fallback on publish failure)

### Versioning & Compatibility

Every payload carries `"version": "1.0"`. **Tolerant reader** strategy (backward compatibility):
- Consumer reads `version` and adapts parsing as needed  
- Adding a new field: always optional or with a default value
- Removing a field: never (mark as deprecated first)
- Consumers must use `@JsonIgnoreProperties(ignoreUnknown = true)` on deserialization targets

### Breaking Changes Classification

| Change | Classification | Action required |
|--------|---------------|----------------|
| Adding a new optional field to an event payload | Non-breaking | Update AsyncAPI spec, add default in consumers |
| Adding a new required field to an event payload | **Breaking** | Bump `version`, implement version dispatch in consumers |
| Removing a field from an event payload | **Breaking** | Forbidden — deprecate first, coordinate with all consumers |
| Renaming a field | **Breaking** | Forbidden — add new field + deprecation of old field |
| Changing a field type | **Breaking** | Forbidden — requires new event type |
| Adding optional query parameter to REST API | Non-breaking | Update OpenAPI spec |
| Removing a REST endpoint | **Breaking** | Requires deprecation cycle and consumer migration |

---

## Idempotency — ADR-002

**Two-Level Strategy (mandatory for every new event consumer):**

### Level 1: Application
```java
// Before any processing:
if (isAlreadyProcessed(deployId)) {
    log.warn("Event already processed, discarding", deployId);
    return;
}
```

### Level 2: Database
```sql
-- Atomic insert with deduplication:
INSERT INTO processed_events (event_id, processed_at, service_name)
VALUES (?, now(), 'deploy-orchestrator')
ON CONFLICT DO NOTHING
-- Returns 0 if already exists
```

**Never skip the `processed_events` table** — it serves as audit trail and durability against restarts.

---

## Observability

### Structured Logs (Required JSON format)

Format via logstash-logback-encoder:
```json
{
  "timestamp": "2026-03-30T15:02:38Z",
  "level": "INFO",
  "service": "change-service",
  "correlation_id": "abc123",
  "change_id": "xyz789",
  "message": "Change created",
  "status": "PREPARED"
}
```

**MDC Fields (always populated):**
- `correlation_id`: UUID generated in CorrelationIdFilter, propagated via X-Correlation-Id header
- `service`: "change-service" or "deploy-orchestrator"
- `change_id`: change UUID (when available)
- `deploy_id`: deploy UUID (Flow 2 only)

Every new request handler, Kafka listener, or scheduled job must populate MDC **before the first log statement**. MDC must be **cleared after the operation** to prevent context leakage across thread-pool-reused threads.

**Restriction:** Never log PII (SSN, real email) or credentials. For sensitive data, either omit or mask (`****`). Never use `System.out.println()` in production classes. Never use string concatenation in log statements (`log.info("text " + variable)`) — use parameterized logging (`log.info("text {}", variable)`).

### Metrics — Micrometer + Prometheus

**Counters (per service, exposed at `/actuator/prometheus`):**
- `changes_created_total{service="change-service"}` — Total changes created (change-service)
- `timeline_persistence_failures_total{service="<service>"}` — Timeline event persistence failures (emitted by both `change-service` and `deploy-orchestrator`)
- `changes_completed_total{service="deploy-orchestrator"}` — Changes transitioned to COMPLETED (deploy-orchestrator)
- `changes_failed_total{service="deploy-orchestrator"}` — Changes transitioned to FAILED (deploy-orchestrator)
- `events_published_total{type="<eventType>"}` — Events published by type (both services)
- `events_consumed_total{type="DeployFinishedEvent"}` — Events consumed by type (deploy-orchestrator)
- `events_discarded_total{reason="duplicate"}` — Events discarded due to idempotency check (deploy-orchestrator)
- `events_failed_total{consumer="deploy-orchestrator"}` — Events that permanently failed processing
- `events_dlt_total{consumer="deploy-orchestrator"}` — Events sent to DLT after max retries
- `events_retries_total{consumer="deploy-orchestrator"}` — Retry hops per event

**Histograms:**
- `orchestration_duration_seconds` — End-to-end deploy event processing latency (deploy-orchestrator)
- `http_requests_duration_seconds` — REST request latency (change-service)

**Tag Schema (standard labels applied per metric category):**

| Tag | Values | Applied to |
|-----|--------|------------|
| `service` | `"change-service"`, `"deploy-orchestrator"` | All business and error counters |
| `type` | event type name (e.g. `"ChangePreparedEvent"`) | Event publish and consume counters |
| `consumer` | consumer name (e.g. `"deploy-orchestrator"`) | Consumer-layer error/retry/DLT counters |
| `reason` | reason string (e.g. `"duplicate"`) | Discard counters |
| `job` | job name from scrape config | Auto-added by Prometheus for all metrics |

Scrape config: Prometheus configuration file prometheus.yml (interval 5s, targets at change-service:8080 and deploy-orchestrator:8081)

Visualization: Grafana dashboard `changeops.json` at http://localhost:3001 (admin/changeops)

### Log Aggregation — Loki + Promtail

**Loki** (grafana/loki:2.9.0) aggregates structured JSON logs from all Docker containers:
- Promtail scrapes container stdout/stderr from `/var/lib/docker/containers` and forwards to Loki
- Grafana is provisioned with a Loki datasource out of the box — no manual configuration needed
- Query logs via Grafana Explore: `{container_name="changeops-change-service"}` or `{container_name="changeops-deploy-orchestrator"}`
- Loki API available at http://localhost:3100

**Restart policy:** Both `loki` and `promtail` services use `restart: unless-stopped` in docker-compose.yml.

---

## Security

### Coding Standards (OWASP Top 10)

- **Input Validation:** Bean Validation JSR-380 (`@Valid`, `@NotNull`, `@Size`, `@Pattern` on request DTOs)
- **SQL Injection:** Spring Data JPA + prepared statements (never string concatenation in queries). Never use `EntityManager.createNativeQuery(string + variable)`.
- **XSS (Frontend):** React built-in sanitization; DOMPurify if needed. Never use `dangerouslySetInnerHTML` without `DOMPurify` sanitization.
- **CSRF:** CSRF token configured in SecurityConfig (mocked for local, real for prod/staging). Never call `csrf().disable()` without a documented justification.

### Authentication & Authorization

**Local Profile (POC):** Header `X-User-Id: dev-user-001` simulates identity.

**Prod/Staging Profiles:** Bearer JWT via Spring Security OAuth2 Resource Server. Keycloak/Azure AD integration on roadmap (Phase 2).

**POC Scope:** No complex RBAC (future phases). Only presence validation (@Secured or @PreAuthorize if role restrictions are needed).

**Enforcement rules:**
- Do not add `@Profile("local")` logic inside controllers, services, or domain classes.
- Do not disable `SecurityConfig` globally (e.g., `http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())`) without a profile guard.
- Every new endpoint must be added to `SecurityConfig`.

> Known debt: `X-User-Id` header must be removed before go-live (see ADR-007). New code should prefer the correct solution: mock `JwtDecoder` via `@Profile("local")` rather than conditional logic in production classes.

### PII and Sensitive Data

Never log:
- Real email addresses, phone numbers, names, or national IDs.
- JWT tokens, API keys, passwords, or secrets (even partial values).
- Internal stack traces in API responses (use `ProblemDetail` with a generic message for 500s).

### Event Payload Data Minimization

Kafka event payloads must contain only what consumers need to perform their action. Do not:
- Embed full user objects in events (use only `userId`/`requestedBy` as a string identifier).
- Include sensitive fields (credentials, tokens) in `payload`.

---

## Frontend Testing

### Stack & Conventions

- **Framework:** React 18 + TypeScript (strict mode)
- **Test Runner:** Vitest 4.x  
- **Assertions & Mocking:** React Testing Library, MSW (Mock Service Worker), @testing-library/user-event
- **State:** Zustand (ChangeStore)
- **HTTP Client:** Axios (mocked in tests via MSW)

### Structure

```
src/
├── features/changes/
│   ├── components/     # ChangeForm, ChangeList, ChangeTimeline (with .test.tsx)
│   ├── services/       # changeService.ts (API calls, with .test.ts)
│   ├── store/          # Zustand slices (with .test.ts for complex logic)
│   └── types/          # Change, ChangeStatus, etc.
├── shared/
│   ├── components/     # StatusBadge, reusables
│   ├── hooks/          # usePolling (custom hooks)
│   └── lib/            # Test setup, utilities
```

### Mandatory Tests

Per test-strategy.md:
- Component rendering (FormComponent, ListComponent, TimelineComponent)
- User interactions (form submit, button click, list pagination)
- API calls via MSW (success + error 400/404)
- Polling logic (5s interval, retry on failure)
- Centralized state (Zustand store correct state after action)

**Coverage thresholds** (enforced in `vite.config.ts`): 80% lines, 70% branches.

**Selector preference:** Use `screen.getByRole`, `screen.getByLabelText`, `screen.getByText` — avoid `getByTestId` for behavioral queries. MSW must intercept at network level — never mock `axios` directly in test files.

---

## Frontend Conventions

### Style & Structure

**TypeScript:** Strict mode (`strict: true` in tsconfig.json), no `any`, explicit types.

**Styling:** Tailwind CSS (inline classes, no CSS modules).

**ESLint:** ESLint 8.x with legacy `.eslintrc.cjs` config (not flat `eslint.config.js`). Path alias `@/` mapped to `src/` — always use it for imports. **Zero ESLint warnings are allowed.** Any `// eslint-disable` comment requires explicit justification.

**Organization:**
- Feature-based (by domain: `changes/`, `users/` etc.)
- Separation: presentational components ↔ container components ↔ services
- State: Zustand (centralized micro-stores)
- HTTP: Axios client isolated in `services/`, mocked in tests

### Patterns

**No prop drilling:** If component C needs data from A passing through B, put it in a Zustand store.

**Logic-free components:** Dumb components accept props only; all logic goes in the container above or in the store.

**Polling:** Via custom hook `usePolling(interval=5000)` — refetch every 5s with built-in retry.

---

## Change Status Machine (ChangeStatus Enum)

```
DRAFT → PREPARED → COMPLETED | FAILED | CANCELLED
```

- **DRAFT:** Reserved for future approval workflows; currently unused.
- **PREPARED:** Initial status on creation; signals readiness for deploy (event published).
- **COMPLETED:** After successful deploy + all checks pass.
- **FAILED:** Deploy failed or checks failed.
- **CANCELLED:** Operator cancelled (future feature).

Transitions:
- Flow 1: creation → PREPARED
- Flow 2: PREPARED → COMPLETED | FAILED via DeployFinishedEvent

---

## Build & Validation — CI Commands

After making code changes, **always build and run the same validations that CI performs** for each affected project. Only run validations for projects whose files were actually changed.

### Frontend (`frontend/`)

Run from the `frontend/` directory:

```bash
npx tsc --noEmit                              # Typecheck
npm run lint                                  # ESLint (zero warnings)
npx prettier --check "src/**/*.{ts,tsx}"      # Formatting
npm run test                                  # Vitest
npm run build                                 # Production build
```

### change-service (`backend/change-service/`)

Run from the `backend/change-service/` directory:

```bash
mvn -B clean package -DskipTests   # Build
mvn -B verify                      # Tests + Jacoco coverage
mvn -B checkstyle:check            # Checkstyle
```

### deploy-orchestrator (`backend/deploy-orchestrator/`)

Run from the `backend/deploy-orchestrator/` directory:

```bash
mvn -B clean package -DskipTests   # Build
mvn -B verify                      # Tests + Jacoco coverage
mvn -B checkstyle:check            # Checkstyle
```

### Docker Image Builds (Dockerfiles)

Both backend Dockerfiles use `mvn -B package -DskipTests` (followed by `checkstyle:check`) **intentionally**. Tests are **not** executed during `docker build` because:

- CI pipelines already run `mvn verify` (unit + integration tests) as a separate gate  
- Running tests inside Docker build adds significant build time to `make up` / `docker compose up --build`  
- Integration tests require Testcontainers (Docker-in-Docker), which is unavailable during image build  

**Do not change the Dockerfiles to run tests** — keep `package -DskipTests` and rely on CI for test coverage.

### CI Workflow Rules

- Workflow files live at `.github/workflows/`.
- Backend workflows must include `mvn -B verify` (not just `mvn test`) to enforce JaCoCo.
- Workflow changes that remove or skip coverage, checkstyle, or lint steps are blockers.
- Secrets (tokens, passwords) must only be accessed via `${{ secrets.* }}` — never hardcoded.
- Use `make <target>` in workflow files where an equivalent Makefile target exists — do not add raw `docker build`, `docker-compose`, or `mvn` commands.

---

## Build & Run — Use Makefile

### Main Commands

```bash
make help                  # List all available commands
make up                    # Start stack (docker compose up -d --build)
make down                  # Stop containers (preserves volumes)
make clean-stack           # Remove everything INCLUDING volumes
make restart               # Restart services

make build                 # Build all (backend + frontend)
make build-backend         # MVN clean package -DskipTests
make build-frontend        # npm ci + npm run build

make test                  # All tests (backend + frontend)
make test-backend          # Maven test (unit + integration)
make test-backend-unit     # Unit tests only (fast)
make test-backend-it       # Integration tests only (Testcontainers)
make test-frontend         # Vitest run
make test-frontend-watch   # Vitest watch mode
make test-frontend-coverage # Vitest with coverage

make lint                  # Lint all
make lint-backend          # Spotless check
make lint-frontend         # ESLint
make lint-frontend-fix     # ESLint --fix (auto-fix)

make logs                  # Tail logs (change-service + deploy-orchestrator)
make logs-cs               # Tail change-service logs only
make logs-do               # Tail deploy-orchestrator logs only
make ps                    # Container status (docker compose ps)

make smoke                 # Create a change via curl (test Flow 1)
make publish-deploy-event  # Publish DeployFinishedEvent to Kafka (Flow 2)

make db-shell              # Interactive psql
make kafka-shell           # bash into Kafka container
make kafka-topics          # List topics
make kafka-reset-state     # Clear Kafka/Zookeeper state (Cluster ID mismatch fix)

make install-frontend      # npm install in frontend/
make clean-all             # Remove build artifacts + stop containers (clean-artifacts + clean-stack)
```

**Do not use raw Docker commands:** Always prefer `make <target>` — it encapsulates local context.

---

## Local URLs

| Service | URL | Port |
|---------|-----|------|
| change-service API | http://localhost:8080 | 8080 |
| change-service Swagger | http://localhost:8080/swagger-ui.html | 8080 |
| deploy-orchestrator | http://localhost:8081 | 8081 |
| Frontend | http://localhost:3000 | 3000 |
| Kafka UI | http://localhost:8090 | 8090 |
| Prometheus | http://localhost:9090 | 9090 |
| Grafana | http://localhost:3001 | 3001 (admin/changeops) |
| Loki | http://localhost:3100 | 3100 |
| PostgreSQL | localhost:5432 (changeops/changeops) | 5432 |

---

## Flow Checklists

### Flow 1: Change Creation

When adding features to change creation:

- [ ] REST endpoint validates all required fields (Bean Validation)
- [ ] Domain: `Change.create()` returns domain event (`ChangePreparedEvent`)
- [ ] `correlation_id` is generated as UUID and propagated via header filter
- [ ] Event is wrapped in IntegrationEvent envelope (eventType, version, correlationId, occurredAt, payload)
- [ ] Kafka publishes idempotently (Confluent producer acks=all)
- [ ] Timeline entry recorded in `change_events` (JSONB payload)
- [ ] Metric `changes_created_total` incremented
- [ ] Structured JSON logs with correlation_id and change_id
- [ ] 201 response with Location header pointing to GET /api/v1/changes/{changeId}

Tests:
- Unit: `Change.create()` + validations
- Integration: POST /changes → DB → Kafka topic `changeops.change.prepared`
- Contract: valid OpenAPI request/response

### Flow 2: Deploy Orchestration

When adding features to deploy orchestration:

- [ ] @KafkaListener listens to topic `changeops.deploy.finished` (consumer group `deploy-orchestrator-group`)
- [ ] MDC populated with correlation_id, change_id, deploy_id
- [ ] **Idempotency Level 1:** `isAlreadyProcessed(deployId)` check → discard if true
- [ ] **Idempotency Level 2:** `INSERT processed_events ON CONFLICT DO NOTHING`
- [ ] Post-Deploy Checklist executes (4 simulated checks, may fail)
- [ ] Status updated: `PREPARED → COMPLETED` (success + checks pass) or `PREPARED → FAILED` (deploy fail OR checks fail)
- [ ] `changes` table updated (transactional)
- [ ] Timeline entry recorded in `change_events`
- [ ] Result published to Kafka (topic `changeops.change.result`): `ChangeCompletedEvent` or `ChangeFailedEvent`
- [ ] @RetryableTopic: 4 attempts with exponential backoff (500ms → 10s) → DLT on exhaustion
- [ ] Publish failure fallback → DLQ topic `changeops.events.dlq`
- [ ] Metrics `events_consumed_total` and `events_published_total` incremented
- [ ] JSON logs with full context (correlationId, changeId, deployId)

Tests:
- Unit: Service logic (status transitions, checks)
- Integration: Kafka consume → DB update → Kafka publish (DeployEventConsumerIT + double idempotency)
- Event Consumer: Same event twice → state unchanged on second delivery
- DLQ: Persistent failure → event lands in DLT (verifiable in Kafka UI)

---

## Architectural Decision Records — ADRs

Consult the files for full context:

- **ADR-001** (ADR-001-escolha-message-broker.md): Kafka (vs RabbitMQ) — replay, partitioning, idempotency, consumer groups
- **ADR-002** (ADR-002-estrategia-idempotencia.md): Two-level idempotency — application + DB `ON CONFLICT`
- **ADR-003** (ADR-003-eventos-dominio-vs-integracao.md): Domain ↔ integration event separation — envelope wrapper, independent versioning
- **ADR-004** (ADR-004-atualizacao-status-frontend.md): HTTP polling 5s (vs SSE/WebSocket) — simplicity, proxy-friendly, acceptable latency
- **ADR-005** (ADR-005-estrutura-pacotes.md): Hexagonal architecture — `api → application → domain ← infrastructure`, testability

---

## Roadmap & Known Technical Debt (Phase 2+)

**Known Technical Debt:**
- Transactional Outbox Pattern (not yet implemented) — risk of event loss if Kafka is unavailable after DB commit
- OAuth2 mocked (Phase 2 replaces with Keycloak)
- PostDeployChecklist fully simulated (Phase 2 integrates with real systems)
- Frontend polling (Phase 2 → SSE/WebSocket)

More details: ROADMAP.md

---

## Common Patterns & Anti-Patterns

### ✅ Do

- Test domain in isolation (no Spring)
- Always version events (`version` field in payload)
- Use correlation_id for end-to-end tracing
- Structured JSON logs (never plaintext in prod/staging)
- Separate service layer from React components
- Use `make <target>` instead of raw docker commands
- Coverage applies to testable logic (domain + application) only

### ❌ Don't

- Add `@Entity` or `@Repository` in `domain/`
- Skip idempotency in a new Kafka consumer
- Publish a domain event directly (always wrap in envelope)
- Log PII or credentials
- Ignore JaCoCo coverage threshold; optimize logic instead
- Use prop drilling in React (prefer Zustand store)
- Bypass checkstyle with arbitrary suppressions

### Quick Reference — Anti-Patterns

| Anti-Pattern | Section |
|---|---|
| `@Entity` in `domain/` package | Architecture |
| `import org.springframework.*` in `domain/` | Architecture |
| Publishing domain event directly to Kafka (no `IntegrationEvent` envelope) | Architecture — EDA |
| New Kafka consumer without two-level idempotency | Idempotency |
| `@SuppressWarnings("null")` in `domain/`, `api/`, or `application/` | Backend Conventions — Null Safety |
| String concatenation in SQL/JPQL queries | Backend Conventions — Data Access |
| `log.info("text " + variable)` string concatenation | Observability |
| Missing `correlation_id` in MDC | Observability |
| No `version` field in Kafka event payload | Contracts & Event Versioning |
| `mvn test` without `verify` in CI (skips JaCoCo) | Build & Validation — CI |
| `@Disabled` test without issue reference | Backend Testing |
| `dangerouslySetInnerHTML` without `DOMPurify` | Security |
| `any` type in TypeScript | Frontend Conventions |
| Prop drilling past depth 1 in React | Frontend Conventions |
| New mutation endpoint without rate limiting | Backend Conventions — Rate Limiting |
| Hardcoded secret in workflow file | Build & Validation — CI |

---

## Debugging

### Logs & Correlation

```bash
# 1. Search by correlation_id in console:
make logs | grep "abc123"

# Or query structured logs in Grafana Explore (http://localhost:3001 → Explore → Loki):
# {container_name="changeops-change-service"} |= "abc123"
# {container_name="changeops-deploy-orchestrator"} |= "abc123"

# 2. Kafka UI (http://localhost:8090) to inspect topics
make kafka-topics
# Click each topic to inspect messages

# 3. PostgreSQL for auditing:
make db-shell
SELECT * FROM changes WHERE id = 'xyz789';
SELECT * FROM change_events WHERE change_id = 'xyz789' ORDER BY occurred_at;
SELECT * FROM processed_events WHERE event_id = 'dep-001';

# 4. Prometheus/Grafana (http://localhost:3001):
# Query: changes_created_total{service="change-service"}
# Grafana dashboard: Changes, Events, API Latency, Kafka Listener/Producer latency panels
```

### Full Manual Smoke Test

```bash
# 1. Start stack
make up && sleep 10

# 2. Flow 1: create a change
make smoke
# Capture the changeId from the response

# 3. Flow 2: simulate deploy
make publish-deploy-event CHANGE_ID=<changeId> RESULT=SUCCESS

# 4. Check status
curl http://localhost:8080/api/v1/changes/<changeId>

# 5. View timeline
curl http://localhost:8080/api/v1/changes/<changeId>/events

# 6. Grafana: http://localhost:3001 (verify metrics)
```

---

## Recommended Reading

1. PROJETO_COMPLETO.md — Full project overview
2. ADR-001-escolha-message-broker.md and the other ADRs — Architectural decisions and trade-offs
3. test-strategy.md — Testing strategy in detail
4. change-service.yml — REST API specification
5. events.yml — Kafka event contracts

---

**Version:** 1.1 | **Date:** April/2026 | **Status:** active  
Last update: Merged code review instructions (.instructions.md) into this single file. This document reflects the architecture, conventions, and review criteria implemented in the POC and must be updated when decisions are revised in future phases.
