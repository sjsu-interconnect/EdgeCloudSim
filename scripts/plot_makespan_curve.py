#!/usr/bin/env python3
import argparse
import re
from pathlib import Path

import matplotlib.pyplot as plt


MAKESPAN_RE = re.compile(r"Makespan:\s+([0-9.]+)\s+ms")


def extract_makespans(log_path: Path) -> list[float]:
    makespans: list[float] = []
    with log_path.open("r", errors="ignore") as f:
        for line in f:
            if "DAG complete" not in line:
                continue
            m = MAKESPAN_RE.search(line)
            if m:
                makespans.append(float(m.group(1)))
    return makespans


def moving_avg(values: list[float], window: int) -> list[float]:
    if window <= 1 or len(values) <= 1:
        return values[:]
    out = []
    acc = 0.0
    for i, v in enumerate(values):
        acc += v
        if i >= window:
            acc -= values[i - window]
        denom = min(i + 1, window)
        out.append(acc / denom)
    return out


def main() -> int:
    parser = argparse.ArgumentParser(description="Plot DAG makespan curves from EdgeCloudSim logs.")
    parser.add_argument("--log", action="append", required=True, help="Path to ite*.log (repeatable)")
    parser.add_argument("--label", action="append", required=True, help="Label for each log (repeatable)")
    parser.add_argument("--window", type=int, default=50, help="Moving average window")
    parser.add_argument("--out", default="makespan_curve.png", help="Output PNG path")
    args = parser.parse_args()

    if len(args.log) != len(args.label):
        raise SystemExit("--log and --label must be provided the same number of times")

    plt.figure(figsize=(10, 6))
    any_data = False
    for log_str, label in zip(args.log, args.label):
        path = Path(log_str)
        if not path.exists():
            raise SystemExit(f"log not found: {path}")
        makespans = extract_makespans(path)
        if not makespans:
            print(f"WARNING: no DAG complete lines found in {path}")
            continue
        any_data = True
        x = list(range(1, len(makespans) + 1))
        smooth = moving_avg(makespans, args.window)
        plt.plot(x, smooth, label=f"{label} (MA{args.window})", linewidth=2.0)

    if not any_data:
        raise SystemExit("No makespans found in any logs.")

    plt.xlabel("DAG Episode")
    plt.ylabel("Makespan (ms)")
    plt.title("DAG Makespan Curves")
    plt.legend()
    plt.tight_layout()
    plt.savefig(args.out, dpi=150)
    print(f"Wrote {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
