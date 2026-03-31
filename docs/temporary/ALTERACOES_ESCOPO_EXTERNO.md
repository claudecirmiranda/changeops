# Alterações Necessárias nos Documentos de Escopo Externo

> Este documento lista as alterações que devem ser aplicadas aos documentos de escopo hospedados externamente,
> para que reflitam o estado real da implementação após a separação das métricas de retry e falha permanente.
>
> URLs de referência:
> - `https://soaone.com.br/hyper/viewer.php?file=escopo.md`
> - `https://soaone.com.br/hyper/viewer.php?file=escopo_tecnico.md`

---

## 1. Tabela de Métricas Prometheus

### Estado atual no documento externo

| Métrica | Tipo | Serviço |
|---------|------|---------|
| `changes_created_total` | Counter | change-service |
| `events_published_total` | Counter | change-service |
| `events_consumed_total` | Counter | deploy-orchestrator |
| `events_failed_total` | Counter | deploy-orchestrator |
| `http_server_requests_seconds` | Histogram | change-service |

### Estado correto (após implementação)

| Métrica | Tipo | Serviço | Descrição |
|---------|------|---------|-----------|
| `changes_created_total` | Counter | change-service | Total de mudanças criadas |
| `changes_completed_total` | Counter | deploy-orchestrator | Mudanças com status final COMPLETED |
| `changes_failed_total` | Counter | deploy-orchestrator | Mudanças com status final FAILED |
| `events_published_total{type="..."}` | Counter | change-service, deploy-orchestrator | Eventos publicados no Kafka |
| `events_consumed_total{type="DeployFinishedEvent"}` | Counter | deploy-orchestrator | Eventos consumidos com sucesso |
| `events_retries_total{consumer="deploy-orchestrator"}` | Counter | deploy-orchestrator | Tentativas de reprocessamento (cada retry conta 1) |
| `events_failed_total{consumer="deploy-orchestrator"}` | Counter | deploy-orchestrator | Falhas permanentes (esgotaram todas as tentativas e foram para DLT) |
| `events_dlt_total{consumer="deploy-orchestrator"}` | Counter | deploy-orchestrator | Eventos enviados para Dead Letter Topic |
| `events_discarded_total{reason="duplicate"}` | Counter | deploy-orchestrator | Eventos descartados por idempotência |
| `http_server_requests_seconds` | Histogram | change-service | Latência HTTP por método/path/status |
| `orchestration_duration_seconds` | Timer/Histogram | deploy-orchestrator | Tempo de processamento end-to-end do DeployFinishedEvent |

### Alterações resumidas

1. **Adicionar** `changes_completed_total` e `changes_failed_total` — contadores de status final de mudanças.
2. **Adicionar** `events_retries_total` — nova métrica que conta tentativas de retry (o que antes era `events_failed_total`).
3. **Redefinir** `events_failed_total` — agora conta apenas falhas permanentes (evento que esgotou retries e foi para DLT), não mais cada tentativa de retry.
4. **Adicionar** `events_dlt_total`, `events_discarded_total`, `orchestration_duration_seconds` — já existem na implementação mas não aparecem no escopo externo.

---

## 2. Contagem de Painéis do Dashboard Grafana

### Estado atual no documento externo

Referência a **7 painéis** no dashboard Grafana.

### Estado correto

O dashboard agora possui **14 painéis** organizados em 3 seções:

| Seção | Painéis |
|-------|----------|
| **Changes** | Created, Completed, Failed, Prepared (stat) + Distribution (piechart) + API Latency (timeseries) |
| **Events** | Published, Consumed, Failed, Retries, DLT, Discarded (stat) + Events Rate (timeseries) |
| **Orchestration** | Orchestration Latency p95 (timeseries) |

---

## 3. Semântica de `events_failed_total`

### Estado atual no documento externo

`events_failed_total` é descrita como "Falhas no processamento" ou "Eventos enviados para DLT", o que é ambíguo.

---

## 4. Frontend Containerizado no Docker Compose

### Contexto

Anteriormente, o frontend React precisava ser iniciado manualmente (`npm install` + `npm run dev`) separado da stack Docker Compose.

### Alteração implementada

O frontend foi containerizado com build multi-stage (Node 20 + Nginx 1.27) e adicionado como serviço `frontend` no Docker Compose. Agora **toda a stack sobe com um único comando**:

```bash
docker compose up -d --build
# ou
make up
```

### Impacto nos documentos externos

#### `escopo_tecnico.md` — Seção "Como rodar localmente"

O comando `docker-compose up -d` agora sobe **todos os serviços**, incluindo o frontend. Não é necessário nenhum comando `npm` manual para executar a POC.

**Atualizar de:**
```bash
# Subir toda a stack (broker + db + serviços)
docker-compose up -d
```
**Para:**
```bash
# Subir toda a stack (infra + backend + frontend)
docker-compose up -d --build
# Frontend disponível em http://localhost:3000 automaticamente
```

#### `escopo_tecnico.md` — Seção "Estrutura do Repositório"

Adicionar os novos arquivos de infra do frontend:

```
frontend/
├── Dockerfile          # Multi-stage: Node 20 (build) + Nginx 1.27 (runtime)
├── nginx.conf          # Proxy /api → change-service; SPA fallback
├── .dockerignore
└── src/
    └── ...
```

#### `escopo_tecnico.md` — Seção "Stack"

Atualizar a linha de Infra para refletir Nginx como servidor de produção do frontend:

| Infra | Docker + Docker Compose — 9 serviços (inclui frontend servido por Nginx) |

#### `escopo.md` — Seção "5.2 Stack Tecnológico"

Mesma atualização: mencionar Nginx como servidor estático do frontend containerizado.

### Arquitetura do serviço `frontend` no Compose

| Propriedade | Valor |
|-------------|-------|
| Imagem base build | `node:20-alpine` |
| Imagem base runtime | `nginx:1.27-alpine` |
| Porta | `3000:80` |
| Proxy reverso | `/api/` → `http://change-service:8080/api/` |
| SPA fallback | `try_files $uri $uri/ /index.html` |
| Depende de | `change-service` (service_healthy) |
| Healthcheck | `wget` em `http://localhost/index.html` |

### Checklist de atualização

- [ ] Seção "Como rodar localmente" do `escopo_tecnico.md` — remover instrução de npm manual
- [ ] Seção "Estrutura do Repositório" do `escopo_tecnico.md` — adicionar `Dockerfile` e `nginx.conf` no frontend
- [ ] Seção "Stack" do `escopo_tecnico.md` — atualizar Infra para "9 serviços"
- [ ] Seção "5.2 Stack Tecnológico" do `escopo.md` — mencionar Nginx no frontend
- [ ] Contagem de serviços Docker Compose: **8 → 9**

### Correção necessária

Separar claramente dois conceitos:

| Conceito | Métrica | Onde é incrementada | Significado |
|----------|---------|---------------------|-------------|
| Tentativa de retry | `events_retries_total` | `DeployEventConsumer.onDeployFinished()` — ao entrar em tópico retry | Cada vez que um evento é processado a partir de um tópico `*-retry-*` (contagem de hops de retry, não de falhas) |
| Falha permanente | `events_failed_total` | `DeployEventConsumer.onDlt()` (DLT handler) | Evento que esgotou todas as tentativas e foi descartado para o Dead Letter Topic |

> **Nota:** Um único evento que falha permanentemente gera até **3 incrementos** em `events_retries_total` (um por tópico retry-0/1/2 visitado) e exatamente 1 incremento em `events_failed_total`.

---

## 5. Descrição do Fluxo de Orquestração

Se o documento externo descreve o fluxo do deploy-orchestrator, atualizar para incluir:

1. Após o processamento com sucesso, incrementa `changes_completed_total` ou `changes_failed_total` conforme o resultado do deploy.
2. Em caso de exceção, o evento é reprocessado pelo Spring Kafka Retry (até 4 tentativas, backoff 500ms×2).
3. Se todas as tentativas falharem, o `@DltHandler` incrementa `events_failed_total` e `events_dlt_total`.

---

## 6. Checklist de Revisão

- [ ] Tabela de métricas atualizada com todas as 11 métricas
- [ ] `events_failed_total` redefinido como "falhas permanentes"
- [ ] `events_retries_total` adicionado como "tentativas de retry"
- [ ] `changes_completed_total` e `changes_failed_total` documentados
- [ ] Contagem de painéis do dashboard atualizada (7 → 14)
- [ ] Descrição dos painéis de Events atualizada com Retries/Failed/DLT/Discarded
