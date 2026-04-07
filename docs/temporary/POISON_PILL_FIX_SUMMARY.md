# Poison Pill Fix — Summary

**Date:** 2026-04-01 | **Branch:** `0104261548` | **Service:** `deploy-orchestrator`

---

## Problem 1 — Kafka Poison Pill (root cause)

**Symptom:** Publishing a message with a malformed `changeId` UUID (37 chars instead of 36) to `changeops.deploy.finished` caused an infinite processing loop. All metrics stayed at 0. The consumer never advanced the offset.

**Root cause:** `JsonDeserializer` throws `RecordDeserializationException` during `KafkaConsumer.poll()` — *before* the `@KafkaListener` method is called. The `DefaultErrorHandler` could not handle a `SerializationException` directly and re-raised it as `IllegalStateException`. Because the failure happened at the poll layer, `@RetryableTopic` never intercepted it, and the offset was never committed.

**Fix — `KafkaConfig.java`:** Wrapped `JsonDeserializer` with `ErrorHandlingDeserializer`. On deserialization failure, Spring captures the exception and delivers `null` to the listener instead of crashing. This moves error handling back into the application layer where `@RetryableTopic` can act.

---

## Problem 2 — No null guard in the listener

**Symptom:** Once `ErrorHandlingDeserializer` was in place, a null `event` would reach the service layer and cause a `NullPointerException` deep in business logic — generating confusing logs and going through 4 useless retries before hitting the DLT.

**Fix — `DeployEventConsumer.java`:** Added an explicit null check immediately after receiving the record. A `null` event (or null payload) throws `InvalidOrchestratorStateException`, which is already in the `@RetryableTopic` `exclude` list — routing the message directly to the DLT with zero retries.

```
event == null → InvalidOrchestratorStateException → DLT (0 retries)
```

---

## Problem 3 — `ALWAYS_RETRY_ON_ERROR` in the DLT handler (remote branch)

**Symptom:** The other developer's branch (`0104261548`) had `dltStrategy = ALWAYS_RETRY_ON_ERROR`. This would cause the DLT handler itself to retry on every failure, defeating the purpose of a dead-letter topic.

**Fix — `DeployEventConsumer.java`:** Kept `dltStrategy = DltStrategy.FAIL_ON_ERROR` from the stash. The DLT handler should fail fast and log — not loop.

---

## Problem 4 — DLT handler incompatible with `ByteArraySerializer`

**Symptom:** The remote branch added a `ByteArraySerializer` as the DLT producer (correct — preserves raw bytes). But the existing `@DltHandler` signature `ConsumerRecord<String, DeployFinishedEvent>` could not deserialize `byte[]` payloads, causing a type mismatch.

**Fix — `DeployEventConsumer.java`:** Changed the DLT handler signature to `ConsumerRecord<String, Object>` and added a `byte[]` → `String` conversion before logging:

```java
String payload = record.value() instanceof byte[]
    ? new String((byte[]) record.value(), StandardCharsets.UTF_8)
    : String.valueOf(record.value());
```

---

## Problem 5 — Test using stale DLT handler signature

**Symptom:** After the DLT handler signature changed, the unit test `shouldIncrementDltCounters_whenDltHandlerReceivesNullEvent` still constructed a `ConsumerRecord<String, DeployFinishedEvent>` and called `consumer.onDlt(record)` — compilation would fail.

**Fix — `DeployEventConsumerTest.java`:** Updated the test to `ConsumerRecord<String, Object>` and removed the topic parameter: `consumer.onDlt(record)` (topic is now obtained via `record.topic()` inside the handler).

---

## Problem 6 — `changeId` lookup missing before idempotency check

**Symptom:** If a `DeployFinishedEvent` arrived referencing a `changeId` that does not exist in the database, the service would fail later with an opaque `EmptyResultDataAccessException` from JPA — with no structured log and a retry cycle that would not help.

**Fix — `ProcessDeployResultService.java` + port/adapter/repository:** Added `existsByChangeId(UUID)` through all three hexagonal layers. The service checks existence *before* the idempotency gate and throws `InvalidOrchestratorStateException` (→ DLT directly) with a clear message.

---

## Files Changed

| File | What changed |
|------|-------------|
| `KafkaConfig.java` | `ErrorHandlingDeserializer` wrapping `JsonDeserializer`; replaced `@Primary KafkaTemplate<String, byte[]>` (ByteArraySerializer) with `@Primary KafkaTemplate<String, Object>` (JsonSerializer) for correct `@RetryableTopic` DLT publishing; explicit DLT topic beans |
| `DeployEventConsumer.java` | Null check → `InvalidOrchestratorStateException`; `dltStrategy = FAIL_ON_ERROR`; `@DltHandler` signature → `ConsumerRecord<String, Object>` + `byte[]`→`String`; removed `@Header` from `onDlt()` (uses `record.topic()`); payload truncated to 500 chars; removed `MDC.clear()` |
| `ProcessDeployResultService.java` | `existsByChangeId` pre-check throws `ChangeNotFoundException` (retryable); `orchestrationTimer` Timer metric (adopted from remote); added `MDC.remove("correlation_id")` in finally block |
| `UpdateChangeStatusPort.java` | New method `existsByChangeId(UUID)` |
| `UpdateChangeStatusAdapter.java` | Implementation delegating to repository |
| `ChangeStatusJpaRepository.java` | Spring Data derived query `existsByChangeId(UUID)` |
| `DeployEventConsumerTest.java` | DLT handler test updated for new `ConsumerRecord<String, Object>` signature; removed topic parameter from `onDlt()` calls |
| `ProcessDeployResultServiceTest.java` | Moved `existsByChangeId` stubbing to `@BeforeEach setUp()` (DRY); renamed test to `shouldThrowRetryableException_whenChangeIdNotFound`; asserts `ChangeNotFoundException` |
| `DeployEventConsumerIT.java` | DLT record assertion filtered by key/payload to avoid false positives |
| `tests/run_tests.sh` | CT-13B scenario: poison pill → verify DLT, no loop, consumer stays healthy; dynamic UUID per execution |
| `docker-compose.yml` | Memory limits added to all containers |

---

## Validation Status

| Check | Result |
|-------|--------|
| Unit tests — 30 tests | ✅ PASS |
| Integration tests — 10 tests (DeployEventConsumerIT + IdempotencyIT) | ✅ PASS |
| Checkstyle — 0 violations | ✅ PASS |
| IDE errors on key Java files | ✅ 0 errors |
| `docker compose up -d --build` — all backend containers healthy | ✅ PASS |
| E2E CT-13B — poison pill routed to DLT, no infinite loop | ✅ PASS |
| E2E CT-13B — `events_dlt_total` and `events_failed_total` counters | ✅ FIXED — see Problem 7 below |

---

## Problem 7 — `@DltHandler` counters not incremented (RESOLVED)

**Observed in E2E (CT-13B):** After publishing the poison pill, logs confirmed the message was routed to `changeops.deploy.finished-dlt` without an infinite loop. However `events_dlt_total` and `events_failed_total` remained `0.0`.

**Root cause:** The `@DltHandler` signature included `@Header(KafkaHeaders.RECEIVED_TOPIC) String topic`. When `@RetryableTopic` reuses the same `ErrorHandlingDeserializer<DeployFinishedEvent>` consumer factory for the DLT topic, the inner `JsonDeserializer` fails again on the malformed payload. The `ErrorHandlingDeserializer` captures the error and delivers `null` to the handler, but Spring's `@Header` parameter binding fails before the method body executes — the counters never increment.

**Fix — `DeployEventConsumer.java`:** Removed the `@Header(KafkaHeaders.RECEIVED_TOPIC) String topic` parameter from the `onDlt()` method signature. The topic is now obtained via `record.topic()` inside the method body, which is always available regardless of deserialization state.

```java
// BEFORE (broken):
@DltHandler
public void onDlt(
        ConsumerRecord<String, Object> record,
        @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) { ... }

// AFTER (fixed):
@DltHandler
public void onDlt(ConsumerRecord<String, Object> record) {
    String topic = record.topic();
    ...
}
```

---

## PR Review Fixes

Additional fixes applied during PR #15 review:

| # | Issue | Fix | File |
|---|-------|-----|------|
| 1 | DLT payload logging could expose sensitive data/PII | Truncated payload to max 500 chars before logging | `DeployEventConsumer.java` |
| 2 | `MDC.clear()` in DLT handler removes all MDC keys including framework tracing context | Removed `MDC.clear()` entirely — handler doesn't call `MDC.put()` | `DeployEventConsumer.java` |
| 3 | `correlation_id` leaks in MDC on early failure paths in `execute()` | Added `MDC.remove("correlation_id")` in the `finally` block alongside `deploy_id` and `change_id` | `ProcessDeployResultService.java` |
| 4 | `existsByChangeId` stubbing repeated in ~8 tests | Moved default stub to `@BeforeEach setUp()`; override only in `shouldThrowRetryableException_whenChangeIdNotFound` | `ProcessDeployResultServiceTest.java` |
| 5 | IT DLT assertion `!isEmpty()` could match stale records | Filtered `dltRecords` by key=`test-key` or payload containing `INVALID-NOT-A-UUID` | `DeployEventConsumerIT.java` |
| 6 | CT-13B uses hardcoded UUID → false positive on reruns | Generates unique `deployId` via `newuuid()` per execution | `tests/run_tests.sh` |

---

## Problem 8 — `@Primary KafkaTemplate<String, byte[]>` breaks DLT publishing for valid records (RESOLVED)

**Observed during integration tests:** `DeployEventConsumerIT.shouldSendToDlt_whenProcessingFailsAfterRetries` entered an infinite loop. `SerializationException: Can't convert value of class DeployFinishedEvent to class ByteArraySerializer` appeared repeatedly, the offset was never committed, and the consumer looped.

**Root cause:** To support poison pill DLT publishing (raw bytes), `dltKafkaTemplate` was marked `@Primary`. Spring's `@RetryableTopic` infrastructure picks up the `@Primary KafkaTemplate` to publish retry/DLT records. When a valid deserialized `DeployFinishedEvent` is routed to DLT (e.g., `existsByChangeId` fails → `InvalidOrchestratorStateException`), the framework tries to serialize the Java object with `ByteArraySerializer` — which can only handle `byte[]`. The serialization fails, the DLT publication fails, the offset is never committed → infinite retry loop.

For the poison pill case (deserialization failure, null value), Spring Kafka stores the original raw bytes in Kafka record **headers** automatically via `ErrorHandlingDeserializer`. The `DeadLetterPublishingRecovererFactory` copies these headers to the DLT record, so the raw bytes are preserved regardless of the value serializer used.

**Fix — `KafkaConfig.java`:** Replaced `dltProducerFactory` + `@Primary KafkaTemplate<String, byte[]>` with `defaultProducerFactory` + `@Primary KafkaTemplate<String, Object>` using `JsonSerializer<Object>`. This allows `@RetryableTopic` to serialize any POJO (including `DeployFinishedEvent`) to JSON for DLT publishing, while poison pill raw bytes continue to be preserved in headers.

```java
// BEFORE (broken for non-byte[] payloads):
@Primary @Bean
public KafkaTemplate<String, byte[]> dltKafkaTemplate() { ... } // ByteArraySerializer

// AFTER (correct):
@Primary @Bean
public KafkaTemplate<String, Object> defaultKafkaTemplate() { ... } // JsonSerializer<Object>
```

---

## Documentation Updated

| Document | What changed |
|----------|-------------|
| `docs/architecture/sequence-flow2-orquestracao.md` | Added `existsByChangeId` pre-check step with alt block; added Poison Pill failure scenario (`rect` block) showing ErrorHandlingDeserializer → null → DLT flow; expanded "Detalhes do Fluxo" with items 6–7 |
| `docs/test-strategy.md` | Added `DeployEventConsumerTest` in §3.2; updated `DeployEventConsumerIT` in §3.3 with poison pill + changeId scenarios; added 2 rows in §6 RFP table; added full CT-13B manual test case in §7 |
| `docs/TRACEABILITY.md` | Added 2 rows: poison pill handling (resilience) + pre-condition guard (`existsByChangeId`) |
| `contracts/asyncapi/events.yml` | Added `changeops.deploy.finished-dlt` channel documenting the DLT topic for deserialization failures and exhausted retries |

---

## Problem 9 — DLT consumer fails to deserialize poison pills with `ErrorHandlingDeserializer` (RESOLVED)

**Observed in E2E (CT-10, CT-11, CT-12, CT-13B, CT-29, CT-31, CT-32, CT-SEC-03):** Multiple E2E scenarios failed because the `@DltHandler` could not deserialize poison pill messages. The DLT handler received `null` values and threw NPE during JSON serialization of `DeployFinishedEvent.isSuccess()`.

**Root cause — two-layer deserialization mismatch:** `ErrorHandlingDeserializer<JsonDeserializer>` captures deserialization failures and delivers `null` to the `@KafkaListener`. But when `@RetryableTopic` reuses the same consumer factory for DLT topics, the inner `JsonDeserializer` fails again on the malformed payload — delivering `null` to the `@DltHandler` as well. Additionally, `DeployFinishedEvent.isSuccess()` followed JavaBean naming convention, causing Jackson to serialize it as a `"success"` field and trigger NPE on the `Boolean` autoboxing.

**Fix — complete serialization overhaul:**

| Component | Before | After |
|-----------|--------|-------|
| `KafkaConfig.java` (consumer) | `ErrorHandlingDeserializer<JsonDeserializer>` | `StringDeserializer` |
| `KafkaConfig.java` (DLT/retry producer) | `@Primary KafkaTemplate<String, Object>` (`JsonSerializer`) | `KafkaTemplate<String, String>` (`StringSerializer`) |
| `DeployEventConsumer.java` (listener) | `ConsumerRecord<String, DeployFinishedEvent>` | `ConsumerRecord<String, String>` + manual `ObjectMapper.readValue()` |
| `DeployEventConsumer.java` (DLT handler) | `ConsumerRecord<String, Object>` | `ConsumerRecord<String, String>` |
| `DeployFinishedEvent.java` | `isSuccess()` | `succeeded()` |

**Additional fixes in the same session:**

| Fix | File | Description |
|-----|------|-------------|
| `NoResourceFoundException` → 404 | `GlobalExceptionHandler.java` (change-service) | Actuator `/health` retornava 500 porque Spring 6.2+ lança `NoResourceFoundException` para rotas não encontradas |
| Actuator hardening | `application.yml` (ambos serviços) | `management.endpoints.enabled-by-default: false` — apenas health e prometheus habilitados |
| `succeeded()` rename | `DeployFinishedEvent.java`, `ProcessDeployResultService.java` | Quebra a convenção JavaBean para evitar serialização automática como campo `"success"` |

**Files changed:**

| File | What changed |
|------|-------------|
| `KafkaConfig.java` | `StringDeserializer` + `StringSerializer`; `@Primary KafkaTemplate<String, String>` |
| `DeployEventConsumer.java` | `ConsumerRecord<String, String>`, `ObjectMapper` injection, manual JSON parse, `@DltHandler` → `ConsumerRecord<String, String>` |
| `DeployEventConsumerTest.java` | All 17 tests rewritten for String-based records; 3 new tests (invalid UUID, plain text, blank) |
| `DeployFinishedEvent.java` | `isSuccess()` → `succeeded()` |
| `ProcessDeployResultService.java` | `event.isSuccess()` → `event.succeeded()` |
| `GlobalExceptionHandler.java` (change-service) | `NoResourceFoundException` handler → 404 |
| `application.yml` (both services) | `management.endpoints.enabled-by-default: false` |

**Validation:**

| Check | Result |
|-------|--------|
| Unit tests — deploy-orchestrator | ✅ PASS |
| Unit tests — change-service | ✅ PASS |
| Checkstyle — 0 violations | ✅ PASS |
| E2E — 39/39 scenarios | ✅ PASS |
