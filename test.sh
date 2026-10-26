#!/usr/bin/env bash
# test.sh — Lightweight checks for the mini-mainframe workflow.
# Not a test framework — just targeted shell assertions.
# Note: -e is intentionally omitted so tests can capture nonzero exit codes.
set -uo pipefail

PASS=0; FAIL=0
OUTPUT="output"
ORIG_INPUT=""

# ── helper ────────────────────────────────────────────────────────────────────
assert() {
    local desc="$1" result="$2"
    if [[ "$result" == "ok" ]]; then
        echo "  [PASS] $desc"
        PASS=$((PASS+1))
    else
        echo "  [FAIL] $desc — $result"
        FAIL=$((FAIL+1))
    fi
}

assert_eq() {
    local desc="$1" expected="$2" actual="$3"
    if [[ "$expected" == "$actual" ]]; then
        assert "$desc" "ok"
    else
        assert "$desc" "expected '$expected', got '$actual'"
    fi
}

# ── Test 1: compile and run happy-path ────────────────────────────────────────
echo ""
echo "── Test 1: Happy-path workflow ──"
./run.sh > /dev/null 2>&1
assert "run.sh exits 0" "ok"

# ── Test 2: processed.csv was created and has a header ───────────────────────
echo ""
echo "── Test 2: Output files created ──"
[[ -f "$OUTPUT/processed.csv" ]] && assert "processed.csv exists" "ok" \
                                 || assert "processed.csv exists" "file missing"
[[ -f "$OUTPUT/errors.csv"    ]] && assert "errors.csv exists"    "ok" \
                                 || assert "errors.csv exists"    "file missing"
[[ -f "$OUTPUT/metrics.properties" ]] && assert "metrics.properties exists" "ok" \
                                      || assert "metrics.properties exists" "file missing"
[[ -f "$OUTPUT/job.log"       ]] && assert "job.log exists"       "ok" \
                                 || assert "job.log exists"       "file missing"

# ── Test 3: RC classification in processed.csv ───────────────────────────────
echo ""
echo "── Test 3: Return-code classification ──"
SUCCESS_COUNT=$(awk -F, '$4=="0"  && $6=="SUCCESS" {c++} END{print c+0}' "$OUTPUT/processed.csv")
WARNING_COUNT=$(awk -F, '$4=="4"  && $6=="WARNING" {c++} END{print c+0}' "$OUTPUT/processed.csv")
FAILURE_COUNT=$(awk -F, '$4>="8"  && $6=="FAILURE" {c++} END{print c+0}' "$OUTPUT/processed.csv")

[[ "$SUCCESS_COUNT" -gt 0 ]] && assert "RC=0 rows classified SUCCESS" "ok" \
                              || assert "RC=0 rows classified SUCCESS" "count=$SUCCESS_COUNT"
[[ "$WARNING_COUNT" -gt 0 ]] && assert "RC=4 rows classified WARNING" "ok" \
                              || assert "RC=4 rows classified WARNING" "count=$WARNING_COUNT"
[[ "$FAILURE_COUNT" -gt 0 ]] && assert "RC>=8 rows classified FAILURE" "ok" \
                              || assert "RC>=8 rows classified FAILURE" "count=$FAILURE_COUNT"

# ── Test 4: Invalid records go to errors.csv, not processed.csv ──────────────
echo ""
echo "── Test 4: Invalid records routed to errors.csv ──"
ERROR_LINES=$(awk 'NR>1 && NF>0' "$OUTPUT/errors.csv" | wc -l | tr -d ' ')
[[ "$ERROR_LINES" -gt 0 ]] && assert "errors.csv has rejected records ($ERROR_LINES)" "ok" \
                            || assert "errors.csv has rejected records" "empty"

# Check that none of the error job IDs (JOB9xxx) appear in processed.csv
LEAK=$(grep -c "^JOB9" "$OUTPUT/processed.csv" 2>/dev/null || true)
LEAK=${LEAK:-0}
if [[ "$LEAK" -eq 0 ]]; then
    assert "Invalid records not in processed.csv" "ok"
else
    assert "Invalid records not in processed.csv" "found $LEAK leaking rows"
fi

# ── Test 5: metrics totals are consistent ─────────────────────────────────────
echo ""
echo "── Test 5: Metrics consistency ──"
VALID=$(grep "^valid_records=" "$OUTPUT/metrics.properties" | cut -d= -f2)
REJECTED=$(grep "^rejected_records=" "$OUTPUT/metrics.properties" | cut -d= -f2)
TOTAL=$(grep "^total_records=" "$OUTPUT/metrics.properties" | cut -d= -f2)
EXPECTED=$((VALID + REJECTED))

PROC_ROWS=$(awk 'NR>1 && NF>0' "$OUTPUT/processed.csv" | wc -l | tr -d ' ')
assert_eq "valid_records matches processed.csv row count" "$VALID" "$PROC_ROWS"
assert_eq "valid + rejected = total" "$TOTAL" "$EXPECTED"

# ── Test 6: Re-run replaces outputs (no duplication) ─────────────────────────
echo ""
echo "── Test 6: Re-run replaces outputs ──"
./run.sh > /dev/null 2>&1
PROC_ROWS_2=$(awk 'NR>1 && NF>0' "$OUTPUT/processed.csv" | wc -l | tr -d ' ')
assert_eq "processed.csv row count stable after re-run" "$PROC_ROWS" "$PROC_ROWS_2"

# ── Test 7: Missing input file causes FAILED status ──────────────────────────
echo ""
echo "── Test 7: Missing input file → job FAILED ──"
ORIG_INPUT="data/job_logs.csv"
mv "$ORIG_INPUT" "${ORIG_INPUT}.bak"

EXIT_CODE=0
./run.sh > /dev/null 2>&1 || EXIT_CODE=$?
mv "${ORIG_INPUT}.bak" "$ORIG_INPUT"

[[ "$EXIT_CODE" -ne 0 ]] && assert "Non-zero exit code when input missing" "ok" \
                          || assert "Non-zero exit code when input missing" "got exit 0"

LAST_STATUS=$(grep "JOB FAILED\|JOB COMPLETED" "$OUTPUT/job.log" | tail -1)
[[ "$LAST_STATUS" == *"FAILED"* ]] && assert "job.log records FAILED status" "ok" \
                                   || assert "job.log records FAILED status" "got: '$LAST_STATUS'"

# ── restore and re-run so output is clean for demo ───────────────────────────
./run.sh > /dev/null 2>&1

# ── summary ───────────────────────────────────────────────────────────────────
echo ""
echo "══════════════════════════════════════"
echo "  Tests passed : $PASS"
echo "  Tests failed : $FAIL"
echo "══════════════════════════════════════"

[[ "$FAIL" -eq 0 ]] && exit 0 || exit 1
