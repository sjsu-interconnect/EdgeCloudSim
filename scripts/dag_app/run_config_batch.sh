#!/usr/bin/env bash
set -euo pipefail

# Run one config for N iterations in a single config folder,
# parse each log asynchronously, then generate aggregate summary.
#
# Usage:
# ./run_config_batch.sh <config_name> <edge_devices_file> <applications_file> [num_runs]
# Example:
# ./run_config_batch.sh DAG_APP edge_ai_devices.xml applications_dag.xml 10

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

CONFIG_NAME="${1:-DAG_APP}"
EDGE_DEVICES_FILE="${2:-edge_ai_devices.xml}"
APPLICATIONS_FILE="${3:-applications_dag.xml}"
NUM_RUNS="${4:-10}"

if ! [[ "${NUM_RUNS}" =~ ^[0-9]+$ ]] || [[ "${NUM_RUNS}" -lt 1 ]]; then
  echo "NUM_RUNS must be a positive integer"
  exit 1
fi

STAMP="$(date +%Y%m%d_%H%M%S)"
BATCH_ROOT="${REPO_ROOT}/scripts/output/batch_${CONFIG_NAME}_${STAMP}"
mkdir -p "${BATCH_ROOT}"
CONFIG_ROOT="${BATCH_ROOT}/${CONFIG_NAME}"
mkdir -p "${CONFIG_ROOT}"

echo "Batch root: ${BATCH_ROOT}"

echo "Compiling once before runs..."
"${SCRIPT_DIR}/compile.sh"

PARSER="${SCRIPT_DIR}/parse_iteration_log.py"
PARSER_JOBS=()

for i in $(seq 1 "${NUM_RUNS}"); do
  RUN_TAG="ite$(printf '%02d' "${i}")"

  echo "[${RUN_TAG}] starting simulation"
  "${SCRIPT_DIR}/runner.sh" "${BATCH_ROOT}" "${CONFIG_NAME}" "${EDGE_DEVICES_FILE}" "${APPLICATIONS_FILE}" "${i}"

  LOG_PATH="${CONFIG_ROOT}/ite${i}.log"
  METRIC_JSON="${CONFIG_ROOT}/metrics_ite${i}.json"

  if [[ -f "${LOG_PATH}" ]]; then
    echo "[${RUN_TAG}] parsing log asynchronously"
    python3 "${PARSER}" --log "${LOG_PATH}" --out "${METRIC_JSON}" --run-id "${RUN_TAG}" &
    PARSER_JOBS+=("$!")
  else
    echo "[${RUN_TAG}] WARNING: log not found at ${LOG_PATH}"
  fi
done

echo "Waiting for async parsers to finish..."
for pid in "${PARSER_JOBS[@]:-}"; do
  wait "${pid}"
done

SUMMARY_CSV="${BATCH_ROOT}/summary_runs.csv"
SUMMARY_JSON="${BATCH_ROOT}/summary_aggregate.json"

python3 - "${BATCH_ROOT}" "${CONFIG_NAME}" "${SUMMARY_CSV}" "${SUMMARY_JSON}" <<'PY'
import csv
import json
import sys
from pathlib import Path

batch_root = Path(sys.argv[1])
config_name = sys.argv[2]
summary_csv = Path(sys.argv[3])
summary_json = Path(sys.argv[4])

metric_files = sorted(batch_root.glob(f"run_*/{config_name}/metrics_ite*.json"))
if not metric_files:
    metric_files = sorted((batch_root / config_name).glob("metrics_ite*.json"))
rows = []
for mf in metric_files:
    with mf.open() as f:
        rows.append(json.load(f))

if not rows:
    raise SystemExit("No metrics json files found. Nothing to summarize.")

# per-run CSV
keys = sorted({k for r in rows for k in r.keys()})
with summary_csv.open("w", newline="") as f:
    w = csv.DictWriter(f, fieldnames=keys)
    w.writeheader()
    for r in rows:
        w.writerow(r)

# aggregate JSON (mean over numeric fields)
agg = {"num_runs": len(rows), "means": {}, "min": {}, "max": {}}
for k in keys:
    vals = [r.get(k) for r in rows if isinstance(r.get(k), (int, float))]
    if vals:
        agg["means"][k] = sum(vals) / len(vals)
        agg["min"][k] = min(vals)
        agg["max"][k] = max(vals)

with summary_json.open("w") as f:
    json.dump(agg, f, indent=2)

print(f"Wrote per-run summary CSV: {summary_csv}")
print(f"Wrote aggregate summary JSON: {summary_json}")
PY

echo "Done. Batch outputs at: ${BATCH_ROOT}"
