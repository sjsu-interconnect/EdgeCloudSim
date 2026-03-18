#!/usr/bin/env python3
import argparse
import json
import re
from pathlib import Path
from typing import Dict, Optional


def _extract_float(pattern: str, text: str) -> Optional[float]:
    m = re.search(pattern, text, re.MULTILINE)
    return float(m.group(1)) if m else None


def _extract_int(pattern: str, text: str) -> Optional[int]:
    m = re.search(pattern, text, re.MULTILINE)
    return int(m.group(1)) if m else None


def parse_log(text: str) -> Dict[str, Optional[float]]:
    metrics: Dict[str, Optional[float]] = {}

    metrics["total_dags_configured"] = _extract_int(r"Total DAGs configured:\s*(\d+)", text)
    if metrics["total_dags_configured"] is None:
        metrics["total_dags_configured"] = _extract_int(r"Total DAGs submitted:\s*(\d+)", text)

    metrics["total_dags_arrived"] = _extract_int(r"Total DAGs arrived \(DAG_SUBMIT processed\):\s*(\d+)", text)
    metrics["total_dags_scheduled"] = _extract_int(r"Total DAGs with >=1 task scheduled:\s*(\d+)", text)

    metrics["total_tasks"] = _extract_int(r"# of tasks \(Edge/Cloud/Mobile\):\s*(\d+)\(", text)
    metrics["completed_tasks"] = _extract_int(r"# of completed tasks \(Edge/Cloud/Mobile\):\s*(\d+)\(", text)
    metrics["failed_tasks"] = _extract_int(r"# of failed tasks \(Edge/Cloud/Mobile\):\s*(\d+)\(", text)

    metrics["failed_task_pct"] = _extract_float(r"percentage of failed tasks:\s*([\d.]+)%", text)
    metrics["avg_service_time_sec"] = _extract_float(r"average service time:\s*([\d.]+)\s*seconds", text)
    metrics["avg_processing_time_sec"] = _extract_float(r"average processing time:\s*([\d.]+)\s*seconds", text)
    metrics["avg_network_delay_sec"] = _extract_float(r"average network delay:\s*([\d.]+)\s*seconds", text)

    metrics["total_cost"] = _extract_float(r"Total scheduling cost for simulation window:\s*([\d.]+)\$", text)
    metrics["avg_cost_per_task"] = _extract_float(r"average cost \(per task\):\s*([\d.]+)\$", text)
    metrics["avg_cost_per_dag"] = _extract_float(r"average cost \(per DAG\):\s*([\d.]+)\$", text)

    metrics["avg_edge_util"] = _extract_float(
        r"average server utilization Edge/Cloud/Mobile:\s*([\d.]+)/[\d.]+/[\d.]+", text
    )
    metrics["avg_cloud_util"] = _extract_float(
        r"average server utilization Edge/Cloud/Mobile:\s*[\d.]+/([\d.]+)/[\d.]+", text
    )

    return metrics


def main() -> None:
    parser = argparse.ArgumentParser(description="Parse one EdgeCloudSim ite log into JSON metrics")
    parser.add_argument("--log", required=True, help="Path to iteX.log")
    parser.add_argument("--out", required=True, help="Output metrics json path")
    parser.add_argument("--run-id", required=False, default="", help="Optional run identifier")
    args = parser.parse_args()

    log_path = Path(args.log)
    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)

    text = log_path.read_text(errors="ignore")
    metrics = parse_log(text)
    metrics["run_id"] = args.run_id
    metrics["log_path"] = str(log_path)

    out_path.write_text(json.dumps(metrics, indent=2))


if __name__ == "__main__":
    main()
