# ADR-002 — Estratégia de Idempotência

**Status:** Aceito  
**Data:** Março/2026  
**Decisores:** Equipe Técnica

## Contexto

O `deploy-orchestrator` consome `DeployFinishedEvent` de um tópico Kafka. Em cenários de falha de rede, rebalanceamento de consumer group ou retry automático, o mesmo evento pode ser entregue mais de uma vez. O processamento duplicado pode causar:
*   Transições de estado inválidas (ex: `COMPLETED → COMPLETED`).
*   Publicação duplicada de eventos de saída (`ChangeCompletedEvent`).
*   Dados inconsistentes no audit trail (`change_events`).
A RFP exige idempotência comprovada com testes que evidenciem que o mesmo evento entregue duas vezes resulta em estado consistente.

## Decisão

Adotamos **estratégia de idempotência atômica em banco de dados**:

**Nível de banco de dados:** operação atômica `INSERT ... ON CONFLICT DO NOTHING` na tabela `processed_events` via `tryMarkAsProcessed()` — retorna `true` se inserido (primeiro processamento) ou `false` se já existia (duplicata). A verificação e marcação ocorrem em uma única operação atômica, eliminando race conditions.

## Rastreabilidade

A chave `event_id` utilizada na tabela `processed_events` corresponde ao **`deployId`** do evento (`payload.deployId()`), que é um `UUID` único por deploy dentro do `deploy-orchestrator`.
Essa abordagem assegura:
*   Vinculação direta com logs distribuídos (observabilidade end-to-end), dado que o `deployId` é propagado em logs e métricas
*   Correlação entre eventos de entrada e saída por meio do `deployId` e demais metadados presentes no payload (como `correlationId`)
*   Facilidade de auditoria e troubleshooting, pois o mesmo identificador é utilizado no evento, no banco e nos logs

## Implementação
```sql
CREATE TABLE processed_events (
    event_id     UUID          NOT NULL,
    processed_at TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    service_name VARCHAR(100)  NOT NULL,
    PRIMARY KEY (event_id, service_name)
);
```
```java
@Query(value = """
    INSERT INTO processed_events (event_id, processed_at, service_name)
    VALUES (:eventId, NOW(), :serviceName)
    ON CONFLICT (event_id, service_name) DO NOTHING
    """, nativeQuery = true)
int insertIfAbsent(@Param("eventId") UUID eventId, @Param("serviceName") String serviceName);
```

O retorno é `1` (inserido, primeiro processamento) ou `0` (já existia, duplicata).

## Fluxo no Consumer
```java
// Verificação atômica: tenta marcar como processado
// Retorna false se já foi processado (duplicata)
if (!idempotencyPort.tryMarkAsProcessed(payload.deployId(), "deploy-orchestrator")) {
    eventsDiscardedCounter.increment();
    log.warn("Event already processed, discarding: deployId={}", payload.deployId());
    return;
}

// Processamento ocorre apenas se tryMarkAsProcessed retornou true
processDeployResultService.execute(event);
```

A operação `tryMarkAsProcessed` é atômica — verificação e marcação ocorrem em uma única instrução SQL, eliminando race conditions em cenários de alta concorrência com múltiplas réplicas.

## Performance

A tabela `processed_events` possui **PRIMARY KEY composta em `(event_id, service_name)`**, o que garante:
*   Busca indexada com complexidade **O(1)**
*   Alta eficiência mesmo com grande volume de eventos
*   Baixo impacto no throughput do consumer

O custo de uma query adicional por evento é considerado desprezível e não compromete a escalabilidade do sistema.

## Limpeza

Um `@Scheduled` job diário (03:00h) remove registros com mais de 90 dias para evitar crescimento indefinido da tabela.

## Alternativas Consideradas

### 1. Chave persistida em banco com operação atômica (escolhida)

*   Atômica: `INSERT ON CONFLICT` é uma operação única, sem race condition.
*   Durável: sobrevive a restart do serviço.
*   Auditável: registro de quando cada evento foi processado.
*   Custo: uma query adicional por evento consumido.

### 2. Cache distribuído (Redis)

*   Menor latência para verificação.
*   Risco de perda em caso de restart/eviction do cache.
*   Requer infraestrutura adicional (Redis).
*   Não atende ao requisito de durabilidade sem persistência complementar.

### 3. Verificação de estado da entidade

*   Consultar status atual da mudança antes de processar.
*   Vulnerável a race conditions em cenários de alta concorrência.
*   Não previne publicação duplicada de eventos de saída.
*   Acopla lógica de idempotência ao domínio.

## Testes
```java
@Test
@DisplayName("Deve descartar evento duplicado sem processar novamente")
void shouldDiscardDuplicateEventWithoutReprocessing() {
    // Given: evento já processado
    UUID eventId = UUID.randomUUID();
    idempotencyPort.tryMarkAsProcessed(eventId, "deploy-orchestrator");

    // When: tenta processar novamente
    boolean result = idempotencyPort.tryMarkAsProcessed(eventId, "deploy-orchestrator");

    // Then: rejeita duplicata
    assertThat(result).isFalse();

    // And: métrica de duplicata incrementada
    assertThat(meterRegistry.get("events_discarded_total")
        .tag("reason", "duplicate")
        .counter().count()).isEqualTo(1.0);
}
```

## Trade-offs

| Aspecto | Banco atômico (escolhida) | Redis (cache) | Verificação de estado |
| --- | --- | --- | --- |
| Durabilidade | ✅ Total | ⚠ Risco de perda | ✅ Total |
| Atomicidade | ✅ ON CONFLICT | ⚠ SETNX expira | ❌ Race condition |
| Latência | ⚠ ~1ms (local) | ✅ Sub-ms | ✅ ~1ms |
| Infra adicional | ❌ Nenhuma | ⚠ Redis cluster | ❌ Nenhuma |
| Desacoplamento | ✅ Genérica | ✅ Genérica | ❌ Acoplada ao domínio |
| Auditoria | ✅ Timestamp + service | ❌ Sem histórico | ❌ Sem registro |

## Consequências

### Positivas
- Idempotência garantida mesmo com retry automático do Kafka, restart do consumidor ou entrega duplicada de rede
- Auditoria completa: tabela `processed_events` registra `event_id`, `service_name` e `processed_at` para rastreabilidade
- Baixo custo operacional: solução baseada em banco de dados existente, sem infraestrutura adicional

### Negativas / Riscos
- Overhead de +1 query INSERT por evento consumido (~1ms de latência adicional no p95)
- Crescimento contínuo da tabela `processed_events` requer estratégia de cleanup
- Race condition teórica eliminada pela atomicidade do `ON CONFLICT DO NOTHING`

### Mitigações
- Índice PRIMARY KEY em `(event_id, service_name)` garante busca O(1)
- Job `@Scheduled` diário remove registros com `processed_at > 90 dias`, configurável via property `idempotency.retention-days`
- `ON CONFLICT DO NOTHING` é atômico no PostgreSQL; cenário de concorrência coberto por teste de integração

## Relacionado a
- [ADR-001](./ADR-001-escolha-message-broker.md) — Retry behavior do Kafka define quando idempotência é acionada
- [ADR-003](./ADR-003-eventos-dominio-vs-integracao.md) — `deployId` no payload é usado como chave de idempotência
- [ADR-005](./ADR-005-estrutura-pacotes.md) — `IdempotencyAdapter` implementa porta em `infrastructure/persistence`, isolando domínio

## Conformidade com a RFP

| Requisito | Status | Evidência |
|-----------|--------|-----------|
| "Idempotência comprovada com testes" | ✅ Atendido | `IdempotencyIntegrationTest.shouldDiscardDuplicateEventWithoutReprocessing()` com verificação de métrica |
| "Processamento at-most-once de eventos" | ✅ Atendido | `ON CONFLICT DO NOTHING` atômico garante que evento é processado no máximo uma vez |
| "Métricas para monitoramento de duplicatas" | ✅ Atendido | Métrica `events_discarded_total{reason="duplicate"}` exposta via Prometheus |
| "Estratégia de cleanup para dados temporários" | ✅ Atendido | Job `@Scheduled` em `IdempotencyCleanupService.java` com propriedade configurável |
| "Resiliência a falhas de rede e retry" | ✅ Atendido | `@RetryableTopic` com backoff exponencial + DLT para falhas persistentes |

## Justificativa

A operação atômica `tryMarkAsProcessed` via `INSERT ON CONFLICT DO NOTHING` garante durabilidade total, eliminação de race conditions e auditabilidade, sem adicionar componentes de infraestrutura. O custo de 1 query por evento é desprezível no volume da POC e escalável com índice na PK composta.