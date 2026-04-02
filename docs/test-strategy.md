# ChangeOps — Estratégia de Testes

> Documento de referência para a estratégia de testes da POC ChangeOps Dashboard.

---

## 1. Visão Geral

A estratégia de testes do ChangeOps prioriza **confiança nos fluxos críticos** sobre métricas quantitativas de cobertura. Os testes são organizados em camadas complementares, cada uma com escopo e ferramentas específicas.

### Pirâmide de Testes

```
         ┌────────────────┐
         │  Integration   │  ← Testcontainers (API → DB → Kafka)
         │   Tests (IT)   │
         ├────────────────┤
         │   Unit Tests   │  ← JUnit 5 + Mockito / Vitest
         │  (domínio +    │
         │   services)    │
         └────────────────┘
         ┌────────────────┐
         │   Frontend     │  ← Vitest + Testing Library
         │  Component     │
         │   Tests        │
         └────────────────┘
```

---

## 2. Ferramentas

| Camada | Ferramenta | Justificativa |
|--------|-----------|---------------|
| **Unitário (Backend)** | JUnit 5 + Mockito | Padrão de facto para Java; integração nativa com Spring Boot; suporte a `@ExtendWith(MockitoExtension.class)` para testes sem contexto Spring |
| **Integração (Backend)** | Testcontainers (PostgreSQL 16 + Kafka 7.6.0) | Containers reais garantem fidelidade ao ambiente de produção; sem mocks de infraestrutura; suporte a `@DynamicPropertySource` |
| **Frontend** | Vitest + React Testing Library | Vitest: executor rápido com suporte a ESM e TypeScript; Testing Library: testes focados no comportamento do usuário, não na implementação |
| **Mocking HTTP** | MSW (Mock Service Worker) | Intercepta requisições no nível de rede; mais realista que mocks de módulo |
| **Asserções** | AssertJ (backend) / Jest matchers (frontend) | AssertJ: API fluente e expressiva; built-in no Vitest para frontend |
| **Async Verification** | Awaitility | Verificação de condições assíncronas com timeout configurável; essencial para testes de consumidores Kafka |

---

## 3. Escopo por Camada

### 3.1 Testes Unitários — Backend

**Objetivo:** Validar regras de domínio, transições de estado e lógica de serviço isoladamente.

| Classe de Teste | O que cobre |
|----------------|-------------|
| `ChangeTest` | Ciclo de vida do aggregate: `create()`, `complete()`, `fail()`, `cancel()`, transições inválidas, geração de eventos |
| `CreateChangeServiceTest` | Fluxo de criação: delegação a ports, publicação de eventos, persistência na timeline, incremento de métrica |
| `ListChangesServiceTest` | Delegação ao port de consulta, mapeamento de resultados, passagem de filtros |
| `GetChangeEventsServiceTest` | Consulta de timeline, validação de existência do change, tratamento de lista vazia |
| `CorrelationIdFilterTest` | Propagação de correlation_id via MDC, geração quando ausente, limpeza após request |
| `ChangeControllerTest` | Endpoints REST via MockMvc: criação com payload válido/inválido, listagem paginada, consulta por ID, consulta de timeline |
| `GetChangeServiceTest` | Consulta de mudança por ID: delegação ao port, mapeamento de resultado, tratamento de `ChangeNotFoundException` |
| `RateLimitFilterTest` | Rate limiting: aprovação dentro do limite, rejeição `429` ao exceder 100 req/min por IP, reset de bucket |

**Padrão:** `@ExtendWith(MockitoExtension.class)` com `@Mock` nos ports de saída. Sem contexto Spring.

### 3.2 Testes Unitários — deploy-orchestrator

| Classe de Teste | O que cobre |
|----------------|-------------|
| `ProcessDeployResultServiceTest` | Orquestração completa: idempotência, checklist, atualização de status, publicação de evento, descarte de duplicatas |
| `DeployEventConsumerTest` | Null event handling (→ DLT), null payload handling (event não-nulo com payload nulo → DLT), DLT handler counters (String + null + byte[] + payload truncado >500 chars), retry counter por tópico, proteção contra poison pill |
| `KafkaResultPublisherAdapterTest` | Publicação com sucesso incrementa counter; falha de publicação aciona fallback para DLQ |

### 3.3 Testes de Integração — Backend

**Objetivo:** Validar o fluxo completo API → DB → Kafka com infraestrutura real via Testcontainers.

| Classe de Teste | O que cobre |
|----------------|-------------|
| `CreateChangeIT` | `POST /changes` → persistência → evento no Kafka; validação 400; listagem paginada |
| `DeployEventConsumerIT` | Consumo de `DeployFinishedEvent` → status COMPLETED/FAILED; idempotência (duplicata sem efeito); persistência de evento na timeline; poison pill (UUID malformado → DLT sem loop infinito); `changeId` inexistente → DLT após retries esgotados |

**Infra:** PostgreSQL 16-alpine + Confluent Kafka 7.6.0 via `@Container` + `@DynamicPropertySource`.

### 3.4 Testes de Consumidores de Eventos

**Objetivo:** Validar comportamento em cenários de falha, retry e DLQ.

| Cenário | Verificação |
|---------|------------|
| Deploy com sucesso | Status `COMPLETED` + `ChangeCompletedEvent` publicado |
| Deploy com falha | Status `FAILED` + `ChangeFailedEvent` com reason |
| Evento duplicado | Estado inalterado, `processed_events` count = 1 |
| Falha na publicação | Retry com backoff → DLQ após esgotamento |

### 3.5 Testes Frontend

**Objetivo:** Validar renderização de componentes, interações do usuário e integração com API.

| Classe de Teste | O que cobre |
|----------------|-------------|
| `ChangeForm.test.tsx` | Renderização de campos, submissão válida com callback, exibição de erros de campo sem limpar formulário |
| `ChangeList.test.tsx` | Renderização da tabela, estado vazio, seleção de row para timeline, paginação |
| `ChangeTimeline.test.tsx` | Renderização de eventos com cores por tipo, estado vazio, loading |
| `changeService.test.ts` | Chamadas HTTP: create, list, getEvents — mock de axios |

---

## 4. Trade-offs

| Decisão | Justificativa |
|---------|---------------|
| **Testcontainers vs H2** | H2 não suporta `ON CONFLICT DO NOTHING` (PostgreSQL-specific), JSONB, nem triggers. Testcontainers garante paridade com produção |
| **Cobertura qualitativa vs quantitativa** | Foco em fluxos críticos (criação, idempotência, retry/DLQ) ao invés de meta numérica de coverage. Cada teste tem propósito claro |
| **Sem testes E2E** | Trade-off consciente: testes E2E (Cypress/Playwright) adicionariam confiança mas com alto custo de manutenção e tempo de execução. Os testes de integração + frontend cobrem os cenários da POC |
| **MSW vs mock de módulo** | MSW intercepta no nível HTTP, mais realista. Mock de módulo (`vi.mock`) usado quando MSW é overhead (ex: testes simples de componente) |
| **Awaitility vs Thread.sleep** | Awaitility com timeout configurável é mais robusto que sleeps fixos para verificação de processamento assíncrono Kafka |

---

## 5. Execução

```bash
# Backend — todos os testes (unit + integration)
cd backend/change-service && mvn test
cd backend/deploy-orchestrator && mvn test

# Frontend — todos os testes
cd frontend && npm test

# Ou via task do VS Code
# Task: test-all-backend
```

**Pré-requisitos para testes de integração:** Docker daemon rodando (Testcontainers precisa de acesso ao Docker).

---

## 6. Cobertura de Cenários Obrigatórios (RFP)

| Cenário RFP | Teste | Status |
|-------------|-------|--------|
| Criação com dados válidos → 201 + evento | `CreateChangeIT.shouldCreate_whenPayloadIsValid` | ✅ |
| Criação sem campo obrigatório → 400 | `CreateChangeIT` (validação) | ✅ |
| Deploy com sucesso → COMPLETED + ChangeCompletedEvent | `DeployEventConsumerIT.shouldMarkCompleted` | ✅ |
| Deploy com falha → FAILED + ChangeFailedEvent | `DeployEventConsumerIT.shouldMarkFailed` | ✅ |
| Mesmo evento 2x → estado inalterado | `DeployEventConsumerIT.shouldBeIdempotent` | ✅ |
| Falha na publicação → retry → DLQ | `ProcessDeployResultServiceTest` + IT | ✅ |
| Poison pill (falha de desserialização) → DLT sem loop infinito | `DeployEventConsumerIT.shouldSendToDlt_whenMessageHasMalformedPayload` | ✅ |
| changeId inexistente → DLT (0 retries) | `ProcessDeployResultServiceTest.shouldThrowNonRetryableException_whenChangeIdNotFound` | ✅ |

---

## 7. Roteiro de Testes Manuais

> **Objetivo:** Validar os cenários esperados do sistema com evidências, de forma objetiva e rápida.
> **Pré-requisito:** Stack rodando (`docker compose up --build -d` ou `make up`). Requer `SPRING_PROFILES_ACTIVE=local` (padrão no docker-compose) — o header `X-User-Id` utilizado nos testes só é aceito nesse perfil.
> **Ferramenta sugerida:** Postman, Insomnia ou cURL.

### URLs de Referência

| Serviço | URL |
|---------|-----|
| Change Service API | http://localhost:8080/api/v1 |
| Swagger UI (change-service) | http://localhost:8080/swagger-ui.html |
| Deploy Orchestrator | http://localhost:8081 |
| Swagger UI (orchestrator) | http://localhost:8081/swagger-ui.html |
| Kafka UI | http://localhost:8090 |
| Grafana | http://localhost:3001 (login: `admin` / `changeops`) |
| Prometheus | http://localhost:9090 |
| Frontend | http://localhost:3000 |

### Ordem de Execução Recomendada

1. **CT-01** → Validar que os serviços estão saudáveis antes de tudo
2. **CT-02** → Criar a primeira mudança (base para CT-05 a CT-10)
3. **CT-03, CT-04** → Validações de entrada
4. **CT-05, CT-06, CT-07, CT-08** → Consultas REST
5. **CT-09** → Confirmar que mudança permanece PREPARED sem evento de deploy
6. **CT-10** → Fluxo completo de sucesso
7. **CT-11** → Fluxo completo de falha
8. **CT-12** → Idempotência
9. **CT-13** → DLT/retries
10. **CT-14** → Observabilidade (após ter dados dos testes anteriores)
11. **CT-15** → Frontend (fechamento visual)

### CT-01 — Health Check dos serviços

**Change Service:**

`GET http://localhost:8080/actuator/health`

**Deploy Orchestrator:**

`GET http://localhost:8081/actuator/health`

**Resultado esperado em ambos:**
- Status HTTP: `200 OK`
- Body: `{ "status": "UP" }` com detalhes dos componentes (db, kafka, diskSpace)

**Evidenciar:** Screenshot dos dois responses `200` com `status: "UP"`.

### CT-02 — Criar mudança com dados válidos

**Endpoint:** `POST http://localhost:8080/api/v1/changes`

**Headers:**
```
Content-Type: application/json
X-User-Id: tester-001
```

**Body:**
```json
{
  "title": "Deploy payment-service v3.0",
  "description": "Upgrade do gateway de pagamento",
  "componentId": "payment-service",
  "requestedBy": "tester-001",
  "scheduledAt": "2026-06-01T10:00:00Z"
}
```

**Resultado esperado:**
- Status HTTP: `201 Created`
- Body contém `changeId`, `status: "PREPARED"`, `correlationId`, `createdAt`
- Header `Location` com URL do recurso criado

**Evidenciar:**
- Screenshot do response `201` com o body completo
- Anotar o `changeId` retornado — será reutilizado nos testes CT-04 a CT-10

**Verificação adicional — Kafka UI:**
- Acessar http://localhost:8090 → tópico `changeops.change.prepared`
- Verificar que uma mensagem `ChangePreparedEvent` foi publicada com o `changeId` criado

**Evidenciar:** Screenshot da mensagem no Kafka UI mostrando o `changeId`.

### CT-03 — Criar mudança sem campo obrigatório

**Endpoint:** `POST http://localhost:8080/api/v1/changes`

**Headers:**
```
Content-Type: application/json
```

**Body (sem `title`):**
```json
{
  "description": "Teste de validação",
  "componentId": "auth-service",
  "requestedBy": "tester-001",
  "scheduledAt": "2026-06-01T10:00:00Z"
}
```

**Resultado esperado:**
- Status HTTP: `400 Bad Request`
- Body no formato ProblemDetail com `"fields": { "title": "title is required" }`

**Evidenciar:** Screenshot do response `400` mostrando a mensagem de validação do campo.

### CT-04 — Criar mudança com data no passado

**Endpoint:** `POST http://localhost:8080/api/v1/changes`

**Headers:**
```
Content-Type: application/json
```

**Body:**
```json
{
  "title": "Deploy auth-service v3.0.5",
  "description": "Performance improvements",
  "componentId": "auth-service",
  "requestedBy": "tester-001",
  "scheduledAt": "2025-01-01T09:15:00Z"
}
```

**Resultado esperado:**
- Status HTTP: `400 Bad Request`
- Body com `"fields": { "scheduledAt": "scheduledAt must be a future date" }`

**Evidenciar:** Screenshot do response `400`.

### CT-05 — Listar mudanças com paginação e filtro

**Endpoint:** `GET http://localhost:8080/api/v1/changes?status=PREPARED&page=0&size=5`

**Resultado esperado:**
- Status HTTP: `200 OK`
- Body paginado com: `content[]`, `totalElements`, `totalPages`, `number`, `size`
- Todos os itens em `content` possuem `status: "PREPARED"`

**Evidenciar:** Screenshot do response `200` com a lista paginada e campos de paginação visíveis.

### CT-06 — Consultar mudança por ID

**Endpoint:** `GET http://localhost:8080/api/v1/changes/{changeId}`

> Substituir `{changeId}` pelo valor obtido no CT-02.

**Resultado esperado:**
- Status HTTP: `200 OK`
- Body com todos os campos: `changeId`, `title`, `description`, `componentId`, `requestedBy`, `status`, `correlationId`, `scheduledAt`, `createdAt`, `updatedAt`

**Evidenciar:** Screenshot do response `200` com detalhes completos.

### CT-07 — Consultar mudança inexistente

**Endpoint:** `GET http://localhost:8080/api/v1/changes/00000000-0000-0000-0000-000000000000`

**Resultado esperado:**
- Status HTTP: `404 Not Found`

**Evidenciar:** Screenshot do response `404`.

### CT-08 — Consultar timeline de eventos

**Endpoint:** `GET http://localhost:8080/api/v1/changes/{changeId}/events`

> Substituir `{changeId}` pelo valor obtido no CT-02.

**Resultado esperado:**
- Status HTTP: `200 OK`
- Array com pelo menos 1 evento do tipo `ChangePreparedEvent`
- Cada evento contém: `eventId`, `changeId`, `eventType`, `payload`, `occurredAt`

**Evidenciar:** Screenshot do response `200` com a lista de eventos.

### CT-09 — Mudança permanece PREPARED sem evento de deploy

**Endpoint:** `POST http://localhost:8080/api/v1/changes`

**Headers:**
```
Content-Type: application/json
X-User-Id: tester-001
```

**Body:**
```json
{
  "title": "Deploy inventory-service v1.5",
  "description": "Rollout de nova versão do serviço de inventário",
  "componentId": "inventory-service",
  "requestedBy": "tester-001",
  "scheduledAt": "2026-09-01T10:00:00Z"
}
```

**Resultado esperado:**
- Status HTTP: `201 Created`
- Body contém `changeId` e `status: "PREPARED"`

Anotar o `changeId` retornado.

**Passo 2 — Aguardar 5 segundos** (sem publicar nenhum evento de deploy).

**Passo 3 — Verificar que o status não mudou:**

`GET http://localhost:8080/api/v1/changes/{changeId}`

**Resultado esperado:**
- Status HTTP: `200 OK`
- `status: "PREPARED"` (inalterado)
- Campos `title`, `componentId` e `scheduledAt` presentes

**Passo 4 — Verificar timeline com apenas 1 evento:**

`GET http://localhost:8080/api/v1/changes/{changeId}/events`

**Resultado esperado:**
- Array com **exatamente 1 evento** de tipo `ChangePreparedEvent`
- Nenhum `ChangeCompletedEvent` ou `ChangeFailedEvent` presente

**Evidenciar:**
1. Screenshot do response `201` com o `changeId`
2. Screenshot do GET após 5s mostrando `status: "PREPARED"` inalterado
3. Screenshot da timeline com apenas `ChangePreparedEvent`

### CT-10 — Deploy com sucesso → COMPLETED

> Pré-requisito: `changeId` do CT-02 em status `PREPARED`.

**Passo 1 — Publicar evento no Kafka UI:**
- Acessar http://localhost:8090
- Navegar para tópico `changeops.deploy.finished` → botão "Produce Message"
- **Key:** (deixar vazio)
- **Value:**

```json
{
  "eventType": "DeployFinishedEvent",
  "version": "1.0",
  "correlationId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "occurredAt": "2026-03-25T14:00:00Z",
  "payload": {
    "deployId": "d0d0d0d0-d0d0-d0d0-d0d0-d0d0d0d0d0d0",
    "changeId": "<CHANGE_ID_DO_CT02>",
    "result": "SUCCESS",
    "executedAt": "2026-03-25T14:00:00Z"
  }
}
```

**Passo 2 — Verificar status atualizado (aguardar ~2s):**

`GET http://localhost:8080/api/v1/changes/{changeId}`

- Esperado: `status: "COMPLETED"`

**Passo 3 — Verificar evento publicado:**
- Kafka UI → tópico `changeops.change.result` → deve conter `ChangeCompletedEvent` com o `changeId`

**Passo 4 — Verificar timeline:**

`GET http://localhost:8080/api/v1/changes/{changeId}/events`

- Deve conter `ChangePreparedEvent` e `ChangeCompletedEvent`

**Evidenciar:**
1. Screenshot da mensagem sendo produzida no Kafka UI
2. Screenshot do GET do change com `status: "COMPLETED"`
3. Screenshot da mensagem `ChangeCompletedEvent` no tópico `changeops.change.result`
4. Screenshot da timeline com os dois eventos

### CT-11 — Deploy com falha → FAILED

**Passo 1 — Criar nova mudança:**

`POST http://localhost:8080/api/v1/changes`
```json
{
  "title": "Deploy order-service v2.0",
  "description": "Teste de falha no deploy",
  "componentId": "order-service",
  "requestedBy": "tester-001",
  "scheduledAt": "2026-07-01T10:00:00Z"
}
```
Anotar o `changeId` retornado.

**Passo 2 — Publicar evento FAILURE:**
- Kafka UI → tópico `changeops.deploy.finished` → "Produce Message"
- **Value:**

```json
{
  "eventType": "DeployFinishedEvent",
  "version": "1.0",
  "correlationId": "f1f2f3f4-f5f6-f7f8-f9fa-fbfcfdfeff00",
  "occurredAt": "2026-03-25T14:10:00Z",
  "payload": {
    "deployId": "e1e1e1e1-e1e1-e1e1-e1e1-e1e1e1e1e1e1",
    "changeId": "<CHANGE_ID_NOVO>",
    "result": "FAILURE",
    "executedAt": "2026-03-25T14:10:00Z"
  }
}
```

**Passo 3 — Verificar (aguardar ~2s):**

`GET http://localhost:8080/api/v1/changes/{changeId}`

- Esperado: `status: "FAILED"`

`GET http://localhost:8080/api/v1/changes/{changeId}/events`

- Esperado: `ChangePreparedEvent` + `ChangeFailedEvent`

**Evidenciar:**
1. Screenshot do GET com `status: "FAILED"`
2. Screenshot da timeline com `ChangeFailedEvent`

### CT-12 — Idempotência (mesmo evento duas vezes)

> Pré-requisito: Criar uma nova mudança (seguir o Passo 1 do CT-11) e anotar o `changeId`.

**Passo 1 — Publicar DeployFinishedEvent SUCCESS:**
- Kafka UI → tópico `changeops.deploy.finished` → "Produce Message"

```json
{
  "eventType": "DeployFinishedEvent",
  "version": "1.0",
  "correlationId": "c1c2c3c4-c5c6-c7c8-c9ca-cbcccdcecfc0",
  "occurredAt": "2026-03-25T14:20:00Z",
  "payload": {
    "deployId": "b2b2b2b2-b2b2-b2b2-b2b2-b2b2b2b2b2b2",
    "changeId": "<CHANGE_ID_NOVO>",
    "result": "SUCCESS",
    "executedAt": "2026-03-25T14:20:00Z"
  }
}
```

**Passo 2 — Aguardar ~2s** e confirmar `status: "COMPLETED"`.

**Passo 3 — Publicar o MESMO payload novamente** (mesmo `deployId`, mesmo `changeId`).

**Passo 4 — Verificar (aguardar ~2s):**

`GET http://localhost:8080/api/v1/changes/{changeId}`
- Status permanece `COMPLETED` (não alterou)

`GET http://localhost:8080/api/v1/changes/{changeId}/events`
- Timeline contém apenas **1 `ChangeCompletedEvent`** (não duplicou)

**Evidenciar:**
1. Screenshot mostrando que o status continua `COMPLETED`
2. Screenshot da timeline sem duplicação de eventos

### CT-13 — DLT (evento para changeId inexistente)

**Publicar evento com changeId inexistente:**
- Kafka UI → tópico `changeops.deploy.finished` → "Produce Message"

```json
{
  "eventType": "DeployFinishedEvent",
  "version": "1.0",
  "correlationId": "deadbeef-dead-beef-dead-beefdeadbeef",
  "occurredAt": "2026-03-25T14:30:00Z",
  "payload": {
    "deployId": "cc000000-0000-0000-0000-000000000001",
    "changeId": "00000000-0000-0000-0000-000000000099",
    "result": "SUCCESS",
    "executedAt": "2026-03-25T14:30:00Z"
  }
}
```

**Aguardar ~30-60 segundos** (tempo necessário para as 4 tentativas com backoff exponencial).

**Verificar no Kafka UI:**
- Tópicos `changeops.deploy.finished-retry-0`, `retry-1`, `retry-2` devem ter recebido a mensagem durante o processo de retry
- Tópico `changeops.deploy.finished-dlt` deve conter a mensagem após exaustão das tentativas

**Evidenciar:**
1. Screenshot do tópico `changeops.deploy.finished-dlt` com a mensagem
2. Screenshot dos tópicos de retry mostrando mensagens processadas

### CT-13B — Poison Pill (UUID malformado no payload)

> Verifica que um payload com UUID inválido (poison pill) é roteado ao DLT sem causar loop infinito, e que o consumer continua funcional após o incidente.

**Passo 1 — Publicar poison pill no Kafka UI:**
- Acessar http://localhost:8090
- Navegar para tópico `changeops.deploy.finished` → "Produce Message"
- **Value:**

```json
{
  "eventType": "DeployFinishedEvent",
  "version": "1.0",
  "correlationId": "f10f409d-2eee-4053-82f2-80fac03fd65b",
  "occurredAt": "2026-03-23T11:42:00Z",
  "payload": {
    "deployId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "changeId": "e69a604a-d54b-4915-9504-c7c28685d52411",
    "result": "SUCCESS",
    "executedAt": "2026-03-23T11:42:00Z"
  }
}
```

> Nota: `changeId` tem 37 caracteres (UUID inválido) — causa falha no `JsonDeserializer`.

**Passo 2 — Aguardar ~10 segundos.**

**Passo 3 — Verificar DLT:**
- Kafka UI → tópico `changeops.deploy.finished-dlt` → deve conter a mensagem (sem loop infinito no consumer principal)

**Passo 4 — Verificar que o consumer continua funcional:**
- Criar uma nova mudança via `POST /api/v1/changes` → esperado: `201 Created`
- Publicar um `DeployFinishedEvent` SUCCESS válido para o novo `changeId` → esperado: status `COMPLETED`

**Passo 5 — Verificar métricas:**
- `GET http://localhost:8081/actuator/prometheus`
- `events_dlt_total` > 0
- `events_failed_total` > 0
- `events_retries_total` = 0 (exceção em `exclude` do `@RetryableTopic`, sem retries)

**Passo 6 — Verificar que o poison pill NÃO foi registrado em `processed_events`:**
- `make db-shell` → `SELECT COUNT(*) FROM processed_events WHERE event_id = 'a1b2c3d4-e5f6-7890-abcd-ef1234567890';`
- Esperado: `0`

**Evidenciar:**
1. Screenshot da mensagem no tópico DLT
2. Screenshot do consumer funcional após o poison pill (nova mudança criada com sucesso)
3. Screenshot das métricas no actuator
4. Screenshot do `processed_events` sem registro do poison pill

### CT-14 — Observabilidade (Grafana + Prometheus)

> Executar após os testes anteriores para ter dados suficientes nos painéis.

**Passo 1 — Grafana:**
- Acessar http://localhost:3001 (login: `admin` / `changeops`)
- Navegar para **Dashboards → ChangeOps Dashboard**
- Verificar que os painéis mostram dados:
  - **"Changes - Created (total)"** — valor > 0
  - **"Changes - Completed"** — valor > 0 (após CT-10)
  - **"Changes - Failed"** — valor > 0 (após CT-11)
  - **"Changes - Prepared"** — stats por status
  - **"Changes - By Status"** — pizza com distribuição de status
  - **"Events - Published (total)"** — valor > 0
  - **"Events - Consumed (total)"** — valor > 0
  - **"Events - Retries (total)"** — valor ≥ 0 (tentativas de reprocessamento)
  - **"Events - Failed (total)"** — valor ≥ 0 (falhas permanentes, após CT-13)
  - **"Events - DLT (total)"** — valor ≥ 0 (após CT-13)
  - **"Events - Discarded (total)"** — valor ≥ 0 (após CT-12)
  - **"API Latency (p95)"** — gráfico com dados de latência
  - **"Events - Rate (per min)"** — gráfico com linhas Published/Consumed/Retries/Failed/DLT/Discarded
  - **"Orchestration Latency (p95)"** — gráfico com dados de latência do orquestrador

**Passo 2 — Prometheus:**
- Acessar http://localhost:9090
- Executar queries:
  - `changes_by_status` → retorna gauges por status
  - `events_published_total` → valor > 0
  - `events_consumed_total` → valor > 0

**Evidenciar:**
1. Screenshot do dashboard Grafana completo (todos os painéis visíveis)
2. Screenshot de uma query Prometheus com resultado

### CT-15 — Frontend (Interface Web)

**Passo 1 — Acessar** http://localhost:3000

**Passo 2 — Criar mudança pelo formulário:**
- Preencher: Title, Description, Component ID, Scheduled At (data futura)
- Clicar no botão de criação
- Verificar que a mudança aparece na lista com badge de status `PREPARED`

**Passo 3 — Verificar polling automático:**
- Publicar um `DeployFinishedEvent` SUCCESS via Kafka UI para o `changeId` criado (seguir CT-10 Passo 1)
- Aguardar até **5 segundos** sem recarregar a página
- O badge de status deve mudar automaticamente para `COMPLETED`

**Passo 4 — Verificar timeline:**
- Clicar na linha da mudança na tabela
- Verificar que a timeline lateral exibe os eventos com ícones coloridos e timestamps

**Evidenciar:**
1. Screenshot do formulário preenchido
2. Screenshot da lista com a mudança em status `PREPARED`
3. Screenshot da lista com a mudança em status `COMPLETED` (após polling)
4. Screenshot da timeline de eventos aberta

### Resumo de Evidências

| Teste | Cenário | O que evidenciar |
|-------|---------|-----------------|
| CT-01 | Health check | Ambos os serviços com `status: "UP"` |
| CT-02 | Criação válida | Response 201 + mensagem `ChangePreparedEvent` no Kafka |
| CT-03 | Campo obrigatório ausente | Response 400 com campo e mensagem de erro |
| CT-04 | Data no passado | Response 400 com mensagem de validação |
| CT-05 | Listagem paginada | Response 200 com `content[]` e metadados de paginação |
| CT-06 | Consulta por ID | Response 200 com detalhes completos |
| CT-07 | ID inexistente | Response 404 |
| CT-08 | Timeline de eventos | Response 200 com `ChangePreparedEvent` |
| CT-09 | PREPARED sem deploy | Screenshot do GET após 5s com status PREPARED + timeline com 1 evento |
| CT-10 | Deploy SUCCESS | Status `COMPLETED` + `ChangeCompletedEvent` no Kafka + timeline com 2 eventos |
| CT-11 | Deploy FAILURE | Status `FAILED` + `ChangeFailedEvent` na timeline |
| CT-12 | Idempotência | Status inalterado + sem duplicação na timeline |
| CT-13 | DLT após retries | Mensagem no tópico `changeops.deploy.finished-dlt` |
| CT-13B | Poison pill (UUID malformado) | Mensagem no DLT sem loop infinito, consumer funcional, métricas incrementadas, sem registro em `processed_events` |
| CT-14 | Observabilidade | Dashboard Grafana completo + query Prometheus |
| CT-15 | Frontend | Formulário, lista, polling automático, timeline |
