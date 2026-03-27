#!/bin/bash
CHANGE_ID_CT01=""
CHANGE_ID_CT09=""
CHANGE_ID_CT10=""
ASSERT_PASS=0
ASSERT_FAIL=0
TEST_PASS=0
TEST_FAIL=0
CURRENT_TEST_FAILED=0
FAILED_TESTS=""

newuuid() {
  python3 -c "import uuid; print(uuid.uuid4())" 2>/dev/null || \
  cat /proc/sys/kernel/random/uuid 2>/dev/null || \
  uuidgen 2>/dev/null
}

check() {
  local test=$1
  local expected=$2
  local actual=$3
  local desc=$4
  if [ "$actual" = "$expected" ]; then
    echo "  PASS [$test] $desc"
    ASSERT_PASS=$((ASSERT_PASS+1))
  else
    echo "  FAIL [$test] $desc | expected=$expected got=$actual"
    ASSERT_FAIL=$((ASSERT_FAIL+1))
    CURRENT_TEST_FAILED=1
  fi
}

begin_test() {
  CURRENT_TEST_FAILED=0
}

end_test() {
  if [ "$CURRENT_TEST_FAILED" -eq 0 ]; then
    TEST_PASS=$((TEST_PASS+1))
  else
    TEST_FAIL=$((TEST_FAIL+1))
    if [ -z "$FAILED_TESTS" ]; then
      FAILED_TESTS="$1"
    else
      FAILED_TESTS="$FAILED_TESTS, $1"
    fi
  fi
}

pass_msg() {
  local test=$1
  local desc=$2
  echo "  PASS [$test] $desc"
  ASSERT_PASS=$((ASSERT_PASS+1))
}

fail_msg() {
  local test=$1
  local desc=$2
  echo "  FAIL [$test] $desc"
  ASSERT_FAIL=$((ASSERT_FAIL+1))
  CURRENT_TEST_FAILED=1
}

# NOTA: Este script automatiza os cenários CT-02 a CT-14.
# CT-01 (Health Check) e CT-15 (Frontend) são intencionalmente não automatizados:
#   - CT-01: verificação de saúde (health check) deve ser feita manualmente antes de executar
#   - CT-15: validação de frontend requer interação visual no browser

echo ""
echo "========================================"
echo "  CT-02: Criar mudança com dados válidos"
echo "========================================"
begin_test
resp=$(curl -s -w "\nHTTP:%{http_code}" -X POST http://localhost:8080/api/v1/changes \
  -H "Content-Type: application/json" \
  -H "X-User-Id: tester-001" \
  -d '{"title":"Deploy payment-service v3.0","description":"Upgrade do gateway de pagamento","componentId":"payment-service","requestedBy":"tester-001","scheduledAt":"2026-06-01T10:00:00Z"}')
body=$(echo "$resp" | head -n -1)
code=$(echo "$resp" | tail -1 | sed 's/HTTP://')
CHANGE_ID_CT01=$(echo "$body" | grep -o '"changeId":"[^"]*"' | cut -d'"' -f4)
status_val=$(echo "$body" | grep -o '"status":"[^"]*"' | cut -d'"' -f4)
corr=$(echo "$body" | grep -o '"correlationId":"[^"]*"' | cut -d'"' -f4)

check "CT-02" "201" "$code" "HTTP 201 Created"
check "CT-02" "PREPARED" "$status_val" "status=PREPARED"
[ -n "$CHANGE_ID_CT01" ] && echo "  INFO changeId=$CHANGE_ID_CT01" || fail_msg "CT-02" "no changeId"
[ -n "$corr" ] && echo "  INFO correlationId=$corr" || fail_msg "CT-02" "no correlationId"
end_test "CT-02"

echo ""
echo "========================================"
echo "  CT-03: Campo obrigatório ausente (sem title)"
echo "========================================"
begin_test
resp=$(curl -s -w "\nHTTP:%{http_code}" -X POST http://localhost:8080/api/v1/changes \
  -H "Content-Type: application/json" \
  -d '{"description":"Teste de validação","componentId":"auth-service","requestedBy":"tester-001","scheduledAt":"2026-06-01T10:00:00Z"}')
body=$(echo "$resp" | head -n -1)
code=$(echo "$resp" | tail -1 | sed 's/HTTP://')
check "CT-03" "400" "$code" "HTTP 400 Bad Request"
echo "$body" | grep -q '"title"' && echo "  INFO body contém field validation" || echo "  WARN body sem field detail"
end_test "CT-03"

echo ""
echo "========================================"
echo "  CT-04: Data no passado"
echo "========================================"
begin_test
resp=$(curl -s -w "\nHTTP:%{http_code}" -X POST http://localhost:8080/api/v1/changes \
  -H "Content-Type: application/json" \
  -d '{"title":"Deploy auth-service v3.0.5","description":"Performance improvements","componentId":"auth-service","requestedBy":"tester-001","scheduledAt":"2025-01-01T09:15:00Z"}')
body=$(echo "$resp" | head -n -1)
code=$(echo "$resp" | tail -1 | sed 's/HTTP://')
check "CT-04" "400" "$code" "HTTP 400 Bad Request (past date)"
echo "$body" | grep -q 'scheduledAt\|future' && echo "  INFO body menciona scheduledAt" || echo "  WARN body sem scheduledAt detail"
end_test "CT-04"

echo ""
echo "========================================"
echo "  CT-05: Listar mudanças com paginação"
echo "========================================"
begin_test
resp=$(curl -s -w "\nHTTP:%{http_code}" "http://localhost:8080/api/v1/changes?status=PREPARED&page=0&size=5")
body=$(echo "$resp" | head -n -1)
code=$(echo "$resp" | tail -1 | sed 's/HTTP://')
check "CT-05" "200" "$code" "HTTP 200 OK"
echo "$body" | grep -q '"content"' && echo "  INFO body tem content[]" || fail_msg "CT-05" "sem content"
echo "$body" | grep -q '"totalElements"' && echo "  INFO tem totalElements" || fail_msg "CT-05" "sem totalElements"
echo "$body" | grep -q '"totalPages"' && echo "  INFO tem totalPages" || fail_msg "CT-05" "sem totalPages"
end_test "CT-05"

echo ""
echo "========================================"
echo "  CT-06: Consultar mudança por ID"
echo "========================================"
begin_test
resp=$(curl -s -w "\nHTTP:%{http_code}" "http://localhost:8080/api/v1/changes/$CHANGE_ID_CT01")
body=$(echo "$resp" | head -n -1)
code=$(echo "$resp" | tail -1 | sed 's/HTTP://')
check "CT-06" "200" "$code" "HTTP 200 OK"
echo "$body" | grep -q '"title"' && echo "  INFO body tem title" || fail_msg "CT-06" "sem title"
echo "$body" | grep -q '"description"' && echo "  INFO body tem description" || fail_msg "CT-06" "sem description"
echo "$body" | grep -q '"componentId"' && echo "  INFO body tem componentId" || fail_msg "CT-06" "sem componentId"
echo "$body" | grep -q '"scheduledAt"' && echo "  INFO body tem scheduledAt" || fail_msg "CT-06" "sem scheduledAt"
end_test "CT-06"

echo ""
echo "========================================"
echo "  CT-07: Consultar ID inexistente"
echo "========================================"
begin_test
resp=$(curl -s -w "\nHTTP:%{http_code}" "http://localhost:8080/api/v1/changes/00000000-0000-0000-0000-000000000000")
code=$(echo "$resp" | tail -1 | sed 's/HTTP://')
check "CT-07" "404" "$code" "HTTP 404 Not Found"
end_test "CT-07"

echo ""
echo "========================================"
echo "  CT-08: Consultar timeline de eventos"
echo "========================================"
begin_test
resp=$(curl -s -w "\nHTTP:%{http_code}" "http://localhost:8080/api/v1/changes/$CHANGE_ID_CT01/events")
body=$(echo "$resp" | head -n -1)
code=$(echo "$resp" | tail -1 | sed 's/HTTP://')
check "CT-08" "200" "$code" "HTTP 200 OK"
echo "$body" | grep -q 'ChangePreparedEvent' && echo "  INFO contém ChangePreparedEvent" || fail_msg "CT-08" "sem ChangePreparedEvent"
echo "$body" | grep -q '"eventId"' && echo "  INFO tem eventId" || fail_msg "CT-08" "sem eventId"
echo "$body" | grep -q '"occurredAt"' && echo "  INFO tem occurredAt" || fail_msg "CT-08" "sem occurredAt"
end_test "CT-08"

echo ""
echo "========================================"
echo "  CT-09: Mudança permanece PREPARED sem evento de deploy"
echo "========================================"
begin_test
CHANGE_ID_CT15=""
resp=$(curl -s -w "\nHTTP:%{http_code}" -X POST http://localhost:8080/api/v1/changes \
  -H "Content-Type: application/json" \
  -H "X-User-Id: tester-001" \
  -d '{"title":"Deploy inventory-service v1.5","description":"Rollout de nova versão do serviço de inventário","componentId":"inventory-service","requestedBy":"tester-001","scheduledAt":"2026-09-01T10:00:00Z"}')
body=$(echo "$resp" | head -n -1)
code=$(echo "$resp" | tail -1 | sed 's/HTTP://')
CHANGE_ID_CT15=$(echo "$body" | grep -o '"changeId":"[^"]*"' | cut -d'"' -f4)
check "CT-09" "201" "$code" "HTTP 201 Created"
[ -n "$CHANGE_ID_CT15" ] && echo "  INFO changeId=$CHANGE_ID_CT15" || fail_msg "CT-09" "no changeId"

echo "  INFO aguardando 5s sem publicar nenhum evento de deploy..."
sleep 5

resp=$(curl -s -w "\nHTTP:%{http_code}" "http://localhost:8080/api/v1/changes/$CHANGE_ID_CT15")
body=$(echo "$resp" | head -n -1)
code=$(echo "$resp" | tail -1 | sed 's/HTTP://')
status_val=$(echo "$body" | grep -o '"status":"[^"]*"' | cut -d'"' -f4)
check "CT-09" "200" "$code" "GET retorna 200"
check "CT-09" "PREPARED" "$status_val" "status permanece PREPARED"

timeline=$(curl -s "http://localhost:8080/api/v1/changes/$CHANGE_ID_CT15/events")
count=$(echo "$timeline" | grep -o 'ChangePreparedEvent' | wc -l)
completed_count=$(echo "$timeline" | grep -o 'ChangeCompletedEvent' | wc -l)
failed_count=$(echo "$timeline" | grep -o 'ChangeFailedEvent' | wc -l)
check "CT-09" "1" "$count" "timeline contém exatamente 1 ChangePreparedEvent"
check "CT-09" "0" "$completed_count" "timeline sem ChangeCompletedEvent"
check "CT-09" "0" "$failed_count" "timeline sem ChangeFailedEvent"
end_test "CT-09"

echo ""
echo "========================================"
echo "  CT-10: Deploy SUCCESS -> COMPLETED"
echo "========================================"
begin_test
# Publicar DeployFinishedEvent SUCCESS via kafka-console-producer
DEPLOY_ID_08=$(newuuid)
EVENT_08='{"eventType":"DeployFinishedEvent","version":"1.0","correlationId":"a1b2c3d4-e5f6-7890-abcd-ef1234567890","occurredAt":"2026-03-26T12:00:00Z","payload":{"deployId":"'"$DEPLOY_ID_08"'","changeId":"'"$CHANGE_ID_CT01"'","result":"SUCCESS","executedAt":"2026-03-26T12:00:00Z"}}'
echo "$EVENT_08" | docker exec -i changeops-kafka kafka-console-producer --bootstrap-server localhost:9092 --topic changeops.deploy.finished 2>/dev/null
echo "  INFO evento publicado, aguardando 3s..."
sleep 3
resp=$(curl -s -w "\nHTTP:%{http_code}" "http://localhost:8080/api/v1/changes/$CHANGE_ID_CT01")
body=$(echo "$resp" | head -n -1)
code=$(echo "$resp" | tail -1 | sed 's/HTTP://')
status_val=$(echo "$body" | grep -o '"status":"[^"]*"' | cut -d'"' -f4)
check "CT-10" "200" "$code" "GET retorna 200"
check "CT-10" "COMPLETED" "$status_val" "status mudou para COMPLETED"

# Verifica timeline
resp=$(curl -s "http://localhost:8080/api/v1/changes/$CHANGE_ID_CT01/events")
echo "$resp" | grep -q 'ChangeCompletedEvent' && echo "  INFO timeline tem ChangeCompletedEvent" || echo "  FAIL sem ChangeCompletedEvent na timeline"
echo "$resp" | grep -q 'ChangeCompletedEvent' && echo "  INFO timeline tem ChangeCompletedEvent" || fail_msg "CT-10" "sem ChangeCompletedEvent na timeline"
echo "$resp" | grep -q 'ChangePreparedEvent' && echo "  INFO timeline tem ChangePreparedEvent" || fail_msg "CT-10" "sem ChangePreparedEvent"
end_test "CT-10"

echo ""
echo "========================================"
echo "  CT-11: Deploy FAILURE -> FAILED"
echo "========================================"
begin_test
# Criar nova mudança para CT-11
resp=$(curl -s -w "\nHTTP:%{http_code}" -X POST http://localhost:8080/api/v1/changes \
  -H "Content-Type: application/json" \
  -d '{"title":"Deploy order-service v2.0","description":"Teste de falha no deploy","componentId":"order-service","requestedBy":"tester-001","scheduledAt":"2026-07-01T10:00:00Z"}')
body=$(echo "$resp" | head -n -1)
CHANGE_ID_CT09=$(echo "$body" | grep -o '"changeId":"[^"]*"' | cut -d'"' -f4)
echo "  INFO changeId CT-11=$CHANGE_ID_CT09"

DEPLOY_ID_09=$(newuuid)
EVENT_09='{"eventType":"DeployFinishedEvent","version":"1.0","correlationId":"f1f2f3f4-f5f6-f7f8-f9fa-fbfcfdfeff00","occurredAt":"2026-03-26T12:10:00Z","payload":{"deployId":"'"$DEPLOY_ID_09"'","changeId":"'"$CHANGE_ID_CT09"'","result":"FAILURE","executedAt":"2026-03-26T12:10:00Z"}}'
echo "$EVENT_09" | docker exec -i changeops-kafka kafka-console-producer --bootstrap-server localhost:9092 --topic changeops.deploy.finished 2>/dev/null
echo "  INFO evento FAILURE publicado, aguardando 3s..."
sleep 3
resp=$(curl -s -w "\nHTTP:%{http_code}" "http://localhost:8080/api/v1/changes/$CHANGE_ID_CT09")
body=$(echo "$resp" | head -n -1)
status_val=$(echo "$body" | grep -o '"status":"[^"]*"' | cut -d'"' -f4)
check "CT-11" "FAILED" "$status_val" "status mudou para FAILED"

resp=$(curl -s "http://localhost:8080/api/v1/changes/$CHANGE_ID_CT09/events")
echo "$resp" | grep -q 'ChangeFailedEvent' && echo "  INFO timeline tem ChangeFailedEvent" || fail_msg "CT-11" "sem ChangeFailedEvent na timeline"
end_test "CT-11"

echo ""
echo "========================================"
echo "  CT-12: Idempotência"
echo "========================================"
begin_test
resp=$(curl -s -w "\nHTTP:%{http_code}" -X POST http://localhost:8080/api/v1/changes \
  -H "Content-Type: application/json" \
  -d '{"title":"Deploy idempotency-service v1.0","description":"Teste de idempotência","componentId":"idempotency-service","requestedBy":"tester-001","scheduledAt":"2026-07-01T10:00:00Z"}')
body=$(echo "$resp" | head -n -1)
CHANGE_ID_CT10=$(echo "$body" | grep -o '"changeId":"[^"]*"' | cut -d'"' -f4)
echo "  INFO changeId CT-12=$CHANGE_ID_CT10"

DEPLOY_ID_10=$(newuuid)
EVENT_10='{"eventType":"DeployFinishedEvent","version":"1.0","correlationId":"c1c2c3c4-c5c6-c7c8-c9ca-cbcccdcecfc0","occurredAt":"2026-03-26T12:20:00Z","payload":{"deployId":"'"$DEPLOY_ID_10"'","changeId":"'"$CHANGE_ID_CT10"'","result":"SUCCESS","executedAt":"2026-03-26T12:20:00Z"}}'

# Publicar 1a vez
echo "$EVENT_10" | docker exec -i changeops-kafka kafka-console-producer --bootstrap-server localhost:9092 --topic changeops.deploy.finished 2>/dev/null
sleep 3
status_after1=$(curl -s "http://localhost:8080/api/v1/changes/$CHANGE_ID_CT10" | grep -o '"status":"[^"]*"' | cut -d'"' -f4)
check "CT-12" "COMPLETED" "$status_after1" "1a vez: status=COMPLETED"

# Publicar exato mesmo evento 2a vez
echo "$EVENT_10" | docker exec -i changeops-kafka kafka-console-producer --bootstrap-server localhost:9092 --topic changeops.deploy.finished 2>/dev/null
sleep 3
status_after2=$(curl -s "http://localhost:8080/api/v1/changes/$CHANGE_ID_CT10" | grep -o '"status":"[^"]*"' | cut -d'"' -f4)
check "CT-12" "COMPLETED" "$status_after2" "2a vez: status permanece COMPLETED"

# Verifica que não duplicou eventos
timeline=$(curl -s "http://localhost:8080/api/v1/changes/$CHANGE_ID_CT10/events")
count=$(echo "$timeline" | grep -o 'ChangeCompletedEvent' | wc -l)
check "CT-12" "1" "$count" "apenas 1 ChangeCompletedEvent na timeline (sem duplicata)"
end_test "CT-12"

echo ""
echo "========================================"
echo "  CT-13: DLT (changeId inexistente)"
echo "========================================"
begin_test
EVENT_11='{"eventType":"DeployFinishedEvent","version":"1.0","correlationId":"deadbeef-dead-beef-dead-beefdeadbeef","occurredAt":"2026-03-26T12:30:00Z","payload":{"deployId":"cc000000-0000-0000-0000-000000000001","changeId":"00000000-0000-0000-0000-000000000099","result":"SUCCESS","executedAt":"2026-03-26T12:30:00Z"}}'
echo "$EVENT_11" | docker exec -i changeops-kafka kafka-console-producer --bootstrap-server localhost:9092 --topic changeops.deploy.finished 2>/dev/null
echo "  INFO evento DLT publicado (changeId inexistente)"
echo "  INFO aguardando 15s para retries + DLT..."
sleep 15
# Verifica se topic DLT existe e tem mensagens
dlt_count=$(docker exec changeops-kafka kafka-console-consumer --bootstrap-server localhost:9092 --topic changeops.deploy.finished-dlt --from-beginning --max-messages 10 --timeout-ms 5000 2>/dev/null | wc -l)
[ "$dlt_count" -gt 0 ] && pass_msg "CT-13" "mensagem chegou no DLT Kafka (count=$dlt_count)" || fail_msg "CT-13" "nenhuma mensagem no DLT Kafka"

# Verifica se o counter events_dlt_total foi incrementado no deploy-orchestrator
dlt_metric=$(curl -s http://localhost:8081/actuator/prometheus | grep '^events_dlt_total' | grep -v '#' | awk '{print $2}' | head -1)
dlt_int=$(echo "$dlt_metric" | grep -o '^[0-9]*' | head -1)
[ -n "$dlt_int" ] && [ "$dlt_int" -gt 0 ] && pass_msg "CT-13" "events_dlt_total=$dlt_metric (counter incrementado)" || fail_msg "CT-13" "events_dlt_total=${dlt_metric:-0} (counter não incrementado)"
end_test "CT-13"

echo ""
echo "========================================"
echo "  CT-14: Observabilidade (Prometheus)"
echo "========================================"
begin_test
prom=$(curl -s http://localhost:9090/api/v1/query --data-urlencode 'query=changes_created_total')
echo "$prom" | grep -q '"result"' && echo "  INFO Prometheus respondeu" || echo "  FAIL Prometheus sem resposta"
val=$(echo "$prom" | grep -o '"value":\[[^]]*\]' | head -1 | grep -o '[0-9]*\.[0-9]*"' | tr -d '"')
[ -n "$val" ] && echo "  INFO changes_created_total=$val" || echo "  WARN sem valor de changes_created_total"

prom2=$(curl -s http://localhost:9090/api/v1/query --data-urlencode 'query=events_published_total')
val2=$(echo "$prom2" | grep -o '"value":\[[^]]*\]' | head -1 | grep -o '[0-9.]*"' | tail -1 | tr -d '"')
[ -n "$val2" ] && echo "  INFO events_published_total=$val2" || echo "  WARN sem valor de events_published_total"

# Verifica métricas do deploy-orchestrator diretamente via actuator (porta 8081)
echo "  --- deploy-orchestrator (actuator) ---"
prom_orch=$(curl -s http://localhost:8081/actuator/prometheus)

echo "$prom_orch" | grep -q 'events_consumed_total' \
  && pass_msg "CT-14" "events_consumed_total exposto" \
  || fail_msg "CT-14" "events_consumed_total ausente"

echo "$prom_orch" | grep -q 'events_failed_total' \
  && pass_msg "CT-14" "events_failed_total exposto" \
  || fail_msg "CT-14" "events_failed_total ausente"

dlt_prom=$(echo "$prom_orch" | grep '^events_dlt_total' | grep -v '#' | awk '{print $2}' | head -1)
dlt_prom_int=$(echo "$dlt_prom" | grep -o '^[0-9]*' | head -1)
[ -n "$dlt_prom_int" ] && [ "$dlt_prom_int" -gt 0 ] \
  && pass_msg "CT-14" "events_dlt_total=$dlt_prom (> 0 após CT-13)" \
  || fail_msg "CT-14" "events_dlt_total=${dlt_prom:-0} (esperado > 0)"

discarded_prom=$(echo "$prom_orch" | grep '^events_discarded_total' | grep -v '#' | awk '{print $2}' | head -1)
[ -n "$discarded_prom" ] && echo "  INFO events_discarded_total=$discarded_prom" || echo "  WARN events_discarded_total ausente"
end_test "CT-14"

echo ""
echo "========================================"
echo "  RESUMO FINAL"
echo "========================================"
echo "  CENÁRIOS TOTAL  : $((TEST_PASS+TEST_FAIL))"
echo "  CENÁRIOS PASSARAM: $TEST_PASS"
echo "  CENÁRIOS FALHARAM: $TEST_FAIL"
[ "$TEST_FAIL" -gt 0 ] && echo "  CENÁRIOS COM FALHA: $FAILED_TESTS"
echo "  ASSERTS FALHARAM: $ASSERT_FAIL"
echo ""
