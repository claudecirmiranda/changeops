# ChangeOps — Roteiro de Testes Manuais

> **Objetivo:** Validar os cenários esperados do sistema com evidências, de forma objetiva e rápida.  
> **Pré-requisito:** Stack rodando (`docker compose up --build -d` ou `make up`). Requer `SPRING_PROFILES_ACTIVE=local` (padrão no docker-compose) — o header `X-User-Id` utilizado nos testes só é aceito nesse perfil.  
> **Ferramenta sugerida:** Postman, Insomnia ou cURL.

---

## URLs de Referência

| Serviço | URL |
|---------|-----|
| Change Service API | http://localhost:8080/api/v1 |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| Deploy Orchestrator | http://localhost:8081 |
| Kafka UI | http://localhost:8090 |
| Grafana | http://localhost:3001 (login: `admin` / `changeops`) |
| Prometheus | http://localhost:9090 |
| Frontend | http://localhost:3000 |

---

## Ordem de Execução Recomendada

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

---

---

## CT-01 — Health Check dos serviços

**Change Service:**

`GET http://localhost:8080/actuator/health`

**Deploy Orchestrator:**

`GET http://localhost:8081/actuator/health`

**Resultado esperado em ambos:**
- Status HTTP: `200 OK`
- Body: `{ "status": "UP" }` com detalhes dos componentes (db, kafka, diskSpace)

**Evidenciar:** Screenshot dos dois responses `200` com `status: "UP"`.

---

## CT-02 — Criar mudança com dados válidos

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

---

## CT-03 — Criar mudança sem campo obrigatório

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

---

## CT-04 — Criar mudança com data no passado

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

---

## CT-05 — Listar mudanças com paginação e filtro

**Endpoint:** `GET http://localhost:8080/api/v1/changes?status=PREPARED&page=0&size=5`

**Resultado esperado:**
- Status HTTP: `200 OK`
- Body paginado com: `content[]`, `totalElements`, `totalPages`, `number`, `size`
- Todos os itens em `content` possuem `status: "PREPARED"`

**Evidenciar:** Screenshot do response `200` com a lista paginada e campos de paginação visíveis.

---

## CT-06 — Consultar mudança por ID

**Endpoint:** `GET http://localhost:8080/api/v1/changes/{changeId}`

> Substituir `{changeId}` pelo valor obtido no CT-01.

**Resultado esperado:**
- Status HTTP: `200 OK`
- Body com todos os campos: `changeId`, `title`, `description`, `componentId`, `requestedBy`, `status`, `correlationId`, `scheduledAt`, `createdAt`, `updatedAt`

**Evidenciar:** Screenshot do response `200` com detalhes completos.

---

## CT-07 — Consultar mudança inexistente

**Endpoint:** `GET http://localhost:8080/api/v1/changes/00000000-0000-0000-0000-000000000000`

**Resultado esperado:**
- Status HTTP: `404 Not Found`

**Evidenciar:** Screenshot do response `404`.

---

## CT-08 — Consultar timeline de eventos

**Endpoint:** `GET http://localhost:8080/api/v1/changes/{changeId}/events`

> Substituir `{changeId}` pelo valor obtido no CT-01.

**Resultado esperado:**
- Status HTTP: `200 OK`
- Array com pelo menos 1 evento do tipo `ChangePreparedEvent`
- Cada evento contém: `eventId`, `changeId`, `eventType`, `payload`, `occurredAt`

**Evidenciar:** Screenshot do response `200` com a lista de eventos.

---

## CT-09 — Mudança permanece PREPARED sem evento de deploy

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

---

## CT-10 — Deploy com sucesso → COMPLETED

> Pré-requisito: `changeId` do CT-01 em status `PREPARED`.

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

---

## CT-11 — Deploy com falha → FAILED

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

---

## CT-12 — Idempotência (mesmo evento duas vezes)

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

---

## CT-13 — DLT (evento para changeId inexistente)

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

---

## CT-14 — Observabilidade (Grafana + Prometheus)

> Executar após os testes anteriores para ter dados suficientes nos painéis.

**Passo 1 — Grafana:**
- Acessar http://localhost:3001 (login: `admin` / `changeops`)
- Navegar para **Dashboards → ChangeOps Dashboard**
- Verificar que os painéis mostram dados:
  - **"Changes Created (total)"** — valor > 0
  - **"Events Published (total)"** — valor > 0
  - **"Events Consumed (total)"** — valor > 0
  - **"Events Failed (total)"** — valor > 0 (após o CT-13)
  - **"Completed"** / **"Failed"** / **"Prepared"** — stats por status
  - **"Changes by Status"** — pizza com distribuição de status
  - **"API Request Duration (p95)"** — gráfico com dados de latência
  - **"Events Published Rate (per min)"** — gráfico com linhas Published/Consumed/Failed

**Passo 2 — Prometheus:**
- Acessar http://localhost:9090
- Executar queries:
  - `changes_by_status` → retorna gauges por status
  - `events_published_total` → valor > 0
  - `events_consumed_total` → valor > 0

**Evidenciar:**
1. Screenshot do dashboard Grafana completo (todos os painéis visíveis)
2. Screenshot de uma query Prometheus com resultado

---

## CT-15 — Frontend (Interface Web)

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

---

## Resumo de Evidências

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
| CT-14 | Observabilidade | Dashboard Grafana completo + query Prometheus |
| CT-15 | Frontend | Formulário, lista, polling automático, timeline |
