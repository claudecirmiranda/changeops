# ChangeOps Dashboard — Preparação para Apresentação ao Cliente

> **Data:** Março 2026  
> **Audiência:** Time técnico e stakeholders da CONTRATANTE  
> **Objetivo:** Demonstrar maturidade arquitetural e capacidade de entrega de software distribuído de alta complexidade

---

## Índice

1. [Contexto e Objetivo da POC](#1-contexto-e-objetivo-da-poc)
2. [O que será demonstrado](#2-o-que-será-demonstrado)
3. [Visão Arquitetural](#3-visão-arquitetural)
4. [Stack Tecnológica](#4-stack-tecnológica)
5. [Fluxos de Negócio](#5-fluxos-de-negócio)
6. [Qualidade de Código e Testabilidade](#6-qualidade-de-código-e-testabilidade)
7. [Resiliência e Confiabilidade](#7-resiliência-e-confiabilidade)
8. [Observabilidade em Tempo Real](#8-observabilidade-em-tempo-real)
9. [Segurança](#9-segurança)
10. [Contratos de API e Eventos](#10-contratos-de-api-e-eventos)
11. [Decisões Arquiteturais (ADRs)](#11-decisões-arquiteturais-adrs)
12. [Tradeoffs Estratégicos](#12-tradeoffs-estratégicos)
13. [Roadmap Evolutivo](#13-roadmap-evolutivo)
14. [Roteiro de Demonstração](#14-roteiro-de-demonstração)
15. [Checklist Pré-Apresentação](#15-checklist-pré-apresentação)

---

## 1. Contexto e Objetivo da POC

### O Problema

Ambientes corporativos de alto volume operam com dezenas de deploys simultâneos por dia, cada um exigindo rastreabilidade, validação pós-execução e visibilidade de status em tempo real. Sistemas legados gerenciam este fluxo de forma síncrona, acoplada e sem auditoria estruturada — criando gargalos, perdas de rastreabilidade e ausência de resiliência a falhas intermediárias.

### A Solução

O **ChangeOps Dashboard** demonstra a capacidade de construir esse ciclo de vida com:

- **Arquitetura desacoplada** (dois microserviços independentes)
- **Comunicação assíncrona** via eventos Kafka
- **Rastreabilidade ponta a ponta** com `correlation_id` propagado por toda a stack
- **Confiabilidade** com idempotência, retries automáticos e Dead Letter Queue
- **Observabilidade** com logs estruturados, métricas Prometheus e dashboards Grafana
- **Interface web** em tempo real com atualização automática de status

### Escopo da POC

| Cenário | Descrição |
|---------|-----------|
| **Fluxo 1** | Criação de mudança técnica → publicação de evento de domínio (`ChangePreparedEvent`) |
| **Fluxo 2** | Consumo do resultado de deploy → validação pós-execução → atualização de status → publicação de evento de conclusão |

---

## 2. O que será demonstrado

### Interface Web (localhost:3000)

- Criação de uma mudança técnica via formulário com validação em tempo real
- Listagem paginada de mudanças com filtros por status
- Timeline de eventos por mudança (clique em uma linha da tabela)
- Atualização automática de status (pull a cada 5 segundos)
- Cards de estatísticas: Total, Preparadas, Concluídas, Falhas

### API REST (localhost:8080)

```bash
# Criação de mudança
POST http://localhost:8080/api/v1/changes
Content-Type: application/json
X-User-Id: demo-operator

{
  "title": "Atualização do módulo de pagamentos v2.1.0",
  "description": "Deploy da versão 2.1.0 com correção de encoding em faturas internacionais",
  "componentId": "payment-service",
  "scheduledAt": "2026-03-26T10:00:00Z"
}

# Listagem com status filter
GET http://localhost:8080/api/v1/changes?status=PREPARED&page=0&size=10

# Timeline de eventos de uma mudança
GET http://localhost:8080/api/v1/changes/{changeId}/events
```

### Fluxo Assíncrono (Kafka UI: localhost:8090)

```bash
# Publicar resultado de deploy (simula sistema externo de CI/CD)
make publish-deploy-event CHANGE_ID=<changeId>

# Ou manualmente via Kafka UI
# Tópico: changeops.deploy.finished
# Payload: {"eventType":"DeployFinishedEvent","version":"1.0","payload":{"deployId":"...","changeId":"...","result":"SUCCESS","executedAt":"..."}}
```

### Observabilidade (Grafana: localhost:3001)

- Dashboard "ChangeOps" com 14 painéis (3 seções: Changes, Events, Orchestration)
- Métricas ao vivo: changes criadas/completadas/falhadas/preparadas, eventos publicados/consumidos/falhados/retries/DLT/descartados
- Latência de API (histograma p95) e latência de orquestração p95
- Distribuição de status em pizza, taxa de eventos/min

---

## 3. Visão Arquitetural

### Padrão Central: Arquitetura Hexagonal (Ports & Adapters)

Ambos os serviços seguem rigorosamente o padrão hexagonal, garantindo que a lógica de negócio (domínio) nunca dependa de detalhes de infraestrutura:

```
┌─────────────────────────────────────────────────────────────┐
│                    change-service                           │
│                                                             │
│  [REST Controller] ──► [CreateChangeUseCase] ──► [Change]  │
│       (API Adapter)      (Application)       (Domain)       │
│                              │                              │
│                    ┌─────────┴──────────┐                  │
│                    ▼                    ▼                   │
│           [SaveChangePort]    [PublishEventPort]            │
│                    │                    │                   │
│           [JPA Adapter]       [Kafka Adapter]               │
│           (PostgreSQL)         (Kafka)                      │
└─────────────────────────────────────────────────────────────┘
```

```
┌─────────────────────────────────────────────────────────────┐
│                  deploy-orchestrator                        │
│                                                             │
│  [Kafka Consumer] ──► [ProcessDeployResultUseCase]         │
│     (Infra Adapter)        (Application)                    │
│                                │                           │
│           ┌────────────────────┼──────────────────┐        │
│           ▼                    ▼                  ▼        │
│  [IdempotencyPort]  [UpdateChangeStatusPort]  [PublishPort] │
│       (PostgreSQL)       (PostgreSQL)           (Kafka)     │
└─────────────────────────────────────────────────────────────┘
```

**Regra de dependência:** `API → Application → Domain ← Infrastructure`  
O domínio não conhece Spring, Kafka ou JPA — é Java puro.

### Visão de Containers (C4 Level 2)

```
                    ┌────────────────┐
              ┌────►│   Frontend     │ React + TypeScript
              │     │  :3000         │ Polling 5s
              │     └───────┬────────┘
              │             │ HTTP/REST
              │     ┌───────▼────────┐    ┌──────────────────┐
              │     │ change-service │───►│    PostgreSQL     │
              │     │ :8080          │    │ changes           │
              │     └───────┬────────┘    │ change_events     │
              │             │ Kafka       │ processed_events  │
              │     ┌───────▼────────┐    └──────────┬───────┘
              │     │ Kafka Broker   │               │
              │     │ :9092          │    ┌──────────▼───────┐
              │     └───────┬────────┘    │deploy-orchestr.  │
              │             │             │ :8081             │
              │     ┌───────▼────────┐    └──────────────────┘
              │     │  Prometheus    │
              └─────│  Grafana       │
                    └────────────────┘
```

### Tópicos Kafka

| Tópico | Produtor | Consumidor | Finalidade |
|--------|----------|------------|------------|
| `changeops.change.prepared` | change-service | deploy-orchestrator (externo) | Sinaliza mudança pronta para deploy |
| `changeops.deploy.finished` | Sistema externo (CI/CD) | deploy-orchestrator | Resultado do deploy recebido |
| `changeops.change.result` | deploy-orchestrator | Frontend (via API polling) | Encerramento da mudança |
| `changeops.change.result-dlt` | Spring Kafka (retry) | Monitoramento | Dead letter — mensagens não processadas após 4 tentativas |
| `changeops.events.dlq` | deploy-orchestrator | Monitoramento | Fallback para falhas de publicação |

---

## 4. Stack Tecnológica

### Backend

| Tecnologia | Versão | Justificativa |
|------------|--------|---------------|
| **Java 17** | LTS | Records, sealed classes, performance — padrão corporativo consolidado |
| **Spring Boot** | 3.2.3 | Auto-configuração robusta, ecossistema maduro, integração nativa com Kafka e PostgreSQL |
| **Spring Kafka** | 3.1 | `@RetryableTopic`, `@KafkaListener`, producer idempotente nativos |
| **Spring Security** | 6 | OAuth2 Resource Server com JWT, RBAC baseado em roles do realm Keycloak |
| **Spring Data JPA** | 3.2 | Ports de persistência implementados como adapters com zero acoplamento no domínio |
| **Flyway** | 9 | Migrations versionadas (`V1__create_changes_schema.sql`), rollback controlado |
| **Bucket4j** | 8.7 | Rate limiting por IP com token bucket — 100 req/min, header `Retry-After` |
| **Micrometer + Prometheus** | 1.12 | Métricas customizadas sem acoplar domínio a frameworkde observabilidade |
| **Lombok** | 1.18 | Reduz boilerplate sem interferir na arquitetura hexagonal |

### Frontend

| Tecnologia | Versão | Justificativa |
|------------|--------|---------------|
| **React** | 18.3 | Concurrent rendering, hooks maduros, ampla adoção corporativa |
| **TypeScript** | 5.4 | Tipagem forte — interfaces de domínio espelham contratos do backend |
| **Zustand** | 4.5 | State management sem boilerplate Redux — ideal para escala da POC |
| **Axios** | 1.6 | Interceptors para injeção de headers e normalização de erros da API |
| **Tailwind CSS** | 3.4 | Componentes consistentes com `StatusBadge` e paleta de cores por estado |
| **Vitest + Testing Library** | 4.1 | Testes de componente rápidos, sem JSDOM overhead |

### Mensageria e Infraestrutura

| Tecnologia | Versão | Justificativa |
|------------|--------|---------------|
| **Apache Kafka** | 7.6.0 (CP) | Log imutável, replay, particionamento por `changeId`, produtor idempotente nativo. Detalhado em [ADR-001](adr/ADR-001-escolha-message-broker.md) |
| **PostgreSQL** | 16 | JSONB para payloads de eventos, `ON CONFLICT DO NOTHING` para idempotência atômica, triggers de `updated_at` |
| **Docker Compose** | v2 | Stack completa com healthchecks em todos os serviços, `depends_on: condition: service_healthy` |
| **Prometheus** | 2.50 | Scrape de 4 endpoints (change-service, deploy-orchestrator, kafka, self) |
| **Grafana** | 12.4.1 | Dashboard pré-provisionado via `provisioning/` — zero configuração manual |

### Testes

| Ferramenta | Uso |
|------------|-----|
| **JUnit 5** | Testes unitários de domínio e serviços de aplicação |
| **Mockito** | Mock de ports de saída — testa serviços sem infraestrutura real |
| **Testcontainers** | PostgreSQL 16 + Kafka 7.6 reais em testes de integração |
| **Awaitility** | Assertions em fluxos assíncronos Kafka sem sleeps arbitrários |
| **AssertJ** | Assertions fluentes, erros descritivos |
| **MSW + Vitest** | Mock de endpoints HTTP para testes de componente frontend |
| **JaCoCo** | 80% de cobertura de linhas no change-service, 70% no deploy-orchestrator |

---

## 5. Fluxos de Negócio

### Fluxo 1 — Criação de Mudança Técnica

```
Operador
  │
  ├─► [Frontend] POST /api/v1/changes
  │       │ X-Correlation-Id (gerado ou propagado)
  │
  ├─► [ChangeController] @Valid — Bean Validation
  │       │ @NotBlank title, description, componentId
  │       │ @Future scheduledAt
  │       │ @Size(max=200) description
  │
  ├─► [CreateChangeService] @Transactional
  │       │
  │       ├─► Change.create() ──► status=PREPARED, correlationId=UUID, domainEvent=ChangePreparedEvent
  │       │
  │       ├─► SaveChangePort.save() ──► PostgreSQL (changes table)
  │       │
  │       ├─► PublishEventPort.publish() ──► Kafka (changeops.change.prepared)
  │       │       │ IntegrationEvent { eventType, version="1.0", correlationId, payload }
  │       │       │ acks=all + enable.idempotence=true + max.in.flight=1
  │       │
  │       └─► SaveChangeEventPort.save() ──► PostgreSQL (change_events timeline)
  │
  └─► HTTP 201 Created + Location: /api/v1/changes/{changeId}
          │ Increment: changes_created_total, events_published_total (Prometheus)
          │ MDC: correlation_id, change_id, user_id (Logback JSON)
```

**Pontos de destaque:**
- `Change.create()` é um **factory method de domínio puro** — sem dependências Spring
- `pullDomainEvents()` implementa **pull-semantics**: o agregado acumula eventos, o serviço extrai e publica após a persistência
- Dois níveis de validação: client-side (React) + server-side (Bean Validation)
- `correlationId` gerado no domínio e propagado por toda a cadeia

### Fluxo 2 — Orquestração de Deploy

```
Sistema CI/CD
  │
  ├─► Kafka (changeops.deploy.finished)
  │       │ DeployFinishedEvent { deployId, changeId, result, executedAt }
  │
  ├─► [DeployEventConsumer] @KafkaListener @RetryableTopic
  │       │ MDC: correlation_id, change_id, deploy_id
  │
  ├─► [ProcessDeployResultService]
  │       │
  │       ├─► 1. IdempotencyPort.tryMarkAsProcessed(deployId)
  │       │       ├─ NEW ──► atomically insert + continue
  │       │       └─ DUPLICATE ──► log WARN + discard silently
  │       │
  │       ├─► 2. PostDeployChecklistService.run()
  │       │       ├─ Deploy result gate
  │       │       ├─ Healthcheck
  │       │       ├─ Smoke test
  │       │       └─ Error rate threshold
  │       │
  │       ├─► 3. Build ChangeResult (SUCCESS ou FAILURE + reason)
  │       │
  │       ├─► 4. UpdateChangeStatusPort.markCompleted/Failed() ──► PostgreSQL UPDATE
  │       │       └─ SaveChangeEventPort.save() ──► change_events timeline
  │       │
  │       └─► 5. PublishResultEventPort.publish() ──► Kafka (changeops.change.result)
  │               └─ Fallback DLQ em caso de falha do broker
  │
  └─► Frontend polling detecta mudança de status em até 5 segundos

Retry/DLT (falha no processamento):
  Tentativa 1 (imediata) → Tentativa 2 (500ms) → Tentativa 3 (2s) → Tentativa 4 (4s)
  └─► Dead Letter Topic (changeops.change.result-dlt)
          └─► Log ERROR + increment events_failed_total
```

**Pontos de destaque:**
- **Idempotência atômica**: `INSERT INTO processed_events ON CONFLICT DO NOTHING` — sem race conditions em escala horizontal
- **Checklist extensível**: `PostDeployChecklistService` é um ponto de extensão para integrar healthchecks e métricas reais
- **Retry com backoff exponencial**: 500ms → 1s → 2s → 4s antes de enviar para DLT
- **Separação de eventos**: `ChangePreparedEvent` (domínio) é mapeado para `IntegrationEvent` (integração) antes de publicar

---

## 6. Qualidade de Código e Testabilidade

### Arquitetura Hexagonal — Benefícios práticos

A arquitetura hexagonal não é um objetivo estético — ela entrega benefícios concretos:

| Benefício | Como se manifesta no código |
|-----------|----------------------------|
| **Testabilidade** | `ProcessDeployResultService` mockea todos os ports — sem Kafka, sem DB nos testes unitários |
| **Substituição de tecnologia** | Para trocar PostgreSQL por Cassandra: criar novo adapter, zero mudança no domínio |
| **Clareza de responsabilidades** | `Change.java` tem apenas regras de negócio, sem imports de `javax.persistence` |
| **Independência de framework** | `CreateChangeUseCase` é uma interface Java pura — Spring não vaza para o domínio |

### Domain-Driven Design

```java
// Change.java — Aggregate Root com factory method e transições de estado protegidas
public class Change {

    public static Change create(String title, String description, 
                                 String componentId, String requestedBy,
                                 Instant scheduledAt) {
        // Gera correlationId, define status=PREPARED, adiciona ChangePreparedEvent
        // Regra de negócio: só PREPARED pode ser criado (DRAFT = fluxo futuro)
    }

    public void complete() {
        if (this.status != ChangeStatus.PREPARED) {
            throw new InvalidChangeStateException(...);
        }
        // Transição protegida — impossível completar uma change já FAILED
    }

    public List<DomainEvent> pullDomainEvents() {
        // Pull-semantics: retorna e limpa a lista de eventos
    }
}
```

### Pirâmide de Testes

```
         /\
        /E2E\        (Excluído intencionalmente na POC)
       /──────\
      / Integr. \    ← Testcontainers: PostgreSQL 16 + Kafka 7.6 reais
     /────────────\    CreateChangeIT, DeployEventConsumerIT
    /    Unitário   \  ← JUnit 5 + Mockito
   /──────────────────\  ChangeTest, CreateChangeServiceTest,
  /                    \  ProcessDeployResultServiceTest, ChangeControllerTest
 /______________________ \
```

**Cobertura:**

| Módulo | Threshold | O que é coberto |
|--------|-----------|-----------------|
| change-service | 80% linhas | Domain model, todos os services, controller, observability, rate limit |
| deploy-orchestrator | 70% linhas | Domain model, checklist, process service, consumer retry |

### Exemplos de Testes Significativos

```java
// ChangeTest — Domínio puro, sem Spring
@Test
void shouldThrowWhenCompletingCancelledChange() {
    Change change = Change.create(...);
    change.cancel();
    assertThatThrownBy(() -> change.complete())
        .isInstanceOf(InvalidChangeStateException.class);
}

// ProcessDeployResultServiceTest — Idempotência
@Test
void shouldDiscardDuplicateEvent() {
    when(idempotencyPort.tryMarkAsProcessed(any(), any())).thenReturn(false);
    service.execute(event);
    verifyNoInteractions(updateChangeStatusPort, publishResultEventPort);
}
```

---

## 7. Resiliência e Confiabilidade

### Idempotência Atômica

**Estratégia:** `INSERT INTO processed_events (event_id, processed_at, service_name) ON CONFLICT DO NOTHING`

```sql
-- Operação atômica no PostgreSQL — sem race conditions
-- Retorna 1 se inserido (novo evento) ou 0 se já existia (duplicata)
INSERT INTO processed_events (event_id, processed_at, service_name)
VALUES (?, NOW(), 'deploy-orchestrator')
ON CONFLICT DO NOTHING
```

- Durável: persiste no banco relacional (não em cache)
- Auditável: `processed_at` e `service_name` registrados
- Cleanup automático: job diário às 03:00 remove registros > 90 dias

### Retry com Backoff Exponencial e DLT

```java
@RetryableTopic(
    attempts = "4",
    backoff = @Backoff(delay = 500, multiplier = 2, maxDelay = 10000),
    dltTopicSuffix = "-dlt"
)
@KafkaListener(topics = "${changeops.kafka.topics.deploy-finished}")
public void consume(DeployFinishedEvent event) { ... }
```

| Tentativa | Após | Tópico |
|-----------|------|--------|
| 1ª | Imediato | `changeops.deploy.finished` |
| 2ª | 500ms | `changeops.deploy.finished-retry-0` |
| 3ª | 1s | `changeops.deploy.finished-retry-1` |
| 4ª | 2s | `changeops.deploy.finished-retry-2` |
| DLT | — | `changeops.change.result-dlt` |

### Produtor Idempotente Kafka

```yaml
Producer configurado com:
  acks: all                        # Lider + todas as réplicas confirmam
  enable.idempotence: true         # Exactly-once semantics do produtor
  max.in.flight.requests.per.connection: 1  # Garante ordering por partição
```

### Rastreabilidade E2E com Correlation ID

```
HTTP Request ──► X-Correlation-Id ──► MDC (Logback) ──► Kafka Envelope ──► Consumer MDC
   (header)     (UUID automático)   (todos os logs)  (correlationId no   (propagado pelo
                                                       IntegrationEvent)  evento de domínio)
```

Todo log de qualquer serviço carrega `correlation_id`, permitindo correlacionar uma request HTTP com todos os eventos Kafka associados — sem necessidade de distributed tracing externo na POC.

---

## 8. Observabilidade em Tempo Real

### Logs Estruturados (JSON)

```json
{
  "timestamp": "2026-03-24T14:32:01.123Z",
  "level": "INFO",
  "service": "change-service",
  "correlation_id": "550e8400-e29b-41d4-a716-446655440000",
  "change_id": "d290f1ee-6c54-4b01-90e6-d701748f0851",
  "user_id": "operator-123",
  "message": "Change created successfully",
  "logger": "com.changeops.changeservice.application.service.CreateChangeService"
}
```

Todos os campos são indexáveis — integração direta com Elasticsearch/Splunk/Cloud Logging.

### Métricas Prometheus (Custom)

| Métrica | Tipo | Descrição |
|---------|------|-----------|
| `changes_created_total` | Counter | Total de mudanças criadas |
| `changes_completed_total` | Counter | Total de mudanças com status COMPLETED |
| `changes_failed_total` | Counter | Total de mudanças com status FAILED |
| `events_published_total{type="..."}` | Counter | Eventos publicados no Kafka, dimensionado por tipo de evento |
| `events_consumed_total` | Counter | Eventos consumidos pelo orchestrator |
| `events_retries_total` | Counter | Tentativas de reprocessamento (retry) |
| `events_failed_total` | Counter | Falhas permanentes (evento enviado para DLT) |
| `events_dlt_total` | Counter | Total de eventos enviados para DLT |
| `events_discarded_total` | Counter | Eventos descartados (duplicatas) |
| `orchestration_duration_seconds` | Timer | Latência end-to-end do processamento |
| `changes_by_status{status}` | Gauge | Distribuição atual por status (refresh 60s) |
| `http_server_requests_seconds` | Histogram | Latência de API por método/path/status |

### Dashboard Grafana (Pré-provisionado)

O dashboard **ChangeOps** é carregado automaticamente ao iniciar a stack. Sem configuração manual.

| Painel | Visualização | Query |
|--------|--------------|-------|
| Changes - Created | Stat | `sum(changes_created_total)` |
| Changes - Completed | Stat (verde) | `sum(changes_completed_total)` |
| Changes - Failed | Stat (vermelho) | `sum(changes_failed_total)` |
| Changes - Prepared | Stat (azul) | `created - completed - failed` |
| Changes - By Status | PieChart | Completed / Failed / Prepared |
| API Latency p95 | TimeSeries | `histogram_quantile(0.95, http_server_requests_seconds_bucket)` |
| Events - Published | Stat | `sum(events_published_total)` |
| Events - Consumed | Stat | `sum(events_consumed_total)` |
| Events - Retries | Stat (vermelho) | `sum(events_retries_total)` |
| Events - Failed | Stat (vermelho) | `sum(events_failed_total)` — falhas permanentes |
| Events - DLT | Stat (dark-red) | `sum(events_dlt_total)` |
| Events - Discarded | Stat (roxo) | `sum(events_discarded_total)` |
| Events Rate | TimeSeries | Published / Consumed / Retries / Failed / DLT / Discarded por minuto |
| Orchestration Latency p95 | TimeSeries | `histogram_quantile(0.95, orchestration_duration_seconds_bucket)` |

---

## 9. Segurança

### JWT + RBAC (Pronto para Produção)

```java
// SecurityConfig.java — Profile-based
@Profile("!local")
http.oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt
    .jwtAuthenticationConverter(customJwtAuthenticationConverter)));

// Roles extraídas do claim realm_access.roles do JWT (Keycloak-compatible)
// GET /changes → requer role OPERATOR ou ADMIN
// POST /changes → requer role OPERATOR ou ADMIN
// /actuator/prometheus → requer role ADMIN
```

Na POC, o profile `local` permite requisições sem JWT. A infraestrutura de segurança está wired e testada — ativar em produção é questão de configuração de variáveis de ambiente (issuer URI do Keycloak).

### Rate Limiting por IP

```
Bucket4j Token Bucket:
  - 100 requisições por minuto por IP
  - Aplica apenas em POST /api/v1/changes
  - Resposta HTTP 429 com header Retry-After
  - IP resolve X-Forwarded-For com fallback para remote address
```

### Validação de Entrada

Todas as entradas passam por Bean Validation com erros RFC 7807 (ProblemDetail):

```json
HTTP 400 Bad Request
{
  "type": "https://changeops.io/errors/validation",
  "title": "Validation Failed",
  "status": 400,
  "errors": [
    {"field": "scheduledAt", "message": "must be a future date"},
    {"field": "title", "message": "must not be blank"}
  ]
}
```

### CORS Configurado (Não Wildcard)

```java
// Permite apenas origens explicitamente aprovadas
allowedOriginPatterns: ["http://localhost:3000", "https://*.changeops.io"]
// Credenciais habilitadas para JWT Bearer Token
allowCredentials: true
```

---

## 10. Contratos de API e Eventos

### OpenAPI 3.1 — REST API

Contrato em [`contracts/openapi/change-service.yml`](../contracts/openapi/change-service.yml)

| Endpoint | Método | Status | Descrição |
|----------|--------|--------|-----------|
| `/api/v1/changes` | `POST` | 201 | Criar mudança + Location header |
| `/api/v1/changes` | `GET` | 200 | Listar com paginação + filtros |
| `/api/v1/changes/{changeId}` | `GET` | 200/404 | Detalhe da mudança |
| `/api/v1/changes/{changeId}/events` | `GET` | 200/404 | Timeline de eventos |

Swagger UI disponível em: [change-service](http://localhost:8080/swagger-ui.html) (`:8080`) e [deploy-orchestrator](http://localhost:8081/swagger-ui.html) (`:8081`)

### AsyncAPI 2.6 — Event Contracts

Contrato em [`contracts/asyncapi/events.yml`](../contracts/asyncapi/events.yml)

Envelope padrão para todos os eventos:

```json
{
  "eventType": "ChangePreparedEvent",
  "version": "1.0",
  "correlationId": "uuid-v4",
  "occurredAt": "2026-03-24T14:32:01Z",
  "payload": { ... }
}
```

A versão semântica (`version: "1.0"`) suporta **backward compatibility**: consumidores que desconhecem campos novos simplesmente os ignoram — sem breaking change para adição de campos.

---

## 11. Decisões Arquiteturais (ADRs)

Todas as decisões técnicas relevantes estão documentadas como Architecture Decision Records em [`docs/adr/`](adr/).

### Resumo dos 5 ADRs

| ADR | Decisão | Justificativa Principal |
|-----|---------|------------------------|
| [ADR-001](adr/ADR-001-escolha-message-broker.md) | **Apache Kafka** como message broker | Replay de eventos, particionamento por `changeId` (ordering garantido), produtor idempotente nativo, base para Event Sourcing / Schema Registry no roadmap |
| [ADR-002](adr/ADR-002-estrategia-idempotencia.md) | **`INSERT ... ON CONFLICT DO NOTHING`** para idempotência | Operação atômica durável — sem race conditions ao escalar o consumer horizontalmente; auditável com `processed_at` e `service_name` |
| [ADR-003](adr/ADR-003-eventos-dominio-vs-integracao.md) | **Separação explícita** entre eventos de domínio e integração | Domínio evolui independente do contrato de transporte; versionamento separado; adapter isola a transformação |
| [ADR-004](adr/ADR-004-atualizacao-status-frontend.md) | **HTTP Polling** (5s) para atualização de status | Implementação simples e robusta para POC; hook `usePolling()` é genérico — migração para SSE requer apenas trocar a implementação interna |
| [ADR-005](adr/ADR-005-estrutura-pacotes.md) | **Arquitetura Hexagonal** com pacotes por camada | Testabilidade máxima, separação de responsabilidades, dependência unidirecional clara: `api → application → domain ← infrastructure` |

---

## 12. Tradeoffs Estratégicos

Todo projeto de software envolve tradeoffs. Os abaixo foram decisões deliberadas, alinhadas ao objetivo da POC, com caminho de evolução documentado.

---

### T1 — Banco de Dados Compartilhado entre Serviços

**Decisão:** `change-service` e `deploy-orchestrator` compartilham o mesmo PostgreSQL.

**Por que funciona para a POC:**
- Simplifica drasticamente o setup de demo (um único `docker compose up`)
- O `deploy-orchestrator` faz `UPDATE` direto na tabela `changes` sem necessidade de API interna
- Demonstra os conceitos arquiteturais sem adicionar complexidade operacional desnecessária

**Caminho de evolução (Roadmap Phase 3):**
- Separar em schemas distintos com Row Level Security
- `deploy-orchestrator` passa a notificar via API interna do `change-service` para atualizar status
- Elimina acoplamento via banco — deploy independente dos serviços

---

### T2 — HTTP Polling vs. SSE / WebSocket no Frontend

**Decisão:** Polling HTTP a cada 5 segundos.

**Por que é uma vantagem no contexto da POC:**
- Zero infra adicional no backend (não requer endpoint SSE ou suporte a WebSocket)
- Funciona atrás de qualquer proxy/load balancer sem configuração especial
- Latência de até 5 segundos é aceitável para um fluxo de mudança que leva minutos
- `usePolling()` é um hook genérico — a interface de alto nível permanece idêntica independentemente do mecanismo de atualização

**Caminho de evolução (Roadmap Phase 2.4):**
- Substituir internamente `usePolling()` por SSE — sem mudança na interface do componente
- Usar `text/event-stream` com Spring Boot `SseEmitter`

---

### T3 — Sem Outbox Pattern (Publicação Kafka Pós-Commit)

**Decisão:** `CreateChangeService` persiste no DB e então publica no Kafka em operação separada (porém dentro da transação Java).

**Por que é aceitável na POC:**
- O produtor Kafka é idempotente (`acks=all`, `enable.idempotence=true`) — falhas transientes resultam em retry automático
- O consumer implementa idempotência atômica — mesmo em eventual duplicata, o processamento é seguro
- Simplifica drasticamente o código sem adicionar uma terceira tabela e um worker de polling

**Caminho de evolução (Roadmap Phase 2.1):**
- Implementar Transactional Outbox com tabela `outbox_events`
- Worker publica eventos pendentes com garantia de at-least-once durável

---

### T4 — Fator de Replicação Kafka = 1 (Padrão)

**Decisão:** Tópicos criados com `replicas=1` por padrão.

**Por que é adequado para a POC:**
- Ambiente single-broker local — não haveria réplicas para distribuir mesmo com fator maior
- Configurável via variável de ambiente `KAFKA_REPLICATION_FACTOR` — sem necessidade de alterar código
- O padrão de configuração externalizada já está implementado

**Caminho de evolução (Produção):**
- `KAFKA_REPLICATION_FACTOR=3` em ambientes com cluster Kafka multi-broker
- Ajuste de `min.insync.replicas=2` para garantia de durabilidade

---

### T5 — Autenticação Frontend via localStorage (Dev Only)

**Decisão:** Frontend lê `access_token` e `user_id` de `localStorage` como fallback de desenvolvimento.

**Por que não é um risco na POC:**
- Profile `local` no backend aceita requisições sem JWT por design
- O `X-User-Id` header tem **menor prioridade** que o JWT Subject — jwt vence sempre se presente
- A infraestrutura completa de OAuth2/JWT está implementada no backend (`SecurityConfig.java`, `CustomJwtAuthenticationConverter.java`)

**Caminho de evolução (Roadmap Phase 2.2):**
- Implementar OAuth2 PKCE flow com Keycloak
- Tokens armazenados em `HttpOnly Secure Cookie` — elimina risco de XSS

---

### T6 — PostDeployChecklist Simulado

**Decisão:** `PostDeployChecklistService` simula 4 verificações (healthcheck, smoke test, error rate, deploy result).

**Por que é uma escolha inteligente:**
- Demonstra o **padrão arquitetural** sem dependência de infraestrutura externa real
- O serviço é um **extension point explícito** — substituir cada verificação por chamada HTTP real é uma mudança localizada
- A lógica do checklist (`ChecklistResult`, `failureReason`) já está completa e testada

**Caminho de evolução (Fase imediata pós-POC):**
- Injetar `HealthCheckClient`, `MetricsClient` como ports de saída
- Implementar adapters para chamar actuator health do serviço deployado e Prometheus da aplicação

---

## 13. Roadmap Evolutivo

A POC foi construída como **base sólida**, não como protótipo descartável. O roadmap detalha a evolução natural em 4 fases:

```
AGORA             CURTO PRAZO           MÉDIO PRAZO          LONGO PRAZO
Phase 1 (POC)  →  Phase 2 (Hardening) →  Phase 3 (Scale)  →  Phase 4 (Enterprise)
   MVP               Produção               Kubernetes          Multi-tenant
```

### Phase 1 — MVP (Concluído na POC ✅)

- Arquitetura hexagonal em ambos os serviços
- Fluxos 1 e 2 completos end-to-end via Kafka
- Idempotência, retry, DLT, correlation ID
- Observabilidade (logs JSON, Prometheus, Grafana)
- Segurança JWT/RBAC pronta para ativação
- Contratos OpenAPI + AsyncAPI
- Suite de testes (unitários, integração, Testcontainers)
- Rate limiting (Bucket4j)
- Docker Compose com healthchecks

### Phase 2 — Production Hardening

- **Transactional Outbox** — elimina gap de publicação Kafka
- **OAuth2/PKCE + Keycloak** — autenticação real E2E
- **OpenTelemetry** — distributed tracing com spans correlacionados
- **DB Maintenance** — TTL em `change_events`, particionamento mensal, read replicas

### Phase 3 — Scale & Resilience

- **Kubernetes + Helm** — deploy em cluster, HPA por consumo Kafka
- **Kafka scaling** — múltiplas partições, consumer concurrency = partition count
- **Circuit Breaker** — Resilience4j nos adapters de saída críticos

### Phase 4 — Multi-Tenancy & Compliance

- **Schema-per-tenant** com Row Level Security
- **Append-only audit log** imutável com hash de integridade
- **GDPR/LGPD** — pseudonymização de PII, API de erasure

---

## 14. Roteiro de Demonstração

### Pré-requisitos

```bash
# Verificar stack rodando
docker compose ps
# Todos os serviços devem estar "healthy"
```

### Passo a Passo

#### Demo 1 — Fluxo Feliz (Happy Path)

**1. Abrir a interface** — `http://localhost:3000`

**2. Criar uma mudança via interface:**
- Clicar em "New Change"
- Preencher:
  - Title: `Deploy do módulo de pagamentos v2.1.0`
  - Description: `Correção de encoding em faturas internacionais no checkout`
  - Component ID: `payment-service`
  - Scheduled At: data futura (ex: amanhã às 10:00)
- Clicar em "Create Change"
- Observar: mudança aparece na lista com status `PREPARED`

**3. Verificar evento Kafka no Kafka UI** — `http://localhost:8090`
- Tópico: `changeops.change.prepared`
- Observar envelope `IntegrationEvent` com `correlationId` e `payload`

**4. Publicar resultado de deploy (simula CI/CD):**
```bash
# Substituir pelo changeId gerado na etapa 2
make publish-deploy-event CHANGE_ID=<changeId>

# Ou usar o comando direto:
curl -X POST http://localhost:9092 ... # (via Kafka UI ou make target)
```

**5. Observar atualização automática:**
- Em até 5 segundos, status muda para `COMPLETED` na interface
- Clicar na linha para ver a Timeline de Eventos

**6. Ver logs estruturados:**
```bash
docker compose logs change-service --follow | head -20
docker compose logs deploy-orchestrator --follow | head -20
# Observar correlation_id idêntico nos dois serviços
```

**7. Abrir Grafana** — `http://localhost:3001`
- Dashboard: **ChangeOps**
- Observar incremento em: Changes Created, Events Published, Events Consumed
- Verificar API Latency p95

---

#### Demo 2 — Cenário de Falha e Idempotência

**1. Publicar evento de deploy com resultado FAILURE:**
```bash
make publish-deploy-event CHANGE_ID=<changeId> RESULT=FAILURE
```
- Observar status mudando para `FAILED`
- Timeline mostra `ChangeFailedEvent` com reason

**2. Publicar o mesmo evento duas vezes:**
```bash
# Mesmo deployId e changeId — deve ser descartado silenciosamente
make publish-deploy-event CHANGE_ID=<changeId> DEPLOY_ID=<mesmo-deploy-id>
```
- Status não muda (idempotência funcionando)
- Log do orchestrator mostra `[IDEMPOTENCY] Event already processed, discarding`

---

#### Demo 3 — Validação de Entrada

**1. Criar mudança com dados inválidos via API:**
```bash
curl -X POST http://localhost:8080/api/v1/changes \
  -H "Content-Type: application/json" \
  -H "X-User-Id: demo-operator" \
  -d '{"title": "", "scheduledAt": "2020-01-01T00:00:00Z"}'
```
- Resposta: HTTP 400 com ProblemDetail RFC 7807
- Campos marcados: `title` (blank), `scheduledAt` (past date), `description` (missing), `componentId` (missing)

---

#### Demo 4 — Rate Limiting

```bash
# Disparar mais de 100 POSTs em sequência
for i in {1..105}; do
  curl -s -o /dev/null -w "%{http_code}\n" \
    -X POST http://localhost:8080/api/v1/changes \
    -H "Content-Type: application/json" \
    -H "X-User-Id: spammer" \
    -d '{"title":"t","description":"d","componentId":"c","scheduledAt":"2026-12-01T00:00:00Z"}'
done
# Observar 201 para os primeiros 100, 429 com Retry-After a partir do 101
```

---

#### Extras (se tempo disponível)

```bash
# Ver métricas raw do Prometheus
curl http://localhost:8080/actuator/prometheus | grep changes_

# Ver health checks
curl http://localhost:8080/actuator/health
curl http://localhost:8081/actuator/health

# Swagger UI
open http://localhost:8080/swagger-ui.html
open http://localhost:8081/swagger-ui.html
```

---

## 15. Checklist Pré-Apresentação

### Infraestrutura

- [ ] `docker compose up --build -d` executado com sucesso
- [ ] `docker compose ps` — todos os serviços em estado `healthy`
  - [ ] postgres
  - [ ] zookeeper
  - [ ] kafka
  - [ ] change-service
  - [ ] deploy-orchestrator
  - [ ] prometheus
  - [ ] grafana
- [ ] http://localhost:3000 carrega o frontend
- [ ] http://localhost:8080/actuator/health retorna `{"status":"UP"}`
- [ ] http://localhost:8081/actuator/health retorna `{"status":"UP"}`
- [ ] http://localhost:8090 — Kafka UI acessível
- [ ] http://localhost:9090 — Prometheus acessível
- [ ] http://localhost:3001 — Grafana acessível (admin/admin)

### Dados e Visualização

- [ ] Grafana — Dashboard "ChangeOps" carregado automaticamente
- [ ] Fazer um smoke test: `make smoke` (cria uma change via cURL)
- [ ] Verificar que a change aparece na interface web
- [ ] Kafka UI mostra mensagem no tópico `changeops.change.prepared`

### Browser

- [ ] Abrir `http://localhost:3000` em aba principal
- [ ] Abrir `http://localhost:8090` (Kafka UI) em aba secundária
- [ ] Abrir `http://localhost:3001` (Grafana) em terceira aba
- [ ] Abrir DevTools → Network para mostrar polling em ação (opcional)

### Comandos Prontos para Copiar

```bash
# Criar mudança via API
curl -X POST http://localhost:8080/api/v1/changes \
  -H "Content-Type: application/json" \
  -H "X-User-Id: demo-operator" \
  -d '{
    "title": "Deploy do módulo de pagamentos v2.1.0",
    "description": "Correção de encoding em faturas internacionais",
    "componentId": "payment-service",
    "scheduledAt": "2026-12-01T10:00:00Z"
  }'

# Publicar evento de deploy (substituir CHANGE_ID)
make publish-deploy-event CHANGE_ID=<uuid>

# Verificar logs com correlation_id
docker compose logs change-service --follow
docker compose logs deploy-orchestrator --follow

# Ver métricas
curl http://localhost:8080/actuator/prometheus | grep -E "changes_|events_"
```

---

## Apêndice — Conformidade com a Revisão Arquitetural

A seguinte tabela evidencia que todos os problemas identificados na revisão arquitetural de 2026-03-23 foram endereçados:

| ID | Problema | Status | Resolução |
|----|----------|--------|-----------|
| P1 | Missing `dlq` property no deploy-orchestrator | ✅ **CORRIGIDO** | `changeops.kafka.topics.dlq: changeops.events.dlq` em `application.yml` |
| P2 | `Change.fromEntity()` violando camada de domínio | ✅ **CORRIGIDO** | Substituído por `Change.reconstitute()` sem imports de infraestrutura |
| P3 | `GetChangeEventsService` injetando JPA diretamente | ✅ **CORRIGIDO** | Usa `ChangeExistsPort` + `LoadChangeEventsPort` |
| P4 | V2 seed migration executando em produção | ✅ **CORRIGIDO** | Movida para `src/test/resources/db/migration/` |
| P5 | Kafka replicas=1 hardcoded | ✅ **EXTERNALIZADO** | Configurável via `KAFKA_REPLICATION_FACTOR` env var |
| P6 | Cast inseguro `((ChangePreparedEvent) event)` | ✅ **CORRIGIDO** | Interface `DomainEvent` com `occurredAt()`, sem cast |
| P7 | `curl` no healthcheck Alpine | ✅ **CORRIGIDO** | Substituído por `wget -q --spider` |
| P8 | Frontend localStorage para auth | ⚠️ **DEV-ONLY** | JWT tem prioridade — header apenas como fallback local documentado |
| R1 | Sem Outbox Pattern | 📋 **ROADMAP** | Documentado como Fase 2.1 — mitigado por producer idempotente + consumer idempotente |
| R2 | DB compartilhado | 📋 **ROADMAP** | Documentado como tradeoff de POC — Fase 3 separa schemas |
| R3 | Race condition na idempotência | ✅ **CORRIGIDO** | `tryMarkAsProcessed()` com `ON CONFLICT DO NOTHING` atômico |
| S1 | X-User-Id spooofing | ✅ **CORRIGIDO** | JWT subject tem **prioridade** — header é fallback apenas em profile `local` |

---

*Documento gerado em 24/03/2026 para apresentação ao cliente.*  
*Referência: [PROJETO_COMPLETO.md](PROJETO_COMPLETO.md) | [ROADMAP.md](ROADMAP.md)*
