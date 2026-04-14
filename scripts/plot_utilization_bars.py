#!/usr/bin/env python3
import argparse
import json
import re
from pathlib import Path

import matplotlib.pyplot as plt


POLICY_RE = re.compile(r"Policy:\s+([A-Z0-9_]+)")


def detect_policy(log_path: Path) -> str:
    with log_path.open("r", errors="ignore") as f:
        for line in f:
            if "Policy:" in line:
                m = POLICY_RE.search(line)
                if m:
                    return m.group(1)
                break
    return "UNKNOWN"


def load_metrics(metrics_path: Path) -> dict:
    with metrics_path.open("r") as f:
        return json.load(f)


def main() -> int:
    parser = argparse.ArgumentParser(description="Plot edge/cloud utilization bars per policy.")
    parser.add_argument("--root", required=True, help="Root output folder containing batch_* runs")
    parser.add_argument("--out", default="utilization_bars.png", help="Output PNG path")
    parser.add_argument("--cap100", action="store_true", help="Cap utilization values at 100%%")
    args = parser.parse_args()

    root = Path(args.root)
    if not root.exists():
        raise SystemExit(f"root not found: {root}")

    entries = []
    for batch in sorted(root.glob("batch_*")):
        # Find first matching log/metrics under the batch directory
        log_paths = sorted(batch.glob("*/ite1.log"))
        metrics_paths = sorted(batch.glob("*/metrics_ite1.json"))
        if not log_paths or not metrics_paths:
            continue
        log_path = log_paths[0]
        metrics_path = metrics_paths[0]

        policy = detect_policy(log_path)
        metrics = load_metrics(metrics_path)
        edge_util = metrics.get("avg_edge_util")
        cloud_util = metrics.get("avg_cloud_util")
        if edge_util is None or cloud_util is None:
            continue
        entries.append((policy, edge_util, cloud_util))

    if not entries:
        raise SystemExit("No utilization metrics found.")

    # Sort by policy name for stable ordering
    entries.sort(key=lambda x: x[0])
    policies = [e[0] for e in entries]
    edge_vals = [e[1] for e in entries]
    cloud_vals = [e[2] for e in entries]

    if args.cap100:
        edge_vals = [min(100.0, v) for v in edge_vals]
        cloud_vals = [min(100.0, v) for v in cloud_vals]

    x = list(range(len(policies)))
    width = 0.38

    plt.figure(figsize=(10, 6))
    plt.bar([i - width / 2 for i in x], edge_vals, width=width, label="Edge Util (%)")
    plt.bar([i + width / 2 for i in x], cloud_vals, width=width, label="Cloud Util (%)")

    plt.xticks(x, policies, rotation=20, ha="right")
    ylabel = "Average Utilization (%)"
    title = "Average Edge vs Cloud Utilization by Policy"
    if args.cap100:
        ylabel = "Average Utilization (%), capped at 100"
        title = "Average Edge vs Cloud Utilization by Policy (Capped at 100%)"
    plt.ylabel(ylabel)
    plt.title(title)
    plt.legend()
    plt.tight_layout()
    plt.savefig(args.out, dpi=150)
    print(f"Wrote {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
