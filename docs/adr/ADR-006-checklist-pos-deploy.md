# ADR-006 — Checklist Pós-Deploy como Contrato de Integração Simulado

**Status:** Aceito
**Data:** Março/2026
**Decisores:** Equipe Técnica

## Contexto

O `deploy-orchestrator` executa um `PostDeployChecklistService` com 4 verificações após receber um `DeployFinishedEvent`. A RFP exige "simulação de validação pós-deploy" como parte do Cenário 2.

A questão central é: **qual o nível de integração real esperado nesses checks para esta POC?**

Sistemas de mudança em produção tipicamente integram com:
- Endpoints de healthcheck dos serviços deployados
- APIs de monitoramento (Prometheus, Datadog, New Relic)
- Pipelines de smoke test automatizados (Selenium, k6, Postman)
- Thresholds de error rate em janelas de tempo pós-deploy

Implementar essas integrações reais exigiria infraestrutura de suporte (serviços-alvo, APIs de monitoramento externo, ambientes de staging) fora do escopo da POC.

## Decisão

Adotamos a implementação dos 4 checks como **stubs que representam contratos de integração**, não como lógica real. Cada stub:

1. Declara explicitamente sua **intenção** (o que verificaria em produção)
2. Deriva seu resultado do campo `deploySucceeded` do evento de entrada — única fonte de verdade disponível sem integrações externas
3. Retorna `ChecklistResult` com `passed`, `failedStep` e `reason` — estrutura idêntica à que uma integração real popularia

## Os 4 Checks e seus Contratos de Produção

### 1. `deploy-result-gate`

| | POC (stub) | Produção |
|---|---|---|
| **O que faz** | Verifica `deploySucceeded == true` | Consulta status final do pipeline CI/CD via API (GitHub Actions, Jenkins, ArgoCD) |
| **Fonte de dados** | Campo do evento recebido | `GET /api/deploy/{deployId}/status` no sistema de CI/CD |
| **Falha quando** | `deploySucceeded == false` | Status pipeline = `FAILED`, `CANCELLED` ou timeout |

### 2. `healthcheck`

| | POC (stub) | Produção |
|---|---|---|
| **O que faz** | Retorna `passed` se deploy ok | `GET /actuator/health` no serviço deployado com timeout de 30s |
| **Fonte de dados** | Resultado do check anterior | Endpoint Spring Actuator ou equivalente do serviço alvo |
| **Falha quando** | Deploy falhou | HTTP != 200 ou `{"status": "DOWN"}` |

### 3. `smoke-test`

| | POC (stub) | Produção |
|---|---|---|
| **O que faz** | Retorna `passed` se checks anteriores ok | Executa suite de smoke tests (Postman/Newman, k6, script curl) via API de testes |
| **Fonte de dados** | Resultado acumulado do checklist | `POST /test-runner/run?suite=smoke&target={componentId}` |
| **Falha quando** | Deploy falhou | >= 1 teste de smoke falhou |

### 4. `error-rate-threshold`

| | POC (stub) | Produção |
|---|---|---|
| **O que faz** | Retorna `passed` se checks anteriores ok | Consulta Prometheus/Datadog para taxa de erro nos 5 min pós-deploy |
| **Fonte de dados** | Resultado acumulado | `GET /api/v1/query?query=rate(http_requests_total{status=~"5..",job="{componentId}"}[5m])` |
| **Falha quando** | Deploy falhou | Taxa de erros 5xx > threshold configurável (default: 1%) |

## Fluxo Atual (Stub)

```
DeployFinishedEvent { deploySucceeded: true/false }
  └─ PostDeployChecklistService
       ├─ deploy-result-gate  → passed = deploySucceeded
       ├─ healthcheck         → passed = resultado anterior
       ├─ smoke-test          → passed = resultado anterior
       └─ error-rate-threshold→ passed = resultado anterior
            └─ ChecklistResult { passed: true/false, failedStep, reason }
```

O resultado do primeiro check (`deploy-result-gate`) propaga-se em cascata. Em cenário de sucesso, todos os 4 checks passam. Em falha, `failedStep` aponta para `deploy-result-gate` e a razão é propagada para o evento de saída.

## Fluxo em Produção (Contrato)

```
DeployFinishedEvent { deployId, componentId, correlationId }
  └─ PostDeployChecklistService
       ├─ deploy-result-gate  → GET cicd-api/deploy/{deployId}
       ├─ healthcheck         → GET {componentId}/actuator/health
       ├─ smoke-test          → POST test-runner/run?suite=smoke
       └─ error-rate-threshold→ GET prometheus/query (rate 5xx [5m])
            └─ ChecklistResult { passed, failedStep, reason, checkDetails[] }
```

A interface `ChecklistResult` foi desenhada para absorver ambos os fluxos sem alteração de assinatura — substituir stubs por integrações reais requer apenas alterar o corpo dos métodos privados em `PostDeployChecklistService`, sem impacto em `ProcessDeployResultService` ou no contrato de eventos de saída.

## Evidência de Rastreabilidade

Cada resultado do checklist é persistido no event timeline da mudança via `SaveChangeEventPort`, com:
- `stepName`: nome do check que falhou
- `reason`: motivo da falha (propagado para `ChangeFailedEvent`)
- `correlationId`: rastreabilidade end-to-end nos logs

Isso garante que, mesmo com stubs, o fluxo de observabilidade e auditoria está completamente funcional.

## Alternativas Consideradas

### 1. Checks stubados com resultado configurável por evento (escolhida)

- Permite demonstrar cenários de sucesso e falha controlados via `deploySucceeded` no evento
- Estrutura final idêntica à integração real (contrato preservado)
- Zero dependência de infraestrutura externa

### 2. Integração real com Actuator do próprio change-service

- Usaria `localhost:8080/actuator/health` como alvo do healthcheck
- Funcionaria no ambiente Docker Compose, mas seria auto-referente (serviço verificando a si mesmo)
- Daria falsa impressão de integração real sem demonstrar o padrão correto

### 3. Mock HTTP com WireMock

- Simularia APIs externas via servidor HTTP fake
- Maior complexidade de setup sem ganho de demonstração arquitetural
- Adiciona dependência de infraestrutura de teste ao runtime

## Trade-offs

| Aspecto | Stubs (escolhida) | Integração real | WireMock |
|---|---|---|---|
| Fidelidade ao contrato | ✅ Estrutura idêntica | ✅ Total | ✅ Alta |
| Dependência externa | ✅ Nenhuma | ❌ CI/CD, Prometheus, etc. | ⚠ WireMock server |
| Demonstração de cenários | ✅ Controlável via evento | ⚠ Depende de infra real | ✅ Controlável |
| Esforço de implementação | ✅ Baixo | ❌ Alto | ⚠ Médio |
| Valor arquitetural | ✅ Contrato documentado | ✅ Total | ⚠ Parcial |

## Consequências

### Positivas
- Fluxo completo de orquestração demonstrável sem infraestrutura de CI/CD externa
- Contrato de integração explícito: cada stub documenta a API que chamaria em produção
- Estrutura permite substituição incremental por integrações reais sem refatoração (open/closed principle)

### Negativas / Riscos
- Checks não validam o estado real do serviço deployado — resultado é sempre derivado do evento de entrada
- Risco de interpretação equivocada de que o sistema está "totalmente integrado" sem leitura dos stubs

### Mitigações
- Comentários `// STUB: em produção, chamar GET {url}` em cada método do `PostDeployChecklistService`
- Este ADR documenta explicitamente o que cada check representa, eliminando ambiguidade
- Métricas de checklist (`checklist_steps_total{step, result}`) emitidas mesmo para stubs, mantendo observabilidade

## Roadmap

**Phase 2 — Integração Real do Checklist:**
- `deploy-result-gate`: integração com GitHub Actions API ou ArgoCD
- `healthcheck`: cliente HTTP para `/actuator/health` do `componentId` resolvido via service registry
- `smoke-test`: integração com test runner (Newman/k6) via API
- `error-rate-threshold`: query ao Prometheus via HTTP API com threshold configurável por `componentId`

## Relacionado a

- [ADR-001](./ADR-001-escolha-message-broker.md) — `DeployFinishedEvent` consumido do Kafka desencadeia execução do checklist
- [ADR-002](./ADR-002-estrategia-idempotencia.md) — Idempotência garante que o checklist não é re-executado em duplicatas
- [ADR-003](./ADR-003-eventos-dominio-vs-integracao.md) — Resultado do checklist é encapsulado em `ChangeCompletedEvent`/`ChangeFailedEvent` via envelope de integração
- [ADR-005](./ADR-005-estrutura-pacotes.md) — `PostDeployChecklistService` vive em `application/service/`, sem dependências de infraestrutura direta

## Conformidade com a RFP

| Requisito | Status | Evidência |
|-----------|--------|-----------|
| "Simulação de validação pós-deploy" | ✅ Atendido | `PostDeployChecklistService` com 4 checks explícitos, resultado persistido no timeline |
| "Resultado de sucesso/falha avaliado" | ✅ Atendido | `ChecklistResult.passed` determina `COMPLETED` vs `FAILED` em `ProcessDeployResultService` |
| "Rastreabilidade do motivo de falha" | ✅ Atendido | `failedStep` e `reason` propagados para evento de saída e logs estruturados |
| "Extensibilidade para integrações reais" | ✅ Atendido | Contrato de cada check documentado neste ADR; substituição sem impacto externo |
| "Logs com contexto de fluxo" | ✅ Atendido | MDC com `correlation_id`, `change_id`, `checklist_step` em cada verificação |
