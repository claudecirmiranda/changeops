Índice
------

- [Diagrama 1 - Solução de Ingestão - Mermaid](#diagrama-1-solução-de-ingestão-mermaid)
- [Diagrama 2 - Solução de Ingestão - Mermaid](#diagrama-2-solução-de-ingestão-mermaid)

# Diagrama 1 - Solução de Ingestão - Mermaid

```mermaid 
flowchart TB
 subgraph Platform["Ingestão de Dados"]
        DataIngest["Solução de Ingestão<br><br>Valida, transforma e roteia dados<br>de diversas fontes de forma assíncrona"]
  end
    DataSources["Fontes de Dados<br>[Sistema Externo]<br><br>Sistemas legados (Retek, SAP),<br>APIs de parceiros,<br>Sensores IoT,<br>Ficheiros"] -- Envia dados para ingestão --> DataIngest
    DataIngest -- Carrega dados processados --> DataLake["Data Lake / Warehouse<br>[Sistema Externo]<br><br>Repositório central de dados<br>para Analytics e BI"]
    Admin["Administrador da Plataforma<br>(Gerencia e monitora a saúde da plataforma)"] -- Monitora e gerencia --> DataIngest
    DataLake -- Fornece dados para análise --> BITools["Ferramentas de BI<br>[Sistema Externo]<br><br>Consomem dados do Data Lake<br>para gerar relatórios e dashboards"]

    DataIngest@{ shape: rect}
     DataIngest:::system
     DataSources:::external
     DataLake:::external
     Admin:::person
     BITools:::external

    classDef external fill:#999,stroke:#666,stroke-width:2px,color:#fff
    classDef system fill:#1168bd,stroke:#0b4884,stroke-width:2px,color:#fff
    classDef person fill:#08427b,stroke:#052e56,stroke-width:2px,color:#fff
```

# Diagrama 2 - Solução de Ingestão - Mermaid

```mermaid 
flowchart TB
 subgraph K8s["Solução de Ingestão de Dados"]
        IngestAPI["API de Ingestão<br>[Container: .NET 8]<br><br>Validação de Schema & Segurança.<br>Publica eventos brutos."]
        FileWatcher["Serviço de Ficheiros<br>[Container: .NET 8]<br><br>Monitora diretórios.<br>Validação de Schema & Segurança.<br>Publica eventos brutos."]
        Broker[("Solace Event Broker<br><br>Orquestra o fluxo de dados<br>através de tópicos")]
        DataProcessor["Processador de Dados<br>[Container: .NET 8]<br><br>Consome eventos brutos,<br>aplica regras de negócio,<br>validação e enriquecimento."]
        DataLoader["Carregador de Dados<br>[Container: .NET 8]<br><br>Consome eventos processados<br>e carrega os dados no Data Lake."]
        DLQ((" Dead Letter Queue<br><br>Tratamento de Falhas"))
 end

    IngestAPI -- Publica em 'raw_events'<br>(JSON/Avro) --> Broker
    FileWatcher -- Publica em 'raw_events'<br>(JSON/Avro) --> Broker
    Broker -- Consome de 'raw_events'<br>(JSON/Avro) --> DataProcessor
    
    DataProcessor -- Publica em 'processed_events'<br>(JSON/Avro) --> Broker
    DataProcessor -.-> |"Falhas de Processamento"| DLQ
    
    Broker -- Consome de 'processed_events'<br>(JSON/Avro) --> DataLoader
    
    DataSources["Fontes de Dados<br>[Sistema Externo]<br><br>Sistemas legados,<br>APIs,<br>Ficheiros, etc."] -- Envia dados via HTTPS --> IngestAPI
    DataSources -- Disponibiliza ficheiros via SFTP --> FileWatcher
    DataLoader -- Carrega dados<br>(JDBC/API) --> DataLake["Data Lake / Warehouse<br>[Sistema Externo]<br><br>Repositório de dados analíticos"]

     IngestAPI:::container
     FileWatcher:::container
     Broker:::database
     DataProcessor:::container
     DataLoader:::container
     DataSources:::external
     DataLake:::external
     DLQ:::failure

    classDef external fill:#999,stroke:#666,stroke-width:2px,color:#fff
    classDef container fill:#1168bd,stroke:#0b4884,stroke-width:2px,color:#fff
    classDef database fill:#438dd5,stroke:#2e6295,stroke-width:2px,color:#fff
    classDef failure fill:#d43f3a,stroke:#761c19,stroke-width:2px,color:#fff
```
