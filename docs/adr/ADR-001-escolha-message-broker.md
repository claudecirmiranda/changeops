# ADR-001 — Escolha do Message Broker

**Status:** Aceito  
**Data:** Março/2026  
**Decisores:** Equipe Técnica

---

## Contexto

A arquitetura do ChangeOps exige comunicação assíncrona entre `change-service` (Fluxo 1) e `deploy-orchestrator` (Fluxo 2). Eventos de domínio (`ChangePreparedEvent`) e eventos de integração (`DeployFinishedEvent`, `ChangeCompletedEvent`, `ChangeFailedEvent`) precisam ser publicados e consumidos com garantias de entrega, ordenação e rastreabilidade.

A RFP permite **Kafka** ou **RabbitMQ** como opções de broker.

---

## Decisão

Adotamos **Apache Kafka** (Confluent CP 7.6.0) como message broker.

---

## Alternativas Consideradas

### 1. Apache Kafka (escolhido)

- Log imutável com retenção configurável — permite replay de eventos.
- Particionamento por chave (`changeId`) — garante ordenação dentro da partição.
- Produtor idempotente nativo (`enable.idempotence=true`).
- Consumer groups com offset management — suporte a múltiplos consumidores.
- Ecossistema maduro com Confluent Schema Registry (roadmap Phase 2).
- Ampla integração com Spring Kafka (`@RetryableTopic`, `@KafkaListener`).

### 2. RabbitMQ

- Modelo push-based com ACK individual — mais simples para cenários request-reply.
- Exchanges e bindings flexíveis para roteamento complexo.
- Dead Letter Exchange (DLX) nativo.
- Menor complexidade operacional para baixo volume.
- Sem replay nativo — mensagens consumidas são removidas da fila.

---

## Trade-offs

| Aspecto | Kafka | RabbitMQ |
|---------|-------|----------|
| Replay de eventos | ✅ Nativo | ❌ Requer plugin |
| Ordenação | ✅ Por partição | ⚠ Apenas por fila |
| Throughput | ✅ Alto (append-only log) | ⚠ Médio |
| Complexidade operacional | ⚠ Zookeeper (depreciando) | ✅ Mais simples |
| DLQ/DLT | ✅ Via Spring @RetryableTopic | ✅ DLX nativo |
| Idempotência do produtor | ✅ Nativa | ❌ Manual |
| Schema Registry | ✅ Confluent nativo | ⚠ Via plugin |
| Curva de aprendizado | ⚠ Mais íngreme | ✅ Mais acessível |

**Justificativa:** Kafka foi escolhido por oferecer replay de eventos (essencial para auditoria e reprocessamento), produtor idempotente nativo, ordenação por partição via `changeId` como chave, e compatibilidade direta com o roadmap evolutivo (Schema Registry, CQRS, Event Sourcing).

O overhead adicional de Zookeeper é aceitável em ambiente Dockerizado e será eliminado em versões futuras do Kafka (KRaft mode).
