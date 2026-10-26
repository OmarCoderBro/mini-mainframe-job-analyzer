#!/usr/bin/env python3
"""Generate synthetic mainframe-style job execution records for testing."""
import csv, random, sys

random.seed(42)

STEPS     = ["EXTRACT", "VALIDATE", "TRANSFORM", "SORT", "REPORT", "ARCHIVE"]
JOB_NAMES = ["PAYROLL", "INVENTORY", "BILLING", "LEDGER", "AUDIT",
             "CLAIMS", "ORDERS", "DISPATCH", "RETURNS", "RECON"]

def pick_rc():
    r = random.random()
    if r < 0.72: return 0
    if r < 0.83: return 4
    if r < 0.90: return 8
    if r < 0.94: return 12
    if r < 0.96: return 2   # UNKNOWN
    if r < 0.97: return 16
    return 0

rows = []

# 200 well-formed jobs, 3 steps each = 600 valid records
for i in range(1, 201):
    job_id   = f"JOB{i:04d}"
    job_name = JOB_NAMES[i % len(JOB_NAMES)]
    for step in random.sample(STEPS, 3):
        rc  = pick_rc()
        dur = random.randint(40, 500)
        rows.append([job_id, job_name, step, rc, dur])

# ~20 invalid records scattered in
invalid = [
    # missing field
    ["JOB9001", "PAYROLL", "EXTRACT", "", "100"],
    ["JOB9002", "", "VALIDATE", "0", "80"],
    ["",        "BILLING", "REPORT",  "0", "60"],
    # bad return code
    ["JOB9003", "AUDIT",  "SORT",    "abc", "120"],
    ["JOB9004", "LEDGER", "ARCHIVE", "-1",  "90"],
    # bad duration
    ["JOB9005", "PAYROLL","EXTRACT", "0",   "xyz"],
    ["JOB9006", "CLAIMS", "SORT",    "4",   "-50"],
    # too few fields
    ["JOB9007", "ORDERS"],
    # negative rc
    ["JOB9008", "RETURNS","VALIDATE","-4",  "70"],
    # extra commas (too many fields — still parseable as valid, not invalid)
    # blank line will be skipped in processor
    ["JOB9009", "RECON",  "REPORT",  "",   "55"],
    ["JOB9010", "AUDIT",  "EXTRACT", "0",  ""],
    ["JOB9011", "BILLING","VALIDATE","8",   "not_a_number"],
    ["JOB9012", "PAYROLL","SORT",    "four","200"],
    ["JOB9013", "CLAIMS", "ARCHIVE", "-8",  "150"],
    ["JOB9014", "LEDGER", "REPORT",  "abc","100"],
    ["",        "",       "",        "",   ""],
    ["JOB9016", "ORDERS", "EXTRACT", "0",  "-1"],
    ["JOB9017", "DISPATCH","VALIDATE","",""],
    ["JOB9018", "RECON",  "SORT",    "12", "not_num"],
    ["JOB9019", "AUDIT",  "ARCHIVE", "abc","90"],
]

# Interleave invalid records at fixed positions
combined = rows[:]
for idx, inv in enumerate(invalid):
    pos = (idx + 1) * 30
    combined.insert(min(pos, len(combined)), inv)

with open("data/job_logs.csv", "w", newline="") as f:
    writer = csv.writer(f)
    writer.writerow(["job_id","job_name","step","return_code","duration_ms"])
    writer.writerows(combined)

print(f"Generated {len(combined)} records ({len(rows)} valid, {len(invalid)} invalid)")
