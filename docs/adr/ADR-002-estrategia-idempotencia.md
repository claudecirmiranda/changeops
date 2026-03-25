# ADR-002 — Estratégia de Idempotência

**Status:** Aceito  
**Data:** Março/2026  
**Decisores:** Equipe Técnica

---

## Contexto

O `deploy-orchestrator` consome `DeployFinishedEvent` de um tópico Kafka. Em cenários de falha de rede, rebalanceamento de consumer group ou retry automático, o mesmo evento pode ser entregue mais de uma vez. O processamento duplicado pode causar:

- Transições de estado inválidas (ex: `COMPLETED → COMPLETED`).
- Publicação duplicada de eventos de saída (`ChangeCompletedEvent`).
- Dados inconsistentes no audit trail (`change_events`).

A RFP exige idempotência comprovada com testes que evidenciem que o mesmo evento entregue duas vezes resulta em estado consistente.

---

## Decisão

Adotamos **estratégia de idempotência em dois níveis**:

1. **Nível de aplicação:** verificação antecipada via `isAlreadyProcessed()` no consumer — retorno imediato sem processamento redundante se o evento já foi processado.
2. **Nível de banco de dados:** operação atômica `INSERT ... ON CONFLICT DO NOTHING` na tabela `processed_events` — salvaguarda para cenários de concorrência (ex: múltiplas réplicas do serviço).

### Implementação

```sql
CREATE TABLE processed_events (
    event_id     UUID PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    service_name VARCHAR(100) NOT NULL
);
```

```java
@Query(value = """
    INSERT INTO processed_events (event_id, processed_at, service_name)
    VALUES (:eventId, NOW(), :serviceName)
    ON CONFLICT (event_id) DO NOTHING
    """, nativeQuery = true)
int insertIfAbsent(@Param("eventId") UUID eventId, @Param("serviceName") String serviceName);
```

O retorno é `1` (inserido, primeiro processamento) ou `0` (já existia, duplicata).

**Fluxo completo no consumer:**

```java
// 1. Check de aplicação (ANTES do banco) — evita processamento redundante
if (idempotencyPort.isAlreadyProcessed(event.deployId())) {
    log.warn("Event already processed, discarding: deployId={}", event.deployId());
    return;
}
// 2. Processamento
processDeployResultService.execute(command);
// 3. O banco garante via ON CONFLICT DO NOTHING (salvaguarda para concorrência)
```

Em duplicata detectada no nível de aplicação, apenas um log de `WARN` é emitido e o evento é descartado silenciosamente sem acesso ao banco. O `ON CONFLICT DO NOTHING` na persistência serve como segunda linha de defesa em cenários de alta concorrência (ex: múltiplas réplicas consumindo simultaneamente).

### Limpeza

Um `@Scheduled` job diário (03:00h) remove registros com mais de 90 dias para evitar crescimento indefinido da tabela.

---

## Alternativas Consideradas

### 1. Chave persistida em banco (escolhida)

- Atômica: `INSERT ON CONFLICT` é uma operação única, sem race condition.
- Durável: sobrevive a restart do serviço.
- Auditável: registro de quando cada evento foi processado.
- Custo: uma query adicional por evento consumido.

### 2. Cache distribuído (Redis)

- Menor latência para verificação.
- Risco de perda em caso de restart/eviction do cache.
- Requer infraestrutura adicional (Redis).
- Não atende ao requisito de durabilidade sem persistência complementar.

### 3. Verificação de estado da entidade

- Consultar status atual da mudança antes de processar.
- Vulnerável a race conditions em cenários de alta concorrência.
- Não previne publicação duplicada de eventos de saída.
- Acopla lógica de idempotência ao domínio.

---

## Trade-offs

| Aspecto | Banco (escolhida) | Redis (cache) | Verificação de estado |
|---------|-------------------|---------------|----------------------|
| Durabilidade | ✅ Total | ⚠ Risco de perda | ✅ Total |
| Atomicidade | ✅ ON CONFLICT | ⚠ SETNX expira | ❌ Race condition |
| Latência | ⚠ ~1ms (local) | ✅ Sub-ms | ✅ ~1ms |
| Infra adicional | ❌ Nenhuma | ⚠ Redis cluster | ❌ Nenhuma |
| Desacoplamento | ✅ Genérica | ✅ Genérica | ❌ Acoplada ao domínio |
| Auditoria | ✅ Timestamp + service | ❌ Sem histórico | ❌ Sem registro |

**Justificativa:** A abordagem com banco garante durabilidade total, operação atômica sem race conditions e auditabilidade, sem adicionar componentes de infraestrutura (Redis). O custo de 1 query adicional por evento é desprezível no volume da POC e escalável com índice na PK.
