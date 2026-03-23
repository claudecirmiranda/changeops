# Diagrama de Sequência — Fluxo 1: Criação de Mudança

```mermaid
sequenceDiagram
    autonumber
    participant U as Operador (Browser)
    participant FE as Frontend (React)
    participant CS as change-service
    participant DB as PostgreSQL
    participant K as Kafka

    U->>FE: Preenche formulário e clica "Create Change"
    FE->>FE: Validação client-side (campos obrigatórios, data futura)

    FE->>CS: POST /api/v1/changes<br/>{title, description, componentId, requestedBy, scheduledAt}
    
    Note over CS: CorrelationIdFilter<br/>Lê X-Correlation-Id ou gera UUID<br/>Armazena no MDC

    CS->>CS: Bean Validation (@Valid)<br/>Campos obrigatórios, maxLength, @Future

    alt Validação falha
        CS-->>FE: 400 Bad Request<br/>ProblemDetail RFC 7807 + fields map
        FE-->>U: Exibe erros por campo (form data preservado)
    end

    CS->>CS: Change.create(title, desc, componentId, requestedBy, scheduledAt)<br/>→ status = PREPARED<br/>→ correlationId = UUID.randomUUID()<br/>→ domainEvents.add(ChangePreparedEvent)

    CS->>DB: INSERT INTO changes (...) VALUES (...)<br/>[SaveChangePort.save()]
    DB-->>CS: Change persistido

    CS->>CS: saved.pullDomainEvents()<br/>→ List&lt;ChangePreparedEvent&gt;

    CS->>K: kafkaTemplate.send("changeops.change.prepared", changeId, IntegrationEvent)<br/>[PublishEventPort.publish()]
    Note over CS,K: IntegrationEvent envelope:<br/>{eventType, version:"1.0", correlationId, occurredAt, payload}
    K-->>CS: ACK (acks=all)

    CS->>DB: INSERT INTO change_events (event_type, payload, change_id)<br/>[SaveChangeEventPort.save()]
    DB-->>CS: Evento registrado na timeline

    CS->>CS: changesCreatedCounter.increment()

    CS-->>FE: 201 Created + Location header<br/>{changeId, status:"PREPARED", correlationId, createdAt}

    FE->>FE: Fecha formulário, exibe sucesso
    FE-->>U: Mudança aparece na listagem

    Note over FE: Polling a cada 5s atualiza listagem
```

## Detalhes do Fluxo

1. **Validação em duas camadas:** client-side (HTML5 + JS) e server-side (Bean Validation + domínio).
2. **Status direto:** `Change.create()` define status como `PREPARED` (não passa por `DRAFT` — ver [ADR-005](../adr/ADR-005-estrutura-pacotes.md)).
3. **Evento de domínio → integração:** `ChangePreparedEvent` é encapsulado em `IntegrationEvent` pelo adapter Kafka antes da publicação.
4. **Transacionalidade:** `@Transactional` no `CreateChangeService.execute()` — DB commit + Kafka publish na mesma transação lógica (gap: sem Outbox — ver Roadmap Phase 2.1).
5. **Observabilidade:** `correlation_id` no MDC desde o filtro HTTP, presente em todos os logs e no evento publicado.
