# Roteiro de Apresentação — ChangeOps Dashboard

> **Para uso do apresentador.** Documento de suporte: `ARCHITECTURE_GUIDE.md` aberto ao lado.
> Blocos de código estão colapsados no guia — expanda sob demanda se o cliente pedir.

---

## Info

| | |
|---|---|
| **Duração** | ~25 min (sem demo) / ~35 min (com demo ao vivo) |
| **Audiência** | Time técnico sênior |
| **Tom** | Direto, profissional, sem formalidade excessiva |

---

## Checklist

- [ ] `ARCHITECTURE_GUIDE.md` aberto e renderizado
- [ ] `make up` executado, containers em pé (`make ps`)
- [ ] `localhost:3000`, `localhost:8090` (Kafka UI), `localhost:3001` (Grafana) acessíveis
- [ ] Terminal pronto para `make smoke` / `make publish-deploy-event`

---

## 1. Abertura ⏱ ~2 min

- Apresentação guiada pela arquitetura do ChangeOps Dashboard
- Problema que resolve: rastreabilidade ponta a ponta, idempotência e visibilidade de status em ambientes com muitos deploys
- Dois microserviços desacoplados via Kafka
- Dois fluxos: criação de change + orquestração de deploy
- Código real está no documento — expandam os blocos se quiserem ver detalhes

---

## 2. Visão Geral + Estados ⏱ ~3 min

**▶ Seções 1 e 2 do documento**

- Diagrama C4: Frontend (React :3000) → change-service (:8080) → Kafka → deploy-orchestrator (:8081)
- Banco PostgreSQL compartilhado (mesmo domínio), Kafka faz a separação de responsabilidades
- Prometheus scrape a cada 5s, Grafana com dashboard pré-provisionado
- Máquina de estados: `DRAFT → PREPARED → COMPLETED | FAILED | CANCELLED`
  - POC: criação vai direto para PREPARED
  - Transições guardadas no aggregate root (domínio puro, sem Spring)
  - DRAFT e CANCELLED previstos no roadmap

---

## 3. Fluxo 1: Criação de Change ⏱ ~5 min

**▶ Seções 3 e 4 do documento**

**Frontend (Seção 3):**
- Operador preenche form → hook `useCreateChange` → `changeService.create()` → API
- Interceptor Axios injeta headers automaticamente (`X-User-Id` em dev, JWT em prod)
- Retorno 201 → toast de sucesso, formulário fecha

**Backend (Seção 4) — percorrer o diagrama de sequência:**
- `CorrelationIdFilter` (@Order 1): gera/propaga UUID no MDC → rastreabilidade em todos os logs
- `RateLimitFilter`: 100 req/min por IP (Bucket4j), retorna 429 se exceder
- `ChangeController`: validação com Bean Validation → erros em RFC 7807 ProblemDetail
- `Change.create()`: static factory no domínio puro, registra `ChangePreparedEvent` em memória
- `pullDomainEvents()` → persiste → publica no Kafka (envelope `IntegrationEvent`) → salva na timeline
- Publish bloqueante: `future.get(10s)` — se Kafka não confirmar, rollback

**Pontos-chave:** domínio sem Spring, evento nunca publicado cru (sempre envelope), publicação síncrona intencional

*Se houver tempo:* demo com `make smoke`

---

## 4. Frontend: Listagem e Timeline ⏱ ~2 min

**▶ Seções 5 e 6 do documento**

- Polling HTTP a cada 5s (ADR-004: proxy-friendly, stateless, latência aceitável)
- `usePolling`: espera callback completar antes do próximo tick — sem acúmulo
- Estado centralizado no Zustand — lista e cards sempre sincronizados, sem prop drilling
- Falha no poll: banner amarelo, sessão preservada
- Timeline: clique na change → `GET /changes/{id}/events` → lista com payload JSONB expansível

---

## 5. Fluxo 2: Orquestração de Deploy ⏱ ~5 min

**▶ Seção 7 do documento — percorrer o diagrama de sequência**

- Sistema externo publica `DeployFinishedEvent` no tópico `deploy.finished`
- `DeployEventConsumer` com `@RetryableTopic` (4 tentativas, backoff exponencial)
- Validação do payload: nulo/malformado → `InvalidOrchestratorStateException` → direto para DLT
- Pre-condition: verifica se changeId existe **antes** da idempotência (ordem intencional)
- Idempotência: `INSERT processed_events ON CONFLICT DO NOTHING` — 0 rows = já processado, descarta
- `PostDeployChecklistService`: 4 checks simulados — pode reprovar mesmo com deploy SUCCESS
- Status update + timeline em transação única (`@Transactional`)
- Publica resultado em `change.result`: `ChangeCompletedEvent` ou `ChangeFailedEvent`

*Se houver tempo:*
```
make smoke                                    # Cria change
make publish-deploy-event CHANGE_ID=<id>     # Simula deploy
```

---

## 6. Resiliência ⏱ ~4 min

**▶ Seções 8, 9, 10 e 11 do documento**

**Idempotência (Seção 8):**
- Dois níveis: check na aplicação (rápido) + `ON CONFLICT DO NOTHING` no banco (durável)
- Chave composta `(event_id, service_name)` — cleanup automático após 90 dias

**Retry + DLT (Seção 9):**
- `@RetryableTopic`: 4 tentativas, backoff 500ms × 2.0, cap 10s
- Tópicos `retry-0`, `retry-1`, `retry-2` criados automaticamente (0-based index)
- Após 4 tentativas → `deploy.finished-dlt` — payload disponível no Kafka UI para diagnóstico

**Poison Pill (Seção 10):**
- JSON inválido ou campos nulos → `InvalidOrchestratorStateException` (exclude do retry) → direto para DLT, zero retries
- Diferença: `ChangeNotFoundException` é retryable, `InvalidOrchestratorStateException` não é

**Falha na Publicação (Seção 11):**
- Se publicação do resultado falhar → fallback para DLQ (`change.result-dlt`)
- Se DLQ também falhar → log CRITICAL, intervenção manual
- Outbox Pattern previsto para fase 2 (dívida técnica documentada)

---

## 7. Observabilidade ⏱ ~3 min

**▶ Seções 12 e 13 do documento**

**Métricas (Seção 12):**
- Micrometer → Prometheus (scrape 5s) → Grafana (PromQL)
- Métricas-chave: `changes_created_total`, `events_discarded_total{reason=duplicate}`, `orchestration_duration_seconds` (p95), `events_dlt_total` (alerta implícito)
- Dashboard Grafana com 4 linhas: Changes, Events, Orchestration, Kafka — provisionado automaticamente

**Logs (Seção 13):**
- JSON estruturado via logstash-logback-encoder
- `correlation_id` propagado: nasce no CorrelationIdFilter → envelope Kafka → MDC do orchestrator
- Mesma query no Grafana/Loki rastreia os dois serviços
- Regra: nunca logar PII ou credenciais

*Se houver tempo:* abrir Grafana (`localhost:3001`) e mostrar dashboard

---

## 8. Contratos ⏱ ~2 min

**▶ Seção 14 do documento**

- Três tópicos: `change.prepared`, `deploy.finished`, `change.result` + retry/DLT automáticos
- `IntegrationEvent`: envelope com `eventType`, `version`, `correlationId`, `occurredAt`, `payload`
- Versionamento: tolerant reader — campo novo é opcional, remoção primeiro depreca
- Schema: 3 tabelas (`changes`, `change_events`, `processed_events`) com índices via Flyway

---

<details>
<summary>Demo ao Vivo (opcional) ⏱ ~10 min</summary>

**Pré-requisitos:** `make up` executado, todos os containers em pé (`make ps`).

### Fluxo 1 — Criação

1. Abrir `localhost:3000` — mostrar interface limpa
2. Criar uma change pelo formulário — anotar o `changeId` do retorno
3. Mostrar a change na lista (polling atualiza em ~5s)
4. Clicar na change → timeline com evento `ChangePreparedEvent`
5. Kafka UI (`localhost:8090`) → tópico `changeops.change.prepared` → mostrar mensagem com envelope

### Fluxo 2 — Deploy

1. No terminal:
   ```
   make publish-deploy-event CHANGE_ID=<id> RESULT=SUCCESS
   ```
2. Voltar ao frontend — status muda de PREPARED → COMPLETED
3. Timeline agora tem `ChangeCompletedEvent`
4. Kafka UI → tópico `changeops.change.result` → mensagem com envelope

### Observabilidade

1. Grafana (`localhost:3001`, admin/changeops) → dashboard ChangeOps
2. Mostrar contadores: `changes_created_total`, `events_consumed_total`
3. Mostrar histograma `orchestration_duration_seconds`

### Alternativa rápida (só terminal)

```bash
make smoke                                           # Cria change
curl -s http://localhost:8080/api/v1/changes | jq .  # Lista
make publish-deploy-event CHANGE_ID=<id>             # Simula deploy
curl -s http://localhost:8080/api/v1/changes/<id>/events | jq .  # Timeline
```

</details>

---

## 9. Fechamento ⏱ ~2 min

**Resumo em três frases:**
- Arquitetura hexagonal + EDA com Kafka, domínio isolado de frameworks
- Resiliência em camadas: idempotência, retry com backoff, DLT, DLQ — cada falha tem destino definido
- Observabilidade completa: métricas, logs JSON, correlation_id ponta a ponta

**Roadmap fase 2:** OAuth2 com Keycloak, Outbox Pattern, SSE no frontend

---

## Top 3 FAQ

**"E se o Kafka cair durante a publicação?"**
`future.get(10s)` lança exceção → rollback → change não salva → cliente recebe 500. Decisão conservadora. Outbox Pattern na fase 2.

**"Idempotência funciona se o serviço reiniciar no meio?"**
Sim. `processed_events` e status update estão na mesma transação. Morreu antes do commit: retry reprocessa. Morreu depois: retry encontra a chave e descarta.

**"Os testes cobrem tudo isso?"**
Pirâmide: 60% unitários (domínio + mocks), 30% integração (Testcontainers com Postgres + Kafka reais), 10% consumer (idempotência, DLT, estados). JaCoCo: 80% change-service, 70% orchestrator — build quebra se cair.
