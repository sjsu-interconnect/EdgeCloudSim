#!/usr/bin/env python3
import argparse
import json
from pathlib import Path
import random


def load_dags(path: Path):
    with path.open() as f:
        data = json.load(f)
    if isinstance(data, dict) and "dags" in data and isinstance(data["dags"], list):
        return data["dags"]
    if isinstance(data, list):
        return data
    # single DAG object
    return [data]


def main():
    parser = argparse.ArgumentParser(description="Create a 5K DAG container from a larger JSON container.")
    parser.add_argument("--input", required=True, help="Path to source DAG JSON (container or list).")
    parser.add_argument("--output", required=True, help="Path to write 5K container JSON.")
    parser.add_argument("--count", type=int, default=5000, help="Number of DAGs to include (default: 5000).")
    parser.add_argument("--seed", type=int, default=42, help="Random seed for reproducibility.")
    parser.add_argument("--shuffle", action="store_true", help="Shuffle DAGs before slicing.")
    args = parser.parse_args()

    src = Path(args.input).expanduser()
    dst = Path(args.output).expanduser()
    dags = load_dags(src)
    if not dags:
        raise SystemExit(f"No DAGs found in {src}")

    if args.shuffle:
        random.seed(args.seed)
        random.shuffle(dags)

    subset = dags[: args.count]
    dst.parent.mkdir(parents=True, exist_ok=True)
    with dst.open("w") as f:
        json.dump({"dags": subset}, f)

    print(f"Wrote {len(subset)} DAGs to {dst}")


if __name__ == "__main__":
    main()
