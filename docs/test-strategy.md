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
| `KafkaResultPublisherAdapterTest` | Publicação com sucesso incrementa counter; falha de publicação aciona fallback para DLQ |

### 3.3 Testes de Integração — Backend

**Objetivo:** Validar o fluxo completo API → DB → Kafka com infraestrutura real via Testcontainers.

| Classe de Teste | O que cobre |
|----------------|-------------|
| `CreateChangeIT` | `POST /changes` → persistência → evento no Kafka; validação 400; listagem paginada |
| `DeployEventConsumerIT` | Consumo de `DeployFinishedEvent` → status COMPLETED/FAILED; idempotência (duplicata sem efeito); persistência de evento na timeline |

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
