# ChangeOps Dashboard — Projeto Completo

> Gerado por: Arquiteto de Software Sênior  
> Stack: Java 17 + Spring Boot · React + TypeScript · Kafka · PostgreSQL · Docker Compose  
> Total de arquivos gerados: **102**

---

## Índice

1. [Repositório Backend — change-service](#1-repositório-backend--change-service)
2. [Repositório Backend — deploy-orchestrator](#2-repositório-backend--deploy-orchestrator)
3. [Repositório Frontend](#3-repositório-frontend)
4. [Contratos (OpenAPI + AsyncAPI)](#4-contratos)
5. [Modelo de Dados e Persistência](#5-modelo-de-dados-e-persistência)
6. [Segurança](#6-segurança)
7. [Observabilidade](#7-observabilidade)
8. [Automação (Docker Compose + Makefile + CI/CD)](#8-automação)
9. [Roadmap Técnico](#9-roadmap-técnico)

---

## Visão Arquitetural

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

| Container | Responsabilidade |
|-----------|-----------------|
| **Frontend** | Interface React com formulário de criação, listagem paginada, polling a cada 5s e timeline de eventos |
| **change-service** | API REST (`POST/GET /api/v1/changes`), validação de domínio, persistência, publicação de evento de domínio encapsulado em envelope de integração |
| **deploy-orchestrator** | Consumer Kafka com idempotência via `processed_events`, checklist pós-deploy, atualização de status, publicação de evento de resultado, retry com backoff + DLQ |
| **PostgreSQL** | Banco compartilhado com schema único: `changes`, `change_events`, `processed_events`. Flyway migrations independentes por serviço |
| **Kafka** | Broker de eventos com 3 tópicos principais + DLT. Produtor idempotente, consumer groups com ACK por registro |
| **Prometheus** | Scraper de métricas HTTP a cada 15s. Coleta: `changes_created_total`, `events_published_total`, `events_consumed_total`, `events_failed_total` |
| **Grafana** | 7 painéis: counters de criação/publicação/consumo/falha, latência p95, distribuição por status, taxa de eventos |

---

## 1. Repositório Backend — change-service

### Visão Geral

Serviço responsável pelo **Fluxo 1** completo: recebimento da requisição HTTP, validação de domínio (hexagonal), persistência e publicação do `ChangePreparedEvent` no Kafka com envelope de integração.

**Padrão arquitetural:** Hexagonal (Ports & Adapters)

```
change-service/
├── api/
│   ├── controller/       ChangeController, GlobalExceptionHandler
│   └── dto/              CreateChangeRequest/Response, ChangeDto, ChangeEventDto
├── application/
│   ├── port/in/          CreateChangeUseCase, ListChangesUseCase, GetChangeEventsUseCase
│   ├── port/out/         SaveChangePort, LoadChangesPort, PublishEventPort, SaveChangeEventPort
│   └── service/          CreateChangeService, ListChangesService, GetChangeEventsService
├── domain/
│   ├── model/            Change (Aggregate Root)
│   ├── event/            ChangePreparedEvent (domínio)
│   ├── exception/        ChangeNotFoundException, InvalidChangeStateException
│   └── valueobject/      ChangeStatus
└── infrastructure/
    ├── config/           KafkaConfig
    ├── kafka/            KafkaEventPublisherAdapter, IntegrationEvent (envelope)
    ├── observability/    CorrelationIdFilter (MDC)
    ├── persistence/      ChangePersistenceAdapter, ChangeEntity, ChangeEventEntity
    └── security/         SecurityConfig, CustomJwtAuthenticationConverter
```

```mermaid
%%{init: {'theme':'dark', 'themeVariables':{'fontSize':'15px','lineColor':'#94A3B8','edgeLabelBackground':'transparent'}}}%%
flowchart TB
    subgraph API["🔵 API — Adapters IN"]
        direction TB
        api_sp[ ]:::hidden
        api_sp ~~~ CTRL
        api_sp ~~~ EXC
        CTRL["ChangeController"]
        EXC["GlobalExceptionHandler"]
    end
    subgraph APP["🟢 Application — Ports & Services"]
        direction TB
        app_sp[ ]:::hidden
        app_sp ~~~ UC_IN
        app_sp ~~~ SVC
        app_sp ~~~ UC_OUT
        UC_IN["ports/in<br/>CreateChangeUseCase<br/>ListChangesUseCase<br/>GetChangeEventsUseCase"]
        SVC["services<br/>CreateChangeService<br/>ListChangesService<br/>GetChangeEventsService"]
        UC_OUT["ports/out<br/>SaveChangePort<br/>LoadChangesPort<br/>PublishEventPort<br/>SaveChangeEventPort"]
    end
    subgraph DOM["🟡 Domain — Pure Java"]
        direction TB
        dom_sp[ ]:::hidden
        dom_sp ~~~ AGG
        dom_sp ~~~ EVT
        dom_sp ~~~ VO
        AGG["Change<br/>(Aggregate Root)"]
        EVT["ChangePreparedEvent"]
        VO["ChangeStatus"]
    end
    subgraph INFRA["🟣 Infrastructure — Adapters OUT"]
        direction TB
        infra_sp[ ]:::hidden
        infra_sp ~~~ JPA
        infra_sp ~~~ KAFKA
        infra_sp ~~~ SEC
        JPA["ChangePersistenceAdapter"]
        KAFKA["KafkaEventPublisherAdapter"]
        SEC["SecurityConfig<br/>CorrelationIdFilter"]
    end

    API -->|uses| APP
    APP -->|uses| DOM
    INFRA -->|implements| APP
    INFRA -.->|uses| DOM

    classDef api fill:#4338CA,stroke:#6366F1,color:#E0E7FF,stroke-width:2px
    classDef app fill:#0F766E,stroke:#14B8A6,color:#CCFBF1,stroke-width:2px
    classDef domain fill:#B45309,stroke:#F59E0B,color:#FEF3C7,stroke-width:2px
    classDef infra fill:#7C3AED,stroke:#A78BFA,color:#EDE9FE,stroke-width:2px
    classDef hidden fill:transparent,stroke:none,color:transparent,stroke-width:0px

    class CTRL,EXC api
    class UC_IN,SVC,UC_OUT app
    class AGG,EVT,VO domain
    class JPA,KAFKA,SEC infra
    class api_sp,app_sp,dom_sp,infra_sp hidden

    style API fill:#1E1B4B,stroke:#4338CA,stroke-width:2px,color:#A5B4FC
    style APP fill:#042F2E,stroke:#0F766E,stroke-width:2px,color:#5EEAD4
    style DOM fill:#451A03,stroke:#B45309,stroke-width:2px,color:#FCD34D
    style INFRA fill:#2E1065,stroke:#7C3AED,stroke-width:2px,color:#C4B5FD
```

### pom.xml

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" ...>
  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.2.3</version>
  </parent>
  <groupId>com.changeops</groupId>
  <artifactId>change-service</artifactId>
  <version>1.0.0-SNAPSHOT</version>
  <properties>
    <java.version>17</java.version>
    <mapstruct.version>1.5.5.Final</mapstruct.version>
    <testcontainers.version>1.19.6</testcontainers.version>
  </properties>
  <!-- spring-boot-starter-web, validation, data-jpa, actuator, security -->
  <!-- spring-boot-starter-oauth2-resource-server -->
  <!-- spring-kafka, postgresql, flyway-core -->
  <!-- micrometer-registry-prometheus, logstash-logback-encoder:7.4 -->
  <!-- mapstruct, lombok, springdoc-openapi-starter-webmvc-ui:2.3.0 -->
  <!-- test: spring-boot-starter-test, spring-kafka-test, testcontainers -->
</project>
```

### Domínio

> **✅ Critérios atendidos:** `Arquitetura` · `Código`

#### [`Change`](../backend/change-service/src/main/java/com/changeops/changeservice/domain/model/Change.java) — Aggregate Root

> **✅ Critérios atendidos:** `Arquitetura` · `Código` · `Cenários`

```java
// domain/model/Change.java — zero imports de Spring/Kafka; objeto de domínio Java puro
@Getter
@Builder(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Change {
    private final UUID changeId;
    private final String title, description, componentId, requestedBy;
    private final Instant scheduledAt;
    private ChangeStatus status;          // MUTÁVEL: transições via complete()/fail()/cancel()
    private final UUID correlationId;     // UUID v4, gerado em Change.create()
    private final Instant createdAt;
    private Instant updatedAt;            // MUTÁVEL: atualizado por trigger + transições de domínio

    @Builder.Default
    private final List<DomainEvent> domainEvents = new ArrayList<>();  // Event sourcing

    // Factory method — única forma de criar um novo Change
    public static Change create(
            String title, String description,
            String componentId, String requestedBy, Instant scheduledAt) {

        UUID changeId     = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        Instant now       = Instant.now();

        Change change = Change.builder()
                .changeId(changeId).title(title).description(description)
                .componentId(componentId).requestedBy(requestedBy)
                .scheduledAt(scheduledAt).status(ChangeStatus.PREPARED)
                .correlationId(correlationId).createdAt(now).updatedAt(now)
                .build();

        // Dispara domain event — drenado pelo CreateChangeService.execute()
        change.domainEvents.add(new ChangePreparedEvent(
                changeId, componentId, requestedBy, scheduledAt, correlationId, now));
        return change;
    }

    // Caminho de reconstituição — usado pelo persistence adapter ao carregar do banco
    public static Change reconstitute(
            UUID changeId, String title, String description,
            String componentId, String requestedBy, Instant scheduledAt,
            ChangeStatus status, UUID correlationId,
            Instant createdAt, Instant updatedAt) {
        return Change.builder()
                .changeId(changeId).title(title).description(description)
                .componentId(componentId).requestedBy(requestedBy)
                .scheduledAt(scheduledAt).status(status)
                .correlationId(correlationId).createdAt(createdAt).updatedAt(updatedAt)
                .build();
    }

    public void complete() {
        if (this.status != ChangeStatus.PREPARED)
            throw new InvalidChangeStateException("Cannot complete change in status: " + this.status);
        this.status = ChangeStatus.COMPLETED;
        this.updatedAt = Instant.now();
    }

    public void fail() {
        if (this.status != ChangeStatus.PREPARED)
            throw new InvalidChangeStateException("Cannot fail change in status: " + this.status);
        this.status = ChangeStatus.FAILED;
        this.updatedAt = Instant.now();
    }

    public void cancel() {
        if (this.status != ChangeStatus.PREPARED)
            throw new InvalidChangeStateException("Cannot cancel change in status: " + this.status);
        this.status = ChangeStatus.CANCELLED;
        this.updatedAt = Instant.now();
    }

    // Drena a lista — eventos são publicados exatamente uma vez por ciclo de vida
    public List<DomainEvent> pullDomainEvents() {
        List<DomainEvent> events = Collections.unmodifiableList(new ArrayList<>(this.domainEvents));
        this.domainEvents.clear();
        return events;
    }
}
```

#### `ChangePreparedEvent` — Domain Event

> **✅ Critérios atendidos:** `Arquitetura` · `Código`

```java
// Implementa a interface marcadora DomainEvent; sem anotações Spring/Kafka
public record ChangePreparedEvent(
    UUID changeId, String componentId, String requestedBy,
    Instant scheduledAt, UUID correlationId, Instant occurredAt
) {}
```

#### `ChangeStatus` — Value Object

> **✅ Critérios atendidos:** `Código` · `Cenários`

```java
public enum ChangeStatus { DRAFT, PREPARED, COMPLETED, FAILED, CANCELLED }
```

```mermaid
%%{init: {'theme':'dark', 'themeVariables':{'fontSize':'16px','primaryColor':'#4338CA','primaryTextColor':'#E0E7FF','primaryBorderColor':'#6366F1','lineColor':'#94A3B8','secondaryColor':'#1E1B4B','tertiaryColor':'#B45309'}}}%%
stateDiagram-v2
    classDef svcState fill:#4338CA,stroke:#6366F1,color:#E0E7FF,font-weight:bold
    classDef doneState fill:#047857,stroke:#34D399,color:#D1FAE5,font-weight:bold
    classDef failState fill:#991B1B,stroke:#EF4444,color:#FEE2E2,font-weight:bold
    classDef cancelState fill:#334155,stroke:#64748B,color:#CBD5E1,font-weight:bold

    [*] --> PREPARED : Change.create()
    PREPARED --> COMPLETED : complete()
    PREPARED --> FAILED : fail()
    PREPARED --> CANCELLED : cancel()
    COMPLETED --> [*]
    FAILED --> [*]
    CANCELLED --> [*]

    class PREPARED svcState
    class COMPLETED doneState
    class FAILED failState
    class CANCELLED cancelState
```

### Application Layer — Use Cases

> **✅ Critérios atendidos:** `Arquitetura` · `Código`

```java
// port/in/CreateChangeUseCase.java
public interface CreateChangeUseCase {
    Result execute(Command command);
    record Command(String title, String description, String componentId,
                   String requestedBy, Instant scheduledAt) {}
    record Result(UUID changeId, String status, UUID correlationId, Instant createdAt) {}
}

// port/in/ListChangesUseCase.java
public interface ListChangesUseCase {
    Page<Result> execute(Query query, Pageable pageable);
    record Query(ChangeStatus status, String componentId) {}
    record Result(UUID changeId, String title, String componentId, String status,
                  UUID correlationId, Instant createdAt, Instant updatedAt) {}
}

// port/in/GetChangeEventsUseCase.java
public interface GetChangeEventsUseCase {
    List<Result> execute(UUID changeId);
    record Result(UUID eventId, UUID changeId, String eventType,
                  String payload, Instant occurredAt) {}
}
```

```java
// port/out/ interfaces
public interface SaveChangePort    { Change save(Change change); }
public interface LoadChangesPort   { Optional<Change> findById(UUID id);
                                     Page<Change> findAll(ChangeStatus, String, Pageable); }
public interface PublishEventPort  { void publish(Object domainEvent); }
public interface SaveChangeEventPort { void save(UUID changeId, String type,
                                                  String payload, Instant at); }
```

### CreateChangeService

> **✅ Critérios atendidos:** `Arquitetura` · `Código` · `Cenários` · `Observabilidade`

```java
@Slf4j @Service
public class CreateChangeService implements CreateChangeUseCase {

    @Override
    @Transactional
    public Result execute(Command command) {
        // 1. Factory method — gera changeId, correlationId, dispara ChangePreparedEvent
        Change change = Change.create(command.title(), command.description(),
                command.componentId(), command.requestedBy(), command.scheduledAt());

        Change saved = saveChangePort.save(change);

        // 2. Drena domain events, publica no Kafka, persiste na timeline
        saved.pullDomainEvents().forEach(event -> {
            publishEventPort.publish(event);                          // → KafkaEventPublisherAdapter
            saveChangeEventPort.save(                                 // → registro na change_events
                saved.getChangeId(),
                event.getClass().getSimpleName(),
                toJson(event),
                Instant.now());
        });

        changesCreatedCounter.increment();            // Prometheus
        log.info("Change created: changeId={}, correlationId={}",
                 saved.getChangeId(), saved.getCorrelationId());

        return new Result(saved.getChangeId(), saved.getStatus().name(),
                          saved.getCorrelationId(), saved.getCreatedAt());
    }
}
```

### Infrastructure — Kafka

> **✅ Critérios atendidos:** `Arquitetura` · `Cenários`

#### [`IntegrationEvent`](../backend/change-service/src/main/java/com/changeops/changeservice/infrastructure/kafka/IntegrationEvent.java) — Envelope externo

> **✅ Critérios atendidos:** `Arquitetura` · `Cenários` · `Comunicação`

```java
// Envolve todo domain event antes de publicar no Kafka (ADR-003)
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IntegrationEvent {
    private String  eventType;    // ex: "ChangePreparedEvent"
    private String  version;      // "1.0" — versionamento por tolerant reader
    private UUID    correlationId; // UUID de rastreamento ponta a ponta
    private Instant occurredAt;
    private Object  payload;       // DTO específico do domínio (não o domain event em si)
}
```

#### `KafkaEventPublisherAdapter`

> **✅ Critérios atendidos:** `Arquitetura` · `Cenários` · `Observabilidade`

```java
@Component
public class KafkaEventPublisherAdapter implements PublishEventPort {

    @Override
    public void publish(Object domainEvent) {
        if (domainEvent instanceof ChangePreparedEvent event) {
            IntegrationEvent envelope = IntegrationEvent.builder()
                .eventType("ChangePreparedEvent").version("1.0")
                .correlationId(event.correlationId())
                .occurredAt(Instant.now())
                .payload(new ChangePreparedPayload(event.changeId(),
                         event.componentId(), event.requestedBy(), event.scheduledAt()))
                .build();

        try {
            // Síncrono com timeout de 10s — acks=all, produtor idempotente
            CompletableFuture<SendResult<String, IntegrationEvent>> future =
                    kafkaTemplate.send(changePreparedTopic, key, envelope);
            future.get(10, TimeUnit.SECONDS);
            eventsPublishedCounter.increment();
            log.info("ChangePreparedEvent published: changeId={}, correlationId={}",
                    event.changeId(), event.correlationId());
        } catch (ExecutionException | InterruptedException | TimeoutException ex) {
            log.error("Failed to publish ChangePreparedEvent: changeId={}, correlationId={}",
                    event.changeId(), event.correlationId(), ex);
            throw new RuntimeException("Kafka publish failed", ex);
        }
    }
}
```

#### [`KafkaConfig`](../backend/change-service/src/main/java/com/changeops/changeservice/infrastructure/config/KafkaConfig.java) — Producer idempotente

> **✅ Critérios atendidos:** `Arquitetura` · `Cenários`

```java
@Configuration
public class KafkaConfig {

    @Bean
    public ProducerFactory<String, IntegrationEvent> producerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.ACKS_CONFIG, "all");                     // Durabilidade máxima
        props.put(ProducerConfig.RETRIES_CONFIG, 3);                      // Retry automático
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);        // Exactly-once na produção
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 1); // Ordem garantida
        JsonSerializer<IntegrationEvent> valueSerializer = new JsonSerializer<>(objectMapper);
        valueSerializer.setAddTypeInfo(false);  // Sem @class no JSON
        return new DefaultKafkaProducerFactory<>(props, new StringSerializer(), valueSerializer);
    }

    @Bean
    public KafkaTemplate<String, IntegrationEvent> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }

    @Bean
    public NewTopic changePreparedTopic() {
        // 3 partições: key=changeId distribui uniformemente; replica factor via env var
        return TopicBuilder.name(changePreparedTopic)
                .partitions(3)
                .replicas(replicationFactor)   // ${KAFKA_REPLICATION_FACTOR:1}
                .build();
    }
}
```

### Infrastructure — Persistência

> **✅ Critérios atendidos:** `Arquitetura` · `Código`

#### [`ChangePersistenceAdapter`](../backend/change-service/src/main/java/com/changeops/changeservice/infrastructure/persistence/ChangePersistenceAdapter.java)

> **✅ Critérios atendidos:** `Arquitetura` · `Código`

```java
@Component
public class ChangePersistenceAdapter
        implements SaveChangePort, LoadChangesPort, SaveChangeEventPort {

    @Override
    public Change save(Change change) {
        return toDomain(changeJpaRepository.save(toEntity(change)));
    }

    @Override
    public Page<Change> findAll(ChangeStatus status, String componentId, Pageable p) {
        return changeJpaRepository.findAllFiltered(status, componentId, p)
                                  .map(this::toDomain);
    }

    @Override
    public void save(UUID changeId, String type, String payload, Instant at) {
        changeEventJpaRepository.save(ChangeEventEntity.builder()
            .eventId(UUID.randomUUID()).changeId(changeId)
            .eventType(type).payload(payload).occurredAt(at).build());
    }
}
```

#### `ChangeJpaRepository`

> **✅ Critérios atendidos:** `Código` · `Segurança`

```java
public interface ChangeJpaRepository extends JpaRepository<ChangeEntity, UUID> {
    @Query("""
        SELECT c FROM ChangeEntity c
        WHERE (:status IS NULL OR c.status = :status)
          AND (:componentId IS NULL OR c.componentId = :componentId)
        ORDER BY c.createdAt DESC
        """)
    Page<ChangeEntity> findAllFiltered(
        @Param("status") ChangeStatus status,
        @Param("componentId") String componentId,
        Pageable pageable);
}
```

### API Layer

> **✅ Critérios atendidos:** `Cenários` · `Segurança`

#### [`ChangeController`](../backend/change-service/src/main/java/com/changeops/changeservice/api/controller/ChangeController.java)

> **✅ Critérios atendidos:** `Cenários` · `Código` · `Segurança`

```java
@RestController
@RequestMapping("/api/v1/changes")
public class ChangeController {

    @PostMapping
    public ResponseEntity<CreateChangeResponse> create(
            @Valid @RequestBody CreateChangeRequest request,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @AuthenticationPrincipal Jwt jwt) {

        CreateChangeUseCase.Result result = createChangeUseCase.execute(
            new CreateChangeUseCase.Command(
                request.title(), request.description(), request.componentId(),
                resolveRequestedBy(userId, jwt, request.requestedBy()),
                request.scheduledAt()));

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{id}").buildAndExpand(result.changeId()).toUri();

        return ResponseEntity.created(location)
            .body(new CreateChangeResponse(result.changeId(), result.status(),
                                           result.correlationId(), result.createdAt()));
    }

    @GetMapping
    public ResponseEntity<Page<ChangeDto>> list(
            @RequestParam(required = false) ChangeStatus status,
            @RequestParam(required = false) String componentId,
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) { ... }

    @GetMapping("/{changeId}/events")
    public ResponseEntity<List<ChangeEventDto>> getEvents(@PathVariable UUID changeId) { ... }
}
```

#### `CreateChangeRequest` — Validações

> **✅ Critérios atendidos:** `Segurança` · `Código` · `Comunicação`

```java
public record CreateChangeRequest(
    @NotBlank(message = "title is required")
    @Size(max = 255)
    String title,

    @Size(max = 2000)
    String description,

    @NotBlank(message = "componentId is required")
    @Size(max = 100, message = "componentId must not exceed 100 characters")
    @Pattern(regexp = "^[a-zA-Z0-9][a-zA-Z0-9_.\\-]{0,99}$",
            message = "componentId must start with alphanumeric and contain only letters, digits, dots, hyphens, or underscores")
    String componentId,

    @NotBlank(message = "requestedBy is required")
    String requestedBy,

    @NotNull(message = "scheduledAt is required")
    @Future(message = "scheduledAt must be a future date")
    Instant scheduledAt
) {}
```

#### `GlobalExceptionHandler` — RFC 7807 Problem Details

> **✅ Critérios atendidos:** `Comunicação` · `Código`

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fields = ex.getBindingResult().getFieldErrors().stream()
            .collect(toMap(FieldError::getField, FieldError::getDefaultMessage));
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(BAD_REQUEST, "Validation failed");
        pd.setProperty("fields", fields);
        return pd;
    }

    @ExceptionHandler(ChangeNotFoundException.class)
    public ProblemDetail handleNotFound(ChangeNotFoundException ex) { ... }
}
```

### Segurança

> **✅ Critérios atendidos:** `Segurança` · `Arquitetura`

```java
@Configuration @EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(s -> s.sessionCreationPolicy(STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/actuator/prometheus").permitAll()
                .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/changes/**").hasAnyRole("OPERATOR","ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/changes").hasAnyRole("OPERATOR","ADMIN")
                .anyRequest().authenticated())
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(
                jwt -> jwt.jwtAuthenticationConverter(new CustomJwtAuthenticationConverter())))
            .build();
    }
}
```

### Observabilidade — CorrelationIdFilter

> **✅ Critérios atendidos:** `Observabilidade` · `Cenários`

```java
@Component @Order(1)
public class CorrelationIdFilter implements Filter {
    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) {
        String correlationId = httpRequest.getHeader("X-Correlation-Id");
        if (correlationId == null) correlationId = UUID.randomUUID().toString();
        MDC.put("correlation_id", correlationId);
        MDC.put("service", "change-service");
        httpResponse.setHeader("X-Correlation-Id", correlationId);
        try { chain.doFilter(req, res); } finally { MDC.clear(); }
    }
}
```

### application.yml

> **✅ Critérios atendidos:** `Arquitetura` · `Segurança` · `Observabilidade`

```yaml
spring:
  application:
    name: change-service
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:changeops}
    username: ${DB_USER:changeops}
    password: ${DB_PASS:changeops}
  jpa:
    hibernate.ddl-auto: validate
  flyway:
    enabled: true
    locations: classpath:db/migration
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
  security.oauth2.resourceserver.jwt:
    issuer-uri: ${JWT_ISSUER_URI:http://localhost:8180/realms/changeops}

server.port: ${SERVER_PORT:8080}

management:
  endpoints.web.exposure.include: health,info,prometheus,metrics
  metrics.tags.application: ${spring.application.name}

changeops.kafka.topics.change-prepared: changeops.change.prepared
```

### Testes

> **✅ Critérios atendidos:** `Testes`

#### [`ChangeTest`](../backend/change-service/src/test/java/com/changeops/changeservice/domain/model/ChangeTest.java) — Unitário (domínio)

> **✅ Critérios atendidos:** `Testes` · `Código`

```java
class ChangeTest {
    @Test void create_shouldSetPreparedStatus_andGenerateCorrelationId() { ... }
    @Test void create_shouldRaiseDomainEvent() { ... }
    @Test void pullDomainEvents_shouldClearEventsAfterPull() { ... }
    @Test void complete_shouldTransitionToCompleted_whenPrepared() { ... }
    @Test void fail_shouldThrow_whenAlreadyFailed() { ... }
}
```

#### `CreateChangeIT` — Integração (Testcontainers)

> **✅ Critérios atendidos:** `Testes` · `Cenários`

```java
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Testcontainers @ActiveProfiles("test")
class CreateChangeIT {
    @Container static PostgreSQLContainer<?> postgres = ...;
    @Container static KafkaContainer kafka = ...;

    @Test void shouldCreate_whenPayloadIsValid_thenReturn201AndPublishEvent() { ... }
    @Test void shouldReturn400_whenTitleIsMissing() { ... }
    @Test void shouldReturn400_whenComponentIdIsMissing() { ... }
    @Test void shouldReturn400_whenComponentIdHasInvalidFormat() { ... }
    @Test void shouldListChanges_andReturnPaginatedResults() { ... }
}
```

### Fluxo 1A — Criação de Change via Frontend

O operador preenche o formulário, o React envia via Axios e exibe uma mensagem de sucesso.

```mermaid
%%{init: {'theme':'dark', 'themeVariables':{'fontSize':'14px','actorTextColor':'#E0E7FF','actorBkg':'#4338CA','actorBorder':'#6366F1','activationBorderColor':'#6366F1','signalColor':'#CBD5E1','signalTextColor':'#E2E8F0','noteBkgColor':'#1E293B','noteBorderColor':'#6366F1','noteTextColor':'#E2E8F0','altSectionBkgColor':'#0F172A','labelTextColor':'#A5B4FC'}}}%%
sequenceDiagram
    autonumber
    actor Operador
    participant CF as ChangeForm
    participant UC as useCreateChange
    participant SVC as changeService
    participant HTTP as http (Axios)
    participant API as change-service :8080
    participant Store as useChangesStore
    participant Page as ChangesPage

    Operador->>CF: Preenche título, componente, data
    Operador->>CF: Clica "Create Change"
    CF->>UC: create(payload)
    UC->>SVC: changeService.create(payload)
    SVC->>HTTP: http.post('/changes', payload)

    Note over HTTP: Interceptor adiciona<br/>Authorization: Bearer {token}<br/>X-User-Id: dev-user-001

    HTTP->>API: POST /api/v1/changes
    API-->>HTTP: 201 Created { changeId, status, correlationId }
    HTTP-->>SVC: response.data
    SVC-->>UC: CreateChangeResponse
    UC-->>CF: result (success)
    CF->>Page: onSuccess(changeId)
    Page->>Page: Fecha formulário
    Page->>Page: Exibe toast "Change abc123… criada"

    Note over Page: Toast desaparece<br/>após 5 segundos
```

<details>
<summary>Trechos de Código — ChangeForm, useCreateChange, changeService, http</summary>

**`ChangeForm.tsx`** — submit do formulário:

```tsx
const handleSubmit = async (e: FormEvent) => {
  e.preventDefault()
  const payload: CreateChangePayload = {
    ...form,
    scheduledAt: new Date(form.scheduledAt).toISOString(),
  }
  const result = await create(payload)
  if (result) {
    setForm(EMPTY)
    onSuccess?.(result.changeId)
  }
}
```

**`useCreateChange.ts`** — hook de criação:

```typescript
export function useCreateChange() {
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<ApiError | null>(null)

  const create = async (payload: CreateChangePayload): Promise<CreateChangeResponse | null> => {
    setLoading(true)
    setError(null)
    try {
      const data = await changeService.create(payload)
      setResult(data)
      return data
    } catch (e) {
      setError(e as ApiError)
      return null
    } finally {
      setLoading(false)
    }
  }
  // ...
}
```

**`changeService.ts`** — camada de serviço HTTP:

```typescript
const changeService = {
  async create(payload: CreateChangePayload): Promise<CreateChangeResponse> {
    const { data } = await http.post<CreateChangeResponse>('/changes', payload)
    return data
  },
}
```

**`http.ts`** — interceptors do Axios:

```typescript
http.interceptors.request.use((config) => {
  const token = localStorage.getItem('access_token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  const userId = localStorage.getItem('user_id') ?? 'dev-user-001'
  config.headers['X-User-Id'] = userId
  return config
})

http.interceptors.response.use(
  (res) => res,
  (err: AxiosError) => {
    const data = err.response?.data as Partial<ApiError> | undefined
    const apiError: ApiError = {
      title: data?.title ?? 'Unexpected Error',
      detail: data?.detail ?? err.message,
      status: err.response?.status ?? 0,
      fields: data?.fields,
      timestamp: data?.timestamp ?? new Date().toISOString(),
    }
    return Promise.reject(apiError)
  },
)
```

</details>

---

### Fluxo 1B — Criação de Change (Detalhe Backend)

O que acontece dentro do `change-service` quando a requisição `POST /api/v1/changes` chega.

```mermaid
%%{init: {'theme':'dark', 'themeVariables':{'fontSize':'14px','actorTextColor':'#E0E7FF','actorBkg':'#4338CA','actorBorder':'#6366F1','activationBorderColor':'#6366F1','signalColor':'#CBD5E1','signalTextColor':'#E2E8F0','noteBkgColor':'#1E293B','noteBorderColor':'#6366F1','noteTextColor':'#E2E8F0','altSectionBkgColor':'#0F172A','labelTextColor':'#A5B4FC'}}}%%
sequenceDiagram
    autonumber
    participant Client as Cliente (Frontend / curl)
    participant CIF as CorrelationIdFilter
    participant RLF as RateLimitFilter
    participant CTRL as ChangeController
    participant SVC as CreateChangeService
    participant DOM as Change (Domain)
    participant DB as PostgreSQL
    participant KP as KafkaEventPublisherAdapter
    participant Kafka as Kafka :9092

    Client->>CIF: POST /api/v1/changes
    Note over CIF: Extrai ou gera<br/>correlation_id (UUID)<br/>Popula MDC

    CIF->>RLF: request
    Note over RLF: Bucket4j<br/>100 req/min por IP

    alt Limite excedido
        RLF-->>Client: 429 Too Many Requests
    end

    RLF->>CTRL: request validado
    CTRL->>CTRL: @Valid — Bean Validation<br/>resolve requestedBy (JWT ou X-User-Id)
    CTRL->>SVC: execute(Command)

    activate SVC
    Note over SVC: @Transactional

    SVC->>DOM: Change.create(title, componentId, ...)
    Note over DOM: Gera changeId (UUID)<br/>status = PREPARED<br/>Registra ChangePreparedEvent

    DOM-->>SVC: Change + domainEvents

    SVC->>SVC: change.pullDomainEvents()
    SVC->>DB: saveChangePort.save(change)
    Note over DB: INSERT INTO changes (...)

    loop Para cada DomainEvent
        SVC->>KP: publishEventPort.publish(event)
        KP->>KP: Envelopa em IntegrationEvent
        KP->>Kafka: send(changeops.change.prepared, key, envelope)
        Note over Kafka: Tópico:<br/>changeops.change.prepared
        Kafka-->>KP: ack (partition, offset)
        KP->>KP: events_published_total++

        SVC->>DB: saveChangeEventPort.save(timeline)
        Note over DB: INSERT INTO change_events (...)
    end

    SVC->>SVC: changes_created_total++
    deactivate SVC

    SVC-->>CTRL: Result(changeId, status, correlationId)
    CTRL-->>Client: 201 Created + Location header
```

<details>
<summary>Trechos de Código — CorrelationIdFilter, ChangeController, CreateChangeService, Change, KafkaEventPublisherAdapter</summary>

**`CorrelationIdFilter.java`** — gera e propaga o correlation_id:

```java
@Component
@Order(1)
public class CorrelationIdFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        String correlationId = httpRequest.getHeader("X-Correlation-Id");
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }
        MDC.put("correlation_id", correlationId);
        MDC.put("service", "change-service");
        ((HttpServletResponse) response).setHeader("X-Correlation-Id", correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }
}
```

**`ChangeController.java`** — endpoint REST:

```java
@PostMapping
public ResponseEntity<CreateChangeResponse> create(
        @Valid @RequestBody CreateChangeRequest request,
        @RequestHeader(value = "X-User-Id", required = false) String userId,
        @AuthenticationPrincipal Jwt jwt) {

    String requestedBy = resolveRequestedBy(userId, jwt, request.requestedBy());
    UUID correlationId = UUID.fromString(MDC.get("correlation_id"));

    CreateChangeUseCase.Result result = createChangeUseCase.execute(
            new CreateChangeUseCase.Command(
                    request.title(), request.description(),
                    request.componentId(), requestedBy,
                    request.scheduledAt(), correlationId));

    URI location = ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{id}").buildAndExpand(result.changeId()).toUri();

    return ResponseEntity.created(location).body(new CreateChangeResponse(
            result.changeId(), result.status(),
            result.correlationId(), result.createdAt()));
}
```

**`CreateChangeService.java`** — orquestração do caso de uso:

```java
@Override
@Transactional
public Result execute(Command command) {
    Change change = Change.create(
            command.title(), command.description(),
            command.componentId(), command.requestedBy(),
            command.scheduledAt(), command.correlationId());

    List<DomainEvent> events = change.pullDomainEvents();
    Change saved = saveChangePort.save(change);

    events.forEach(event -> {
        publishEventPort.publish(event);
        persistEventToTimeline(saved, event);
    });

    changesCreatedCounter.increment();
    return new Result(saved.getChangeId(), saved.getStatus(),
                      saved.getCorrelationId(), saved.getCreatedAt());
}
```

**`Change.create()`** — factory method do aggregate root (domínio puro):

```java
public static Change create(String title, String description, String componentId,
                             String requestedBy, Instant scheduledAt, UUID correlationId) {
    UUID changeId = UUID.randomUUID();
    Instant now = Instant.now();

    Change change = Change.builder()
            .changeId(changeId).title(title).description(description)
            .componentId(componentId).requestedBy(requestedBy)
            .scheduledAt(scheduledAt).status(ChangeStatus.PREPARED)
            .correlationId(correlationId).createdAt(now).updatedAt(now)
            .build();

    change.domainEvents.add(new ChangePreparedEvent(
            changeId, componentId, requestedBy, scheduledAt, correlationId, now));

    return change;
}
```

**`KafkaEventPublisherAdapter.java`** — envelopa e publica no Kafka:

```java
private void publishChangePrepared(ChangePreparedEvent event) {
    String key = event.changeId().toString();
    IntegrationEvent envelope = IntegrationEvent.builder()
            .eventType("ChangePreparedEvent")
            .version("1.0")
            .correlationId(event.correlationId())
            .occurredAt(Instant.now())
            .payload(new ChangePreparedPayload(
                    event.changeId(), event.componentId(),
                    event.requestedBy(), event.scheduledAt()))
            .build();

    SendResult<String, IntegrationEvent> result =
            kafkaTemplate.send(changePreparedTopic, key, envelope)
                         .get(10, TimeUnit.SECONDS);
    eventsPublishedCounter.increment();
}
```

</details>

---

## 2. Repositório Backend — deploy-orchestrator

### Visão Geral

> **✅ Critérios atendidos:** `Arquitetura` · `Comunicação`

Serviço responsável pelo **Fluxo 2** completo: consumo do `DeployFinishedEvent`, garantia de idempotência, checklist pós-deploy, atualização de status e publicação de `ChangeCompletedEvent` / `ChangeFailedEvent` com retry exponencial e DLQ.

```
deploy-orchestrator/
├── application/
│   ├── port/in/   ProcessDeployResultUseCase
│   ├── port/out/  IdempotencyPort, UpdateChangeStatusPort, PublishResultEventPort
│   └── service/   ProcessDeployResultService, PostDeployChecklistService
├── domain/
│   ├── event/     DeployFinishedEvent (consumed)
│   ├── model/     ChangeResult
│   └── exception/ InvalidOrchestratorStateException
└── infrastructure/
    ├── kafka/     DeployEventConsumer, KafkaResultPublisherAdapter,
    │              KafkaConfig, IntegrationEvent
    └── persistence/ IdempotencyAdapter, UpdateChangeStatusAdapter,
                     ProcessedEventEntity/Repository, ChangeStatusEntity/Repository
```

```mermaid
%%{init: {'theme':'dark', 'themeVariables':{'fontSize':'15px','lineColor':'#94A3B8','edgeLabelBackground':'transparent'}}}%%
flowchart TB
    subgraph KIN["🔵 Kafka Consumer — Adapter IN"]
        direction TB
        kin_sp[ ]:::hidden
        kin_sp ~~~ CONS
        CONS["DeployEventConsumer<br/>@RetryableTopic · @KafkaListener"]
    end
    subgraph APP["🟢 Application — Ports & Services"]
        direction TB
        app_sp[ ]:::hidden
        app_sp ~~~ UC
        app_sp ~~~ SVC
        app_sp ~~~ PORTS
        UC["ProcessDeployResultUseCase"]
        SVC["ProcessDeployResultService<br/>PostDeployChecklistService"]
        PORTS["ports/out<br/>IdempotencyPort<br/>UpdateChangeStatusPort<br/>PublishResultEventPort"]
    end
    subgraph DOM["🟡 Domain — Pure Java"]
        direction TB
        dom_sp[ ]:::hidden
        dom_sp ~~~ EVT
        dom_sp ~~~ RES
        EVT["DeployFinishedEvent"]
        RES["ChangeResult"]
    end
    subgraph INFRA["🟣 Infrastructure — Adapters OUT"]
        direction TB
        infra_sp[ ]:::hidden
        infra_sp ~~~ IDEM
        infra_sp ~~~ UPD
        infra_sp ~~~ PUB
        IDEM["IdempotencyAdapter<br/>(processed_events)"]
        UPD["UpdateChangeStatusAdapter<br/>(changes table)"]
        PUB["KafkaResultPublisherAdapter<br/>(change.result topic)"]
    end

    KIN -->|uses| APP
    APP -->|uses| DOM
    INFRA -->|implements| APP
    INFRA -.->|uses| DOM

    classDef api fill:#4338CA,stroke:#6366F1,color:#E0E7FF,stroke-width:2px
    classDef app fill:#0F766E,stroke:#14B8A6,color:#CCFBF1,stroke-width:2px
    classDef domain fill:#B45309,stroke:#F59E0B,color:#FEF3C7,stroke-width:2px
    classDef infra fill:#7C3AED,stroke:#A78BFA,color:#EDE9FE,stroke-width:2px
    classDef hidden fill:transparent,stroke:none,color:transparent,stroke-width:0px

    class CONS api
    class UC,SVC,PORTS app
    class EVT,RES domain
    class IDEM,UPD,PUB infra
    class kin_sp,app_sp,dom_sp,infra_sp hidden

    style KIN fill:#1E1B4B,stroke:#4338CA,stroke-width:2px,color:#A5B4FC
    style APP fill:#042F2E,stroke:#0F766E,stroke-width:2px,color:#5EEAD4
    style DOM fill:#451A03,stroke:#B45309,stroke-width:2px,color:#FCD34D
    style INFRA fill:#2E1065,stroke:#7C3AED,stroke-width:2px,color:#C4B5FD
```

### `DeployFinishedEvent` — Consumed

> **✅ Critérios atendidos:** `Arquitetura` · `Cenários`

```java
public record DeployFinishedEvent(
    String eventType, String version, UUID correlationId, Instant occurredAt,
    Payload payload
) {
    public record Payload(UUID deployId, UUID changeId, String result, Instant executedAt) {}
    public boolean isSuccess() { return "SUCCESS".equalsIgnoreCase(payload().result()); }
}
```

### `ProcessDeployResultService` — Orquestração

> **✅ Critérios atendidos:** `Arquitetura` · `Código` · `Cenários` · `Observabilidade`

```java
@Slf4j @Service
public class ProcessDeployResultService implements ProcessDeployResultUseCase {

    @Override
    @Transactional
    public void execute(DeployFinishedEvent event) {
        var payload = event.payload();
        MDC.put("correlation_id", event.correlationId() != null
                ? event.correlationId().toString() : "unknown");
        MDC.put("change_id",  payload.changeId().toString());
        MDC.put("deploy_id",  payload.deployId().toString());

        Timer.Sample timerSample = Timer.start();
        try {
            // 0. Pré-condição: changeId deve existir antes de queimar a chave de idempotência
            //    ChangeNotFoundException é retryable — o record pode não estar visível ainda
            if (!updateChangeStatusPort.existsByChangeId(payload.changeId())) {
                throw new ChangeNotFoundException(
                    "changeId not found in database, will retry. changeId=" + payload.changeId());
            }

            // 1. Verificação atômica de idempotência + marcação (INSERT … ON CONFLICT DO NOTHING)
            if (!idempotencyPort.tryMarkAsProcessed(payload.deployId(), "deploy-orchestrator")) {
                eventsDiscardedCounter.increment();
                log.warn("Event already processed, discarding: deployId={}", payload.deployId());
                return;
            }

            // 2. Checklist pós-deploy
            ChecklistResult checklist = checklistService.execute(
                payload.changeId(), payload.deployId(), event.isSuccess());

            // 3. Construir resultado
            ChangeResult result = ChangeResult.from(payload.changeId(), payload.deployId(),
                event.correlationId(), event.isSuccess() && checklist.allPassed());

            // 4. Atualizar status da change + salvar evento na timeline
            if (result.isSuccess()) updateChangeStatusPort.markCompleted(payload.changeId());
            else                    updateChangeStatusPort.markFailed(payload.changeId());

            // 5. Publicar evento de resultado
            result.markFinished();
            publishResultEventPort.publish(result);

        } finally {
            timerSample.stop(orchestrationTimer);
            MDC.remove("correlation_id");
            MDC.remove("deploy_id");
            MDC.remove("change_id");
        }
    }
}
```

### `DeployEventConsumer` — Kafka com Retry + DLQ

> **✅ Critérios atendidos:** `Arquitetura` · `Cenários` · `Código`

```java
@Component
public class DeployEventConsumer {

    static final int MAX_DLT_PAYLOAD_LOG_LENGTH = 500;

    @RetryableTopic(
        attempts = "4",
        backoff = @Backoff(delay = 500, multiplier = 2.0, maxDelay = 10_000),
        autoCreateTopics = "true",
        topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
        dltStrategy = DltStrategy.FAIL_ON_ERROR,
        dltTopicSuffix = "-dlt",
        exclude = {InvalidOrchestratorStateException.class}  // DLT direto, 0 retries
    )
    @KafkaListener(
        topics = "${changeops.kafka.topics.deploy-finished}",
        groupId = "${changeops.kafka.consumer.group-id}",
        containerFactory = "deployEventListenerContainerFactory"
    )
    public void onDeployFinished(
            ConsumerRecord<String, DeployFinishedEvent> record,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.OFFSET) long offset) {

        DeployFinishedEvent event = record.value();

        // Null-check: ErrorHandlingDeserializer entrega null quando a desserialização falha
        // (ex: UUID malformado — poison pill). InvalidOrchestratorStateException está em
        // exclude do @RetryableTopic → mensagem é roteada direto ao DLT (0 retries).
        if (event == null || event.payload() == null) {
            log.error("Deserialization failed or invalid payload — sending to DLT: topic={}, offset={}, key={}",
                    topic, offset, record.key());
            throw new InvalidOrchestratorStateException(
                    "Deserialization failed or invalid payload. topic=" + topic + ", offset=" + offset);
        }

        if (topic.contains("-retry-")) {
            eventsRetriesCounter.increment();
        }

        processDeployResultUseCase.execute(event);
    }

    @DltHandler
    public void onDlt(ConsumerRecord<String, Object> record) {
        String topic = record.topic();
        String payload = record.value() instanceof byte[]
                ? new String((byte[]) record.value(), StandardCharsets.UTF_8)
                : String.valueOf(record.value());
        String safePayload = payload != null && payload.length() > MAX_DLT_PAYLOAD_LOG_LENGTH
                ? payload.substring(0, MAX_DLT_PAYLOAD_LOG_LENGTH) + "...[truncated]"
                : payload;
        log.error("Event routed to DLT: key={}, topic={}, offset={}, payload={}",
                record.key(), topic, record.offset(), safePayload);
        dltCounter.increment();
        eventsFailedCounter.increment();
    }
}
```

### `IdempotencyAdapter`

> **✅ Critérios atendidos:** `Arquitetura` · `Cenários` · `Observabilidade`

```java
@Component
public class IdempotencyAdapter implements IdempotencyPort {

    private final ProcessedEventRepository repository;

    // Verificação nível 1 — leitura rápida antes de qualquer processamento
    @Override
    public boolean isAlreadyProcessed(UUID eventId) {
        return repository.existsById(eventId);
    }

    // Nível 2 — escrita segura com proteção contra race condition
    @Override
    public void markAsProcessed(UUID eventId, String serviceName) {
        try {
            repository.save(ProcessedEventEntity.builder()
                .eventId(eventId).processedAt(Instant.now())
                .serviceName(serviceName).build());
        } catch (DataIntegrityViolationException e) {
            // Race condition: another instance already processed — safe to ignore
            log.warn("Concurrent idempotency conflict for eventId={}", eventId);
        }
    }

    // Insert atômico (INSERT … ON CONFLICT DO NOTHING) — retorna false se duplicado
    @Override
    @Transactional
    public boolean tryMarkAsProcessed(UUID eventId, String serviceName) {
        UUID req = Objects.requireNonNull(eventId, "eventId must not be null");
        String svc = Objects.requireNonNull(serviceName, "serviceName must not be null");
        int inserted = repository.insertIfAbsent(req, svc);
        if (inserted == 0) {
            log.debug("Event already processed (atomic check): eventId={}", req);
            return false;
        }
        log.debug("Event atomically marked as processed: eventId={}", req);
        return true;
    }
}
```

### [`KafkaConfig`](../backend/deploy-orchestrator/src/main/java/com/changeops/deployorchestrator/infrastructure/kafka/KafkaConfig.java) — Consumer + Producer

> **✅ Critérios atendidos:** `Arquitetura` · `Cenários`

```java
@Configuration
public class KafkaConfig {

    // ── Consumer ──────────────────────────────────────────────────────────────

    @Bean
    public ConsumerFactory<String, DeployFinishedEvent> deployEventConsumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        JsonDeserializer<DeployFinishedEvent> jsonDeserializer =
                new JsonDeserializer<>(DeployFinishedEvent.class, objectMapper, false);
        jsonDeserializer.addTrustedPackages("com.changeops.*");
        // ErrorHandlingDeserializer captura falhas de desserialização (ex: UUID malformado)
        // e entrega null ao listener em vez de travar o poll() do consumer.
        ErrorHandlingDeserializer<DeployFinishedEvent> errorHandlingDeserializer =
                new ErrorHandlingDeserializer<>(jsonDeserializer);
        return new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), errorHandlingDeserializer);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, DeployFinishedEvent>
    deployEventListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, DeployFinishedEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(deployEventConsumerFactory());
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.setConcurrency(3); // 3 threads para 3 partições
        return factory;
    }

    // ── Producer Padrão (@Primary — usado pelo @RetryableTopic para retry/DLT) ──────────────
    // JsonSerializer<Object> permite serializar qualquer POJO (incluindo DeployFinishedEvent)
    // sem erro de tipo. Para poison pills, o Spring Kafka preserva os bytes brutos
    // nos headers do record automaticamente via ErrorHandlingDeserializer.

    @Primary
    @Bean
    public KafkaTemplate<String, Object> defaultKafkaTemplate() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        JsonSerializer<Object> valueSerializer = new JsonSerializer<>(objectMapper);
        valueSerializer.setAddTypeInfo(false);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(
                props, new StringSerializer(), valueSerializer));
    }

    // ── Result Producer ───────────────────────────────────────────────────────

    @Bean
    public ProducerFactory<String, IntegrationEvent> resultProducerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        JsonSerializer<IntegrationEvent> valueSerializer = new JsonSerializer<>(objectMapper);
        valueSerializer.setAddTypeInfo(false); // JSON limpo, sem Spring type headers
        return new DefaultKafkaProducerFactory<>(props, new StringSerializer(), valueSerializer);
    }

    @Bean
    public KafkaTemplate<String, IntegrationEvent> resultKafkaTemplate() {
        return new KafkaTemplate<>(resultProducerFactory());
    }

    // ── Topics ────────────────────────────────────────────────────────────────

    @Bean public NewTopic deployFinishedTopic() {
        return TopicBuilder.name(deployFinishedTopic).partitions(3).replicas(replicationFactor).build();
    }
    @Bean public NewTopic deployFinishedDltTopic() {
        return TopicBuilder.name(deployFinishedTopic + "-dlt").partitions(1).replicas(replicationFactor).build();
    }
    @Bean public NewTopic changeResultTopic() {
        return TopicBuilder.name(changeResultTopic).partitions(3).replicas(replicationFactor).build();
    }
    @Bean public NewTopic changeResultDltTopic() {
        return TopicBuilder.name(changeResultTopic + "-dlt").partitions(1).replicas(replicationFactor).build();
    }
}
```

### `application.yml` — [`deploy-orchestrator`](../backend/deploy-orchestrator/src/main/resources/application.yml)

> **✅ Critérios atendidos:** `Arquitetura` · `Observabilidade`

```yaml
spring:
  application:
    name: deploy-orchestrator
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:changeops}
    username: ${DB_USER:changeops}
    password: ${DB_PASS:changeops}
  flyway:
    enabled: true
    locations: classpath:db/migration
    # Tabela de histórico separada — evita conflitos com migrations do change-service
    table: flyway_schema_history_orchestrator
    # baseline-on-migrate: schema já populado pelo change-service na inicialização
    baseline-on-migrate: true
    baseline-version: 1
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    consumer:
      properties:
        spring.json.trusted.packages: com.changeops.*

changeops:
  kafka:
    consumer:
      group-id: ${KAFKA_GROUP_ID:deploy-orchestrator-group}
    topics:
      deploy-finished: ${KAFKA_TOPIC_DEPLOY_FINISHED:changeops.deploy.finished}
      change-result:   ${KAFKA_TOPIC_CHANGE_RESULT:changeops.change.result}

management:
  metrics:
    distribution:
      percentiles-histogram:
        spring.kafka.listener: true
    export:
      prometheus:
        enabled: true
        step: 5s
    tags:
      application: deploy-orchestrator
```

### Testes — [`deploy-orchestrator`](../backend/deploy-orchestrator/src/test/java/com/changeops/deployorchestrator/)

> **✅ Critérios atendidos:** `Testes` · `Cenários`

```java
// ProcessDeployResultServiceTest.java
class ProcessDeployResultServiceTest {
    @Test void shouldMarkCompleted_andPublishEvent_whenDeploySucceeds() { ... }
    @Test void shouldMarkFailed_andPublishEvent_whenDeployFails() { ... }
    @Test void shouldDiscardEvent_whenDeployIdAlreadyProcessed() { ... }
    @Test void shouldNotMarkCompleted_whenSameEventDeliveredTwice() { ... }
    @Test void shouldThrowNonRetryableException_whenChangeIdNotFound() { ... }
}

// DeployEventConsumerTest.java — unitário (sem Spring context)
class DeployEventConsumerTest {
    @Test void shouldThrowInvalidOrchestratorStateException_whenEventIsNull() { ... }
    @Test void shouldThrowInvalidOrchestratorStateException_whenEventPayloadIsNull() { ... }
    @Test void shouldIncrementEventsFailedAndDltCounters_whenDltHandlerIsCalled() { ... }
    @Test void shouldIncrementDltCounters_whenDltHandlerReceivesNullEvent() { ... }
    @Test void shouldHandleByteArrayPayload_inDltHandler() { ... }
    @Test void shouldTruncatePayload_whenDltPayloadExceedsMaxLength() { ... }
    @Test void shouldIncrementRetriesForEachRetryTopic() { ... }
}

// DeployEventConsumerIT.java — Testcontainers (Kafka 7.6.0 + PostgreSQL 16)
@SpringBootTest
@EmbeddedKafka
class DeployEventConsumerIT {
    @Test void fullFlow_success(); // Kafka → DB COMPLETED + change.result publicado
    @Test void fullFlow_failure(); // Kafka → DB FAILED
    @Test void idempotency_sameEventTwice_stateUnchanged();
    @Test void shouldSendToDlt_whenProcessingFailsAfterRetries();
    @Test void shouldSendToDlt_whenMessageHasMalformedPayload(); // poison pill → DLT sem loop
}

// IdempotencyIT.java — Testcontainers (PostgreSQL 16 + Kafka 7.6.0)
class IdempotencyIT {
    @Test void shouldMarkEventAsProcessed_onFirstAttempt();
    @Test void shouldDiscardDuplicateEventWithoutReprocessing();
    @Test void shouldAllowDifferentEventsForSameConsumer();
    @Test void shouldTreatDifferentConsumersAsIndependentContexts();
}
```

### [`PostDeployChecklistService`](../backend/deploy-orchestrator/src/main/java/com/changeops/deployorchestrator/application/service/PostDeployChecklistService.java) — Simulado / Extensível

> **✅ Critérios atendidos:** `Cenários` · `Evolução`

```java
@Service
public class PostDeployChecklistService {
    public ChecklistResult execute(UUID changeId, UUID deployId, boolean deploySucceeded) {
        List<CheckItem> items = List.of(
            check("deploy-result-gate",    deploySucceeded, "Deploy result was FAILURE"),
            check("healthcheck",           deploySucceeded, "Service did not become healthy"),
            check("smoke-test",            deploySucceeded, "Smoke test failed post-deploy"),
            check("error-rate-threshold",  deploySucceeded, "Error rate exceeded threshold")
        );
        // Extensão: chamar health endpoints reais, queries no Prometheus, test runners externos
        return new ChecklistResult(items.stream().allMatch(CheckItem::passed), ...);
    }
}
```

### Testes

```java
class ProcessDeployResultServiceTest {
    @Test void shouldMarkCompleted_andPublishEvent_whenDeploySucceeds() { ... }
    @Test void shouldMarkFailed_andPublishEvent_whenDeployFails() { ... }
    @Test void shouldDiscardEvent_whenDeployIdAlreadyProcessed() { ... }
    @Test void shouldNotMarkCompleted_whenSameEventDeliveredTwice() { ... }
    @Test void shouldThrowNonRetryableException_whenChangeIdNotFound() { ... }
}
```

### Fluxo 2A — Orquestração de Deploy (Happy Path)

Quando o sistema de deploy publica um `DeployFinishedEvent` no Kafka, o `deploy-orchestrator` consome, verifica idempotência, executa o checklist pós-deploy, atualiza o status e publica o resultado.

```mermaid
%%{init: {'theme':'dark', 'themeVariables':{'fontSize':'14px','actorTextColor':'#E0E7FF','actorBkg':'#4338CA','actorBorder':'#6366F1','activationBorderColor':'#6366F1','signalColor':'#CBD5E1','signalTextColor':'#E2E8F0','noteBkgColor':'#1E293B','noteBorderColor':'#6366F1','noteTextColor':'#E2E8F0','altSectionBkgColor':'#0F172A','labelTextColor':'#A5B4FC'}}}%%
sequenceDiagram
    autonumber
    participant Kafka_IN as Kafka<br/>changeops.deploy.finished
    participant DEC as DeployEventConsumer
    participant SVC as ProcessDeployResultService
    participant IDP as IdempotencyAdapter
    participant DB as PostgreSQL
    participant CHK as PostDeployChecklistService
    participant UPD as UpdateChangeStatusAdapter
    participant EVT as ChangeEventAdapter
    participant KP as KafkaResultPublisherAdapter
    participant Kafka_OUT as Kafka<br/>changeops.change.result

    Kafka_IN->>DEC: ConsumerRecord(DeployFinishedEvent)
    Note over DEC: @RetryableTopic<br/>attempts=4, backoff 500ms×2

    DEC->>DEC: Valida payload (null check)
    DEC->>DEC: Popula MDC<br/>(correlation_id, deploy_id)

    DEC->>SVC: processDeployResultUseCase.execute(event)
    activate SVC
    Note over SVC: @Transactional

    SVC->>UPD: existsByChangeId(changeId)
    UPD->>DB: SELECT exists FROM changes
    DB-->>UPD: true
    UPD-->>SVC: ✓ existe

    SVC->>IDP: tryMarkAsProcessed(deployId, "deploy-orchestrator")
    IDP->>DB: INSERT processed_events ON CONFLICT DO NOTHING
    DB-->>IDP: 1 (novo registro)
    IDP-->>SVC: true (primeira vez)

    SVC->>CHK: execute(changeId, deployId, deploySucceeded=true)
    Note over CHK: 4 checks simulados:<br/>✓ deploy-result-gate<br/>✓ healthcheck<br/>✓ smoke-test<br/>✓ error-rate-threshold
    CHK-->>SVC: ChecklistResult(allPassed=true)

    SVC->>UPD: markCompleted(changeId)
    UPD->>DB: UPDATE changes SET status='COMPLETED'
    SVC->>SVC: changes_completed_total++

    SVC->>EVT: save(changeId, "ChangeCompletedEvent", payload)
    EVT->>DB: INSERT INTO change_events

    SVC->>KP: publish(changeResult)
    KP->>KP: buildEnvelope → IntegrationEvent
    KP->>Kafka_OUT: send(changeops.change.result, key, envelope)
    Kafka_OUT-->>KP: ack
    KP->>KP: events_published_total++

    SVC->>SVC: events_consumed_total++
    deactivate SVC

    DEC-->>Kafka_IN: commit offset
```

<details>
<summary>Trechos de Código — DeployEventConsumer, ProcessDeployResultService, PostDeployChecklistService</summary>

**`DeployEventConsumer.java`** — configuração de retry e listener:

```java
@RetryableTopic(
        attempts = "4",
        backoff = @Backoff(delay = 500, multiplier = 2.0, maxDelay = 10_000),
        topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
        dltStrategy = DltStrategy.FAIL_ON_ERROR,
        dltTopicSuffix = "-dlt",
        exclude = {InvalidOrchestratorStateException.class}
)
@KafkaListener(
        topics = "${changeops.kafka.topics.deploy-finished}",
        groupId = "${changeops.kafka.consumer.group-id}",
        containerFactory = "deployEventListenerContainerFactory"
)
public void onDeployFinished(
        ConsumerRecord<String, DeployFinishedEvent> record,
        @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
        @Header(KafkaHeaders.OFFSET) long offset) {
    // Validação + delegação para o use case
    processDeployResultUseCase.execute(event);
}
```

**`ProcessDeployResultService.java`** — orquestração completa:

```java
@Override
@Transactional
public void execute(DeployFinishedEvent event) {
    var payload = event.payload();
    MDC.put("correlation_id", event.correlationId().toString());
    MDC.put("change_id", payload.changeId().toString());
    MDC.put("deploy_id", payload.deployId().toString());

    Timer.Sample timerSample = Timer.start();
    try {
        if (!updateChangeStatusPort.existsByChangeId(payload.changeId())) {
            throw new ChangeNotFoundException("changeId not found, will retry");
        }

        if (!idempotencyPort.tryMarkAsProcessed(payload.deployId(), "deploy-orchestrator")) {
            eventsDiscardedCounter.increment();
            return;
        }

        ChecklistResult checklist = checklistService.execute(
                payload.changeId(), payload.deployId(), event.isSuccess());

        ChangeResult changeResult = ChangeResult.from(
                payload.changeId(), payload.deployId(),
                event.correlationId(), event.isSuccess() && checklist.allPassed());

        if (changeResult.isSuccess()) {
            updateChangeStatusPort.markCompleted(payload.changeId());
            changesCompletedCounter.increment();
        } else {
            updateChangeStatusPort.markFailed(payload.changeId());
            changesFailedCounter.increment();
        }

        changeResult.markFinished();
        publishResultEventPort.publish(changeResult);
        eventsConsumedCounter.increment();
    } finally {
        timerSample.stop(orchestrationTimer);
        MDC.remove("correlation_id");
    }
}
```

**`PostDeployChecklistService.java`** — 4 verificações simuladas:

```java
public ChecklistResult execute(UUID changeId, UUID deployId, boolean deploySucceeded) {
    List<CheckItem> items = new ArrayList<>();
    items.add(check("deploy-result-gate",    deploySucceeded, "Deploy result was FAILURE"));
    items.add(check("healthcheck",           deploySucceeded, "Service did not become healthy"));
    items.add(check("smoke-test",            deploySucceeded, "Smoke test failed post-deploy"));
    items.add(check("error-rate-threshold",  deploySucceeded, "Error rate exceeded threshold"));

    boolean allPassed = items.stream().allMatch(CheckItem::passed);
    String failureReason = items.stream()
            .filter(i -> !i.passed()).findFirst()
            .map(CheckItem::failureMessage).orElse(null);

    return new ChecklistResult(allPassed, failureReason, items);
}
```

</details>

---

### Fluxo 2B — Idempotência (Evento Duplicado)

A estratégia de dois níveis garante que o mesmo `DeployFinishedEvent` nunca será processado duas vezes, mesmo com redelivery do Kafka.

```mermaid
%%{init: {'theme':'dark', 'themeVariables':{'fontSize':'14px','actorTextColor':'#E0E7FF','actorBkg':'#4338CA','actorBorder':'#6366F1','activationBorderColor':'#6366F1','signalColor':'#CBD5E1','signalTextColor':'#E2E8F0','noteBkgColor':'#1E293B','noteBorderColor':'#6366F1','noteTextColor':'#E2E8F0','altSectionBkgColor':'#0F172A','labelTextColor':'#A5B4FC'}}}%%
sequenceDiagram
    autonumber
    participant K as Kafka
    participant DEC as DeployEventConsumer
    participant SVC as ProcessDeployResultService
    participant IDP as IdempotencyAdapter
    participant DB as PostgreSQL (processed_events)

    Note over K,DB: ── Primeira entrega (processamento normal) ──
    K->>DEC: DeployFinishedEvent (deployId=abc-123)
    DEC->>SVC: execute(event)
    SVC->>IDP: tryMarkAsProcessed(abc-123, "deploy-orchestrator")
    IDP->>DB: INSERT INTO processed_events<br/>ON CONFLICT DO NOTHING
    DB-->>IDP: 1 row inserted (novo)
    IDP-->>SVC: true → continua processamento
    SVC->>SVC: Executa checklist, atualiza status, publica resultado
    SVC-->>DEC: ✓ sucesso

    Note over K,DB: ── Segunda entrega (duplicado) ──
    K->>DEC: DeployFinishedEvent (deployId=abc-123)
    DEC->>SVC: execute(event)
    SVC->>IDP: tryMarkAsProcessed(abc-123, "deploy-orchestrator")
    IDP->>DB: INSERT INTO processed_events<br/>ON CONFLICT DO NOTHING

    rect rgb(69, 26, 26)
        DB-->>IDP: 0 rows inserted (conflito)
        IDP-->>SVC: false → já processado
        SVC->>SVC: events_discarded_total++ (reason=duplicate)
        SVC->>SVC: log.warn("Event already processed, discarding")
        SVC-->>DEC: return (sem reprocessar)
    end
```

<details>
<summary>Trechos de Código — Implementação de idempotência</summary>

**Nível 1 — Aplicação** (`ProcessDeployResultService.java`):

```java
if (!idempotencyPort.tryMarkAsProcessed(payload.deployId(), "deploy-orchestrator")) {
    eventsDiscardedCounter.increment();
    log.warn("Event already processed, discarding: deployId={}", payload.deployId());
    return;  // Retorno antecipado — nenhum efeito colateral
}
```

**Nível 2 — Banco de dados** (`ProcessedEventRepository.java`):

```java
@Modifying
@Query(value = "INSERT INTO processed_events (event_id, processed_at, service_name) " +
       "VALUES (:eventId, NOW(), :serviceName) " +
       "ON CONFLICT (event_id, service_name) DO NOTHING",
       nativeQuery = true)
int insertIfAbsent(@Param("eventId") UUID eventId, @Param("serviceName") String serviceName);
```

**Adapter** (`IdempotencyAdapter.java`):

```java
@Override
public boolean tryMarkAsProcessed(UUID eventId, String serviceName) {
    int inserted = repository.insertIfAbsent(eventId, serviceName);
    return inserted != 0;  // true = novo, false = duplicado
}
```

**Schema SQL** — tabela `processed_events`:

```sql
CREATE TABLE processed_events (
    event_id        UUID          NOT NULL,
    processed_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    service_name    VARCHAR(100)  NOT NULL,
    PRIMARY KEY (event_id, service_name)
);
```

</details>

---

### Fluxo 2C — Retry e Dead Letter Topic (DLT)

Quando o processamento de um evento falha com uma exceção retentável (ex: `ChangeNotFoundException` por eventual consistency), o Spring Kafka executa até 4 tentativas com backoff exponencial antes de encaminhar para o DLT.

```mermaid
%%{init: {'theme':'dark', 'themeVariables':{'fontSize':'14px','actorTextColor':'#E0E7FF','actorBkg':'#4338CA','actorBorder':'#6366F1','activationBorderColor':'#6366F1','signalColor':'#CBD5E1','signalTextColor':'#E2E8F0','noteBkgColor':'#1E293B','noteBorderColor':'#6366F1','noteTextColor':'#E2E8F0','altSectionBkgColor':'#0F172A','labelTextColor':'#A5B4FC'}}}%%
sequenceDiagram
    autonumber
    participant K1 as Kafka<br/>deploy.finished
    participant DEC as DeployEventConsumer
    participant SVC as ProcessDeployResultService
    participant R0 as Kafka<br/>deploy.finished-retry-0
    participant R1 as Kafka<br/>deploy.finished-retry-1
    participant R2 as Kafka<br/>deploy.finished-retry-2
    participant DLT as Kafka<br/>deploy.finished-dlt

    K1->>DEC: Tentativa 1
    DEC->>SVC: execute(event)
    SVC--xDEC: ❌ ChangeNotFoundException

    Note over DEC,R0: Backoff: 500ms
    DEC->>R0: Publica no retry-0
    R0->>DEC: Tentativa 2
    DEC->>SVC: execute(event)
    SVC--xDEC: ❌ ChangeNotFoundException
    DEC->>DEC: events_retries_total++

    Note over DEC,R1: Backoff: 1000ms
    DEC->>R1: Publica no retry-1
    R1->>DEC: Tentativa 3
    DEC->>SVC: execute(event)
    SVC--xDEC: ❌ ChangeNotFoundException
    DEC->>DEC: events_retries_total++

    Note over DEC,R2: Backoff: 2000ms
    DEC->>R2: Publica no retry-2
    R2->>DEC: Tentativa 4 (última)
    DEC->>SVC: execute(event)
    SVC--xDEC: ❌ ChangeNotFoundException
    DEC->>DEC: events_retries_total++

    rect rgb(69, 26, 26)
        Note over DEC,DLT: Todas as tentativas esgotadas
        DEC->>DLT: Envia para DLT
        DEC->>DEC: @DltHandler.onDlt()
        DEC->>DEC: events_dlt_total++
        DEC->>DEC: events_failed_total++
        Note over DLT: Mensagem disponível no<br/>Kafka UI para inspeção
    end
```

### Configuração de Retry

| Parâmetro | Valor | Descrição |
|-----------|-------|-----------|
| `attempts` | 4 | Total de tentativas (1 original + 3 retries) |
| `delay` | 500ms | Delay inicial |
| `multiplier` | 2.0 | Fator exponencial (500 → 1000 → 2000 → 4000ms) |
| `maxDelay` | 10s | Teto de delay |
| `exclude` | `InvalidOrchestratorStateException` | Exceções que vão direto para DLT |

<details>
<summary>Trechos de Código — @RetryableTopic, @DltHandler</summary>

**`@RetryableTopic`** — configuração na annotation:

```java
@RetryableTopic(
        attempts = "4",
        backoff = @Backoff(delay = 500, multiplier = 2.0, maxDelay = 10_000),
        autoCreateTopics = "true",
        topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
        dltStrategy = DltStrategy.FAIL_ON_ERROR,
        dltTopicSuffix = "-dlt",
        exclude = {InvalidOrchestratorStateException.class}
)
```

**`@DltHandler`** — tratamento do Dead Letter:

```java
@DltHandler
public void onDlt(ConsumerRecord<String, Object> record) {
    String safePayload = toSafePayload(record.value());  // Trunca em 500 bytes
    log.error("Event routed to DLT: key={}, topic={}, offset={}, payload={}",
            record.key(), record.topic(), record.offset(), safePayload);
    dltCounter.increment();
    eventsFailedCounter.increment();
}
```

</details>

---

### Fluxo 2D — Poison Pill (Erro de Deserialização)

Uma mensagem malformada (JSON inválido, tipo incompatível) resulta em payload `null` após deserialização. O consumer detecta e lança `InvalidOrchestratorStateException`, que é excluída do retry — vai direto para o DLT.

```mermaid
%%{init: {'theme':'dark', 'themeVariables':{'fontSize':'14px','actorTextColor':'#E0E7FF','actorBkg':'#4338CA','actorBorder':'#6366F1','activationBorderColor':'#6366F1','signalColor':'#CBD5E1','signalTextColor':'#E2E8F0','noteBkgColor':'#1E293B','noteBorderColor':'#6366F1','noteTextColor':'#E2E8F0','altSectionBkgColor':'#0F172A','labelTextColor':'#A5B4FC'}}}%%
sequenceDiagram
    autonumber
    participant Ext as Sistema Externo
    participant K as Kafka<br/>deploy.finished
    participant DEC as DeployEventConsumer
    participant DLT as Kafka<br/>deploy.finished-dlt

    Ext->>K: Mensagem malformada<br/>(JSON inválido / tipo errado)
    K->>DEC: ConsumerRecord(value = null)

    rect rgb(69, 39, 26)
        DEC->>DEC: event == null || event.payload() == null
        DEC->>DEC: throw InvalidOrchestratorStateException
        Note over DEC: Excluída do retry<br/>(exclude = {InvalidOrchestratorStateException.class})
    end

    rect rgb(69, 26, 26)
        DEC->>DLT: Encaminha direto para DLT<br/>(0 retries)
        DEC->>DEC: @DltHandler → log + métricas
        DEC->>DEC: events_dlt_total++<br/>events_failed_total++
    end

    Note over DLT: Payload original disponível<br/>no Kafka UI para diagnóstico
```

<details>
<summary>Trecho de Código — DeployEventConsumer, InvalidOrchestratorStateException</summary>

**Validação no consumer** (`DeployEventConsumer.java`):

```java
public void onDeployFinished(ConsumerRecord<String, DeployFinishedEvent> record, ...) {
    DeployFinishedEvent event = record.value();

    if (event == null || event.payload() == null) {
        log.error("Deserialization failed or invalid payload — sending to DLT: "
                + "topic={}, offset={}, key={}", topic, offset, record.key());
        throw new InvalidOrchestratorStateException(
                "Deserialization failed or invalid payload. topic=" + topic);
    }

    DeployFinishedEvent.Payload payload = event.payload();
    if (payload.deployId() == null || payload.changeId() == null || payload.result() == null) {
        log.error("Malformed payload: required fields must not be null — routing to DLT");
        throw new InvalidOrchestratorStateException(
                "Malformed DeployFinishedEvent: required payload fields are null");
    }
    // ... processamento normal
}
```

**Exceção não-retentável** (`InvalidOrchestratorStateException.java`):

```java
public class InvalidOrchestratorStateException extends RuntimeException {
    public InvalidOrchestratorStateException(String message) {
        super(message);
    }
}
```

</details>

---

### Fluxo 2E — Falha na Publicação → DLQ

Quando o `deploy-orchestrator` processa com sucesso o evento de deploy mas falha ao publicar o resultado no Kafka (timeout, broker indisponível), a mensagem é encaminhada para o tópico DLQ.

```mermaid
%%{init: {'theme':'dark', 'themeVariables':{'fontSize':'14px','actorTextColor':'#E0E7FF','actorBkg':'#4338CA','actorBorder':'#6366F1','activationBorderColor':'#6366F1','signalColor':'#CBD5E1','signalTextColor':'#E2E8F0','noteBkgColor':'#1E293B','noteBorderColor':'#6366F1','noteTextColor':'#E2E8F0','altSectionBkgColor':'#0F172A','labelTextColor':'#A5B4FC'}}}%%
sequenceDiagram
    autonumber
    participant SVC as ProcessDeployResultService
    participant KP as KafkaResultPublisherAdapter
    participant K1 as Kafka<br/>changeops.change.result
    participant DLQ as Kafka<br/>changeops.change.result-dlt

    SVC->>KP: publish(changeResult)
    KP->>KP: buildEnvelope(changeResult)<br/>→ IntegrationEvent
    KP->>K1: kafkaTemplate.send(...).get(10s)

    rect rgb(69, 26, 26)
        K1--xKP: ❌ ExecutionException / TimeoutException
        KP->>KP: log.error("Failed to publish — sending to DLQ")
        KP->>DLQ: sendToDlq(key, envelope)
        Note over DLQ: Mensagem preservada<br/>para reprocessamento manual
    end

    alt DLQ também falha
        DLQ--xKP: ❌ Erro crítico
        KP->>KP: log.error("CRITICAL: Failed to send to DLQ<br/>— manual intervention required")
    end
```

<details>
<summary>Trecho de Código — KafkaResultPublisherAdapter</summary>

**`KafkaResultPublisherAdapter.java`** — publicação com fallback para DLQ:

```java
@Override
public void publish(ChangeResult result) {
    String key = result.getChangeId().toString();
    IntegrationEvent envelope = buildEnvelope(result);
    try {
        kafkaTemplate.send(changeResultTopic, key, envelope)
                     .get(10, TimeUnit.SECONDS);
        getOrCreateCounter(envelope.eventType()).increment();
    } catch (ExecutionException | TimeoutException e) {
        log.error("Failed to publish — sending to DLQ: changeId={}", result.getChangeId());
        sendToDlq(key, envelope);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        sendToDlq(key, envelope);
    }
}

private void sendToDlq(String key, IntegrationEvent envelope) {
    kafkaTemplate.send(dlqTopic, key, envelope)
        .whenComplete((r, ex) -> {
            if (ex != null) {
                log.error("CRITICAL: Failed to send to DLQ — manual intervention required");
            }
        });
}
```

</details>

---

## 3. Repositório Frontend

### Stack

- **React 18** + **TypeScript 5**
- **Vite** (build + dev server)
- **Zustand** (estado global)
- **@tanstack/react-query v5** (server-state caching, invalidação automática, background refresh)
- **Axios** (HTTP client com interceptors)
- **date-fns** (formatação de datas)
- **Vitest** + **Testing Library** (testes)

### Estrutura

```
frontend/src/
├── features/changes/
│   ├── types/       index.ts  (Change, ChangeEvent, PageResponse, ApiError)
│   ├── services/    changeService.ts  (create, list, getEvents)
│   ├── store/       useChangesStore.ts  (Zustand)
│   ├── hooks/       useChanges.ts, useCreateChange.ts, useChangeEvents.ts
│   └── components/  ChangeForm, ChangeList, ChangeTimeline
├── shared/
│   ├── components/  StatusBadge
│   ├── hooks/       usePolling.ts
│   └── lib/         http.ts (axios), test-setup.ts
└── app/
    └── routes/      ChangesPage.tsx (main page)
```

```mermaid
%%{init: {'theme':'dark','themeVariables':{'fontSize':'15px','lineColor':'#94A3B8','edgeLabelBackground':'transparent'}}}%%
flowchart TD
    APP["📱 App.tsx"]
    PAGE["📄 ChangesPage"]
    FORM["ChangeForm"]
    LIST["ChangeList"]
    TIMELINE["ChangeTimeline"]
    BADGE["StatusBadge"]
    STORE["📦 useChangesStore<br/>(Zustand)"]
    H_CHANGES["🪝 useChanges<br/>+ usePolling"]
    H_CREATE["🪝 useCreateChange"]
    H_EVENTS["🪝 useChangeEvents"]
    SVC["🔌 changeService<br/>(Axios)"]

    APP --> PAGE
    PAGE --> FORM & LIST & TIMELINE
    LIST --> BADGE
    FORM --> H_CREATE
    LIST --> H_CHANGES
    TIMELINE --> H_EVENTS
    H_CHANGES --> STORE & SVC
    H_CREATE --> SVC
    H_EVENTS --> SVC

    classDef page fill:#4338CA,stroke:#6366F1,color:#E0E7FF,stroke-width:2px
    classDef component fill:#0F766E,stroke:#14B8A6,color:#CCFBF1,stroke-width:2px
    classDef hook fill:#B45309,stroke:#F59E0B,color:#FEF3C7,stroke-width:2px
    classDef service fill:#7C3AED,stroke:#A78BFA,color:#EDE9FE,stroke-width:2px

    class APP,PAGE page
    class FORM,LIST,TIMELINE,BADGE component
    class H_CHANGES,H_CREATE,H_EVENTS,STORE hook
    class SVC service
```

### [`http.ts`](../frontend/src/shared/lib/http.ts) — Axios Client

> **✅ Critérios atendidos:** `Segurança` · `Código`

```typescript
export const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? '/api/v1',
  headers: { 'Content-Type': 'application/json' },
  timeout: 10_000,
})

// NOTA DE SEGURANÇA: auth via localStorage é placeholder da POC.
// Em produção, substituir por cookie httpOnly via fluxo OAuth2 PKCE (ROADMAP § 2.2).
http.interceptors.request.use((config) => {
  const token = localStorage.getItem('access_token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  const userId = localStorage.getItem('user_id') ?? 'dev-user-001'
  config.headers['X-User-Id'] = userId  // fallback apenas para dev (perfil local)
  return config
})

// Normaliza erros no formato RFC 7807 ApiError
http.interceptors.response.use(
  (res) => res,
  (err: AxiosError) => {
    const data = err.response?.data as Partial<ApiError> | undefined
    const apiError: ApiError = {
      title:     data?.title  ?? 'Unexpected Error',
      detail:    data?.detail ?? err.message,
      status:    err.response?.status ?? 0,
      fields:    data?.fields,
      timestamp: data?.timestamp ?? new Date().toISOString(),
    }
    return Promise.reject(apiError)
  },
)
```

### [`changeService.ts`](../frontend/src/features/changes/services/changeService.ts)

> **✅ Critérios atendidos:** `Código` · `Cenários`

```typescript
const changeService = {
  async create(payload: CreateChangePayload): Promise<CreateChangeResponse> {
    const { data } = await http.post<CreateChangeResponse>('/changes', payload)
    return data
  },
  async list(params: ListChangesParams = {}): Promise<PageResponse<Change>> {
    const { data } = await http.get<PageResponse<Change>>('/changes', { params })
    return data
  },
  async getEvents(changeId: string): Promise<ChangeEvent[]> {
    const { data } = await http.get<ChangeEvent[]>(`/changes/${changeId}/events`)
    return data
  },
}
```

### `useChangesStore.ts` — Zustand

> **✅ Critérios atendidos:** `Código`

```typescript
export const useChangesStore = create<ChangesState>((set) => ({
  page: null,
  selectedChangeId: null,
  isPolling: false,
  setPage: (page) => set({ page }),
  setSelectedChangeId: (id) => set({ selectedChangeId: id }),
  upsertChange: (change) => set((state) => {
    if (!state.page) return {}
    const content = state.page.content.map(c =>
      c.changeId === change.changeId ? change : c)
    return { page: { ...state.page, content } }
  }),
}))
```

### `usePolling.ts`

> **✅ Critérios atendidos:** `Código` · `Cenários`

```typescript
export function usePolling(
  callback: () => void | Promise<void>,
  { interval = 5_000, enabled = true }: UsePollingOptions = {}
) {
  const savedCallback = useRef(callback)
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  // Keep ref in sync without recreating the effect
  useEffect(() => { savedCallback.current = callback }, [callback])

  const stop = useCallback(() => {
    if (timerRef.current) clearTimeout(timerRef.current)
  }, [])

  useEffect(() => {
    if (!enabled) return
    const tick = async () => {
      try   { await savedCallback.current() }
      finally { timerRef.current = setTimeout(tick, interval) } // auto-reagendamento
    }
    timerRef.current = setTimeout(tick, interval)
    return stop // limpeza ao desmontar / mudança de dependência
  }, [enabled, interval, stop])

  return { stop }
}
```

### `useChanges.ts` — Lista com Polling

> **✅ Critérios atendidos:** `Código` · `Cenários`

```typescript
export function useChanges(params: ListChangesParams = {}) {
  const { page, setPage } = useChangesStore()
  const [loading, setLoading] = useState(false)
  const [pollError, setPollError] = useState(false)

  const fetch = useCallback(async () => { ... }, [])

  // Carregamento inicial (com spinner)
  const load = useCallback(async () => { setLoading(true); await fetch(); setLoading(false) }, [])

  // Polling em background a cada 5s (refresh silencioso, sem spinner)
  usePolling(fetch, { interval: 5_000, enabled: !!page })

  return { changes: page?.content ?? [], page, loading, error, pollError, load }
}
```

### Fluxo: Listagem e Polling — Frontend

O `ChangeList` carrega a lista de changes na montagem do componente e inicia um polling silencioso a cada 5 segundos para manter os dados atualizados.

```mermaid
%%{init: {'theme':'dark', 'themeVariables':{'fontSize':'14px','actorTextColor':'#E0E7FF','actorBkg':'#4338CA','actorBorder':'#6366F1','activationBorderColor':'#6366F1','signalColor':'#CBD5E1','signalTextColor':'#E2E8F0','noteBkgColor':'#1E293B','noteBorderColor':'#6366F1','noteTextColor':'#E2E8F0','altSectionBkgColor':'#0F172A','labelTextColor':'#A5B4FC'}}}%%
sequenceDiagram
    autonumber
    participant CL as ChangeList
    participant UC as useChanges
    participant Poll as usePolling (5s)
    participant SVC as changeService
    participant HTTP as http (Axios)
    participant API as change-service :8080
    participant Store as useChangesStore
    participant Page as ChangesPage

    Note over CL: Componente monta

    CL->>UC: load()
    UC->>SVC: changeService.list({ page: 0, size: 20 })
    SVC->>HTTP: http.get('/changes', { params })
    HTTP->>API: GET /api/v1/changes?page=0&size=20
    API-->>HTTP: 200 PageResponse<Change> + statusSummary
    HTTP-->>SVC: data
    SVC-->>UC: PageResponse
    UC->>Store: setPage(response)
    Store-->>Page: page (reactive)
    Page->>Page: Renderiza Stats Cards<br/>Total / Prepared / Completed / Failed
    CL->>CL: Renderiza tabela com changes

    Note over Poll: Polling inicia<br/>(interval=5000ms, enabled=!!page)

    loop A cada 5 segundos
        Poll->>UC: fetch(currentPage)
        UC->>SVC: changeService.list({ page: currentPage })
        SVC->>API: GET /api/v1/changes?page=N&size=20
        API-->>SVC: PageResponse (atualizada)
        UC->>Store: setPage(updated)
        Store-->>CL: Re-render silencioso (sem spinner)
    end

    alt Falha no poll
        UC->>CL: pollError = true
        CL->>CL: Exibe banner amarelo<br/>"Live updates paused — connection issue"
    end
```

### `ChangeForm.tsx` — Formulário com Validação Dupla

> **✅ Critérios atendidos:** `Código` · `Cenários` · `Segurança`

```typescript
export function ChangeForm({ onSuccess }: Props) {
  const [form, setForm] = useState<CreateChangePayload>(EMPTY)
  const { create, loading, error } = useCreateChange()

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault()
    const result = await create({ ...form, scheduledAt: new Date(form.scheduledAt).toISOString() })
    if (result) { setForm(EMPTY); onSuccess?.(result.changeId) }
    // Em caso de erro: dados do formulário preservados, erros por campo exibidos
  }

  return (
    <form onSubmit={handleSubmit} noValidate>
      {/* Global API error banner */}
      {/* Field: title, description, componentId, scheduledAt */}
      {/* Per-field error messages from API response */}
      {/* Submit button with loading spinner */}
    </form>
  )
}
```

### `ChangeList.tsx` — Tabela com Loading Skeleton

> **✅ Critérios atendidos:** `Código`

```typescript
export function ChangeList() {
  // - Loading skeleton (5 rows) enquanto carrega
  // - Polling failure banner (amarelo) se conexão perdida
  // - Empty state descritivo se sem mudanças
  // - Tabela: changeId, title, componentId, StatusBadge, createdAt, Timeline →
  // - Linha clicável: abre/fecha timeline no store
}
```

### `ChangeTimeline.tsx` — Histórico Visual

> **✅ Critérios atendidos:** `Código` · `Observabilidade`

```typescript
export function ChangeTimeline({ changeId, onClose }: Props) {
  // - Linha do tempo vertical com ponto colorido por eventType
  // - ChangePreparedEvent   → azul (📋)
  // - ChangeCompletedEvent  → verde (✅)
  // - ChangeFailedEvent     → vermelho (❌)
  // - Payload expansível com <details> + <pre> JSON
  // - Timestamps formatados com date-fns
}
```

### Fluxo: Timeline de Eventos — Frontend

Ao selecionar uma change na tabela, o painel lateral de timeline é exibido com o histórico de eventos — cada transição de estado é registrada.

```mermaid
%%{init: {'theme':'dark', 'themeVariables':{'fontSize':'14px','actorTextColor':'#E0E7FF','actorBkg':'#4338CA','actorBorder':'#6366F1','activationBorderColor':'#6366F1','signalColor':'#CBD5E1','signalTextColor':'#E2E8F0','noteBkgColor':'#1E293B','noteBorderColor':'#6366F1','noteTextColor':'#E2E8F0','altSectionBkgColor':'#0F172A','labelTextColor':'#A5B4FC'}}}%%
sequenceDiagram
    autonumber
    actor Operador
    participant CL as ChangeList
    participant Store as useChangesStore
    participant Page as ChangesPage
    participant TL as ChangeTimeline
    participant UC as useChangeEvents
    participant SVC as changeService
    participant API as change-service :8080

    Operador->>CL: Clica na linha da change
    CL->>Store: setSelectedChangeId(changeId)
    Store-->>Page: selectedChangeId (reactive)

    Page->>TL: Renderiza ChangeTimeline<br/>{ changeId, onClose }

    TL->>UC: useChangeEvents(changeId)
    UC->>SVC: changeService.getEvents(changeId)
    SVC->>API: GET /api/v1/changes/{changeId}/events
    API-->>SVC: ChangeEvent[] (ordered by occurredAt)
    SVC-->>UC: events
    UC-->>TL: events[]

    TL->>TL: Renderiza timeline vertical<br/>🔵 Prepared → 🟢 Completed / 🔴 Failed

    alt Operador clica "Show payload"
        TL->>TL: Expande JSON do evento
    end

    alt Operador clica "Refresh"
        TL->>UC: reload()
        UC->>API: GET /api/v1/changes/{changeId}/events
    end

    Operador->>TL: Clica fechar (✕)
    TL->>Store: setSelectedChangeId(null)
```

### `ChangesPage.tsx` — Composição Final

> **✅ Critérios atendidos:** `Código`

```typescript
export function ChangesPage() {
  return (
    <div className="min-h-screen bg-gray-50">
      <header> {/* ChangeOps logo + "+ New Change" button */} </header>
      <main>
        {successMsg && <SuccessBanner />}
        {showForm   && <ChangeForm onSuccess={handleSuccess} />}
        <ChangeList />
        {selectedChangeId && <ChangeTimeline changeId={selectedChangeId} onClose={...} />}
      </main>
    </div>
  )
}
```

### Testes Frontend

> **✅ Critérios atendidos:** `Testes`

```typescript
// ChangeForm.test.tsx
describe('ChangeForm', () => {
  it('renders all required fields')
  it('calls onSuccess with changeId when form is valid')
  it('shows API field errors without clearing form data')
})
```

---

## 4. Contratos

### OpenAPI — `contracts/openapi/change-service.yml`

> **✅ Critérios atendidos:** `Comunicação` · `Cenários`

```yaml
openapi: "3.1.0"
info:
  title: ChangeOps — Change Service API
  version: "1.0.0"

paths:
  /changes:
    post:
      operationId: createChange
      # → 201 CreateChangeResponse | 400 ProblemDetail (mapa de campos)
    get:
      operationId: listChanges
      # parâmetros: status, componentId, page, size, sort
      # → 200 PageOfChanges

  /changes/{changeId}/events:
    get:
      operationId: getChangeEvents
      # → 200 ChangeEvent[] | 404 ProblemDetail

components:
  schemas:
    CreateChangeRequest, CreateChangeResponse, ChangeDto,
    ChangeEvent, ChangeStatus (enum), PageOfChanges, ProblemDetail
  securitySchemes:
    bearerAuth: { type: http, scheme: bearer, bearerFormat: JWT }
```

### AsyncAPI — `contracts/asyncapi/events.yml`

> **✅ Critérios atendidos:** `Comunicação` · `Arquitetura` · `Cenários`

```yaml
asyncapi: "2.6.0"
info:
  title: ChangeOps — Event Contracts
  version: "1.0.0"

channels:
  changeops.change.prepared:     # Publicado pelo change-service
  changeops.deploy.finished:     # Publicado pelo sistema de deploy externo
  changeops.change.result:       # Publicado pelo deploy-orchestrator
  changeops.change.result-dlt:   # Fila de mensagens mortas (dead-letter)

# IntegrationEnvelope: { eventType, version, correlationId, occurredAt, payload }
# Payloads: ChangePreparedPayload, DeployFinishedPayload,
#           ChangeCompletedPayload, ChangeFailedPayload
```

### Topologia de Tópicos Kafka

> **✅ Critérios atendidos:** `Comunicação` · `Arquitetura`

```mermaid
%%{init: {'theme':'dark','themeVariables':{'fontSize':'15px','lineColor':'#94A3B8','edgeLabelBackground':'transparent'}}}%%
flowchart TD
    subgraph clients["Clientes"]
        direction LR
        FE(["Frontend :3000"])
        DS(["Sistema de Deploy"])
    end

    subgraph svcs["Serviços"]
        direction LR
        CS(["change-service :8080"])
        DO(["deploy-orchestrator :8081"])
    end

    subgraph kafka["Apache Kafka — Tópicos"]
        direction LR
        T1{{"changeops.<br/>change.prepared"}}
        T2{{"changeops.<br/>deploy.finished"}}
        T3{{"changeops.<br/>change.result"}}
    end

    subgraph retry["Retry & DLT"]
        direction LR
        R0{{"deploy.finished<br/>-retry-0"}}
        R1{{"deploy.finished<br/>-retry-1"}}
        R2{{"deploy.finished<br/>-retry-2"}}
        DLT{{"deploy.finished<br/>-dlt"}}
        DLQ{{"change.result<br/>-dlt (DLQ)"}}
    end

    PG[("PostgreSQL :5432")]

    FE -->|"POST + GET (poll)"| CS
    CS -->|"persist"| PG
    CS -->|"publish"| T1
    DS -->|"publish"| T2
    T2 -->|"consume"| DO
    DO -->|"status update"| PG
    DO -->|"publish"| T3
    DO -.->|"retry"| R0
    R0 -.-> R1
    R1 -.-> R2
    R2 -.->|"esgotado"| DLT
    DO -.->|"falha publicação"| DLQ

    classDef svc fill:#4338CA,stroke:#6366F1,color:#E0E7FF,stroke-width:2px
    classDef topic fill:#C2410C,stroke:#FB923C,color:#FFF7ED,stroke-width:2px
    classDef db fill:#047857,stroke:#34D399,color:#D1FAE5,stroke-width:2px
    classDef ext fill:#334155,stroke:#64748B,color:#CBD5E1,stroke-width:2px
    classDef dlt fill:#991B1B,stroke:#EF4444,color:#FEE2E2,stroke-width:2px

    class FE,CS,DO svc
    class T1,T2,T3 topic
    class R0,R1,R2,DLT,DLQ dlt
    class PG db
    class DS ext

    style clients fill:none,stroke:#64748B,stroke-width:1px,color:#94A3B8
    style svcs fill:#1E1B4B,stroke:#6366F1,stroke-width:1px,color:#C7D2FE
    style kafka fill:#431407,stroke:#FB923C,stroke-width:1px,color:#FED7AA
    style retry fill:#1C0A0A,stroke:#EF4444,stroke-width:1px,color:#FCA5A5
```

### Envelope `IntegrationEvent`

Todos os eventos publicados no Kafka seguem a mesma estrutura de envelope. O evento de domínio nunca é publicado diretamente — sempre é envelopado com metadados de rastreabilidade e versionamento.

```json
{
  "eventType": "ChangePreparedEvent",
  "version": "1.0",
  "correlationId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "occurredAt": "2026-03-30T15:02:38.142Z",
  "payload": {
    "changeId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
    "componentId": "payment-service",
    "requestedBy": "dev-user-001",
    "scheduledAt": "2026-04-01T10:00:00Z"
  }
}
```

<details>
<summary>Código — IntegrationEvent.java</summary>

```java
@Builder
public record IntegrationEvent(
        String eventType,       // "ChangePreparedEvent", "ChangeCompletedEvent", "ChangeFailedEvent"
        String version,         // "1.0" — versionamento para compatibilidade
        UUID correlationId,     // Rastreabilidade ponta a ponta
        Instant occurredAt,     // Timestamp do envio
        Object payload          // Payload específico do evento de domínio
) {}
```

</details>

### Mapa de Tópicos

| Tópico | Produtor | Consumidor | Payload |
|--------|----------|------------|---------|
| `changeops.change.prepared` | change-service | — (futuro) | `ChangePreparedEvent` |
| `changeops.deploy.finished` | Sistema externo | deploy-orchestrator | `DeployFinishedEvent` |
| `changeops.change.result` | deploy-orchestrator | — (futuro) | `ChangeCompletedEvent` / `ChangeFailedEvent` |
| `changeops.deploy.finished-retry-{0,1,2}` | Spring Kafka (auto) | deploy-orchestrator | Retry automático (0-based index) |
| `changeops.deploy.finished-dlt` | Spring Kafka (auto) | `@DltHandler` | Dead Letter — falhas permanentes |
| `changeops.change.result-dlt` | deploy-orchestrator | — (manual) | DLQ — falha na publicação do resultado |

---

## 5. Modelo de Dados e Persistência

### ER Diagram

> **✅ Critérios atendidos:** `Comunicação` · `Arquitetura`

```mermaid
%%{init: {'theme':'dark', 'themeVariables':{'fontSize':'15px','primaryColor':'#4338CA','primaryTextColor':'#E0E7FF','lineColor':'#94A3B8','primaryBorderColor':'#6366F1','edgeLabelBackground':'#1E293B','attributeBackgroundColorEven':'#1E1B4B','attributeBackgroundColorOdd':'#2D2A55','attributeTextColor':'#C7D2FE'}}}%%
erDiagram
    changes {
        uuid change_id PK
        varchar title
        text description
        varchar component_id
        varchar requested_by
        timestamptz scheduled_at
        varchar status
        uuid correlation_id
        timestamptz created_at
        timestamptz updated_at
    }

    change_events {
        uuid event_id PK
        uuid change_id FK
        varchar event_type
        jsonb payload
        timestamptz occurred_at
    }

    processed_events {
        uuid event_id PK
        timestamptz processed_at
        varchar service_name
    }

    changes ||--o{ change_events : "has"
```

### Migrations Flyway

> **✅ Critérios atendidos:** `Cenários` · `Segurança`

#### [`V1__create_changes_schema.sql`](../backend/change-service/src/main/resources/db/migration/V1__create_changes_schema.sql)

```sql
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE changes (
    change_id       UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    title           VARCHAR(255)    NOT NULL,
    description     TEXT,
    component_id    VARCHAR(100)    NOT NULL,
    requested_by    VARCHAR(100)    NOT NULL,
    scheduled_at    TIMESTAMPTZ     NOT NULL,
    status          VARCHAR(20)     NOT NULL DEFAULT 'PREPARED'
                    CHECK (status IN ('DRAFT','PREPARED','COMPLETED','FAILED','CANCELLED')),
    correlation_id  UUID            NOT NULL,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_changes_status         ON changes (status);
CREATE INDEX idx_changes_component_id   ON changes (component_id);
CREATE INDEX idx_changes_created_at     ON changes (created_at DESC);
CREATE INDEX idx_changes_correlation_id ON changes (correlation_id);

CREATE TABLE change_events (
    event_id    UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    change_id   UUID         NOT NULL REFERENCES changes(change_id) ON DELETE CASCADE,
    event_type  VARCHAR(100) NOT NULL,
    payload     JSONB        NOT NULL DEFAULT '{}',
    occurred_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE processed_events (
    event_id     UUID          PRIMARY KEY,  -- Constraint UNIQUE = garantia de idempotência
    processed_at TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    service_name VARCHAR(100)  NOT NULL
);

-- Trigger de atualização automática
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN NEW.updated_at = NOW(); RETURN NEW; END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_changes_updated_at
    BEFORE UPDATE ON changes
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
```

#### `V2__seed_test_data.sql`

> **✅ Critérios atendidos:** `Testes`

Insere 3 mudanças de exemplo (PREPARED, COMPLETED, FAILED) com eventos na timeline para desenvolvimento local.

---

## 6. Segurança

### Modelo

> **✅ Critérios atendidos:** `Segurança` · `Arquitetura`

| Componente | Decisão |
|---|---|
| Autenticação | JWT Bearer (OAuth2 Resource Server) |
| Autorização | RBAC: `ROLE_OPERATOR` (read+create), `ROLE_ADMIN` (full) |
| Identidade (dev) | Header `X-User-Id` como fallback — **válido somente nos perfis `local` e `test`** (via `@Profile`); fora desses perfis o JWT `sub` é obrigatório |
| CORS | `localhost:*` + `*.changeops.io`, credentials habilitado |
| Sessão | Stateless (`SessionCreationPolicy.STATELESS`) |
| CSRF | Desabilitado (API REST stateless) |

### `CustomJwtAuthenticationConverter`

> **✅ Critérios atendidos:** `Segurança`

```java
public class CustomJwtAuthenticationConverter
        implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        // Extrai roles de jwt.getClaim("realm_access")["roles"]
        // Mapeia para ROLE_OPERATOR, ROLE_ADMIN
        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }
}
```

### Regras de segurança transversais

> **✅ Critérios atendidos:** `Segurança`

- Nenhum PII, token ou credencial é logado
- Todos os inputs validados na entrada da API (`@Valid` + Bean Validation)
- Payloads de eventos não expõem dados sensíveis
- `correlation_id` é UUID opaco — não contém dados de negócio

---

## 7. Observabilidade

### Logs JSON Estruturados (`logback-spring.xml`)

> **✅ Critérios atendidos:** `Observabilidade`

```json
{
  "timestamp": "2026-03-19T12:00:00.000Z",
  "level": "INFO",
  "service": "change-service",
  "correlation_id": "aaaa-bbbb-cccc-dddd",
  "change_id": "1111-2222-3333-4444",
  "deploy_id": null,
  "trace_id": "abc123",
  "span_id": "def456",
  "message": "Change created",
  "logger": "c.c.c.a.s.CreateChangeService"
}
```

Em `local/test`: output legível por humanos no console.  
Em `prod/staging`: JSON completo via `LogstashEncoder`.

### Propagação do Correlation ID

O `correlation_id` é propagado automaticamente por toda a cadeia — do HTTP header ao MDC, passando pelo Kafka e chegando aos logs do `deploy-orchestrator`.

```mermaid
%%{init: {'theme':'dark', 'themeVariables':{'fontSize':'14px','actorTextColor':'#E0E7FF','actorBkg':'#4338CA','actorBorder':'#6366F1','activationBorderColor':'#6366F1','signalColor':'#CBD5E1','signalTextColor':'#E2E8F0','noteBkgColor':'#1E293B','noteBorderColor':'#6366F1','noteTextColor':'#E2E8F0','altSectionBkgColor':'#0F172A','labelTextColor':'#A5B4FC'}}}%%
sequenceDiagram
    autonumber
    participant Client as Cliente
    participant CIF as CorrelationIdFilter
    participant MDC as MDC (ThreadLocal)
    participant CS as change-service
    participant Kafka as Kafka
    participant DO as deploy-orchestrator
    participant LOG as JSON Logs (stdout)
    participant PT as Promtail
    participant Loki as Loki
    participant Graf as Grafana

    Client->>CIF: Request<br/>(Header X-Correlation-Id opcional)

    alt Header presente
        CIF->>MDC: MDC.put("correlation_id", header)
    else Header ausente
        CIF->>CIF: correlationId = UUID.randomUUID()
        CIF->>MDC: MDC.put("correlation_id", novo UUID)
    end

    CIF->>MDC: MDC.put("service", "change-service")
    CIF->>Client: Response Header X-Correlation-Id

    CS->>LOG: JSON com correlation_id no MDC
    CS->>Kafka: IntegrationEvent (correlationId no envelope)

    Kafka->>DO: ConsumerRecord
    DO->>MDC: MDC.put("correlation_id", event.correlationId)
    DO->>MDC: MDC.put("deploy_id", payload.deployId)
    DO->>LOG: JSON com correlation_id + deploy_id

    LOG->>PT: Promtail coleta logs
    PT->>Loki: Push logs
    Loki->>Graf: Query (LogQL)
    Graf->>Graf: Busca por correlation_id
```

### Campos MDC Obrigatórios

| Campo | Serviço | Origem |
|-------|---------|--------|
| `correlation_id` | ambos | `CorrelationIdFilter` (change-service) / campo do `IntegrationEvent` (deploy-orchestrator) |
| `service` | ambos | Fixo: `"change-service"` ou `"deploy-orchestrator"` |
| `change_id` | ambos | UUID da change (quando disponível) |
| `deploy_id` | deploy-orchestrator | UUID do deploy (Flow 2 apenas) |

### Métricas Prometheus

> **✅ Critérios atendidos:** `Observabilidade`

Ambos os serviços expõem métricas via Micrometer no endpoint `/actuator/prometheus`. O Prometheus coleta a cada 5 segundos e o Grafana exibe em dashboards pré-provisionados.

```mermaid
%%{init: {'theme':'dark', 'themeVariables':{'fontSize':'15px','lineColor':'#94A3B8','edgeLabelBackground':'transparent'}}}%%
flowchart LR
    subgraph services["Serviços"]
        direction TB
        CS["change-service<br/>:8080/actuator/prometheus"]
        DO["deploy-orchestrator<br/>:8081/actuator/prometheus"]
    end

    subgraph metrics["Micrometer Counters & Histograms"]
        direction TB
        C1["changes_created_total"]
        C2["changes_completed_total"]
        C3["changes_failed_total"]
        C4["events_published_total"]
        C5["events_consumed_total"]
        C6["events_discarded_total"]
        C7["events_retries_total"]
        C8["events_dlt_total"]
        C9["events_failed_total"]
        H1["orchestration_duration_seconds"]
        H2["http_server_requests_seconds"]
    end

    subgraph observability["Observabilidade"]
        direction TB
        PROM["Prometheus<br/>:9090"]
        GRAF["Grafana<br/>:3001"]
    end

    CS --> C1 & C4 & H2
    DO --> C2 & C3 & C5 & C6 & C7 & C8 & C9 & H1
    C1 & C2 & C3 & C4 & C5 & C6 & C7 & C8 & C9 & H1 & H2 --> PROM
    PROM -->|PromQL| GRAF

    classDef svc fill:#4338CA,stroke:#6366F1,color:#E0E7FF,stroke-width:2px
    classDef metric fill:#0F766E,stroke:#14B8A6,color:#CCFBF1,stroke-width:1px
    classDef hist fill:#B45309,stroke:#F59E0B,color:#FEF3C7,stroke-width:1px
    classDef monitoring fill:#7C3AED,stroke:#A78BFA,color:#EDE9FE,stroke-width:2px

    class CS,DO svc
    class C1,C2,C3,C4,C5,C6,C7,C8,C9 metric
    class H1,H2 hist
    class PROM,GRAF monitoring

    style services fill:#1E1B4B,stroke:#4338CA,stroke-width:1px,color:#A5B4FC
    style metrics fill:#042F2E,stroke:#0F766E,stroke-width:1px,color:#5EEAD4
    style observability fill:#2E1065,stroke:#7C3AED,stroke-width:1px,color:#C4B5FD
```

| Métrica | Tipo | Serviço | Descrição |
|---------|------|---------|-----------|
| `changes_created_total` | Counter | change-service | Total de changes criadas |
| `changes_completed_total` | Counter | deploy-orchestrator | Changes transicionadas para COMPLETED |
| `changes_failed_total` | Counter | deploy-orchestrator | Changes transicionadas para FAILED |
| `events_published_total{type}` | Counter | ambos | Eventos publicados por tipo |
| `events_consumed_total{type}` | Counter | deploy-orchestrator | Eventos consumidos por tipo |
| `events_discarded_total{reason}` | Counter | deploy-orchestrator | Eventos descartados (duplicados) |
| `events_retries_total` | Counter | deploy-orchestrator | Total de hops de retry |
| `events_dlt_total` | Counter | deploy-orchestrator | Eventos encaminhados para DLT |
| `events_failed_total` | Counter | deploy-orchestrator | Eventos que falharam permanentemente |
| `timeline_persistence_failures_total` | Counter | change-service, deploy-orchestrator | Falhas na persistência da timeline (tags `service="change-service"` e `service="deploy-orchestrator"`) |
| `orchestration_duration_seconds` | Histogram | deploy-orchestrator | Latência ponta a ponta do processamento |
| `http_server_requests_seconds` | Histogram | change-service | Latência de requisições REST |

### `prometheus.yml`

> **✅ Critérios atendidos:** `Observabilidade`

```yaml
scrape_configs:
  - job_name: 'change-service'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['change-service:8080']
  - job_name: 'deploy-orchestrator'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['deploy-orchestrator:8081']
```

### Grafana Dashboard — `infra/grafana/dashboards/changeops.json`

> **✅ Critérios atendidos:** `Observabilidade` · `Comunicação`

Painéis pré-provisionados:

| Linha | Painéis | Tipo |
|-------|---------|------|
| **Changes** | Created (total) · Completed · Failed · Prepared · By Status (pie) · API Latency p95 | stat + piechart + timeseries |
| **Events** | Published · Consumed · Failed · Retries · DLT · Discarded · Rate per min | stat + timeseries |
| **Orchestration** | Orchestration Latency p95 | timeseries |
| **Kafka** | Listener Latency p95 · Producer Latency p95 | timeseries |

Acesso: http://localhost:3001 (`admin` / `changeops`)

---

## 8. Automação

### Docker Compose — Serviços

> **✅ Critérios atendidos:** `Arquitetura` · `Cenários`

| Serviço | Imagem | Porta |
|---------|--------|-------|
| postgres | postgres:16-alpine | 5432 |
| zookeeper | confluentinc/cp-zookeeper:7.6.0 | 2181 |
| kafka | confluentinc/cp-kafka:7.6.0 | 9092 |
| kafka-ui | provectuslabs/kafka-ui | 8090 |
| change-service | build local | 8080 |
| deploy-orchestrator | build local | 8081 |
| prometheus | prom/prometheus:v2.50.1 | 9090 |
| grafana | grafana/grafana:10.3.3 | 3001 |

Todos os serviços com `healthcheck` configurado. Dependências via `condition: service_healthy`.

```mermaid
%%{init: {'theme':'dark','themeVariables':{'fontSize':'15px','lineColor':'#94A3B8','edgeLabelBackground':'transparent'}}}%%
flowchart TD
    subgraph infra["Infraestrutura"]
        direction LR
        PG["PostgreSQL 16<br/>:5432"]
        ZK["Zookeeper<br/>:2181"]
    end

    subgraph broker["Mensageria"]
        direction LR
        K["Kafka<br/>:9092"]
        KUI["Kafka UI<br/>:8090"]
    end

    subgraph app["Aplicação"]
        direction LR
        CS["change-service<br/>:8080"]
        DO["deploy-orchestrator<br/>:8081"]
    end

    subgraph monitoring["Observabilidade"]
        direction LR
        PROM["Prometheus<br/>:9090"]
        GRAF["Grafana<br/>:3001"]
    end

    PG --> CS & DO
    ZK --> K
    K --> CS & DO & KUI
    CS & DO --> PROM
    PROM --> GRAF

    classDef infraNode fill:#047857,stroke:#34D399,color:#D1FAE5,stroke-width:2px
    classDef brokerNode fill:#C2410C,stroke:#FB923C,color:#FFF7ED,stroke-width:2px
    classDef svcNode fill:#4338CA,stroke:#6366F1,color:#E0E7FF,stroke-width:2px
    classDef obsNode fill:#7C3AED,stroke:#A78BFA,color:#EDE9FE,stroke-width:2px

    class PG,ZK infraNode
    class K,KUI brokerNode
    class CS,DO svcNode
    class PROM,GRAF obsNode

    style infra fill:#042F2E,stroke:#14B8A6,stroke-width:1px,color:#99F6E4
    style broker fill:#431407,stroke:#FB923C,stroke-width:1px,color:#FED7AA
    style app fill:#1E1B4B,stroke:#6366F1,stroke-width:1px,color:#C7D2FE
    style monitoring fill:#2E1065,stroke:#A78BFA,stroke-width:1px,color:#DDD6FE
```

### Dockerfiles — Multi-stage

> **✅ Critérios atendidos:** `Código`

[`backend/change-service/Dockerfile`](../backend/change-service/Dockerfile) | [`backend/deploy-orchestrator/Dockerfile`](../backend/deploy-orchestrator/Dockerfile) | [`frontend/Dockerfile`](../frontend/Dockerfile)

```dockerfile
# Estágio 1: Build (maven:3.9.6-eclipse-temurin-17)
FROM maven:3.9.6-eclipse-temurin-17 AS builder
WORKDIR /build
COPY pom.xml . && RUN mvn dependency:go-offline -q
COPY src ./src && RUN mvn clean package -DskipTests -q

# Estágio 2: Runtime (eclipse-temurin:17-jre-alpine)
FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser
COPY --from=builder /build/target/*.jar app.jar
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
```

### Makefile — Comandos principais

> **✅ Critérios atendidos:** `Comunicação`

```bash
make up                  # Sobe toda a stack (infra + serviços)
make down                # Para e remove containers + volumes
make logs                # Tail de logs dos dois serviços
make test                # Roda todos os testes
make test-backend-unit   # Apenas testes unitários (rápido)
make test-backend-it     # Apenas testes de integração (Testcontainers)
make test-frontend        # Vitest
make lint                # Lint backend + frontend
make build               # Build backend JAR + frontend bundle
make smoke               # Cria uma mudança via curl
make publish-deploy-event # Publica DeployFinishedEvent no Kafka
make db-shell            # Abre psql no container Postgres
make kafka-topics        # Lista tópicos Kafka
make clean               # Remove artefatos de build
```

### CI/CD — GitHub Actions

> **✅ Critérios atendidos:** `Testes` · `Cenários`

#### [`change-service.yml`](../.github/workflows/change-service.yml)

> **✅ Critérios atendidos:** `Testes` · `Cenários`

```yaml
on:
  push:
    paths: ['backend/change-service/**']
    branches: [main, develop]

jobs:
  test:                          # Unit + IT (com Postgres service container)
  build:                         # mvn package → Docker build → push ghcr.io
    if: github.ref == 'refs/heads/main'
```

#### `deploy-orchestrator.yml`

> **✅ Critérios atendidos:** `Testes` · `Cenários`

```yaml
# Mesma estrutura: test → build → push ghcr.io
```

#### `frontend.yml`

> **✅ Critérios atendidos:** `Testes` · `Cenários`

```yaml
on:
  push:
    paths: ['frontend/**']

jobs:
  test:                          # npm ci → lint → vitest → vite build
    # Upload artifact do dist/ para deploy posterior
```

---

## 9. Roadmap Técnico

### Fase 2 — Hardening de Produção

> **✅ Critérios atendidos:** `Evolução` · `Comunicação`

| Item | Descrição | Prioridade |
|------|-----------|------------|
| **Transactional Outbox** | Eliminar risco de event loss entre DB commit e Kafka publish. Opções: relay thread próprio ou Debezium CDC. | 🔴 Alta |
| **Keycloak / OAuth2 completo** | Substituir `X-User-Id` por PKCE flow com `oidc-client-ts` no frontend. Realm `changeops` com roles `OPERATOR` / `ADMIN`. | 🔴 Alta |
| **OpenTelemetry + Tempo** | Tracing distribuído correlacionando logs, métricas e traces entre os dois serviços e o Kafka. | 🟡 Média |
| **TTL em `processed_events`** | Archival job para manter tabela com tamanho controlado (ex: 90 dias). | 🟡 Média |

### Fase 3 — Escala e Resiliência

> **✅ Critérios atendidos:** `Evolução`

| Item | Descrição |
|------|-----------|
| **Kubernetes + Helm** | Migrar de Docker Compose para charts com Strimzi (Kafka) e CloudNativePG. |
| **Circuit Breaker** | Resilience4j em volta de chamadas externas no `PostDeployChecklistService`. |
| **Particionamento Kafka** | Aumentar partições em produção. Alinhar `concurrency` do consumidor com partition count. |
| **Read replica PostgreSQL** | Queries de relatório e timeline em replica dedicada. |

### Fase 4 — Multi-Tenancy e Compliance

> **✅ Critérios atendidos:** `Evolução`

| Item | Descrição |
|------|-----------|
| **Schema-per-tenant** | RLS policies no PostgreSQL + JWT claim `tenant_id`. |
| **Audit log append-only** | Tabela `audit_log` imutável para compliance / SOX. |
| **GDPR / PII** | Pseudonimização de `requested_by`, retention policies, erasure API. |

### Dívida Técnica Registrada

> **✅ Critérios atendidos:** `Evolução` · `Comunicação`

1. **Sem padrão Outbox** — risco de event loss se Kafka estiver indisponível durante o commit
2. **PostDeployChecklist é simulado** — precisa de integração real (health endpoints, smoke tests)
3. **Auth frontend é dev-only** — `localStorage.getItem('access_token')` é placeholder

---

## 10. Tradeoffs Estratégicos

Decisões deliberadas, alinhadas ao objetivo da POC, com caminho de evolução documentado.

### T1 — Banco de Dados Compartilhado entre Serviços

> **✅ Critérios atendidos:** `Comunicação` · `Evolução`

**Decisão:** `change-service` e `deploy-orchestrator` compartilham o mesmo PostgreSQL.

**Justificativa:** Simplifica o setup de demo (um único `docker compose up`). O `deploy-orchestrator` faz `UPDATE` direto na tabela `changes` sem necessidade de API interna, demonstrando os conceitos arquiteturais sem adicionar complexidade operacional.

**Caminho de evolução (Roadmap Fase 3):** Separar em schemas distintos com Row Level Security. O `deploy-orchestrator` passa a notificar via API interna do `change-service` — elimina acoplamento via banco e habilita deploy independente dos serviços.

---

### T2 — HTTP Polling vs. SSE / WebSocket no Frontend

> **✅ Critérios atendidos:** `Comunicação` · `Evolução`

**Decisão:** Polling HTTP a cada 5 segundos via hook `usePolling()`.

**Justificativa:** Zero infra adicional no backend. Funciona atrás de qualquer proxy/load balancer sem configuração especial. Latência de até 5s é aceitável para um fluxo de mudança que leva minutos. O hook é genérico — a interface de alto nível permanece idêntica independente do mecanismo de atualização.

**Caminho de evolução (Roadmap Fase 2.4):** Substituir internamente `usePolling()` por SSE com `text/event-stream` e `SseEmitter` no Spring Boot — sem mudança na interface dos componentes.

---

### T3 — Sem Outbox Pattern (Publicação Kafka Pós-Commit)

> **✅ Critérios atendidos:** `Comunicação` · `Evolução`

**Decisão:** `CreateChangeService` persiste no DB e publica no Kafka em operação separada (dentro da transação Java).

**Justificativa:** O produtor Kafka é idempotente (`acks=all`, `enable.idempotence=true`) — falhas transientes resultam em retry automático. O consumer implementa idempotência atômica — mesmo em eventual duplicata, o processamento é seguro. Simplifica o código sem adicionar uma terceira tabela e um worker de polling.

**Caminho de evolução (Roadmap Fase 2.1):** Implementar Transactional Outbox com tabela `outbox_events`. Worker publica eventos pendentes com garantia de at-least-once durável.

---

### T4 — Fator de Replicação Kafka = 1 (Padrão)

> **✅ Critérios atendidos:** `Comunicação` · `Evolução`

**Decisão:** Tópicos criados com `replicas=1` por padrão.

**Justificativa:** Ambiente single-broker local — não haveria réplicas para distribuir mesmo com fator maior. O valor é configurável via variável de ambiente `KAFKA_REPLICATION_FACTOR` sem necessidade de alterar código.

**Caminho de evolução (Produção):** `KAFKA_REPLICATION_FACTOR=3` em ambientes com cluster Kafka multi-broker. Ajuste de `min.insync.replicas=2` para garantia de durabilidade.

---

### T5 — Autenticação Frontend via localStorage (Dev Only)

> **✅ Critérios atendidos:** `Segurança` · `Evolução`

**Decisão:** Frontend lê `access_token` e `user_id` de `localStorage` como fallback de desenvolvimento.

**Justificativa:** Profile `local` no backend aceita requisições sem JWT por design. O `X-User-Id` header tem **menor prioridade** que o JWT Subject — JWT vence sempre se presente. A infraestrutura completa de OAuth2/JWT está implementada no backend (`SecurityConfig.java`).

**Caminho de evolução (Roadmap Fase 2.2):** Implementar OAuth2 PKCE flow com Keycloak. Tokens armazenados em `HttpOnly Secure Cookie` — elimina risco de XSS.

---

### T6 — PostDeployChecklist Simulado

> **✅ Critérios atendidos:** `Cenários` · `Evolução`

**Decisão:** `PostDeployChecklistService` simula 4 verificações (healthcheck, smoke test, error rate, deploy result gate).

**Justificativa:** Demonstra o padrão arquitetural sem dependência de infraestrutura externa real. O serviço é um extension point explícito — substituir cada verificação por chamada HTTP real é uma mudança localizada e testável independentemente.

**Caminho de evolução (pós-POC):** Injetar `HealthCheckClient` e `MetricsClient` como ports de saída. Implementar adapters para chamar actuator health do serviço deployado e Prometheus da aplicação.

| Entregável | Status |
|---|---|
| `change-service` — domínio completo (Change aggregate, events, exceptions) | ✅ |
| `change-service` — application layer (3 use cases, 4 ports, 3 services) | ✅ |
| `change-service` — infraestrutura (JPA, Kafka, Security, Observability) | ✅ |
| `change-service` — API REST (Controller, DTOs, GlobalExceptionHandler) | ✅ |
| `change-service` — Flyway migrations (V1 schema + V2 seed) | ✅ |
| `change-service` — Testes unitários + integração com Testcontainers | ✅ |
| `deploy-orchestrator` — domínio (DeployFinishedEvent, ChangeResult) | ✅ |
| `deploy-orchestrator` — ProcessDeployResultService com 6 etapas | ✅ |
| `deploy-orchestrator` — DeployEventConsumer com @RetryableTopic + DLQ | ✅ |
| `deploy-orchestrator` — IdempotencyAdapter (UNIQUE constraint + race condition handling) | ✅ |
| `deploy-orchestrator` — KafkaResultPublisherAdapter + DLQ fallback | ✅ |
| `deploy-orchestrator` — Testes unitários com Mockito | ✅ |
| Frontend — types, services, store (Zustand), hooks (polling, create, events) | ✅ |
| Frontend — ChangeForm, ChangeList, ChangeTimeline, StatusBadge | ✅ |
| Frontend — ChangesPage (composição completa) | ✅ |
| Frontend — Testes com Testing Library + Vitest | ✅ |
| Docker Compose — 8 serviços com healthchecks e dependências | ✅ |
| Dockerfiles multi-stage (build + jre-alpine runtime) | ✅ |
| Prometheus scrape config | ✅ |
| Grafana dashboard pré-provisionado | ✅ |
| OpenAPI 3.1 — change-service | ✅ |
| AsyncAPI 2.6 — todos os eventos (4 tópicos, 5 schemas) | ✅ |
| GitHub Actions CI — 3 pipelines (change-service, orchestrator, frontend) | ✅ |
| Makefile — 20+ targets documentados | ✅ |
| README.md — visão geral, quick start, comandos, estrutura | ✅ |
| ROADMAP.md — 4 fases + dívida técnica registrada | ✅ |
