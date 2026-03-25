# ChangeOps Dashboard

> Technical change management platform with full async event-driven orchestration.

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│  CloudFoundry PaaS                                              │
│  ┌───────────────────┐                                          │
│  │   ChangeOps UI    │  React + TypeScript + Zustand            │
│  │   :3000           │  Polling every 5s                        │
│  └────────┬──────────┘                                          │
└───────────│─────────────────────────────────────────────────────┘
            │ REST HTTPS
┌───────────▼─────────────────────────────────────────────────────┐
│  AWS Cloud                                                      │
│                                                                 │
│  ┌───────────────────┐    Kafka     ┌───────────────────────┐   │
│  │  change-service   │────────────▶│  deploy-orchestrator  │  │
│  │  :8080            │              │  :8081                │  │
│  │  POST /changes    │◀────────────│  Idempotency + DLQ    │  │
│  │  GET  /changes    │  result evt  └──────────┬────────────┘   │
│  └────────┬──────────┘                         │                │
│           │                                    │                │
│  ┌────────▼────────────────────────────────────▼────────┐       │
│  │              PostgreSQL  :5432                        │       │
│  │  changes | change_events | processed_events           │       │
│  └───────────────────────────────────────────────────────┘       │
│                                                                  │
│  Kafka :9092  │  Prometheus :9090  │  Grafana :3001              │
└─────────────────────────────────────────────────────────────────┘
```

---

## Quick Start

### Prerequisites

- Docker 24+ and Docker Compose v2
- Java 17+ (for local backend development)
- Node.js 20+ (for local frontend development)
- GNU Make

### Start the full stack

```bash
# Clone and start everything
git clone https://github.com/your-org/changeops.git
cd changeops

make up
```

This starts:

| Service               | URL                                    |
|-----------------------|----------------------------------------|
| change-service API    | http://localhost:8080                  |
| Swagger UI            | http://localhost:8080/swagger-ui.html  |
| deploy-orchestrator   | http://localhost:8081                  |
| Kafka UI              | http://localhost:8090                  |
| Prometheus            | http://localhost:9090                  |
| Grafana               | http://localhost:3001 (`admin/changeops`) |
| Frontend (dev)        | http://localhost:3000                  |

### Start frontend in dev mode

```bash
cd frontend
npm install
npm run dev
```

---

## Smoke Test

```bash
# 1. Create a change
make smoke

# 2. Copy the changeId from the response, then simulate a deploy result:
CHANGE_ID=<your-change-id> make publish-deploy-event

# 3. Watch the status transition in the UI or query directly:
curl http://localhost:8080/api/v1/changes | python3 -m json.tool
```

---

## Repository Structure

```
changeops/
├── backend/
│   ├── change-service/          # REST API — creates changes, publishes events
│   └── deploy-orchestrator/     # Kafka consumer — processes deploy results
├── frontend/                    # React + TypeScript dashboard
├── infra/
│   ├── postgres/                # DB init SQL
│   ├── prometheus/              # Scrape config
│   └── grafana/                 # Dashboards + provisioning
├── contracts/
│   ├── openapi/                 # REST API specs (OpenAPI 3.1)
│   └── asyncapi/                # Event contracts (AsyncAPI 2.6)
├── .github/workflows/           # CI pipelines (GitHub Actions)
├── docker-compose.yml
└── Makefile
```

---

## Backend Services

### change-service (`:8080`)

Hexagonal architecture (Ports & Adapters):

```
api/controller         → REST endpoints + validation
application/port/in    → Use case interfaces
application/port/out   → Repository + event publisher interfaces
application/service    → Business logic
domain/model           → Change aggregate
domain/event           → ChangePreparedEvent (domain)
infrastructure/        → JPA, Kafka, Security, Observability
```

**Key flows:**
- `POST /changes` → validates → persists with `PREPARED` status → publishes `ChangePreparedEvent`
- `GET /changes` → paginated list with optional `status` / `componentId` filters
- `GET /changes/{id}/events` → chronological event timeline

### deploy-orchestrator (`:8081`)

Consumes `DeployFinishedEvent` from Kafka with:
- **Idempotency** via `processed_events` table (`UNIQUE` constraint prevents duplicate processing)
- **Retry** with exponential backoff (4 attempts: 500ms → 1s → 2s → 4s)
- **DLQ** on `changeops.deploy.finished-dlt` after exhausted retries
- **Post-deploy checklist** (simulated — extend `PostDeployChecklistService`)

---

## Event Flow

```
POST /changes
    │
    ▼ (change-service)
PREPARED status persisted
    │
    ▼
ChangePreparedEvent ──▶ [Kafka: changeops.change.prepared]
                                          │
                              (external deploy system)
                                          │
                                          ▼
                         DeployFinishedEvent ──▶ [Kafka: changeops.deploy.finished]
                                                          │
                                             (deploy-orchestrator)
                                                          │
                                             ┌────────────┴─────────────┐
                                             │ idempotency check         │
                                             │ post-deploy checklist     │
                                             │ update status             │
                                             └────────────┬─────────────┘
                                                          │
                                        ┌─────────────────┴──────────────────┐
                                        ▼                                    ▼
                              ChangeCompletedEvent                 ChangeFailedEvent
                        [changeops.change.result]            [changeops.change.result]
```

---

## Database Schema

```sql
changes          (change_id, title, description, component_id,
                  requested_by, scheduled_at, status, correlation_id,
                  created_at, updated_at)

change_events    (event_id, change_id, event_type, payload JSONB, occurred_at)

processed_events (event_id PK, processed_at, service_name)
                  ↑ UNIQUE constraint guarantees idempotency
```

---

## Observability

### Structured JSON logs

Every log line includes:

```json
{
  "timestamp": "2026-03-19T12:00:00Z",
  "level": "INFO",
  "service": "change-service",
  "correlation_id": "aaaa-...",
  "change_id": "1111-...",
  "message": "Change created"
}
```

### Prometheus metrics

| Metric | Type | Description |
|--------|------|-------------|
| `changes_created_total` | Counter | Total changes created |
| `events_published_total` | Counter | Integration events published |
| `events_consumed_total` | Counter | Events consumed by orchestrator |
| `events_failed_total` | Counter | Processing failures |
| `http_server_requests_seconds` | Histogram | API latency |

Grafana dashboard pre-provisioned at http://localhost:3001

---

## Security

- JWT Bearer token validation via Spring Security OAuth2 Resource Server
- RBAC: `ROLE_OPERATOR` (read + create), `ROLE_ADMIN` (full access)
- `X-User-Id` header accepted as dev fallback in `local`/`test` profiles only — full JWT/OAuth2 integration is ready to activate (see [ROADMAP.md](./docs/ROADMAP.md) §2.2)
- CORS configured for `localhost:*` and `*.changeops.io`
- No PII logged — correlation IDs only

---

## Development

```bash
make test              # Run all tests
make test-backend-unit # Fast unit tests only
make test-backend-it   # Integration tests (needs Docker)
make test-frontend     # Frontend vitest
make lint              # Lint all
make clean             # Remove build artifacts
make db-shell          # Open psql session
make kafka-topics      # List Kafka topics
```

---

## Useful Commands

```bash
# Check change-service health
curl http://localhost:8080/actuator/health | python3 -m json.tool

# View Prometheus metrics
curl http://localhost:8080/actuator/prometheus | grep changes_

# Tail structured logs
docker compose logs -f change-service | python3 -m json.tool

# Consume events from result topic
docker compose exec kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic changeops.change.result \
  --from-beginning
```

---

## Roadmap

See [ROADMAP.md](./docs/ROADMAP.md) for planned evolutions.
