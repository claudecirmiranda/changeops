# Diagrama C4 — Container

Visão de containers do ChangeOps Dashboard.

```mermaid
C4Context
    title ChangeOps Dashboard — Container Diagram

    Person(user, "Operador / Admin", "Usuário do portal de mudanças")

    System_Boundary(changeops, "ChangeOps Dashboard") {

        Container(frontend, "Frontend", "React + TypeScript", "Interface web para criação, listagem e acompanhamento de mudanças")

        Container(change_service, "change-service", "Java 17 / Spring Boot", "API REST para criação e consulta de mudanças. Publica ChangePreparedEvent no Kafka")

        Container(deploy_orchestrator, "deploy-orchestrator", "Java 17 / Spring Boot", "Consome DeployFinishedEvent, executa checklist pós-deploy, atualiza status e publica resultado")

        ContainerDb(postgres, "PostgreSQL 16", "Banco Relacional", "Tabelas: changes, change_events, processed_events")

        ContainerQueue(kafka, "Apache Kafka", "Confluent 7.6.0", "Tópicos: change.prepared, deploy.finished, change.result, DLT")

        Container(prometheus, "Prometheus", "v2.50.1", "Coleta métricas dos serviços via /actuator/prometheus")

        Container(grafana, "Grafana", "v10.3.3", "Dashboards de observabilidade: criação, eventos, falhas, latência")
    }

    System_Ext(deploy_system, "Sistema de Deploy", "Publica DeployFinishedEvent no Kafka (simulado)")

    Rel(user, frontend, "Acessa via browser", "HTTPS")
    Rel(frontend, change_service, "REST API", "HTTP/JSON")
    Rel(change_service, postgres, "Persiste mudanças", "JDBC/JPA")
    Rel(change_service, kafka, "Publica ChangePreparedEvent", "Kafka Producer")
    Rel(deploy_system, kafka, "Publica DeployFinishedEvent", "Kafka Producer")
    Rel(kafka, deploy_orchestrator, "Consome DeployFinishedEvent", "Kafka Consumer")
    Rel(deploy_orchestrator, postgres, "Atualiza status + idempotência", "JDBC/JPA")
    Rel(deploy_orchestrator, kafka, "Publica ChangeCompleted/FailedEvent", "Kafka Producer")
    Rel(prometheus, change_service, "Scrape métricas", "HTTP /actuator/prometheus")
    Rel(prometheus, deploy_orchestrator, "Scrape métricas", "HTTP /actuator/prometheus")
    Rel(grafana, prometheus, "Consulta métricas", "PromQL")
```

## Descrição dos Containers

| Container | Responsabilidade |
|-----------|-----------------|
| **Frontend** | Interface React com formulário de criação, listagem paginada, polling a cada 5s e timeline de eventos |
| **change-service** | API REST (`POST/GET /api/v1/changes`), validação de domínio, persistência, publicação de evento de domínio encapsulado em envelope de integração |
| **deploy-orchestrator** | Consumer Kafka com idempotência via `processed_events`, checklist pós-deploy, atualização de status, publicação de evento de resultado, retry com backoff + DLQ |
| **PostgreSQL** | Banco compartilhado com schema único: `changes`, `change_events`, `processed_events`. Flyway migrations independentes por serviço |
| **Kafka** | Broker de eventos com 3 tópicos principais + DLT. Produtor idempotente, consumer groups com ACK por registro |
| **Prometheus** | Scraper de métricas HTTP a cada 15s. Coleta: `changes_created_total`, `events_published_total`, `events_consumed_total`, `events_failed_total` |
| **Grafana** | 7 painéis: counters de criação/publicação/consumo/falha, latência p95, distribuição por status, taxa de eventos |
