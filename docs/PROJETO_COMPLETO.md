Índice
------

- [Arquitetura Orientada a Eventos para Ingestão e Upload de Dados com Mensageria](#arquitetura-orientada-a-eventos-para-ingestão-e-upload-de-dados-com-mensageria)
    - [Histórico de Revisões](#histórico-de-revisões)
    - [1. Introdução](#1-introdução)
        - [1.1. Propósito e Objetivos](#11-propósito-e-objetivos)
        - [1.2. Público-Alvo](#12-público-alvo)
        - [1.3. Serviços Abrangidos](#13-serviços-abrangidos)
        - [1.4. Âmbito](#14-âmbito)
    - [2. Dependências e Referências](#2-dependências-e-referências)
    - [3. Regras de Utilização](#3-regras-de-utilização)
    - [4. Arquitetura da Tipologia](#4-arquitetura-da-tipologia)
        - [4.1. Diagrama de Contexto](#41-diagrama-de-contexto)
        - [4.2. Diagrama de Containers](#42-diagrama-de-containers)
        - [4.3. Fluxo de Alto Nível](#43-fluxo-de-alto-nível)
    - [5. Estrutura do Projeto](#5-estrutura-do-projeto)
        - [5.1. Arquitetura de Dados e Mensageria](#51-arquitetura-de-dados-e-mensageria)
            - [5.1.1. Principais Tópicos de Mensageria](#511-principais-tópicos-de-mensageria)
            - [5.1.2. Formato das Mensagens](#512-formato-das-mensagens)
                - [Versionamento e Evolução de Schemas](#versionamento-e-evolução-de-schemas)
            - [5.1.3. Estratégia de Particionamento e Retenção](#513-estratégia-de-particionamento-e-retenção)
            - [5.1.4. Tamanho de Payload](#514-tamanho-de-payload)
            - [5.1.5. Controlo de Carga na Ingestão (Backpressure e Throttling)](#515-controlo-de-carga-na-ingestão-backpressure-e-throttling)
    - [6. Tecnologias Utilizadas](#6-tecnologias-utilizadas)
    - [7. Segurança](#7-segurança)
        - [7.1. Requisitos Mínimos de Segurança para Ingestão](#71-requisitos-mínimos-de-segurança-para-ingestão)
        - [7.2. Privacidade de Dados (GDPR/LGPD)](#72-privacidade-de-dados-gdprlgpd)
    - [8. Infraestrutura](#8-infraestrutura)
        - [8.1. Gerenciamento de Configuração e Segredos](#81-gerenciamento-de-configuração-e-segredos)
    - [9. Padrões e Princípios Arquiteturais](#9-padrões-e-princípios-arquiteturais)
        - [9.1. Clean Architecture](#91-clean-architecture)
        - [9.2. CQRS (Command Query Responsibility Segregation)](#92-cqrs-command-query-responsibility-segregation)
        - [9.3. Design for Failure](#93-design-for-failure)
        - [9.4. Infraestrutura como Código (IaC)](#94-infraestrutura-como-código-iac)
    - [10. Observabilidade](#10-observabilidade)
        - [10.1. Requisitos Mínimos Obrigatórios](#101-requisitos-mínimos-obrigatórios)
        - [10.2. SLA por Tipo de Fonte de Ingestão](#102-sla-por-tipo-de-fonte-de-ingestão)

# Arquitetura Orientada a Eventos para Ingestão e Upload de Dados com Mensageria

---

## Histórico de Revisões

| Versão | Data       | Autor(es)        | Resumo das Mudanças                                  |
|--------|------------|------------------|------------------------------------------------------|
| 1.0    | 16/12/2025 | Kleber Santos    | Criação inicial do documento de arquitetura de referência. |
| 1.1    | 26/01/2026 | Felipe Klussmann | Ajustes de pequenos detalhes.                        |

---

## 1. Introdução

### 1.1. Propósito e Objetivos

Este documento descreve a arquitetura de referência para a construção de uma plataforma de ingestão e upload de dados para a MC Digital/Sonae. A arquitetura é fundamentada em um modelo orientado a eventos, utilizando uma plataforma de mensageria como sistema nervoso central para garantir o desacoplamento, a escalabilidade e a resiliência dos fluxos de dados. O objetivo é criar uma solução robusta que modernize a forma como os dados são recebidos de múltiplas fontes (APIs, ficheiros e eventos) e processados de forma assíncrona para alimentar o Data Lake e outros sistemas consumidores.

Esta arquitetura define o padrão a ser seguido para todos os novos desenvolvimentos relacionados à ingestão de dados em lote (batch) e em tempo real (streaming). Abrange desde a definição dos serviços de ingestão, a estrutura dos eventos, a orquestração em Kubernetes, até as estratégias de monitoramento e segurança. A solução visa substituir integrações legadas baseadas em ficheiros e comunicações síncronas excessivamente "faladoras" (**chatty**), centralizando o fluxo de dados em uma plataforma unificada e observável.

### 1.2. Público-Alvo

- Engenharia
- Suporte
- Arquitetura

### 1.3. Serviços Abrangidos

- Ingestão via API
- Ingestão via ficheiros (upload)
- Ingestão via eventos
- Processamento assíncrono (workers)

### 1.4. Âmbito

| Objetivo | Descrição |
|---|---|
| **Escalabilidade e Vazão** | Suportar um alto volume de eventos (milhões por dia), escalando horizontalmente para acomodar picos de carga sem degradação de performance. |
| **Resiliência e Confiança** | Garantir a entrega de mensagens (pelo menos uma vez, *at-least-once delivery*) e ser tolerante a falhas de componentes individuais, com mecanismos de recuperação automática. |
| **Desacoplamento** | Isolar produtores e consumidores de dados, permitindo que sistemas evoluam de forma independente sem causar impacto uns nos outros. |
| **Observabilidade** | Fornecer visibilidade completa do fluxo de dados, com logs estruturados, métricas de performance (latência, vazão) e tracing distribuído de ponta a ponta. |
| **Segurança** | Assegurar a autenticação e autorização no acesso aos tópicos, criptografia de dados em trânsito e em repouso, e auditoria completa das operações. |
| **Padronização** | Estabelecer um padrão claro para a integração de novas fontes de dados, reduzindo a heterogeneidade tecnológica e acelerando o desenvolvimento. |

---

## 2. Dependências e Referências

- **Plataforma de mensageria:** Solace (padrão) / Kafka (excecional, CDC/raw data para Data Lake)
- **Contratos de eventos:** JSON Schema / Avro + AsyncAPI
- **Observabilidade:** OpenTelemetry + Grafana/Prometheus — padrão corporativo: [https://ecom4isi.atlassian.net/wiki/spaces/DEVP/pages/5217517761](https://ecom4isi.atlassian.net/wiki/spaces/DEVP/pages/5217517761)
- **Object Storage / Data Lake:** definições específicas por domínio — consultar equipa de Arquitetura de Dados
- **Clean Architecture / CQRS / Design for Failure:** [https://ecom4isi.atlassian.net/wiki/spaces/DEVP/pages/5212340226](https://ecom4isi.atlassian.net/wiki/spaces/DEVP/pages/5212340226)
- **Segurança (definições globais):** [https://ecom4isi.atlassian.net/wiki/spaces/DEVP/pages/5217517761](https://ecom4isi.atlassian.net/wiki/spaces/DEVP/pages/5217517761)

> **Nota:** Caso algum link esteja inacessível, consultar o repositório central de arquitetura ou contactar a equipa de Arquitetura.

---

## 3. Regras de Utilização

Cada tipologia terá abordados aspetos específicos da tipologia, incluindo, sempre que aplicável, referências a diretrizes, padrões gerais e boas práticas.

Este documento não é um conjunto de regras inflexíveis, mas sim um guia de "fortes recomendações". Desvios são permitidos, mas devem ser justificados, documentados em uma ADR (Architecture Decision Record) e aprovados pelo Comitê de Arquitetura.

Kafka é permitido por exceção quando houver requisito explícito de compatibilidade com um ecossistema Kafka existente (consumidores/operadores/streams, CDC/raw data para Data Lake). A exceção deve ser registada via ADR e aprovada pela equipa de Integrações.

---

## 4. Arquitetura da Tipologia

A arquitetura é decomposta em serviços especializados que se comunicam através de eventos via uma plataforma de mensageria. O fluxo principal começa com as fontes de dados enviando informações para um serviço de ingestão, que valida, transforma e publica esses dados em tópicos de mensageria. A partir daí, serviços consumidores processam esses eventos para suas finalidades específicas, como carregar os dados no Data Lake.

### 4.1. Diagrama de Contexto

Este diagrama mostra a visão macro da solução e como ela interage com os sistemas existentes e os utilizadores.

*(Diagrama de contexto — ver imagem original no Confluence: contentId-5298782210)*

### 4.2. Diagrama de Containers

Este diagrama detalha os principais componentes (containers) que compõem a Plataforma de Ingestão de Dados.

*(Diagrama de containers — ver imagem original no Confluence: contentId-5298782210)*

### 4.3. Fluxo de Alto Nível

```
Fonte → Data.Ingest.Api → raw_events
Worker → valida/transforma → processed_events_*
Falhas → dead_letter_queue
Auditoria → audit_log
```

**Estratégia de consistência no pipeline:**

- Usar **Outbox Pattern** na publicação de `raw_events` para garantir que eventos não se percam em caso de falha entre a API e o broker.
- Workers devem ser **idempotentes** e publicar eventos de status (ex: `ingestion.completed`, `ingestion.failed`) para auditoria e retry manual via DLQ.
- Falhas parciais (evento em `raw_events` processado mas não publicado em `processed_events`) devem ser reconciliáveis via replay da DLQ ou reprocessamento por watermark.

---

## 5. Estrutura do Projeto

```
data-upload-ingest-platform/
│
├── README.md
│
├── src/
│   ├── Data.Ingest.Api/
│   │   └── Program.cs
│   │
│   ├── Data.Processor.Worker/
│   │   └── Program.cs
│   │
│   ├── Core/
│   │   ├── Domain/
│   │   │   └── Entities/
│   │   │       └── EventEnvelope.cs
│   │   ├── Application/
│   │   │   └── Handlers/
│   │   │       └── ProcessEventHandler.cs
│   │   └── Infrastructure/
│   │       └── Messaging/
│   │           └── MessagingConsumer.cs
│   │
│   └── Shared/
│       └── Shared.Kernel.csproj
│
└── infra/
    └── k8s/
        └── data-ingest-api.yaml
```

| **Projeto** | **Descrição** |
|---|---|
| Data.Ingest.Api | Ponto de entrada para a ingestão de dados via API REST. Responsável por receber, validar e publicar eventos no tópico `raw_events`. |
| Data.Processor.Worker | Worker service que consome eventos do tópico `raw_events`, aplica a lógica de negócio e publica em `processed_events_*` ou `dead_letter_queue`. |
| Core.Domain | Contém as entidades de negócio, interfaces de repositório e lógica de domínio pura. Sem dependências externas. |
| Core.Application | Orquestra os casos de uso, implementa a lógica de aplicação (handlers MediatR) e depende apenas do Domain. |
| Core.Infrastructure | Implementa as interfaces do Domain, contendo a lógica de acesso a dados, consumidores/produtores Broker e clientes de serviços externos. |
| Shared.Kernel | Biblioteca compartilhada com contratos comuns, DTOs e helpers utilizados por todos os serviços. |

Cada serviço (ex: `Data.Processor`) será organizado da seguinte forma:

- **Domain:** Contém as entidades de negócio, enums e as interfaces dos repositórios. Não possui dependências externas a não ser a própria linguagem.
- **Application:** Orquestra os casos de uso, implementa a lógica de aplicação (ex: handlers de comandos e queries do MediatR) e depende apenas do Domain.
- **Infrastructure:** Implementa as interfaces definidas no `Domain`. Contém os consumidores de mensageria, clientes de APIs externas, e a lógica de acesso a dados (se aplicável).
- **API/Worker:** Ponto de entrada da aplicação. Para serviços com API, contém os `Controllers`. Para workers, contém o `Program.cs` que inicializa o consumidor de mensageria.

### 5.1. Arquitetura de Dados e Mensageria

A plataforma de mensageria é o núcleo da arquitetura, atuando como um buffer durável e escalável entre os serviços.

#### 5.1.1. Principais Tópicos de Mensageria

| Tópico | Descrição |
|---|---|
| `raw_events` | Recebe os dados brutos de todas as fontes de ingestão, sem tratamento. |
| `processed_events_origin_table` | Tópico com dados alinhados diretamente com o schema da tabela de origem — estrutura 1:1 com a fonte, sem agregação ou enriquecimento. |
| `processed_events_mixed` | Contém eventos após validação, transformação e enriquecimento, prontos para consumo analítico. Pode representar dados de n tabelas agregadas ou correlacionadas. |
| `processed_events_cdc` | Utilizado excecionalmente quando as aplicações não conseguem produzir eventos de forma nativa (DML e DDL). Requer aprovação via ADR. |
| `dead_letter_queue` | Armazena eventos que falharam no processamento de forma consistente para análise e reprocessamento manual. |
| `audit_log` | Regista eventos de auditoria importantes, como o início e fim da ingestão de um lote. |

#### 5.1.2. Formato das Mensagens

As mensagens serão serializadas em **JSON** por padrão para facilitar a depuração e a integração. Para cenários de alta performance e evolução de esquema, o uso de **Avro com Schema Registry** é fortemente recomendado.

Cada mensagem terá um cabeçalho (header) padronizado contendo metadados essenciais: `correlationId`, `sourceSystem`, `eventType` e `timestamp`.

##### Versionamento e Evolução de Schemas

Schemas de evento devem seguir **compatibilidade retroativa**:

- **Evolução compatível:** apenas campos aditivos são permitidos sem incremento de versão (ex.: adicionar campo opcional). Remoção ou alteração de tipo de campos existentes constitui *breaking change*.
- **Breaking changes:** exigem nova versão do schema (ex.: `v1` → `v2`), com sobreposição mínima de **6 meses** em que ambas as versões são suportadas em simultâneo.
- **Comunicação obrigatória:** depreciação de versões deve ser comunicada a todos os consumidores conhecidos com antecedência mínima de 30 dias.
- **Schema Registry:** usar o Schema Registry para validação automática de compatibilidade em CI/CD, prevenindo breaking changes não intencionais em produção.
- **Registo:** versões depreciadas e datas de fim de suporte devem ser registadas em ADR e na documentação do contrato.

#### 5.1.3. Estratégia de Particionamento e Retenção

- **Particionamento:** A chave de partição será definida com base em um identificador que garanta a ordem dos eventos quando necessário (ex: `productId`, `storeId`). Se a ordem não for crítica, uma distribuição round-robin pode ser usada para maximizar a paralelização.
- **Retenção:** A política de retenção será configurada de acordo com a necessidade de cada tópico. Para ingestão, recomenda-se um máximo de **7 dias**. Em Solace, a retenção pode ser limitada por quota/espaço e também por políticas configuradas no broker (dependendo do modo/feature em uso).

> **⚠️ Nota:** O valor de 7 dias é um **baseline de partida**. Ajustes devem ser validados com os requisitos de negócio (ex: reprocessamento, auditoria) e capacidade de storage disponível, e registados em ADR quando divergirem do padrão.

#### 5.1.4. Tamanho de Payload

Seguir as definições do DAS Integration Service / Adapter: [https://ecom4isi.atlassian.net/wiki/spaces/DEVP/pages/5196121204](https://ecom4isi.atlassian.net/wiki/spaces/DEVP/pages/5196121204)

> **Resumo dos limites aplicáveis:** máximo de **1 MB por evento JSON** (baseline corporativo). Para ingestão de ficheiros ou payloads maiores, usar multipart upload ou referência a object storage em vez de payload inline. Desvios devem ser validados com Arquitetura e registados em ADR.

#### 5.1.5. Controlo de Carga na Ingestão (Backpressure e Throttling)

Picos de ingestão podem saturar o broker ou os workers downstream, especialmente em cenários de file upload em lote ou integração com fontes de alto volume. A `Data.Ingest.Api` deve implementar:

- **Rate limiting por fonte/cliente:** limitar o número de requisições por segundo por `sourceSystem` ou credencial OAuth, devolvendo `429 Too Many Requests` quando o limite for excedido.
- **Validação assíncrona de payload:** para payloads grandes, validar schema de forma assíncrona após aceitar a mensagem (resposta `202 Accepted`), evitando bloqueio no thread de ingestão.
- **Buffer local com fallback:** em caso de indisponibilidade temporária do broker, implementar queueing local com TTL configurável e retry com backoff exponencial, evitando perda de dados e retorno imediato de erro ao produtor.

---

## 6. Tecnologias Utilizadas

| Categoria | Tecnologia | Observações |
|---|---|---|
| Linguagem | **C# (.NET 8 – LTS)** | Linguagem principal dos serviços |
| Framework | **ASP.NET Core** | APIs REST de ingestão |
| Runtime | **.NET 8** | Execução dos serviços |
| Mensageria | **Solace** (Kafka excecional) | Núcleo da arquitetura (buffer durável) |
| Cliente Mensageria | **Solace** | Produção e consumo de eventos |
| Serialização | **JSON** | Formato padrão das mensagens |
| Serialização (opcional) | **Apache Avro + Schema Registry** | Recomendado para alta performance e evolução de schema |
| API Ingestão | **REST / HTTPS** | Entrada síncrona de dados |
| Worker | **.NET Worker Service** | Processamento assíncrono |
| Containerização | **Docker** | Imagens imutáveis |
| Orquestração | **Kubernetes** | Execução e escalabilidade |
| Autenticação API | **OAuth 2.0 (Client Credentials)** | Azure Entra ID |
| Autorização API | **JWT Bearer Tokens** | Controle de acesso |
| Autenticação Mensageria | **SASL/SCRAM** | Acesso seguro ao Solace |
| Criptografia em Trânsito | **TLS 1.2+** | APIs e Solace |

---

## 7. Segurança

Definições Globais: [https://ecom4isi.atlassian.net/wiki/spaces/DEVP/pages/5217517761](https://ecom4isi.atlassian.net/wiki/spaces/DEVP/pages/5217517761)

### 7.1. Requisitos Mínimos de Segurança para Ingestão

Independentemente das definições globais, todos os serviços de ingestão e processamento devem cumprir obrigatoriamente:

1. **Validação de schema na entrada:** todo payload recebido pela `Data.Ingest.Api` deve ser validado contra o schema registado (JSON Schema ou Avro) antes de ser publicado em `raw_events`. Payloads inválidos devem ser rejeitados com `400 Bad Request` e registados em `audit_log`.
2. **Mascaramento de PII em logs:** campos classificados como dados pessoais (ex: `email`, `nif`, `telefone`, `morada`) devem ser mascarados ou excluídos dos logs estruturados antes de qualquer escrita. Usar lista de campos sensíveis mantida centralmente pela equipa de Arquitetura/Compliance.
3. **TLS obrigatório em trânsito:** todas as conexões entre serviços, APIs e broker devem usar TLS 1.2+. Conexões não encriptadas não são permitidas em produção.
4. **Auditoria de acessos e operações sensíveis:** início e fim de cada lote de ingestão, rejeições de payload e acessos à DLQ devem gerar eventos de auditoria em `audit_log` com `correlationId`, `sourceSystem`, `operatorId` e timestamp.
5. **Gestão de segredos via Vault:** credenciais de acesso ao broker, APIs externas e bases de dados devem ser obtidas do HashiCorp Vault em runtime, montadas via CSI Driver. Nunca em variáveis de ambiente em texto claro ou ficheiros de configuração estáticos.

### 7.2. Privacidade de Dados (GDPR/LGPD)

- **Identificação de PII:** ao integrar uma nova fonte, é obrigatório classificar os campos do payload quanto à presença de dados pessoais antes do primeiro deploy em produção.
- **Minimização de dados:** não ingerir campos que não sejam necessários para o caso de uso declarado.
- **Direito ao apagamento:** para dados pessoais persistidos no Data Lake, deve existir um mecanismo documentado de identificação e exclusão por `subjectId`, compatível com os prazos legais aplicáveis.
- **Retenção diferenciada:** tópicos e storage que contenham PII devem ter política de retenção mais restritiva, definida com a equipa de Compliance.

---

## 8. Infraestrutura

Todos os serviços serão mantidos em containers com Docker e implantados em um cluster Kubernetes, gerenciados via IaC.

- **Orquestração de Containers:** Os serviços serão executados como containers Docker em um cluster Kubernetes. O cluster fornecerá escalabilidade, resiliência e gerenciamento automatizado dos serviços.
- **Broker de Mensageria:** Utilizaremos o Solace para garantir alta disponibilidade, performance e gerenciamento simplificado do cluster.
- **CI/CD:** O processo de build e deploy será automatizado com GitHub Actions. Cada commit na branch principal passará por testes automatizados, build da imagem Docker, push para registry, sincronização via ArgoCD e deploy no cluster de Kubernetes (utilizando uma estratégia como Blue-Green ou Canary).
- **Monitoramento e Observabilidade:** A plataforma será monitorizada com Grafana, coletando métricas, logs e traces, fornecendo dashboards com uma visão consolidada da saúde e performance do sistema.

### 8.1. Gerenciamento de Configuração e Segredos

- **Configurações:** Serão externalizadas em ConfigMaps do Kubernetes e injetadas nos Pods como variáveis de ambiente.
- **Segredos:** Credenciais de acesso à plataforma de mensageria, bancos de dados e outras APIs serão gerenciadas pelo HashiCorp Vault + Vault Agent Injector/CSI, e montadas de forma segura nos Pods utilizando o driver CSI do Vault.

---

## 9. Padrões e Princípios Arquiteturais

Ver definições completas em: [https://ecom4isi.atlassian.net/wiki/spaces/DEVP/pages/5212340226](https://ecom4isi.atlassian.net/wiki/spaces/DEVP/pages/5212340226)

### 9.1. Clean Architecture

Separação estrita em camadas Domain → Application → Infrastructure → API/Worker, garantindo que o núcleo de negócio não depende de detalhes tecnológicos (broker, DB, HTTP).

### 9.2. CQRS (Command Query Responsibility Segregation)

Separação de comandos (ingestão, publicação) e queries (estado de lote, auditoria), permitindo escalar leituras e escritas de forma independente.

### 9.3. Design for Failure

Todos os serviços devem assumir que dependências externas falharão. Aplicar retry com backoff exponencial, circuit breaker e DLQ em todos os pontos de integração.

### 9.4. Infraestrutura como Código (IaC)

Toda a infraestrutura (Kubernetes manifests, configurações de broker, políticas de retenção) deve ser versionada em repositório Git e aplicada via pipeline automatizado.

---

## 10. Observabilidade

Observar o padrão em: [https://ecom4isi.atlassian.net/wiki/spaces/DEVP/pages/5217517761](https://ecom4isi.atlassian.net/wiki/spaces/DEVP/pages/5217517761)

### 10.1. Requisitos Mínimos Obrigatórios

Todos os serviços de ingestão e processamento devem implementar obrigatoriamente:

1. **Logs estruturados** com `correlationId`, `sourceSystem`, `eventType` e `eventId` em cada entrada de log — nunca em texto livre.
2. **Métricas mínimas:** throughput (eventos/s por tópico), consumer lag/backlog, taxa de erro por fonte, latência de processamento (p50/p95/p99).
3. **Tracing distribuído:** propagação de contexto (`traceId`/`correlationId`) desde o ingress da `Data.Ingest.Api` até ao consumer final no Data Lake.
4. **Alertas obrigatórios:** crescimento da DLQ, consumer lag acima de limiar por X minutos, erros consecutivos de publicação, falhas de validação de schema acima de limiar.

### 10.2. SLA por Tipo de Fonte de Ingestão

Cada modalidade de ingestão deve ter o seu SLA individualmente definido, medido e alertado:

- **Ingestão via API:** p95 de latência de aceitação (resposta `202`) < 2 segundos; disponibilidade > 99,9%.
- **Ingestão via file upload:** processamento de 1 GB de dados brutos em < 5 minutos (baseline — ajustar conforme volume real e SLA de negócio).
- **Ingestão via eventos (streaming):** consumer lag < 30 segundos em condições normais de carga.

> **Nota:** os valores acima são **baseline de partida**. SLAs devem ser acordados com o produto/negócio, medidos com as métricas definidas em 10.1, e revistos trimestralmente.

---

> **Aplicação de Exemplo:** [sample-swrefarch-data-upload-ingest](https://github.com/mcdigital-devplatforms/sample-swrefarch-data-upload-ingest)
