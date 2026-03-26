# Diagrama de Sequência — Fluxo 1: Criação de Mudança

```mermaid
%%{init: {'theme':'dark','themeVariables':{'fontSize':'14px','actorTextColor':'#E0E7FF','actorBkg':'#4338CA','actorBorder':'#6366F1','activationBorderColor':'#6366F1','signalColor':'#CBD5E1','signalTextColor':'#E2E8F0','noteBkgColor':'#1E293B','noteBorderColor':'#6366F1','noteTextColor':'#E2E8F0','altSectionBkgColor':'#0F172A','labelTextColor':'#A5B4FC'},'sequence':{'mirrorActors':false,'messageFontSize':14,'noteFontSize':13,'actorFontSize':15,'noteMargin':12,'messageMargin':40,'width':220}}}%%
sequenceDiagram
    autonumber
    participant U as Operador (Browser)
    participant FE as Frontend (React)
    participant CS as change-service
    participant DB as PostgreSQL
    participant K as Kafka

    U->>FE: Preenche formulário e clica "Create Change"
    FE->>FE: Validação client-side

    FE->>CS: POST /api/v1/changes
    Note over FE,CS: {title, description, componentId,<br/>requestedBy, scheduledAt}

    Note over CS: CorrelationIdFilter<br/>Lê X-Correlation-Id ou gera UUID

    CS->>CS: Bean Validation (@Valid)

    alt Validação falha
        CS-->>FE: 400 Bad Request (ProblemDetail RFC 7807)
        FE-->>U: Exibe erros por campo
    end

    CS->>CS: Change.create(...)
    Note over CS: status = PREPARED<br/>correlationId = UUID<br/>domainEvents.add(ChangePreparedEvent)

    CS->>DB: INSERT INTO changes
    DB-->>CS: Change persistido

    CS->>CS: saved.pullDomainEvents()

    CS->>K: Publica ChangePreparedEvent
    Note over CS,K: IntegrationEvent envelope:<br/>{eventType, version, correlationId, payload}
    K-->>CS: ACK (acks=all)

    CS->>DB: INSERT INTO change_events
    DB-->>CS: Evento registrado na timeline

    CS->>CS: changesCreatedCounter.increment()

    CS-->>FE: 201 Created + Location header
    Note over CS,FE: {changeId, status:"PREPARED",<br/>correlationId, createdAt}

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
