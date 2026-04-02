# Plan: Fix Frontend Count Discrepancy & Test Failures

## TL;DR
Frontend stats cards count status only from the current page's items (bug in `ChangesPage.tsx`). Grafana uses counter-based `increase()` queries that drift from the DB snapshot. Fix: add a `/api/v1/changes/stats` backend endpoint, consume it in the frontend, and switch Grafana to use the existing `changes_by_status` gauge. Additionally fix 5 categories of test failures in `run_tests.sh`.

---

## Phase 1: Frontend Count Discrepancy (Primary Issue)

### Root Cause
In `frontend/src/app/routes/ChangesPage.tsx` (lines 20-27):
```
total: page.totalElements        // Global count (from Spring Data Page)
prepared: page.content.filter()  // Current page only! (max 20 items)
completed: page.content.filter() // Current page only!
failed: page.content.filter()    // Current page only!
```

When user is on page 2 with 1 item, stats show Total=21, Prepared=0, Completed=1, Failed=0.

### Steps

**Step 1: Add `GET /api/v1/changes/stats` endpoint (backend)**
- Create `ChangeStatsDto` record in `api/dto/` with fields: `total`, `prepared`, `completed`, `failed` (all `long`)
- Create `GetChangeStatsUseCase` port in `application/port/in/`
- Create `GetChangeStatsService` in `application/service/` using `LoadChangesPort` or a new port
- Add `CountChangesByStatusPort` in `application/port/out/` with method `countByStatus(ChangeStatus): long` and `countAll(): long`
- Implement in `ChangePersistenceAdapter` using existing `ChangeJpaRepository.countByStatus()` and `count()`
- Add `@GetMapping("/stats")` to `ChangeController`
- No auth changes needed (local profile = permitAll)

**Step 2: Add `stats()` method to frontend service**
- Add `stats()` to `changeService.ts` → `GET /api/v1/changes/stats`
- Add `ChangeStats` type to `types/index.ts`

**Step 3: Add stats to Zustand store**
- Add `stats: ChangeStats | null` and `setStats` to `useChangesStore.ts`

**Step 4: Fetch stats in `useChanges` hook**
- Add `fetchStats()` call alongside `fetch()` in `useChanges.ts`
- Include in polling cycle (every 5s)

**Step 5: Use stats from store in `ChangesPage.tsx`**
- Replace the inline `page.content.filter()` logic with `stats` from the store
- `total` comes from stats, not `page.totalElements`

**Relevant files:**
- `backend/change-service/src/main/java/com/changeops/changeservice/api/controller/ChangeController.java` — add stats endpoint
- `backend/change-service/src/main/java/com/changeops/changeservice/api/dto/` — add ChangeStatsDto
- `backend/change-service/src/main/java/com/changeops/changeservice/application/port/in/` — add GetChangeStatsUseCase
- `backend/change-service/src/main/java/com/changeops/changeservice/application/service/` — add GetChangeStatsService
- `backend/change-service/src/main/java/com/changeops/changeservice/application/port/out/` — add CountChangesByStatusPort (or extend LoadChangesPort)
- `backend/change-service/src/main/java/com/changeops/changeservice/infrastructure/persistence/ChangePersistenceAdapter.java` — implement countByStatus
- `backend/change-service/src/main/java/com/changeops/changeservice/infrastructure/persistence/repository/ChangeJpaRepository.java` — already has `countByStatus()`
- `frontend/src/features/changes/services/changeService.ts` — add stats()
- `frontend/src/features/changes/types/index.ts` — add ChangeStats type
- `frontend/src/features/changes/store/useChangesStore.ts` — add stats state
- `frontend/src/features/changes/hooks/useChanges.ts` — fetch stats
- `frontend/src/app/routes/ChangesPage.tsx` — consume stats from store

---

## Phase 2: Grafana Dashboard Accuracy (Minor)

### Root Cause
Grafana "Prepared" panel uses derived formula: `increase(created) - increase(completed) - increase(failed)` over `$__range`. Counter resets (service restarts) and time range selection cause drift. The `ChangeMetricsCollector` already exposes `changes_by_status{status="PREPARED|COMPLETED|FAILED"}` gauge — Grafana should use it.

### Steps

**Step 6: Update Grafana dashboard panels** (*parallel with Phase 1*)
- `infra/grafana/dashboards/changeops.json`
- Change "Changes - Created (total)" target to: `sum(changes_by_status{status="PREPARED"}) + sum(changes_by_status{status="COMPLETED"}) + sum(changes_by_status{status="FAILED"})` (or keep counter-based for "created in time window" semantics, add a separate "Current Total" panel)
- Change "Changes - Completed" to: `sum(changes_by_status{status="COMPLETED"})`
- Change "Changes - Failed" to: `sum(changes_by_status{status="FAILED"})`
- Change "Changes - Prepared" to: `sum(changes_by_status{status="PREPARED"})`
- This makes Grafana match the DB snapshot (same source as frontend)

---

## Phase 3: Fix Flaky Tests CT-10/CT-11/CT-12

### Root Cause
Test script waits only 3 seconds after publishing Kafka event, but:
- Consumer may trigger `ChangeNotFoundException` retries (500ms + 1s + 2s = 3.5s minimum)
- Kafka consumer with concurrency=3 has non-deterministic processing order
- Under Docker load, delays compound

### Steps

**Step 7: Increase wait times or add polling in test script**
- `tests/run_tests.sh`: For CT-10/CT-11/CT-12, replace `sleep 3` with a polling loop:
  - Poll `GET /api/v1/changes/{id}` every 1s for up to 15s
  - Break early when expected status is reached
  - Fail only after timeout

---

## Phase 4: Fix CT-13B/CT-31/CT-32 — Deserialization Errors Not Reaching DLT

### Root Cause
`ErrorHandlingDeserializer` catches deserialization errors and produces a null record value with error metadata in headers. The container's default `CommonErrorHandler` handles these records BEFORE the `@KafkaListener` method is invoked, so `@RetryableTopic`'s DLT routing never triggers. The records are committed and lost.

Flow:
1. Bad message → `ErrorHandlingDeserializer` → null value + error in headers
2. Container detects deserialization exception in headers
3. Default `CommonErrorHandler` logs and commits → record lost
4. Listener is never called → `@RetryableTopic` DLT path is never entered

### Steps

**Step 8: Add `CommonErrorHandler` with DLT routing to `KafkaConfig.java`**
- In `deploy-orchestrator/infrastructure/kafka/KafkaConfig.java`, add to `deployEventListenerContainerFactory()`:
  ```java
  factory.setCommonErrorHandler(
    new DefaultErrorHandler(
      new DeadLetterPublishingRecoverer(defaultKafkaTemplate),
      new FixedBackOff(0L, 0L)  // No retries for deser errors, go straight to DLT
    )
  );
  ```
- This routes deserialization failures directly to `-dlt` topic
- The `@DltHandler` `onDlt()` already handles any DLT message and increments counters

**Step 9: Verify DLT topic naming consistency**
- Ensure `CommonErrorHandler`'s `DeadLetterPublishingRecoverer` uses the same DLT topic suffix `-dlt` as `@RetryableTopic`
- May need a custom `BiFunction<ConsumerRecord, Exception, TopicPartition>` to route to `changeops.deploy.finished-dlt`

---

## Phase 5: Fix CT-SEC-03 — Actuator Endpoints Returning 500

### Root Cause
Both services configure `management.endpoints.web.exposure.include: health,info,prometheus,metrics`. Non-listed endpoints should return 404, but return 500 instead. No `application-local.yml` exists.

Likely cause: The `metrics` inclusion or something in Spring Boot auto-config is exposing all endpoints but some fail at runtime (missing dependencies/beans for env, heapdump, etc.), causing 500.

### Steps

**Step 10: Restrict actuator endpoints explicitly**
- In both `application.yml` files, add explicit exclusion:
  ```yaml
  management:
    endpoints:
      web:
        exposure:
          include: health,info,prometheus
      enabled-by-default: false
    endpoint:
      health:
        enabled: true
      info:
        enabled: true
      prometheus:
        enabled: true
  ```
- Remove `metrics` from the include list (it's only needed internally for Micrometer → Prometheus, not as a web endpoint)
- `enabled-by-default: false` + explicit `enabled: true` ensures no other endpoint is available

**Step 11: Update test expectation if needed**
- After fix, `/actuator/env` etc. should return 404 (endpoint not found)
- Test already accepts 404/401/403 as passing

---

## Verification

1. **Frontend stats**: Navigate to page 2 of changes → stats cards should show correct global counts matching Grafana
2. **Grafana**: Compare dashboard panels with frontend stats cards — should match exactly
3. **Backend tests**: Run `cd backend/change-service && mvn -B verify && mvn -B checkstyle:check`
4. **Frontend tests**: Run `cd frontend && npx tsc --noEmit && npm run lint && npm run test && npm run build`
5. **Integration tests**: Run `wsl bash tests/run_tests.sh` — all 41 scenarios should pass (except rate limiting if skipped)
6. **Actuator check**: `curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/actuator/env` → should return 404

---

## Decisions
- Frontend stats use a dedicated backend endpoint (not Prometheus metrics or client-side aggregation)
- Grafana switches to `changes_by_status` gauge to match DB snapshot (not counter-derived)
- Test flakiness addressed with polling instead of fixed sleep
- Deserialization errors routed to same DLT as business errors
- Actuator endpoints disabled by default (OWASP compliance)

## Scope
- Included: frontend stats fix, Grafana queries, test script improvements, DLT routing, actuator hardening
- Excluded: CT-SEC-07 (body size limit — ROADMAP Phase 2), HSTS/CSP headers (ROADMAP Phase 2)
