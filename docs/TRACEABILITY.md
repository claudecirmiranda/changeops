# Matriz de Rastreabilidade — RFP para Artefatos

| Requisito RFP | ADR | Código | Teste | Métrica | Evidência |
|--------------|-----|--------|-------|---------|-----------|
| Idempotência comprovada | ADR-002 | `IdempotencyAdapter.java` | `IdempotencyIntegrationTest.java` | `events_duplicate_discarded_total` | [Link] |
| Correlation ID ponta a ponta | ADR-004 | `CorrelationIdFilter.java` | `CorrelationPropagationTest.java` | `request_duration_seconds{correlation_id}` | [Link] |
| Eventos versionados | ADR-003 | `ChangePreparedEvent.java` | `EventSchemaCompatibilityTest.java` | `events_schema_version_mismatch_total` | [Link] |
| Retry com backoff | ADR-002 | `@RetryableTopic` config | `RetryBehaviorTest.java` | `events_retry_attempts_total` | [Link] |
| Dead Letter Queue | ADR-002 | `KafkaConfig.java` | `DlqIntegrationTest.java` | `events_dlq_size` | [Link] |
| Logs estruturados | N/A | `logback-spring.xml` | `LoggingIntegrationTest.java` | N/A | [Link] |
| ADRs com trade-offs | N/A | `docs/adr/` | N/A | N/A | [Link] |
| Roadmap evolutivo | N/A | `docs/ROADMAP.md` | N/A | N/A | [Link] |