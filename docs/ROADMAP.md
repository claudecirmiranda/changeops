# ChangeOps — Technical Roadmap

> Evolutionary decisions, planned refactors and scale considerations.

---

## Phase 1 — Foundation (Current)

**Goal:** Functional MVP running locally with full async flow.

- [x] Hexagonal architecture in both services
- [x] Kafka event-driven communication
- [x] Idempotency via `processed_events` table
- [x] Retry with exponential backoff + DLQ
- [x] Structured JSON logging with `correlation_id` propagation
- [x] Prometheus + Grafana observability
- [x] React frontend with polling and event timeline
- [x] OpenAPI + AsyncAPI contracts
- [x] Docker Compose local stack
- [x] GitHub Actions CI pipelines

---

## Phase 2 — Production Hardening

### 2.1 Transactional Outbox Pattern

**Problem:** change-service writes to the DB and then publishes to Kafka in two separate operations. If Kafka is unavailable after the DB commit, the event is lost.

**Solution:** Implement the [Transactional Outbox](https://microservices.io/patterns/data/transactional-outbox.html):

```sql
CREATE TABLE outbox_events (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type  VARCHAR(100) NOT NULL,
    aggregate_id    UUID NOT NULL,
    event_type      VARCHAR(100) NOT NULL,
    payload         JSONB NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    processed_at    TIMESTAMPTZ
);
```

A separate relay process (or Debezium CDC) reads unpublished rows and publishes them to Kafka, then marks them processed. This guarantees at-least-once delivery.

**Options:**
- Custom relay thread (simple, low dependency)
- [Debezium](https://debezium.io/) PostgreSQL connector (robust, CDC-based)

---

### 2.2 Full JWT / OAuth2 Integration *(first priority after POC approval)*

**POC design decision:** Authentication was intentionally mocked during the POC using an `X-User-Id` request header. This choice was deliberate — it allowed the team to focus on validating the core architectural concerns (event-driven flow, async orchestration, idempotency, observability) without coupling the demo to an external Identity Provider. The production path is fully designed and the codebase is already prepared for it.

**What is already in place (no rework needed):**
- `spring-boot-starter-oauth2-resource-server` is already a declared dependency
- `spring.security.oauth2.resourceserver.jwt.issuer-uri` is already present in `application.yml`
- Role model (`OPERATOR`, `ADMIN`) is already referenced in Spring Security configuration
- Rate limiting, correlation ID propagation, and structured logging are all auth-agnostic

**What needs to be activated:**

```yaml
# docker-compose.yml addition
keycloak:
  image: quay.io/keycloak/keycloak:24.0
  command: start-dev
  environment:
    KEYCLOAK_ADMIN: admin
    KEYCLOAK_ADMIN_PASSWORD: admin
  ports: ["8180:8080"]
```

- Import `changeops` realm with `OPERATOR` and `ADMIN` roles
- Replace `X-User-Id` header extraction with JWT claim (`sub` or custom claim)
- Frontend: integrate `oidc-client-ts` for PKCE flow (replace `localStorage` token placeholder)
- Backend: point `issuer-uri` to the running Keycloak instance and enable `@PreAuthorize` annotations already present in the codebase

---

### 2.3 Distributed Tracing

Add OpenTelemetry instrumentation:

```xml
<dependency>
  <groupId>io.micrometer</groupId>
  <artifactId>micrometer-tracing-bridge-otel</artifactId>
</dependency>
<dependency>
  <groupId>io.opentelemetry</groupId>
  <artifactId>opentelemetry-exporter-otlp</artifactId>
</dependency>
```

Deploy Tempo in the Docker Compose stack and wire Grafana to correlate traces with logs.

---

### 2.4 Database Maintenance

- Add TTL/archival job for `processed_events` (keep 90 days, archive older rows)
- Partition `change_events` by `occurred_at` (monthly) for large volumes
- Add read replica for reporting queries

---

## Phase 3 — Scale & Resilience

### 3.1 Kubernetes Migration

Replace Docker Compose with Helm charts:

```
helm/
├── change-service/
├── deploy-orchestrator/
├── kafka/          (Strimzi operator)
└── postgres/       (CloudNativePG operator)
```

Benefits: horizontal scaling, rolling deployments, health-based routing, resource limits.

### 3.2 Kafka Scaling

Current: single broker, 3 partitions per topic.

Scale path:
- Increase `change-service` producer concurrency
- Add partitions for high-throughput topics
- Tune `deploy-orchestrator` consumer `concurrency` to match partition count
- Monitor consumer lag with Prometheus `kafka_consumer_group_lag` metric

### 3.3 Circuit Breaker

Add Resilience4j circuit breakers around external calls in `PostDeployChecklistService`:

```xml
<dependency>
  <groupId>io.github.resilience4j</groupId>
  <artifactId>resilience4j-spring-boot3</artifactId>
</dependency>
```

---

## Phase 4 — Multi-Tenancy & Compliance

### 4.1 Multi-Tenancy

Strategy: **schema-per-tenant** in PostgreSQL.

- Add `tenant_id` column to `changes` and `change_events`
- Row-level security (RLS) policies per tenant
- JWT claim `tenant_id` propagated to all queries via Hibernate filter

### 4.2 Audit Trail

Append-only audit log (never update, never delete):

```sql
CREATE TABLE audit_log (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_type  VARCHAR(100) NOT NULL,
    entity_id    UUID NOT NULL,
    action       VARCHAR(50) NOT NULL,   -- CREATE, UPDATE, STATUS_CHANGE
    actor        VARCHAR(100) NOT NULL,
    old_value    JSONB,
    new_value    JSONB,
    occurred_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

### 4.3 GDPR / PII

- Implement pseudonymisation of `requested_by` field using a lookup table
- Add data retention policies (configurable per tenant)
- Export/erasure API for right-to-be-forgotten requests

---

## Planned Refactors

| Item | Rationale | Priority |
|------|-----------|----------|
| Extract `PostDeployChecklistService` to separate adapter | Pluggable checklist implementations (webhook, script, metric check) | High |
| Replace polling with SSE in frontend | Lower backend load at scale | Medium |
| Add OpenTelemetry to Kafka consumer | Trace correlation across service boundary | Medium |
| ~~Implement `GET /changes/{id}` single-resource endpoint~~ | ~~Needed for detail page routing~~ | ✅ Resolved |
| ~~Add pagination controls to frontend~~ | ~~Currently loads page 0 only~~ | ✅ Resolved |
| Add `CANCELLED` status transition with reason | Needed for operational workflows | Low |

---

## Known Technical Debt

1. **No Outbox pattern** — event loss risk if Kafka is down during write (see Phase 2.1)
2. **PostDeployChecklist is fully simulated** — must be replaced with real integration points
3. ~~**No rate limiting** on `POST /changes` endpoint~~ — ✅ Resolved: `RateLimitFilter` with Bucket4j (100 req/min per IP)
4. **Auth mocked by design for POC** — Backend uses `X-User-Id` header instead of JWT validation; frontend uses a `localStorage` token placeholder instead of OIDC. This was an explicit scope decision to keep the POC focused on the event-driven core. The OAuth2 resource server dependency is already declared and configured — enabling real auth is Phase 2.2, the first post-approval priority.
5. ~~**V2 seed migration** — should be guarded by Spring profile, not always-run Flyway migration~~ — ✅ Resolved: moved to `src/test/resources/db/migration/` (only runs in test context)

---

## Security Gaps Identified by QA (April 2026)

The following gaps were identified during a structured QA+penetration testing pass (CT-SEC-01 to CT-SEC-10). Bugs already fixed inline are marked ✅. Open items are tagged with the target phase.

### Fixed Bugs

| # | Finding | Fix Applied |
|---|---------|------------|
| **BUG-01** | `GlobalExceptionHandler` returned HTTP 500 instead of 405 for unsupported HTTP methods (`PUT`, `DELETE`, `PATCH` on `/api/v1/changes`) | Added `@ExceptionHandler(HttpRequestMethodNotSupportedException.class)` → returns 405 |
| **BUG-02** | `GlobalExceptionHandler` returned HTTP 500 instead of 415 when `Content-Type` header was missing | Added `@ExceptionHandler(HttpMediaTypeNotSupportedException.class)` → returns 415 |
| **BUG-03** | `DeployEventConsumer` only validated `event.payload() == null` but NOT null sub-fields (`deployId`, `changeId`, `result`). A malformed event burned 4 retry attempts before reaching DLT | Added explicit null check for all three required sub-fields; throws `InvalidOrchestratorStateException` (non-retryable) → immediate DLT routing |

### Open Items (Phase 2+)

| # | Finding | Severity | Phase | Action |
|---|---------|----------|-------|--------|
| **SEC-01** | `X-Forwarded-For` header is trusted without validating against a known proxy allowlist. A client can spoof any IP to bypass the rate limiter (100 req/min per IP). | Medium | 2.x | Add `RateLimitFilter` trusted-proxy validation; only honour `X-Forwarded-For` from known reverse proxy IPs (typically nginx/load-balancer CIDR) |
| **SEC-02** | HTTP security response headers absent from API responses: `Strict-Transport-Security`, `Content-Security-Policy`, `X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy`. These belong in the nginx/CDN layer, not Spring Boot. | Medium | 2.x | Add security headers to `nginx.conf` (frontend reverse proxy). For the API, add `X-Content-Type-Options: nosniff` and `X-Frame-Options: DENY` via Spring Security `headers()` DSL |
| **SEC-03** | No explicit `server.tomcat.max-http-form-content-size` or Spring `spring.servlet.multipart.max-request-size` configured for JSON request bodies. Large payloads (>2MB) may cause OOM or slow processing. | Low | 2.x | Add `server.tomcat.max-http-form-content-size=1MB` to `application.yml`; return 413 for oversized bodies |
| **SEC-04** | `X-Correlation-Id` header value is inserted into MDC without sanitisation. A crafted value (newlines, control chars) could cause log injection in plaintext log formats. Current risk is LOW because `logstash-logback-encoder` JSON-encodes all MDC fields automatically. | Low | 2.x | Add UUID-pattern validation in `CorrelationIdFilter`; reject non-UUID values and generate a fresh UUID instead |
| **SEC-05** | Frontend `localStorage` token is a placeholder (see Known Debt #4). `localStorage` is accessible to any JavaScript running on the page (XSS vector). | Low | 2.2 | Replace with `httpOnly` cookie storage once real OIDC/Keycloak integration is implemented in Phase 2.2 |

---

## Itens de Processo Pendentes

Os itens abaixo representam processos de engenharia reconhecidos como boas práticas mas descartados do escopo do POC por decisão de foco:

| Item | Motivação | Fase Sugerida |
|------|-----------|---------------|
| **Registro formal de defeitos** (DISC-13) | Rastreabilidade de bugs encontrados durante desenvolvimento; atualmente gerenciado ad-hoc nas issues do repositório | Pós-aprovação |
| **Quadro Kanban com histórico de tarefas** (DISC-19) | Visibilidade de fluxo de trabalho além do ROADMAP.md; sugerido uso de GitHub Projects | Pós-aprovação |
| **Vídeo de demonstração** (DISC-20) | Material de apresentação do fluxo completo; roteiro disponível em `docs/test-strategy.md` (Seção 7) | Apresentação |
