#!/bin/bash
# ──────────────────────────────────────────────────────────────────────────────
#  run_unity_tests.sh — Run all unit tests (backend + frontend)
#
#  Backend (Maven):  files matching **/*Test (excludes *IT classes)
#  Frontend (Vitest): npm test (vitest run --coverage)
#
#  Usage:
#    wsl bash tests/run_unity_tests.sh
# ──────────────────────────────────────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT_NAME="$(basename "${BASH_SOURCE[0]}" .sh)"
TIMESTAMP="$(date '+%Y%m%d%H%M%S')_$$"
LOG_DIR="$SCRIPT_DIR/logs/$SCRIPT_NAME"
LOG_FILE="$LOG_DIR/$TIMESTAMP.txt"
mkdir -p "$LOG_DIR"
exec > >(tee -a "$LOG_FILE") 2>&1
echo "  LOG $LOG_FILE"

ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

# ── Global counters ───────────────────────────────────────────────────────────
OVERALL_EXIT=0

BACKEND_TOTAL_RUN=0
BACKEND_TOTAL_FAILURES=0
BACKEND_TOTAL_ERRORS=0
BACKEND_TOTAL_SKIPPED=0
BACKEND_SERVICES_PASSED=0
BACKEND_SERVICES_FAILED=0
BACKEND_FAILED_SERVICES=""
BACKEND_FAILED_TESTS=""

FRONTEND_EXIT=0
FRONTEND_TEST_FILES_PASSED=0
FRONTEND_TEST_FILES_FAILED=0
FRONTEND_TESTS_PASSED=0
FRONTEND_TESTS_FAILED=0
FRONTEND_FAILED_TESTS=""

# ── Helpers ───────────────────────────────────────────────────────────────────

# Extract a single integer following "label: " from a Maven results line.
# Usage: mvn_field "Failures" "Tests run: 5, Failures: 0, Errors: 0, Skipped: 0"
mvn_field() {
  local label="$1"
  local line="$2"
  echo "$line" | grep -o "${label}: [0-9]*" | grep -o "[0-9]*"
}

# Run unit tests for one backend service and accumulate counters.
# Usage: run_backend_unit "change-service" "$ROOT_DIR/backend/change-service"
run_backend_unit() {
  local svc_name="$1"
  local svc_dir="$2"
  local tmp_out
  tmp_out="$(mktemp)"

  echo ""
  echo "========================================"
  echo "  UNIT TESTS: $svc_name"
  echo "========================================"

  (cd "$svc_dir" && mvn -B test -Dtest="**/*Test" -DfailIfNoTests=false 2>&1) \
    | tee "$tmp_out"
  local exit_code="${PIPESTATUS[0]}"

  # Find the final Maven results summary line (no trailing "-- in ClassName")
  local summary_line
  summary_line="$(grep -E "Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$" \
    "$tmp_out" | tail -1)"

  if [ -n "$summary_line" ]; then
    local run failures errors skipped
    run="$(mvn_field "Tests run" "$summary_line")"
    failures="$(mvn_field "Failures" "$summary_line")"
    errors="$(mvn_field "Errors" "$summary_line")"
    skipped="$(mvn_field "Skipped" "$summary_line")"

    run="${run:-0}"; failures="${failures:-0}"; errors="${errors:-0}"; skipped="${skipped:-0}"

    BACKEND_TOTAL_RUN=$((BACKEND_TOTAL_RUN + run))
    BACKEND_TOTAL_FAILURES=$((BACKEND_TOTAL_FAILURES + failures))
    BACKEND_TOTAL_ERRORS=$((BACKEND_TOTAL_ERRORS + errors))
    BACKEND_TOTAL_SKIPPED=$((BACKEND_TOTAL_SKIPPED + skipped))

    echo ""
    echo "  --- $svc_name: run=$run  failures=$failures  errors=$errors  skipped=$skipped ---"
  else
    echo ""
    echo "  WARN: could not parse Maven test summary for $svc_name"
  fi

  if [ "$exit_code" -eq 0 ]; then
    BACKEND_SERVICES_PASSED=$((BACKEND_SERVICES_PASSED + 1))
    echo "  RESULT [$svc_name]: PASSED"
  else
    BACKEND_SERVICES_FAILED=$((BACKEND_SERVICES_FAILED + 1))
    OVERALL_EXIT=1
    if [ -z "$BACKEND_FAILED_SERVICES" ]; then
      BACKEND_FAILED_SERVICES="$svc_name"
    else
      BACKEND_FAILED_SERVICES="$BACKEND_FAILED_SERVICES, $svc_name"
    fi
    echo "  RESULT [$svc_name]: FAILED (exit=$exit_code)"
  fi

  # Collect individual failed test names from Maven [ERROR] lines
  local raw_failures
  raw_failures="$(grep -E "\[ERROR\] .*<<< (FAILURE|ERROR)!" "$tmp_out" \
    | grep -v "Tests run:" \
    | sed 's/^\[ERROR\] //; s/ -- Time elapsed.*//')"
  if [ -n "$raw_failures" ]; then
    while IFS= read -r line; do
      [ -z "$line" ] && continue
      BACKEND_FAILED_TESTS="${BACKEND_FAILED_TESTS}    [$svc_name] ${line}"$'\n'
    done <<< "$raw_failures"
  fi

  rm -f "$tmp_out"
}

# Run frontend Vitest unit tests and populate counters.
run_frontend_unit() {
  local tmp_out
  tmp_out="$(mktemp)"

  echo ""
  echo "========================================"
  echo "  UNIT TESTS: frontend (Vitest)"
  echo "========================================"

  (cd "$ROOT_DIR/frontend" && npm test 2>&1) | tee "$tmp_out"
  FRONTEND_EXIT="${PIPESTATUS[0]}"

  # Parse "Test Files  X passed (Y)" or "Test Files  Z failed, X passed (Y)"
  local files_line
  files_line="$(grep -E "Test Files" "$tmp_out" | tail -1)"

  local tests_line
  tests_line="$(grep -E "^ Tests " "$tmp_out" | tail -1)"

  # Files passed
  local fpassed ferr
  fpassed="$(echo "$files_line" | grep -o '[0-9]* passed' | grep -o '[0-9]*')"
  ferr="$(echo "$files_line" | grep -o '[0-9]* failed' | grep -o '[0-9]*')"
  FRONTEND_TEST_FILES_PASSED="${fpassed:-0}"
  FRONTEND_TEST_FILES_FAILED="${ferr:-0}"

  # Tests passed / failed
  local tpassed terr
  tpassed="$(echo "$tests_line" | grep -o '[0-9]* passed' | grep -o '[0-9]*')"
  terr="$(echo "$tests_line" | grep -o '[0-9]* failed' | grep -o '[0-9]*')"
  FRONTEND_TESTS_PASSED="${tpassed:-0}"
  FRONTEND_TESTS_FAILED="${terr:-0}"

  echo ""
  echo "  --- frontend: test-files passed=$FRONTEND_TEST_FILES_PASSED failed=$FRONTEND_TEST_FILES_FAILED ---"
  echo "  --- frontend: tests passed=$FRONTEND_TESTS_PASSED failed=$FRONTEND_TESTS_FAILED ---"

  if [ "$FRONTEND_EXIT" -eq 0 ]; then
    echo "  RESULT [frontend]: PASSED"
  else
    OVERALL_EXIT=1
    echo "  RESULT [frontend]: FAILED (exit=$FRONTEND_EXIT)"
  fi

  # Collect failed test entries from Vitest output.
  # Vitest prints failing items as " FAIL  file > suite > test" or just " FAIL  file".
  local raw_ff
  raw_ff="$(grep -E "^ FAIL " "$tmp_out" | sed 's/^ FAIL  *//')"
  if [ -n "$raw_ff" ]; then
    while IFS= read -r line; do
      [ -z "$line" ] && continue
      FRONTEND_FAILED_TESTS="${FRONTEND_FAILED_TESTS}    [frontend] ${line}"$'\n'
    done <<< "$raw_ff"
  fi

  rm -f "$tmp_out"
}

# ── Run all unit test suites ──────────────────────────────────────────────────

echo ""
echo "============================================================"
echo "  CHANGEOPS — UNIT TEST SUITE"
echo "  $(date '+%Y-%m-%d %H:%M:%S')"
echo "============================================================"

run_backend_unit "change-service"      "$ROOT_DIR/backend/change-service"
run_backend_unit "deploy-orchestrator" "$ROOT_DIR/backend/deploy-orchestrator"
run_frontend_unit

# ── Final compiled summary ────────────────────────────────────────────────────

BACKEND_TOTAL_PASSED=$((BACKEND_TOTAL_RUN - BACKEND_TOTAL_FAILURES - BACKEND_TOTAL_ERRORS))
BACKEND_TOTAL_ISSUES=$((BACKEND_TOTAL_FAILURES + BACKEND_TOTAL_ERRORS))
FRONTEND_TESTS_TOTAL=$((FRONTEND_TESTS_PASSED + FRONTEND_TESTS_FAILED))
FRONTEND_FILES_TOTAL=$((FRONTEND_TEST_FILES_PASSED + FRONTEND_TEST_FILES_FAILED))

echo ""
echo "============================================================"
echo "  COMPILED SUMMARY — UNIT TESTS"
echo "  $(date '+%Y-%m-%d %H:%M:%S')"
echo "============================================================"
echo ""
echo "  ── BACKEND ──────────────────────────────────────────────"
echo "  Services passed : $BACKEND_SERVICES_PASSED / $((BACKEND_SERVICES_PASSED + BACKEND_SERVICES_FAILED))"
[ "$BACKEND_SERVICES_FAILED" -gt 0 ] && \
  echo "  Services failed : $BACKEND_SERVICES_FAILED  [$BACKEND_FAILED_SERVICES]"
echo "  Tests run       : $BACKEND_TOTAL_RUN"
echo "  Tests passed    : $BACKEND_TOTAL_PASSED"
echo "  Tests failed    : $BACKEND_TOTAL_FAILURES"
echo "  Tests errors    : $BACKEND_TOTAL_ERRORS"
echo "  Tests skipped   : $BACKEND_TOTAL_SKIPPED"
if [ -n "$BACKEND_FAILED_TESTS" ]; then
  echo ""
  echo "  Failed backend tests:"
  printf '%s' "$BACKEND_FAILED_TESTS"
fi
echo ""
echo "  ── FRONTEND ─────────────────────────────────────────────"
echo "  Test files      : $FRONTEND_FILES_TOTAL  (passed=$FRONTEND_TEST_FILES_PASSED  failed=$FRONTEND_TEST_FILES_FAILED)"
echo "  Tests run       : $FRONTEND_TESTS_TOTAL"
echo "  Tests passed    : $FRONTEND_TESTS_PASSED"
echo "  Tests failed    : $FRONTEND_TESTS_FAILED"
if [ -n "$FRONTEND_FAILED_TESTS" ]; then
  echo ""
  echo "  Failed frontend tests:"
  printf '%s' "$FRONTEND_FAILED_TESTS"
fi
echo ""
echo "  ── OVERALL ──────────────────────────────────────────────"

COMBINED_PASSED=$((BACKEND_TOTAL_PASSED + FRONTEND_TESTS_PASSED))
COMBINED_FAILED=$((BACKEND_TOTAL_ISSUES + FRONTEND_TESTS_FAILED))
COMBINED_TOTAL=$((BACKEND_TOTAL_RUN + FRONTEND_TESTS_TOTAL))

echo "  Total tests run : $COMBINED_TOTAL"
echo "  Total passed    : $COMBINED_PASSED"
echo "  Total failed    : $COMBINED_FAILED"
echo "  Total skipped   : $BACKEND_TOTAL_SKIPPED"

if [ "$OVERALL_EXIT" -eq 0 ]; then
  echo ""
  echo "  STATUS: ALL UNIT TESTS PASSED"
else
  echo ""
  echo "  STATUS: UNIT TESTS FAILED"
fi
echo "============================================================"

exit "$OVERALL_EXIT"
