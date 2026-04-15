# ChangeOps Dashboard

> POC de gestão de mudanças técnicas com arquitetura event-driven.

O objetivo desta POC é demonstrar maturidade arquitetural em software distribuído: microserviços desacoplados, comunicação assíncrona via eventos, rastreabilidade ponta a ponta, resiliência a falhas e observabilidade em tempo real.

Escopo formal da POC: [Documento de Escopo](https://soaone.com.br/hyper/viewer.php?file=escopo.md) · [Guia de Execução Interno](https://soaone.com.br/hyper/viewer.php?file=escopo_tecnico.md)

---

## O que o sistema faz

O ChangeOps cobre dois fluxos complementares:

- **Fluxo 1 — Criação de mudança:** operador cria uma mudança técnica via API REST ou interface web. O sistema valida, persiste com status `PREPARED` e publica um evento de domínio (`ChangePreparedEvent`) no Kafka, sinalizando prontidão para deploy.
- **Fluxo 2 — Orquestração de deploy:** um sistema externo de CI/CD publica o resultado do deploy (`DeployFinishedEvent`). O `deploy-orchestrator` consome o evento com idempotência atômica, executa um checklist de validações pós-deploy, atualiza o status da mudança para `COMPLETED` ou `FAILED`, e publica o evento de conclusão. A interface web reflete a mudança de status em até 5 segundos.

---

## Arquitetura

```
┌──────────────────────────────────────────────────────────────────────┐
│  Frontend — React + TypeScript + Zustand                             │
│  :3000   Polling 5s                                                  │
└─────────────────────┬────────────────────────────────────────────────┘
                      │ REST HTTPS
┌─────────────────────▼─────────────────────────────────────────────────┐
│  change-service :8080          deploy-orchestrator :8081              │
│  POST /api/v1/changes    ◄──── result event (Kafka)                   │
│  GET  /api/v1/changes    ────► ChangePreparedEvent (Kafka)            │
│                                Idempotência + Retry + DLQ             │
│                                                                       │
│  ┌───────────────────────────────────────────────────────────────┐    │
│  │  PostgreSQL :5432                                             │    │
│  │  changes  |  change_events  |  processed_events               │    │
│  └───────────────────────────────────────────────────────────────┘    │
│                                                                       │
│  Kafka :9092  ·  Kafka UI :8090  ·  Prometheus :9090  ·  Loki :3100  ·  Grafana :3001│
└───────────────────────────────────────────────────────────────────────┘
```

Ambos os serviços seguem **Arquitetura Hexagonal (Ports & Adapters)**: o domínio é Java puro, sem dependências de Spring, JPA ou Kafka. A regra de dependência é unidirecional — `API → Application → Domain ← Infrastructure`.

Documentação detalhada:
- [Diagrama C4 de Containers](docs/architecture/c4-container.md)
- [Diagrama de Sequência — Fluxo 1 (Criação)](docs/architecture/sequence-flow1-criacao.md)
- [Diagrama de Sequência — Fluxo 2 (Orquestração)](docs/architecture/sequence-flow2-orquestracao.md)

---

## Stack

| Camada | Tecnologias |
|--------|-------------|
| **Backend** | Java 17, Spring Boot 3.2, Spring Kafka, Spring Security OAuth2, Spring Data JPA, Flyway, Bucket4j |
| **Frontend** | React 18, TypeScript, Zustand, Axios, Tailwind CSS, Vitest |
| **Mensageria** | Apache Kafka (Confluent 7.6), tópicos versionados, produtor idempotente |
| **Persistência** | PostgreSQL 16 com JSONB para payloads de eventos |
| **Observabilidade** | Micrometer + Prometheus, Grafana (dashboard pré-provisionado), Logback JSON, Loki + Promtail (agregação de logs estruturados) |
| **Infra** | Docker Compose com healthchecks em todos os serviços, Makefile com 20+ targets |

Stack e versões completas: [docs/PROJETO_COMPLETO.md](docs/PROJETO_COMPLETO.md)

---

## Quick Start

**Pré-requisitos:** Docker 24+ e Docker Compose v2. GNU Make.

```bash
# Clone and start everything
git clone https://github.com/your-org/changeops.git
cd changeops

make up
```

Isso sobe toda a stack. Aguarde todos os serviços ficarem `healthy` (`docker compose ps`).

| Serviço | URL |
|---------|-----|
| Frontend | http://localhost:3000 |
| change-service API | http://localhost:8080 |
| **Swagger UI (change-service)** | http://localhost:8080/swagger-ui.html |
| deploy-orchestrator | http://localhost:8081 |
| **Swagger UI (orchestrator)** | http://localhost:8081/swagger-ui.html |
| Kafka UI | http://localhost:8090 |
| Prometheus | http://localhost:9090 |
| **Grafana** | http://localhost:3001 (`admin` / `changeops`) |
| Loki | http://localhost:3100 |

> **Swagger UI** — ambos os serviços expõem sua documentação interativa: [change-service](http://localhost:8080/swagger-ui.html) (`:8080`) e [deploy-orchestrator](http://localhost:8081/swagger-ui.html) (`:8081`). O contrato estático está em [contracts/openapi/change-service.yml](contracts/openapi/change-service.yml).

> **Grafana** — o dashboard **ChangeOps** é carregado automaticamente na inicialização, sem configuração manual. Exibe métricas ao vivo: changes criadas, eventos publicados/consumidos/falhos, latência de API (p95), latência Kafka listener/producer (p95), distribuição de status.

> **Loki** — os logs estruturados JSON de todos os containers são coletados pelo Promtail e disponibilizados no Grafana Explore. Query: `{container_name="changeops-change-service"} |= "correlation_id"`. API disponível em http://localhost:3100.

---

## Usando o Sistema

### Fluxo completo via terminal

```bash
# 1. Criar uma mudança (retorna JSON com changeId)
make smoke

# 2. Simular resultado de deploy — copiar o changeId do passo anterior
make publish-deploy-event CHANGE_ID=<uuid>

# 3. Status muda para COMPLETED em até 5s
curl -s http://localhost:8080/api/v1/changes | python3 -m json.tool
```

### Fluxo via interface web

1. Abrir `http://localhost:3000`
2. Clicar em **New Change** e preencher o formulário
3. Observar a mudança aparecer na lista com status `PREPARED`
4. Verificar o evento `ChangePreparedEvent` no [Kafka UI](http://localhost:8090)
5. Executar `make publish-deploy-event CHANGE_ID=<uuid>` para simular o CI/CD
6. Acompanhar a atualização automática de status e a timeline de eventos

Roteiro detalhado com 15 casos de teste: [docs/test-strategy.md — Seção 7](docs/test-strategy.md#7-roteiro-de-testes-manuais)

---

## Documentação Técnica

### Arquitetura Hexagonal & DDD

Os dois serviços seguem rigorosamente Ports & Adapters. O `Change` é um Aggregate Root com factory method puro (`Change.create()`), pull-semantics para eventos de domínio (`pullDomainEvents()`), e máquina de estados protegida. O domínio não tem nenhuma dependência de framework.

→ [ADR-005 — Estrutura de Pacotes](docs/adr/ADR-005-estrutura-pacotes.md) · [docs/PROJETO_COMPLETO.md §1–2](docs/PROJETO_COMPLETO.md)

### Fluxos Event-Driven

Eventos de domínio (`ChangePreparedEvent`) são separados explicitamente de eventos de integração (`IntegrationEvent` envelope com `eventType`, `version`, `correlationId`). O `correlation_id` é propagado por toda a stack — desde a request HTTP até os logs do consumer Kafka — sem a necessidade de distributed tracing externo na POC.

→ [ADR-001 — Escolha do Message Broker](docs/adr/ADR-001-escolha-message-broker.md) · [ADR-003 — Eventos Domínio vs Integração](docs/adr/ADR-003-eventos-dominio-vs-integracao.md) · [Contratos AsyncAPI](contracts/asyncapi/events.yml)

### Resiliência

Idempotência atômica via `INSERT INTO processed_events ON CONFLICT DO NOTHING` — sem race conditions ao escalar o consumer horizontalmente. Pré-condição `existsByChangeId` garante que eventos referenciando mudanças inexistentes sejam descartados diretamente ao DLT (sem queimar a chave de idempotência). Proteção contra poison pill via `ErrorHandlingDeserializer`: falhas de desserialização (ex: UUID malformado) entregam `null` ao listener, que lança `InvalidOrchestratorStateException` — exceção não-retryable — roteando a mensagem ao DLT com 0 retries. Retry com backoff exponencial (500ms → 1s → 2s → 4s, 4 tentativas) para falhas de processamento. Dead Letter Topic automático (`changeops.deploy.finished-dlt`) para mensagens não processáveis. Fallback DLQ para falhas de publicação.

→ [ADR-002 — Estratégia de Idempotência](docs/adr/ADR-002-estrategia-idempotencia.md)

### Observabilidade

Logs estruturados em JSON com campos `correlation_id`, `change_id`, `deploy_id` em todos os serviços. Métricas customizadas via Micrometer: `changes_created_total`, `events_published_total`, `events_consumed_total`, `events_failed_total`, `timeline_persistence_failures_total`, latência de API e orquestração (histograma). Loki + Promtail para agregação centralizada de logs — consultável via Grafana Explore sem configuração manual. Dashboard Grafana pré-provisionado com painel Kafka Listener/Producer latency.

→ [docs/PROJETO_COMPLETO.md §7](docs/PROJETO_COMPLETO.md)

### Segurança

JWT Bearer via Spring Security OAuth2 Resource Server, RBAC com roles `ROLE_OPERATOR` / `ROLE_ADMIN`. Rate limiting em `POST /changes` (100 req/min por IP, Bucket4j). Validação de entrada com RFC 7807 `ProblemDetail`. CORS configurado sem wildcard. Em `local`/`test` o JWT é dispensado por design — a infraestrutura está pronta para ativação em produção via variáveis de ambiente.

→ [docs/PROJETO_COMPLETO.md §6](docs/PROJETO_COMPLETO.md)

### Testes

Pirâmide completa: testes unitários de domínio e serviços (JUnit 5 + Mockito), testes de integração com infraestrutura real (Testcontainers — PostgreSQL 16 + Kafka 7.6), testes de componente frontend (Vitest + Testing Library + MSW). Cobertura JaCoCo: 80% linhas no `change-service`, 70% no `deploy-orchestrator`.

→ [docs/test-strategy.md](docs/test-strategy.md)

### Tradeoffs

Decisões deliberadas com justificativa e caminho de evolução documentados: banco compartilhado, polling vs SSE, ausência de Outbox pattern, Kafka replicas=1, auth frontend dev-only, checklist simulado.

→ [docs/PROJETO_COMPLETO.md §10 — Tradeoffs Estratégicos](docs/PROJETO_COMPLETO.md)

---

## ADRs

| ADR | Decisão |
|-----|---------|
| [ADR-001](docs/adr/ADR-001-escolha-message-broker.md) | Apache Kafka — replay, particionamento por `changeId`, produtor idempotente nativo |
| [ADR-002](docs/adr/ADR-002-estrategia-idempotencia.md) | `INSERT ... ON CONFLICT DO NOTHING` — idempotência atômica e durável |
| [ADR-003](docs/adr/ADR-003-eventos-dominio-vs-integracao.md) | Separação explícita entre eventos de domínio e integração |
| [ADR-004](docs/adr/ADR-004-atualizacao-status-frontend.md) | HTTP Polling (5s) — simples, robusto, migrável para SSE sem mudança de interface |
| [ADR-005](docs/adr/ADR-005-estrutura-pacotes.md) | Arquitetura Hexagonal — dependência unidirecional, testabilidade máxima |
| [ADR-006](docs/adr/ADR-006-checklist-pos-deploy.md) | Checklist Pós-Deploy como Contrato de Integração Simulado |
| [ADR-007](docs/adr/ADR-007-autenticacao-em-ambiente-de-desenvolvimento.md) | Autenticação em Ambiente de Desenvolvimento |
---

## Contratos

| Contrato | Arquivo | Acesso live |
|----------|---------|-------------|
| REST API — change-service (OpenAPI 3.1) | [contracts/openapi/change-service.yml](contracts/openapi/change-service.yml) | http://localhost:8080/swagger-ui.html |
| REST API — deploy-orchestrator (OpenAPI 3.1) | gerado em runtime pelo springdoc | http://localhost:8081/swagger-ui.html |
| Eventos Kafka (AsyncAPI 2.6) | [contracts/asyncapi/events.yml](contracts/asyncapi/events.yml) | — |

---

## Roadmap

| Fase | Foco |
|------|------|
| **Fase 1 — MVP** ✅ | Arquitetura hexagonal, fluxos 1 e 2, idempotência, retry/DLQ, observabilidade, segurança JWT pronta, testes, Docker Compose |
| **Fase 2 — Hardening** | Transactional Outbox, OAuth2/PKCE + Keycloak, OpenTelemetry distributed tracing, TTL em `processed_events` |
| **Fase 3 — Escala** | Kubernetes + Helm, Kafka multi-partition, Circuit Breaker (Resilience4j), read replicas PostgreSQL |
| **Fase 4 — Enterprise** | Multi-tenancy (RLS), audit log imutável, GDPR/LGPD (pseudonimização, erasure API) |

---

## Desenvolvimento

### Ambiente de Desenvolvimento

**Extensões recomendadas para VS Code:**

| Extensão | ID | Função |
|----------|----|--------|
| ESLint | `dbaeumer.vscode-eslint` | Sublinha erros de lint em tempo real |
| Prettier | `esbenp.prettier-vscode` | Formata o código ao salvar |

Nenhuma configuração manual é necessária — o projeto já inclui `.prettierrc` (aspas simples, sem ponto e vírgula) e `.eslintrc.cjs`. Com as extensões instaladas, o VS Code aplica as regras automaticamente.

**Quando a validação de lint e formatação é executada:**

| Momento | O que roda | Como |
|---------|-----------|------|
| **Ao salvar** (VS Code) | Prettier + ESLint | Automático com as extensões instaladas |
| **Local — qualidade** | ESLint (frontend) + Checkstyle (backend) | `make lint` |
| **Local — formatação** | Prettier check | `npx prettier --check "src/**/*.{ts,tsx}"` (dentro de `frontend/`) |
| **`docker compose build`** | tsc, ESLint, Prettier check, Vitest e Vite build (frontend); `mvn verify` + Checkstyle (backend) | Automático — a imagem **não é construída** se qualquer verificação falhar |
| **GitHub Actions CI** | Mesmas verificações do build Docker | Automático em every push/PR via `.github/workflows/` |

---

```bash
# Ciclo de vida
make up                  # Sobe toda a stack
                         # Alternativa sem make: docker compose up -d --build
make down                # Para e remove containers
                         # Alternativa sem make: docker compose down -v  (limpa volumes — evita erros na subida do Kafka)
make restart             # Reinicia serviços
                         # Alternativa sem make: docker compose restart [service]
make logs                # Tail de todos os logs
                         # Alternativa sem make: docker logs [container] --follow

# Build
make build-backend       # mvn clean package (ambos os serviços)
make build-frontend      # npm ci + build

# Testes
make test                # Todos os testes
make test-backend-unit   # Unitários rápidos (sem Docker)
make test-backend-it     # Integração com Testcontainers (requer Docker)
make test-frontend       # Vitest

# Scripts de teste consolidados (alternativa WSL)
# wsl bash tests/run_unit_tests.sh           # unit — backend (*Test) + frontend (Vitest)
# wsl bash tests/run_integration_tests.sh    # integração — Testcontainers (requer Docker)
# wsl bash tests/run_automated_manual_tests.sh  # cenários CT-02..CT-32, CT-SEC-03..CT-SEC-10
# wsl bash tests/run_rate_tests.sh           # rate limiting CT-SEC-01, CT-SEC-02

# Qualidade
make lint                # Checkstyle + eslint

# Utilitários
make db-shell            # psql na instância local
make kafka-topics        # Lista tópicos Kafka
make kafka-shell         # Shell do container Kafka
make logs-cs             # Logs do change-service
make logs-do             # Logs do deploy-orchestrator
make clean-test-logs     # Remove relatórios de teste locais (surefire/failsafe/vitest)
make clean-all           # Remove artefatos de build + para containers

# Diagnóstico
curl http://localhost:8080/actuator/health
curl http://localhost:8081/actuator/health
curl http://localhost:8080/actuator/prometheus | grep changes_
docker compose logs change-service --follow
docker compose logs deploy-orchestrator --follow

# Nota: todos os comandos `make` são atalhos para operações Docker/Maven/npm definidas no Makefile.
# Caso o make não esteja disponível no ambiente (ex: Windows sem WSL), os comandos equivalentes
# via docker compose podem ser usados diretamente, conforme indicado nas alternativas acima.
```
