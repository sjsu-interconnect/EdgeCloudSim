#!/usr/bin/env bash
set -euo pipefail

# Run one config for N iterations in a single config folder,
# parse each log asynchronously, then generate aggregate summary.
#
# Usage:
# ./run_config_batch.sh <config_name> <edge_devices_file> <applications_file> [num_runs] [policy] [dag_scheduler_dir]
# Example:
# ./run_config_batch.sh DAG_APP edge_ai_devices.xml applications_dag.xml 10

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

CONFIG_NAME="${1:-DAG_APP}"
EDGE_DEVICES_FILE="${2:-edge_ai_devices.xml}"
APPLICATIONS_FILE="${3:-applications_dag.xml}"
NUM_RUNS="${4:-10}"
POLICY="${5:-}"
DAG_SCHEDULER_DIR="${6:-}"

if ! [[ "${NUM_RUNS}" =~ ^[0-9]+$ ]] || [[ "${NUM_RUNS}" -lt 1 ]]; then
  echo "NUM_RUNS must be a positive integer"
  exit 1
fi

start_rl_server() {
  if [[ -z "${DAG_SCHEDULER_DIR}" ]]; then
    echo "DAG_SCHEDULER_DIR is required to start RL server for REMOTE_RL policy"
    exit 1
  fi

  local uvicorn_bin="${DAG_SCHEDULER_DIR}/.venv-rl/bin/uvicorn"
  local conda_env="${RL_CONDA_ENV:-}"
  local conda_sh="${CONDA_SH:-}"
  local use_conda=0

  if [[ -n "${conda_env}" ]]; then
    use_conda=1
    if [[ -z "${conda_sh}" ]]; then
      if [[ -n "${CONDA_EXE:-}" ]]; then
        conda_sh="$(dirname "${CONDA_EXE}")/../etc/profile.d/conda.sh"
      else
        conda_sh="$(conda info --base 2>/dev/null)/etc/profile.d/conda.sh"
      fi
    fi
    if [[ -z "${conda_sh}" || ! -f "${conda_sh}" ]]; then
      echo "CONDA_SH not found. Set CONDA_SH to your conda.sh path or ensure 'conda info --base' works."
      exit 1
    fi
  fi

  if [[ "${use_conda}" -eq 0 ]]; then
    if [[ ! -x "${uvicorn_bin}" ]]; then
      uvicorn_bin="$(command -v uvicorn || true)"
    fi
    if [[ -z "${uvicorn_bin}" ]]; then
      echo "uvicorn not found. Set RL_CONDA_ENV to use conda or install venv at ${DAG_SCHEDULER_DIR}/.venv-rl"
      exit 1
    fi
  fi

  # Ensure checkpoint/reward paths are unique per run
  export RL_CHECKPOINT_DIR="${CONFIG_ROOT}/checkpoints"
  export RL_CHECKPOINT_FILENAME="ppo_${RUN_TAG}.pt"
  export RL_REWARD_PLOT_PATH="${CONFIG_ROOT}/reward_curve_${RUN_TAG}.png"
  export RL_REWARD_CSV_PATH="${CONFIG_ROOT}/rewards_${RUN_TAG}.csv"

  if [[ "${use_conda}" -eq 1 ]]; then
    (
      cd "${DAG_SCHEDULER_DIR}"
      bash -lc "source \"${conda_sh}\" && conda activate \"${conda_env}\" && uvicorn rl_service.app.main:app --host 127.0.0.1 --port 8009"
    ) > "${CONFIG_ROOT}/rl_server_${RUN_TAG}.log" 2>&1 &
  else
    (
      cd "${DAG_SCHEDULER_DIR}"
      "${uvicorn_bin}" rl_service.app.main:app --host 127.0.0.1 --port 8009
    ) > "${CONFIG_ROOT}/rl_server_${RUN_TAG}.log" 2>&1 &
  fi
  RL_SERVER_PID=$!

  # Simple readiness loop (max 20s)
  for _ in $(seq 1 40); do
    if curl -s "http://127.0.0.1:8009/health" >/dev/null 2>&1; then
      return 0
    fi
    sleep 0.5
  done

  echo "RL server failed to start (check ${CONFIG_ROOT}/rl_server_${RUN_TAG}.log)"
  return 1
}

stop_rl_server() {
  if [[ -n "${RL_SERVER_PID:-}" ]]; then
    # Export reward artifacts before shutdown
    curl -s -X POST "http://127.0.0.1:8009/export_rewards" >/dev/null 2>&1 || true
    kill "${RL_SERVER_PID}" >/dev/null 2>&1 || true
    wait "${RL_SERVER_PID}" >/dev/null 2>&1 || true
    unset RL_SERVER_PID
  fi
}

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
  if [[ "${POLICY}" == "REMOTE_RL" ]]; then
    start_rl_server
  fi
  "${SCRIPT_DIR}/runner.sh" "${BATCH_ROOT}" "${CONFIG_NAME}" "${EDGE_DEVICES_FILE}" "${APPLICATIONS_FILE}" "${i}"
  if [[ "${POLICY}" == "REMOTE_RL" ]]; then
    stop_rl_server
  fi

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
