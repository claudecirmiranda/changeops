# Diagrama de Sequência — Fluxo 2: Orquestração de Deploy

```mermaid
sequenceDiagram
    autonumber
    participant ES as Sistema de Deploy (externo)
    participant K as Kafka
    participant DO as deploy-orchestrator
    participant DB as PostgreSQL

    ES->>K: Publica DeployFinishedEvent<br/>topic: changeops.deploy.finished<br/>{deployId, changeId, result, executedAt}

    K->>DO: @KafkaListener consome evento<br/>DeployEventConsumer.onDeployFinished()

    Note over DO: MDC.put(correlation_id, change_id, deploy_id)

    DO->>DO: events_consumed_total.increment()

    DO->>DB: INSERT INTO processed_events (event_id, service_name)<br/>ON CONFLICT DO NOTHING<br/>[IdempotencyPort.tryMarkAsProcessed()]

    alt Evento já processado (retorno = 0)
        DO->>DO: log.warn("Event already processed, discarding")
        Note over DO: Descarta silenciosamente — sem erro, sem reprocessamento
    end

    DO->>DO: PostDeployChecklistService.execute(changeId, deployId, result)<br/>→ 4 checks: deploy-result-gate, healthcheck, smoke-test, error-rate-threshold

    alt Deploy SUCCESS + todos checks passam
        DO->>DB: UPDATE changes SET status='COMPLETED' WHERE change_id=?<br/>[UpdateChangeStatusPort.markCompleted()]
        DO->>DB: INSERT INTO change_events (event_type='ChangeCompletedEvent', payload)<br/>[SaveChangeEventPort.save()]
        DO->>K: kafkaTemplate.send("changeops.change.result", IntegrationEvent)<br/>{eventType: "ChangeCompletedEvent", payload: {changeId, deployId, completedAt}}
    else Deploy FAILURE ou check falha
        DO->>DB: UPDATE changes SET status='FAILED' WHERE change_id=?<br/>[UpdateChangeStatusPort.markFailed()]
        DO->>DB: INSERT INTO change_events (event_type='ChangeFailedEvent', payload)<br/>[SaveChangeEventPort.save()]
        DO->>K: kafkaTemplate.send("changeops.change.result", IntegrationEvent)<br/>{eventType: "ChangeFailedEvent", payload: {changeId, deployId, reason, failedAt}}
    end

    K-->>DO: ACK

    Note over DO: MDC.clear()

    rect rgb(255, 235, 235)
        Note over K,DO: Cenário de Falha — Retry + DLQ

        K->>DO: Consumo falha (exceção)
        DO->>DO: Retry #1 (500ms backoff)
        DO->>DO: Retry #2 (1000ms backoff)
        DO->>DO: Retry #3 (2000ms backoff)
        DO->>DO: Retry #4 (4000ms backoff)
        DO->>K: Envia para DLT: changeops.deploy.finished-dlt
        
        K->>DO: @KafkaListener(dlt) consome de DLT<br/>DeployEventConsumer.onDlt()
        DO->>DO: log.error("Event sent to DLQ after max retries")<br/>events_failed_total.increment()
    end

    rect rgb(255, 235, 235)
        Note over DO,K: Cenário de Falha na Publicação do Resultado

        DO->>K: kafkaTemplate.send() falha (timeout/erro)
        DO->>K: Fallback: envia para DLQ "changeops.events.dlq"<br/>[KafkaResultPublisherAdapter.sendToDlq()]
        DO->>DO: log.error("Failed to publish result event — sending to DLQ")
    end
```

## Detalhes do Fluxo

1. **Idempotência atômica:** `INSERT ... ON CONFLICT DO NOTHING` retorna 1 (primeiro processamento) ou 0 (duplicata). Sem race conditions.
2. **Checklist pós-deploy:** 4 verificações simuladas. Se qualquer check falha, o status é `FAILED` com reason detalhado.
3. **Retry com backoff exponencial:** 4 tentativas com delays de 500ms → 1s → 2s → 4s (max 10s). Configurado via `@RetryableTopic`.
4. **Dois caminhos de DLQ:**
   - **Consumo:** `@RetryableTopic` com DLT suffix — tópico `changeops.deploy.finished-dlt`.
   - **Publicação:** Fallback no adapter — tópico `changeops.events.dlq`.
5. **Observabilidade:** MDC com `correlation_id`, `change_id`, `deploy_id` em todos os logs do fluxo. Métricas: `events_consumed_total`, `events_published_total`, `events_failed_total`.
