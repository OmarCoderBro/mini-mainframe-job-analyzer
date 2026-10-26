#!/usr/bin/env bash
# run.sh — Orchestrates the mini-mainframe job log analysis workflow.
# Inspired by z/OS JES (Job Entry Subsystem) which sequences and executes batch jobs.
set -euo pipefail

# ── read JCL-inspired job configuration ──────────────────────────────────────
CONFIG_FILE="job.conf"
if [[ ! -f "$CONFIG_FILE" ]]; then
    echo "[ERROR] job.conf not found" >&2
    exit 1
fi

JOB_NAME="LOG_ANALYSIS"
INPUT="data/job_logs.csv"
OUTPUT="output"

while IFS='=' read -r key value || [[ -n "$key" ]]; do
    key="${key%%#*}"   # strip inline comments
    key="${key// /}"
    value="${value// /}"
    case "$key" in
        JOB_NAME) JOB_NAME="$value" ;;
        INPUT)    INPUT="$value"    ;;
        OUTPUT)   OUTPUT="${value%/}" ;;  # strip trailing slash
    esac
done < "$CONFIG_FILE"

mkdir -p "$OUTPUT"

# ── compilation ───────────────────────────────────────────────────────────────
echo "Compiling Java source..."
mkdir -p build/java
javac -d build/java src/JobLogProcessor.java

echo "Compiling Scala source..."
mkdir -p build/scala
scalac -d build/scala src/JobReport.scala 2>&1

# ── run Java log processor ────────────────────────────────────────────────────
echo ""
echo "Starting analysis job: $JOB_NAME"
echo "Input: $INPUT → Output: $OUTPUT/"
echo ""

if ! java -cp build/java JobLogProcessor; then
    echo "[FAILED] Java processor exited with error." >&2
    exit 1
fi

# ── run Scala report generator ────────────────────────────────────────────────
echo ""
if ! scala -cp build/scala JobReport "$OUTPUT"; then
    echo "[FAILED] Scala report generator exited with error." >&2
    exit 2
fi

exit 0
