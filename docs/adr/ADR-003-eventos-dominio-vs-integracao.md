# ADR-003 — Separação entre Evento de Domínio e Evento de Integração

**Status:** Aceito  
**Data:** Março/2026  
**Decisores:** Equipe Técnica

## Contexto

A RFP exige separação explícita entre **eventos de domínio** (internos ao bounded context) e **eventos de integração** (publicados para consumo externo via broker). A mistura desses dois conceitos gera acoplamento entre o modelo de domínio e a infraestrutura de transporte, dificultando versionamento independente e evolução do contrato.

## Decisão

Adotamos **separação explícita em duas camadas**:

### Evento de Domínio

Gerado pelo Aggregate Root (`Change`) no momento da transição de estado. Contém apenas dados do domínio, sem metadados de transporte.
```java
// domain/event/ChangePreparedEvent.java
public record ChangePreparedEvent(
    UUID changeId,
    String componentId,
    String requestedBy,
    Instant scheduledAt,
    UUID correlationId,
    Instant occurredAt
) implements DomainEvent {}
```

O evento é adicionado a uma lista interna do aggregate (`domainEvents`) e drenado pelo service após persistência (`pullDomainEvents()`).

### Evento de Integração (Envelope)

Produzido pelo adapter de infraestrutura (`KafkaEventPublisherAdapter`) ao publicar no broker. Encapsula o evento de domínio com metadados de transporte.
```java
// infrastructure/kafka/IntegrationEvent.java
public record IntegrationEvent(
    String eventType,        // "ChangePreparedEvent"
    String version,          // "1.0"
    UUID correlationId,      // rastreabilidade ponta a ponta
    Instant occurredAt,
    Object payload           // evento de domínio serializado
) {}
```

## Origem do correlationId

O `correlationId` é gerado pelo `CorrelationIdFilter` na camada de infraestrutura HTTP, a partir do header `X-Correlation-Id` da requisição recebida. Se o header não estiver presente, um UUID é gerado automaticamente pelo filter.

O valor é propagado via MDC (`correlation_id`) e lido pelo controller antes de construir o `Command`, sendo passado explicitamente para `Change.create()` — que não gera mais seu próprio `correlationId`.

Esse fluxo garante rastreabilidade desde o request HTTP até os eventos Kafka e logs do `deploy-orchestrator`:
```
X-Correlation-Id header (ou UUID gerado pelo filter)
  └─ CorrelationIdFilter → MDC.put("correlation_id", ...)
       └─ ChangeController → UUID.fromString(MDC.get("correlation_id"))
            └─ CreateChangeUseCase.Command(correlationId)
                 └─ Change.create(correlationId)
                      └─ ChangePreparedEvent(correlationId)
                           └─ IntegrationEvent(correlationId)
                                └─ Kafka → deploy-orchestrator MDC
```

## Propagação de Contexto

O campo **`correlationId`** é propagado do evento de domínio para o `IntegrationEvent` **sem qualquer transformação**.
Isso garante:
*   Rastreabilidade ponta a ponta entre serviços
*   Compatibilidade com logging estruturado (MDC)
*   Integração direta com ferramentas de tracing distribuído (ex: OpenTelemetry)

### Fluxo
```
Change.create(correlationId)
  └─ domainEvents.add(ChangePreparedEvent)
       └─ CreateChangeService.execute()
            └─ saved.pullDomainEvents()
                 └─ publishEventPort.publish(domainEvent)
                      └─ KafkaEventPublisherAdapter
                           └─ wrap(domainEvent) → IntegrationEvent
                                └─ kafkaTemplate.send(topic, key, integrationEvent)
```

## Alternativas Consideradas

### 1. Separação em duas camadas (escolhida)

*   Domínio desacoplado da infra de transporte.
*   Versionamento independente: campo `version` no envelope, sem alterar o domínio.
*   Payload tipado no adapter, genérico no domínio.
*   Facilita migração de broker (Kafka → outro) sem impacto no domínio.

### 2. Evento único (domínio = integração)

*   Menor complexidade inicial.
*   Acoplamento entre domínio e infraestrutura.
*   Versionamento requer alteração no modelo de domínio.

### 3. Event Bus interno + publicador separado

*   Maior separação, mas adiciona complexidade com dois mecanismos de publicação.
*   Risco de perda se listener falhar (a menos que use Outbox — ver Roadmap Phase 2).

## Trade-offs

| Aspecto | Separação (escolhida) | Evento único | Event Bus |
| --- | --- | --- | --- |
| Desacoplamento | ✅ Alto | ❌ Baixo | ✅ Alto |
| Complexidade | ⚠ Média (2 tipos) | ✅ Baixa | ⚠ Alta |
| Versionamento | ✅ Independente | ❌ Acoplado ao domínio | ✅ Independente |
| Testabilidade | ✅ Domínio testável isolado | ⚠ Teste depende de infra | ✅ Domínio testável |
| Migração de broker | ✅ Adapter absorve mudança | ❌ Impacta domínio | ✅ Adapter absorve |
| Consistência | ⚠ Sem Outbox (debt) | ⚠ Sem Outbox (debt) | ⚠ Depende de listener |

## Consequências

### Positivas
- Evolução independente: alterações no envelope de integração não impactam lógica de domínio
- Backward compatibility: consumidores usam `@JsonIgnoreProperties(ignoreUnknown = true)` para tolerar campos futuros
- Rastreabilidade unificada: `correlationId` propagado desde o request HTTP até logs do consumidor

### Negativas / Riscos
- Complexidade adicional: duas camadas de evento para entender
- Overhead de serialização: envelope adiciona ~200 bytes por evento
- Risco de inconsistência sem Transactional Outbox

### Mitigações
- Documentação clara com exemplos no próprio ADR
- Débito técnico registrado: Outbox Pattern planejado para Fase 2

## Relacionado a
- [ADR-001](./ADR-001-escolha-message-broker.md) — Kafka transporta envelopes de integração
- [ADR-002](./ADR-002-estrategia-idempotencia.md) — `deployId` no payload é usado como chave de idempotência
- [ADR-004](./ADR-004-atualizacao-status-frontend.md) — `correlationId` do envelope é propagado para respostas HTTP
- [ADR-007](./ADR-007-autenticacao-desenvolvimento.md) — Origem do `correlationId` via `CorrelationIdFilter` documentada

## Conformidade com a RFP

| Requisito | Status | Evidência |
|-----------|--------|-----------|
| "Separação explícita entre eventos de domínio e integração" | ✅ Atendido | `ChangePreparedEvent.java` (domínio) vs `IntegrationEvent.java` (envelope) em pacotes distintos |
| "Versionamento de contratos de evento" | ✅ Atendido | Campo `version` no envelope; estratégia `tolerant reader` em consumidores |
| "Rastreabilidade ponta a ponta via correlation ID" | ✅ Atendido | `correlationId` propagado de header HTTP → MDC → domínio → Kafka → consumidor → logs estruturados |
| "Evolução sem breaking changes" | ✅ Atendido | `@JsonIgnoreProperties(ignoreUnknown = true)` + versionamento major para mudanças incompatíveis |
| "Documentação de contratos com AsyncAPI" | ✅ Atendido | `events.yml` em `contracts/asyncapi/` descrevendo envelope, payload e metadados |

## Consistência Transacional (Outbox)

Atualmente, a publicação do evento ocorre após a persistência da entidade, mas **fora de uma garantia transacional única**. Esse risco é aceito temporariamente como débito técnico, sendo mitigado no roadmap com implementação do Outbox Pattern na Fase 2.

## Compatibilidade com Versões Anteriores

*   **Breaking changes** → bump de versão (`"1.0"` → `"2.0"`)
*   **Non-breaking additions** → sem mudança de versão
*   Consumidores adotam **tolerant reader** (`@JsonIgnoreProperties(ignoreUnknown = true)`)
*   Deploys parciais (rolling updates) são suportados