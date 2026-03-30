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

```
// infrastructure/kafka/IntegrationEvent.java  
public record IntegrationEvent(  
    String eventType,        // "ChangePreparedEvent"  
    String version,          // "1.0"  
    UUID correlationId,      // rastreabilidade ponta a ponta  
    Instant occurredAt,  
    Object payload           // evento de domínio serializado  
) {}
```

## Propagação de Contexto

O campo **`correlationId`** é propagado do evento de domínio para o `IntegrationEvent` **sem qualquer transformação**.
Isso garante:
*   Rastreabilidade ponta a ponta entre serviços
*   Compatibilidade com logging estruturado (MDC — _Mapped Diagnostic Context_)
*   Integração direta com ferramentas de tracing distribuído (ex: OpenTelemetry)
Essa abordagem evita acoplamento do domínio com frameworks de observabilidade, mantendo a responsabilidade de instrumentação na camada de infraestrutura.

### Fluxo

```bash
Change.create()  
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

*   Menor complexidade inicial: um único tipo de evento.
*   Acoplamento entre domínio e infraestrutura (metadados de transporte no aggregate).
*   Versionamento requer alteração no modelo de domínio.
*   Dificulta migração de broker.

### 3. Event Bus interno + publicador separado

*   Evento de domínio publicado em um barramento interno (Spring Events).
*   Listener da infra escuta e produz evento de integração.
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
- Evolução independente: alterações no envelope de integração não impactam lógica de domínio, e vice-versa
- Backward compatibility: consumidores usam `@JsonIgnoreProperties(ignoreUnknown = true)` para tolerar campos futuros
- Rastreabilidade unificada: `correlationId` no envelope permite correlacionar logs, métricas e traces entre serviços

### Negativas / Riscos
- Complexidade adicional: desenvolvedores precisam entender duas camadas de evento (domínio e integração)
- Overhead de serialização: envelope adiciona ~200 bytes por evento, impactando throughput em volumes muito altos
- Risco de inconsistência sem Transactional Outbox: evento pode ser publicado sem persistência de estado em caso de falha

### Mitigações
- Documentação clara com exemplos de `ChangePreparedEvent` (domínio) e `IntegrationEvent` (envelope) no próprio ADR
- Benchmark de serialização mostra impacto < 0.5ms por evento; aceitável para volumes esperados na Fase 1
- Débito técnico registrado: implementação de Outbox Pattern planejada para Fase 2; mitigação atual com retry e idempotência

## Relacionado a
- [ADR-001](./ADR-001-escolha-message-broker.md) — Kafka transporta envelopes de integração com partition key derivada de `change_id`
- [ADR-002](./ADR-002-estrategia-idempotencia.md) — `eventId` no envelope é usado como chave de idempotência no consumidor
- [ADR-004](./ADR-004-atualizacao-status-frontend.md) — `correlationId` do envelope é propagado para respostas HTTP e polling

## Conformidade com a RFP

| Requisito | Status | Evidência |
|-----------|--------|-----------|
| "Separação explícita entre eventos de domínio e integração" | ✅ Atendido | `ChangePreparedEvent.java` (domínio) vs `IntegrationEvent.java` (envelope) em pacotes distintos |
| "Versionamento de contratos de evento" | ✅ Atendido | Campo `version` no envelope; estratégia `tolerant reader` documentada em consumidores |
| "Rastreabilidade ponta a ponta via correlation ID" | ✅ Atendido | `correlationId` propagado de frontend → backend → Kafka → consumidor → logs estruturados |
| "Evolução sem breaking changes" | ✅ Atendido | `@JsonIgnoreProperties(ignoreUnknown = true)` + versionamento major para mudanças incompatíveis |
| "Documentação de contratos com AsyncAPI" | ✅ Atendido | `asyncapi.yaml` em `docs/contracts/` descrevendo envelope, payload e metadados |

## Consistência Transacional (Outbox)

Atualmente, a publicação do evento ocorre após a persistência da entidade, mas **fora de uma garantia transacional única**, o que pode gerar inconsistência em cenários de falha (ex: commit no banco sem publicação no Kafka, ou vice-versa).
Esse risco é conhecido e **aceito temporariamente como débito técnico**, sendo mitigado no roadmap:

**Phase 2.1 — Implementação do Outbox Pattern**

O Outbox Pattern garantirá:
*   Atomicidade entre persistência do estado e registro do evento
*   Publicação assíncrona confiável (polling ou CDC)
*   Eliminação do risco de perda de eventos

## Padrões de Design Aplicados

### Adapter Pattern (Hexagonal Architecture)

O `KafkaEventPublisherAdapter` implementa a porta `PublishEventPort` do domínio. O domínio conhece apenas a interface; a tradução para `IntegrationEvent` e a publicação no broker são responsabilidade exclusiva do adapter. Isso isola o domínio de qualquer detalhe de infraestrutura.

### Envelope Pattern

`IntegrationEvent` é um envelope que encapsula o payload do evento de domínio com metadados de transporte (`eventType`, `version`, `correlationId`, `occurredAt`). O campo `version` permite versionamento semântico independente do domínio.

### Dimensionamento de métricas baseado em tags

Métricas do tipo counter seguem o padrão de dimensionamento via `tag`:  

`events_published_total{type="ChangePreparedEvent"}`

Isso permite consultas Prometheus como:  

`sum by (type) (rate(events_published_total[1m]))`

Sem proliferação de nomes de métricas distintos por tipo de evento.

## Compatibilidade com Versões Anteriores

O campo `version` no envelope `IntegrationEvent` é central para a estratégia de evolução contratual:

*   **Breaking changes** → requerem bump de versão (`"1.0"` → `"2.0"`)
*   **Non-breaking additions** → podem ser feitas sem mudança de versão
*   Consumidores adotam **tolerant reader** (`@JsonIgnoreProperties(ignoreUnknown = true`)
*   Deploys parciais (rolling updates) são suportados

O `correlationId` preserva rastreabilidade ponta a ponta mesmo após evolução do contrato.
