# Diagrama de Sequência — Fluxo 2: Orquestração de Deploy

```mermaid
%%{init: {'theme':'dark','themeVariables':{'fontSize':'14px','actorTextColor':'#E0E7FF','actorBkg':'#4338CA','actorBorder':'#6366F1','activationBorderColor':'#6366F1','signalColor':'#CBD5E1','signalTextColor':'#E2E8F0','noteBkgColor':'#1E293B','noteBorderColor':'#6366F1','noteTextColor':'#E2E8F0','altSectionBkgColor':'#0F172A','labelTextColor':'#A5B4FC'},'sequence':{'mirrorActors':false,'messageFontSize':14,'noteFontSize':13,'actorFontSize':15,'noteMargin':12,'messageMargin':40,'width':220}}}%%
sequenceDiagram
    autonumber
    participant ES as Sistema de Deploy
    participant K as Kafka
    participant DO as deploy-orchestrator
    participant DB as PostgreSQL

    ES->>K: Publica DeployFinishedEvent
    Note over ES,K: topic: changeops.deploy.finished<br/>{deployId, changeId, result, executedAt}

    K->>DO: Consome evento via @KafkaListener

    Note over DO: MDC.put(correlation_id, change_id, deploy_id)

    DO->>DO: events_consumed_total.increment()

    DO->>DB: INSERT INTO processed_events (ON CONFLICT DO NOTHING)
    Note over DO,DB: IdempotencyPort.tryMarkAsProcessed()

    alt Evento já processado (retorno = 0)
        DO->>DO: log.warn("Event already processed")
        Note over DO: Descarta silenciosamente
    end

    DO->>DO: PostDeployChecklistService.execute()
    Note over DO: 4 checks: deploy-result-gate,<br/>healthcheck, smoke-test, error-rate

    alt Deploy SUCCESS + todos checks passam
        DO->>DB: UPDATE changes SET status='COMPLETED'
        DO->>DB: INSERT INTO change_events (ChangeCompletedEvent)
        DO->>K: Publica ChangeCompletedEvent
        Note over DO,K: topic: changeops.change.result
    else Deploy FAILURE ou check falha
        DO->>DB: UPDATE changes SET status='FAILED'
        DO->>DB: INSERT INTO change_events (ChangeFailedEvent)
        DO->>K: Publica ChangeFailedEvent
        Note over DO,K: topic: changeops.change.result
    end

    K-->>DO: ACK
    Note over DO: MDC.clear()

    rect rgb(69, 26, 26)
        Note over K,DO: Cenário de Falha — Retry + DLQ

        K->>DO: Consumo falha (exceção)
        DO->>DO: Retry 1 — entra em changeops.deploy.finished-retry-0
        DO->>DO: events_retries_total.increment() (+1)
        DO->>DO: Retry 2 — entra em changeops.deploy.finished-retry-1
        DO->>DO: events_retries_total.increment() (+1)
        DO->>DO: Retry 3 — entra em changeops.deploy.finished-retry-2
        DO->>DO: events_retries_total.increment() (+1)
        DO->>K: Esgotou retries → Envia para DLT
        Note over K: changeops.deploy.finished-dlt

        K->>DO: @KafkaListener(dlt) consome de DLT
        DO->>DO: log.error + events_failed_total.increment() + events_dlt_total.increment()
    end

    rect rgb(69, 26, 26)
        Note over DO,K: Falha na Publicação do Resultado

        DO->>K: kafkaTemplate.send() falha
        DO->>K: Fallback: envia para DLQ
        Note over K: changeops.events.dlq
        DO->>DO: log.error("Sending to DLQ")
    end
```

## Detalhes do Fluxo

1. **Idempotência atômica:** `INSERT ... ON CONFLICT DO NOTHING` retorna 1 (primeiro processamento) ou 0 (duplicata). Sem race conditions.
2. **Checklist pós-deploy:** 4 verificações simuladas. Se qualquer check falha, o status é `FAILED` com reason detalhado.
3. **Retry com backoff exponencial:** 4 tentativas com delays de 500ms → 1s → 2s → 4s (max 10s). Configurado via `@RetryableTopic`.
4. **Dois caminhos de DLQ:**
   - **Consumo:** `@RetryableTopic` com DLT suffix — tópico `changeops.deploy.finished-dlt`.
   - **Publicação:** Fallback no adapter — tópico `changeops.events.dlq`.
5. **Observabilidade:** MDC com `correlation_id`, `change_id`, `deploy_id` em todos os logs do fluxo. Métricas: `events_consumed_total`, `events_published_total`, `events_retries_total` (tentativas de retry), `events_failed_total` (falhas permanentes), `events_dlt_total`.
