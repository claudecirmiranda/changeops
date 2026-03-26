# ADR-003 — Separação entre Evento de Domínio e Evento de Integração

**Status:** Aceito  
**Data:** Março/2026  
**Decisores:** Equipe Técnica

---

## Contexto

A RFP exige separação explícita entre **eventos de domínio** (internos ao bounded context) e **eventos de integração** (publicados para consumo externo via broker). A mistura desses dois conceitos gera acoplamento entre o modelo de domínio e a infraestrutura de transporte, dificultando versionamento independente e evolução do contrato.

---

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

### Fluxo

```
Change.create()
  └─ domainEvents.add(ChangePreparedEvent)
       └─ CreateChangeService.execute()
            └─ saved.pullDomainEvents()
                 └─ publishEventPort.publish(domainEvent)
                      └─ KafkaEventPublisherAdapter
                           └─ wrap(domainEvent) → IntegrationEvent
                                └─ kafkaTemplate.send(topic, key, integrationEvent)
```

---

## Alternativas Consideradas

### 1. Separação em duas camadas (escolhida)

- Domínio desacoplado da infra de transporte.
- Versionamento independente: campo `version` no envelope, sem alterar o domínio.
- Payload tipado no adapter, genérico no domínio.
- Facilita migração de broker (Kafka → outro) sem impacto no domínio.

### 2. Evento único (domínio = integração)

- Menor complexidade inicial: um único tipo de evento.
- Acoplamento entre domínio e infraestrutura (metadados de transporte no aggregate).
- Versionamento requer alteração no modelo de domínio.
- Dificulta migração de broker.

### 3. Event Bus interno + publicador separado

- Evento de domínio publicado em um barramento interno (Spring Events).
- Listener da infra escuta e produz evento de integração.
- Maior separação, mas adiciona complexidade com dois mecanismos de publicação.
- Risco de perda se listener falhar (a menos que use Outbox — ver Roadmap Phase 2).

---

## Trade-offs

| Aspecto | Separação (escolhida) | Evento único | Event Bus |
|---------|----------------------|--------------|-----------|
| Desacoplamento | ✅ Alto | ❌ Baixo | ✅ Alto |
| Complexidade | ⚠ Média (2 tipos) | ✅ Baixa | ⚠ Alta |
| Versionamento | ✅ Independente | ❌ Acoplado ao domínio | ✅ Independente |
| Testabilidade | ✅ Domínio testável isolado | ⚠ Teste depende de infra | ✅ Domínio testável |
| Migração de broker | ✅ Adapter absorve mudança | ❌ Impacta domínio | ✅ Adapter absorve |
| Consistência | ⚠ Sem Outbox (debt) | ⚠ Sem Outbox (debt) | ⚠ Depende de listener |

**Justificativa:** A separação em domínio/integração é a abordagem recomendada por DDD e pela RFP. O custo adicional é uma classe `IntegrationEvent` e um mapeamento no adapter — complexidade mínima para máximo desacoplamento. O Outbox pattern (Roadmap Phase 2.1) resolverá o gap de consistência eventual.

---

## Padrões de Design Aplicados

### Adapter Pattern (Hexagonal Architecture)

O `KafkaEventPublisherAdapter` implementa a porta `PublishEventPort` do domínio. O domínio conhece apenas a interface; a tradução para `IntegrationEvent` e a publicação no broker são responsabilidade exclusiva do adapter. Isso isola o domínio de qualquer detalhe de infraestrutura.

### Envelope Pattern

`IntegrationEvent` é um envelope que encapsula o payload do evento de domínio com metadados de transporte (`eventType`, `version`, `correlationId`, `occurredAt`). O campo `version` permite versionamento semântico independente do domínio.

### Tag-based Metric Dimensioning

Métricas do tipo counter seguem o padrão de dimensionamento via `tag`: `events_published_total{type="ChangePreparedEvent"}`. Isso permite consultas Prometheus como `sum by (type) (rate(events_published_total[1m]))`, sem proliferação de nomes de métricas distintos por tipo de evento.

---

## Compatibilidade com Versões Anteriores

O campo `version` no envelope `IntegrationEvent` é central para a estratégia de evolução contratual:

- **Breaking changes** (remoção de campos obrigatórios, mudança semântica) requerem bump de versão: `"1.0"` → `"2.0"`.
- **Non-breaking additions** (campos opcionais via `@JsonInclude(NON_NULL)`) podem ser introduzidos sem mudança de versão.
- **Consumidores** devem adotar tolerant reader: ignorar campos desconhecidos (`@JsonIgnoreProperties(ignoreUnknown = true)`) para suportar deployments parciais (rolling).
- O `correlationId` preserva rastreabilidade ponta a ponta mesmo após versionamento do payload.
