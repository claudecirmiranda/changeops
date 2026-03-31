# Diagrama C4 — Container

Visão de containers do ChangeOps Dashboard.

```mermaid
%%{init: {'theme':'dark', 'themeVariables':{'fontSize':'16px','lineColor':'#94A3B8','edgeLabelBackground':'transparent'}}}%%
flowchart TD
    subgraph ext[" "]
        user(["👤 Operador / Admin"])
        deploy_system(["🔧 Sistema de Deploy"])
    end

    subgraph boundary["ChangeOps Dashboard"]
        direction TB

        subgraph services["Serviços"]
            direction LR
            frontend["Frontend<br/>React + TypeScript<br/>:3000"]
            change_service["change-service<br/>Spring Boot<br/>:8080"]
            deploy_orchestrator["deploy-orchestrator<br/>Spring Boot<br/>:8081"]
        end

        subgraph infra[" "]
            direction LR

            subgraph data["Dados & Mensageria"]
                direction TB
                data_pad[ ]:::hidden
                data_pad ~~~ kafka
                kafka{{"Apache Kafka<br/>Confluent 7.6.0<br/>:9092"}}
                postgres[("PostgreSQL 16<br/>:5432")]
                kafka ~~~ postgres
            end

            subgraph obs["Observabilidade"]
                direction TB
                obs_pad[ ]:::hidden
                obs_pad ~~~ grafana
                grafana["Grafana<br/>v10.3.3<br/>:3001"]
                prometheus["Prometheus<br/>v2.50.1<br/>:9090"]
            end
        end
    end

    user -->|HTTPS| frontend
    frontend -->|REST API| change_service
    deploy_system --- kafka

    change_service --- postgres
    change_service --- kafka

    kafka --- deploy_orchestrator
    deploy_orchestrator --- postgres
    deploy_orchestrator --- kafka

    prometheus -.->|scrape| change_service
    prometheus -.->|scrape| deploy_orchestrator
    grafana -.->|PromQL| prometheus

    classDef person fill:#334155,stroke:#64748B,color:#E2E8F0,stroke-width:2px
    classDef svc fill:#4338CA,stroke:#6366F1,color:#E0E7FF,stroke-width:2px
    classDef db fill:#047857,stroke:#34D399,color:#D1FAE5,stroke-width:2px
    classDef broker fill:#C2410C,stroke:#FB923C,color:#FFF7ED,stroke-width:2px
    classDef monitoring fill:#7C3AED,stroke:#A78BFA,color:#EDE9FE,stroke-width:2px
    classDef extSys fill:#334155,stroke:#64748B,color:#E2E8F0,stroke-width:2px
    classDef hidden fill:transparent,stroke:none,color:transparent,stroke-width:0px

    class user person
    class frontend,change_service,deploy_orchestrator svc
    class postgres db
    class kafka broker
    class prometheus,grafana monitoring
    class deploy_system extSys

    style ext fill:none,stroke:none
    style infra fill:none,stroke:none
    style boundary fill:#0F172A,stroke:#6366F1,stroke-width:2px,color:#A5B4FC
    style services fill:#1E1B4B,stroke:#4338CA,stroke-width:1px,color:#A5B4FC
    style data fill:#042F2E,stroke:#047857,stroke-width:1px,color:#5EEAD4
    style obs fill:#2E1065,stroke:#7C3AED,stroke-width:1px,color:#C4B5FD
```

## Descrição dos Containers

| Container | Responsabilidade |
|-----------|-----------------|
| **Frontend** | Interface React com formulário de criação, listagem paginada, polling a cada 5s e timeline de eventos |
| **change-service** | API REST (`POST/GET /api/v1/changes`), validação de domínio, persistência, publicação de evento de domínio encapsulado em envelope de integração |
| **deploy-orchestrator** | Consumer Kafka com idempotência via `processed_events`, checklist pós-deploy, atualização de status, publicação de evento de resultado, retry com backoff + DLQ |
| **PostgreSQL** | Banco compartilhado com schema único: `changes`, `change_events`, `processed_events`. Flyway migrations independentes por serviço |
| **Kafka** | Broker de eventos com 3 tópicos principais + DLT. Produtor idempotente, consumer groups com ACK por registro |
| **Prometheus** | Scraper de métricas HTTP a cada 15s. Coleta: `changes_created_total`, `changes_completed_total`, `changes_failed_total`, `events_published_total`, `events_consumed_total`, `events_retries_total`, `events_failed_total`, `events_dlt_total`, `events_discarded_total`, `orchestration_duration_seconds` |
| **Grafana** | 14 painéis: stats de Changes (Created, Completed, Failed, Prepared), pie chart por status, stats de Events (Published, Consumed, Failed, Retries, DLT, Discarded), latência API p95, taxa de eventos/min, latência de orquestração p95 |
