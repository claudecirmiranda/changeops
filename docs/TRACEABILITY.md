# Matriz de Rastreabilidade — RFP para Artefatos

| Requisito RFP | ADR | Código | Teste | Métrica | Evidência |
|--------------|-----|--------|-------|---------|-----------|
| Idempotência comprovada | ADR-002 | `IdempotencyAdapter.java` | `IdempotencyIntegrationTest.java` | `events_discarded_total` | [Link] |
| Correlation ID ponta a ponta | ADR-004 | `CorrelationIdFilter.java` | `CorrelationIdFilterTest.java` | `http_server_requests_seconds_bucket` | [Link] |
| Eventos versionados | ADR-003 | `ChangePreparedEvent.java` | `EventSchemaCompatibilityTest.java` (planejado) | `events_schema_version_mismatch_total` (planejado) | [Link] |
| Retry com backoff | ADR-002 | `@RetryableTopic` config | `RetryBehaviorTest.java` (planejado) | `events_retries_total` | [Link] |
| Dead Letter Queue | ADR-002 | `KafkaConfig.java` | `DlqIntegrationTest.java` (planejado) | `events_dlt_total` | [Link] |
| Logs estruturados | N/A | `logback-spring.xml` | `LoggingIntegrationTest.java` (planejado) | N/A | [Link] |
| ADRs com trade-offs | N/A | `docs/adr/` | N/A | N/A | [Link] |
| Roadmap evolutivo | N/A | `docs/ROADMAP.md` | N/A | N/A | [Link] |
| Ciclo de vida de Changes | N/A | `ChangeMetrics.java` | N/A | `changes_created_total`, `changes_completed_total`, `changes_failed_total` | [Link] |
| Volume de eventos Kafka | N/A | `EventMetrics.java` | N/A | `events_published_total`, `events_consumed_total`, `events_failed_total` | [Link] |
| Latência HTTP (p95) | N/A | `http_server_requests_seconds_bucket` | N/A | `http_server_requests_seconds_bucket{job="change-service\|deploy-orchestrator"}` | [Link] |
| Latência de orquestração (p95) | N/A | deploy-orchestrator | N/A | `orchestration_duration_seconds_bucket` | [Link] |