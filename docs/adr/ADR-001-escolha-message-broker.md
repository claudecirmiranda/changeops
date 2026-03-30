# ADR-001 — Escolha do Message Broker

**Status:** Aceito  
**Data:** Março/2026  
**Decisores:** Equipe Técnica

## Contexto

A arquitetura do ChangeOps exige comunicação assíncrona entre `change-service` (Fluxo 1) e `deploy-orchestrator` (Fluxo 2). Eventos de domínio (`ChangePreparedEvent`) e eventos de integração (`DeployFinishedEvent`, `ChangeCompletedEvent`, `ChangeFailedEvent`) precisam ser publicados e consumidos com garantias de entrega, ordenação e rastreabilidade.
A RFP permite **Kafka** ou **RabbitMQ** como opções de broker.

## Decisão

Adotamos **Apache Kafka** (Confluent CP 7.6.0) como message broker.

## Alternativas Consideradas

### 1. Apache Kafka (escolhido)

*   Log imutável com retenção configurável — permite replay de eventos.
*   Particionamento por chave (`changeId`) — garante ordenação dentro da partição.
*   Produtor idempotente nativo (`enable.idempotence=true`).
*   Consumer groups com offset management — suporte a múltiplos consumidores.
*   Ecossistema maduro com Confluent Schema Registry (roadmap Phase 2).
*   Ampla integração com Spring Kafka (`@RetryableTopic`, `@KafkaListener`).

### 2. RabbitMQ

*   Modelo push-based com ACK individual — mais simples para cenários request-reply.
*   Exchanges e bindings flexíveis para roteamento complexo.
*   Dead Letter Exchange (DLX) nativo.
*   Menor complexidade operacional para baixo volume.
*   Sem replay nativo — mensagens consumidas são removidas da fila.

## Trade-offs

| Aspecto | Kafka | RabbitMQ |
| --- | --- | --- |
| Replay de eventos | ✅ Nativo | ❌ Requer plugin |
| Ordenação | ✅ Por partição | ⚠ Apenas por fila |
| Throughput | ✅ Alto (append-only log) | ⚠ Médio |
| Complexidade operacional | ⚠ Zookeeper (depreciando) | ✅ Mais simples |
| DLQ/DLT | ✅ Via Spring @RetryableTopic | ✅ DLX nativo |
| Idempotência do produtor | ✅ Nativa | ❌ Manual |
| Schema Registry | ✅ Confluent nativo | ⚠ Via plugin |
| Curva de aprendizado | ⚠ Mais íngreme | ✅ Mais acessível |

## Consequências

### Positivas
- Replay de eventos habilita reprocessamento de cenários de falha sem dependência de backup de banco de dados
- Ordenação garantida por partição assegura sequência de eventos por `change_id`, crítico para consistência de estado
- Retenção configurável (7+ dias) permite auditoria pós-incidente e análise forense de eventos

### Negativas / Riscos
- Overhead operacional: Kafka exige gerenciamento de cluster (ZooKeeper ou KRaft), aumentando complexidade de infraestrutura
- Curva de aprendizado para desenvolvedores não familiarizados com padrões de streaming e consumo de eventos
- Custo de infraestrutura superior a alternativas gerenciadas como Amazon SQS para volumes baixos

### Mitigações
- Docker Compose com imagens oficiais para desenvolvimento local, reduzindo barreira de entrada
- Roadmap para migração para KRaft (Kafka sem ZooKeeper) na Fase 2, simplificando operações
- Monitoramento via Prometheus com métricas `kafka_consumer_lag` e `kafka_producer_retry_total` para detecção proativa de problemas

## Relacionado a
- [ADR-002](./ADR-002-estrategia-idempotencia.md) — Retry behavior do Kafka define requisitos para idempotência no consumidor
- [ADR-003](./ADR-003-eventos-dominio-vs-integracao.md) — Partition key derivada de `change_id` garante ordenação de eventos relacionados
- [ADR-004](./ADR-004-atualizacao-status-frontend.md) — correlationId propagado via headers do Kafka para rastreabilidade ponta a ponta

## Conformidade com a RFP

| Requisito | Status | Evidência |
|-----------|--------|-----------|
| "Message broker para comunicação assíncrona" | ✅ Atendido | Kafka configurado em `docker-compose.yml` com tópicos `changes.prepared` e `changes.deploy.finished` |
| "Retry com backoff exponencial" | ✅ Atendido | `@RetryableTopic` com `backoff = @Backoff(delay = 1000, multiplier = 2.0)` em `DeployFinishedConsumer.java` |
| "Dead Letter Queue para eventos falhos" | ✅ Atendido | Tópico `.dlq` configurado em `KafkaConfig.java` com métrica `events_dlq_size` |
| "Estratégia de evolução de infraestrutura" | ✅ Atendido | Roadmap para KRaft documentado; compatibilidade com operadores gerenciados (Confluent, MSK) |
| "Observabilidade do broker" | ✅ Atendido | Métricas Prometheus expostas; dashboard Grafana com painéis de lag e throughput |

## Justificativa

Kafka foi escolhido por oferecer replay de eventos (essencial para auditoria e reprocessamento), produtor idempotente nativo, ordenação por partição via `changeId` como chave, e compatibilidade direta com o roadmap evolutivo (Schema Registry, CQRS, Event Sourcing).
O overhead adicional de Zookeeper é aceitável em ambiente Dockerizado e será eliminado em versões futuras do Kafka.

## Observabilidade

O uso do Kafka permite monitoramento avançado da plataforma de mensageria, especialmente através da métrica de **consumer lag**, que representa o atraso entre produção e consumo de eventos.

Esse monitoramento pode ser realizado via **JMX metrics exportadas para Prometheus**, possibilitando:

*   Criação de dashboards operacionais (ex: Grafana)
*   Alertas proativos de atraso no processamento
*   Diagnóstico de gargalos em consumidores

Essa capacidade fornece maior previsibilidade operacional em comparação com brokers baseados em fila tradicional.

## Evolução da Infraestrutura

Como parte do roadmap, está prevista a migração do cluster Kafka para o modo **KRaft (Kafka Raft Metadata Mode)**, eliminando a dependência do Zookeeper.
O KRaft substitui o Zookeeper por um mecanismo interno baseado em consenso (Raft), onde o próprio Kafka gerencia metadados e eleição de líderes, simplificando a arquitetura e reduzindo a complexidade operacional .

Principais benefícios esperados:

*   Eliminação do cluster Zookeeper (menos componentes para operar)
*   Redução de latência em operações de controle
*   Melhor escalabilidade para clusters grandes
*   Recuperação mais rápida em falhas

A partir do Kafka 3.3+, o modo KRaft já é considerado pronto para novos clusters, e versões mais recentes caminham para remoção completa do Zookeeper .

A migração será planejada em fases, garantindo compatibilidade e estabilidade durante a transição do ambiente produtivo.
