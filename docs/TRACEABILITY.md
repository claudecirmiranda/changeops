# Matriz de Rastreabilidade — RFP para Artefatos

| Requisito RFP | ADR | Código | Teste | Métrica | Evidência |
|--------------|-----|--------|-------|---------|-----------|
| Idempotência comprovada | ADR-002 | `IdempotencyAdapter.java` | `IdempotencyIT.java` | `events_discarded_total` | [Link] |
| Correlation ID ponta a ponta | ADR-004 | `CorrelationIdFilter.java` | `CorrelationIdFilterTest.java` | `http_server_requests_seconds_bucket` | [Link] |
| Eventos versionados | ADR-003 | `ChangePreparedEvent.java` | `EventSchemaCompatibilityTest.java` (planejado) | `events_schema_version_mismatch_total` (planejado) | [Link] |
| Retry com backoff | ADR-002 | `DeployEventConsumer.java` (`@RetryableTopic`) | `RetryBehaviorTest.java` (planejado) | `events_retries_total` | [Link] |
| Dead Letter Queue | ADR-002 | `DeployEventConsumer.java` (`@DltHandler`) | `DlqIntegrationTest.java` (planejado) | `events_dlt_total` | [Link] |
| Logs estruturados | N/A | `logback-spring.xml` | `LoggingIntegrationTest.java` (planejado) | N/A | [Link] |
| ADRs com trade-offs | N/A | `docs/adr/` | N/A | N/A | [Link] |
| Roadmap evolutivo | N/A | `docs/ROADMAP.md` | N/A | N/A | [Link] |
| Ciclo de vida de Changes | N/A | `ChangeMetricsCollector.java`, `CreateChangeService.java`, `ProcessDeployResultService.java` | N/A | `changes_created_total`, `changes_completed_total`, `changes_failed_total` | [Link] |
| Volume de eventos Kafka | N/A | `KafkaEventPublisherAdapter.java`, `DeployEventConsumer.java`, `KafkaResultPublisherAdapter.java` | N/A | `events_published_total`, `events_consumed_total`, `events_failed_total` | [Link] |
| Latência HTTP (p95) | N/A | `http_server_requests_seconds_bucket` | N/A | `http_server_requests_seconds_bucket{job="change-service\|deploy-orchestrator"}` | [Link] |
| Latência de orquestração (p95) | N/A | deploy-orchestrator | N/A | `orchestration_duration_seconds_bucket` | [Link] |
| Poison pill handling (resiliência) | N/A | `KafkaConfig.java` (`StringDeserializer`), `DeployEventConsumer.java` (manual JSON parse → DLT) | `DeployEventConsumerIT.shouldSendToDlt_whenMessageHasMalformedPayload`, CT-13B, CT-32 | `events_dlt_total`, `events_failed_total` | [Link] |
| Pre-condition guard (`existsByChangeId`) | ADR-002 | `ProcessDeployResultService.java`, `UpdateChangeStatusPort.java` | `ProcessDeployResultServiceTest.shouldThrowRetryableException_whenChangeIdNotFound` | N/A | [Link] |
| Actuator endpoint security | N/A | `application.yml` (`enabled-by-default: false`), `GlobalExceptionHandler.java` (`NoResourceFoundException` → 404) | CT-SEC-03 | N/A | [Link] |
| Timeline persistence resilience | ADR-002 | `ChangeEventAdapter.java` (`@Transactional(propagation = REQUIRES_NEW)`), `ProcessDeployResultService.java` (try-catch + counter) | `ProcessDeployResultServiceTest` | `timeline_persistence_failures_total{service="deploy-orchestrator"}` | [Link] |
| Kafka producer close-timeout | N/A | `KafkaConfig.java` (change-service + deploy-orchestrator): `factory.setPhysicalCloseTimeout(producerCloseTimeoutSeconds)` | N/A | N/A | [Link] |
| Test automation scripts | N/A | `tests/run_unity_tests.sh`, `tests/run_integration_tests.sh`, `tests/run_automated_manual_tests.sh` (CT-02..CT-32, CT-SEC-*), `tests/run_rate_tests.sh` | CT-02 through CT-SEC-10 | N/A | [Link] |