# ChangeOps — Pacote de Diagramas

Todos os diagramas estão em formato **Mermaid** (`.mermaid`) — renderizáveis diretamente no GitHub, VS Code (extensão Mermaid), [mermaid.live](https://mermaid.live) ou qualquer ferramenta compatível.

---

## Visão Geral dos Arquivos

| Arquivo | Tipo | Escopo | Fase |
|---------|------|--------|------|
| [`c4-01-context-as-is.mermaid`](./c4-01-context-as-is.mermaid) | C4 Level 1 — System Context | Sistema completo + atores externos | AS-IS |
| [`c4-02-container-as-is.mermaid`](./c4-02-container-as-is.mermaid) | C4 Level 2 — Containers | Todos os containers em execução | AS-IS |
| [`c4-03-component-change-service.mermaid`](./c4-03-component-change-service.mermaid) | C4 Level 3 — Components | Internos do change-service | AS-IS |
| [`c4-04-component-deploy-orchestrator.mermaid`](./c4-04-component-deploy-orchestrator.mermaid) | C4 Level 3 — Components | Internos do deploy-orchestrator | AS-IS |
| [`c4-05-container-tobe-phase2.mermaid`](./c4-05-container-tobe-phase2.mermaid) | C4 Level 2 — Containers | Outbox, Keycloak, OpenTelemetry, Tempo | TO-BE Phase 2 |
| [`c4-06-container-tobe-phase3-4.mermaid`](./c4-06-container-tobe-phase3-4.mermaid) | C4 Level 2 — Containers | Kubernetes, Strimzi, multi-tenancy, GDPR | TO-BE Phase 3–4 |
| [`seq-01-change-creation.mermaid`](./seq-01-change-creation.mermaid) | Sequência | Fluxo 1 completo: operador → Kafka | AS-IS |
| [`seq-02-deploy-orchestration.mermaid`](./seq-02-deploy-orchestration.mermaid) | Sequência | Fluxo 2 completo: Kafka → status → resultado | AS-IS |
| [`seq-03-idempotency-detail.mermaid`](./seq-03-idempotency-detail.mermaid) | Sequência | 4 cenários de idempotência com race condition | AS-IS |
| [`state-01-change-lifecycle.mermaid`](./state-01-change-lifecycle.mermaid) | Máquina de Estados | Ciclo de vida completo da mudança | AS-IS + TO-BE |
| [`domain-01-model.mermaid`](./domain-01-model.mermaid) | Diagrama de Classes | Modelo de domínio: agregado, ports, adapters | AS-IS |
| [`event-01-kafka-topology.mermaid`](./event-01-kafka-topology.mermaid) | Topologia de Eventos | Tópicos Kafka, produtores, consumidores, DLT | AS-IS |

---

## Diagramas AS-IS — Descrição

### C4 Level 1 — System Context
Mostra o sistema ChangeOps como uma caixa preta e seus relacionamentos com atores externos: Operador, Administrador, Sistema de CI/CD e Identity Provider (Phase 2).

### C4 Level 2 — Container (AS-IS)
Detalha os 7 containers em execução: Frontend, change-service, deploy-orchestrator, PostgreSQL, Kafka, Prometheus e Grafana — com todos os fluxos de comunicação.

### C4 Level 3 — change-service
Componentes internos: CorrelationIdFilter, RateLimitFilter, ChangeController, CreateChangeService, Change (aggregate), KafkaEventPublisherAdapter, ChangePersistenceAdapter, ObservabilityConfig, SecurityConfig.

### C4 Level 3 — deploy-orchestrator
Componentes internos: DeployEventConsumer, IdempotencyAdapter, ProcessDeployResultService, PostDeployChecklistService, UpdateChangeStatusAdapter, ChangeEventAdapter, KafkaResultPublisherAdapter, ProcessedEventsCleanupJob, DltHandler.

### Sequência — Fluxo 1: Criação de Mudança
Passo a passo completo do POST /api/v1/changes: filtros → validação → domínio → persistência → Kafka → resposta → polling do frontend.

### Sequência — Fluxo 2: Orquestração de Deploy
Passo a passo do DeployFinishedEvent: consumo → idempotência → checklist → atualização de status → evento de resultado. Inclui cenário de retry + DLT.

### Sequência — Idempotência (Detalhe)
4 cenários: primeiro processamento, re-entrega duplicada, race condition com duas réplicas, falha com rollback transacional e re-entrega legítima.

### Máquina de Estados — Ciclo de Vida da Mudança
Transições: `[*] → PREPARED → COMPLETED/FAILED/CANCELLED`. Documenta que DRAFT existe no enum mas não é usado na Phase 1 (ver ADR-005).

### Diagrama de Classes — Modelo de Domínio
Change (aggregate root), ChangeStatus, DomainEvent, ChangePreparedEvent, IntegrationEvent (envelope), ports inbound/outbound, adapters de infraestrutura.

### Topologia Kafka
Todos os tópicos (`changeops.change.prepared`, `changeops.deploy.finished`, `changeops.change.result`, DLT), produtores, consumidores, estrutura do IntegrationEvent envelope e Kafka UI.

---

## Diagramas TO-BE — Descrição

### C4 Level 2 — Phase 2 (Production Hardening)
Adiciona: Outbox Relay (garantia at-least-once), Keycloak (OAuth2/OIDC real), OpenTelemetry + Grafana Tempo (distributed tracing), Kafka KRaft (sem ZooKeeper), Schema Registry.

### C4 Level 2 — Phase 3–4 (Scale & Multi-Tenancy)
Adiciona: Kubernetes + Helm (change-service HPA, deploy-orchestrator HPA), Strimzi operator, CloudNativePG, Checklist Integration Service extraído como serviço dedicado, Circuit Breaker (Resilience4j), multi-tenancy com schema-per-tenant + RLS, GDPR Erasure API.

---

## Como Renderizar

**GitHub:** Os arquivos `.mermaid` são renderizados automaticamente na visualização de arquivos.

**VS Code:**
```
Extensão: Mermaid Preview (bierner.mermaid-markdown-syntax-highlighting)
Atalho: Ctrl+Shift+P → "Mermaid: Preview"
```

**Online:**
- Copie o conteúdo em [mermaid.live](https://mermaid.live)
- Export: PNG, SVG ou PDF disponíveis

**CLI:**
```bash
npm install -g @mermaid-js/mermaid-cli
mmdc -i c4-02-container-as-is.mermaid -o c4-02-container-as-is.svg -t dark
```
