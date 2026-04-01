#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path


def load_lines(path: Path) -> list[str]:
    return path.read_text().splitlines(keepends=True)


def apply_overrides(lines: list[str], overrides: dict[str, str]) -> list[str]:
    out = []
    seen = set()
    for line in lines:
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in line:
            out.append(line)
            continue
        key = line.split("=", 1)[0].strip()
        if key in overrides:
            out.append(f"{key}={overrides[key]}\n")
            seen.add(key)
        else:
            out.append(line)
    # append missing keys at end
    missing = [k for k in overrides.keys() if k not in seen]
    if missing:
        out.append("\n# Auto-added overrides\n")
        for k in missing:
            out.append(f"{k}={overrides[k]}\n")
    return out


def main() -> None:
    script_dir = Path(__file__).resolve().parent
    config_dir = script_dir / "config"
    base_props = config_dir / "DAG_APP.properties"
    if not base_props.exists():
        raise SystemExit(f"Base properties not found: {base_props}")

    repo_root = script_dir.parent.parent
    dags_dir = repo_root / "src" / "edu" / "boun" / "edgecloudsim" / "dagsim"

    workloads = [
        # Light: fewer arrivals, long enough sim window to admit most DAGs
        {
            "id": "W1_LIGHT_1K",
            "dag_file": dags_dir / "synthetic_dags_1k_container.json",
            "interarrival": "1.0",
            "simulation_time": "1200",
        },
        # Baseline: matches prior logs (mean 0.36s)
        {
            "id": "W2_BASE_1K",
            "dag_file": dags_dir / "synthetic_dags_1k_container.json",
            "interarrival": "0.36",
            "simulation_time": "600",
        },
        # Heavy: bursty arrivals
        {
            "id": "W3_HEAVY_1K",
            "dag_file": dags_dir / "synthetic_dags_1k_container.json",
            "interarrival": "0.10",
            "simulation_time": "600",
        },
        # Larger DAG pool, baseline arrivals
        {
            "id": "W4_BASE_10K",
            "dag_file": dags_dir / "synthetic_dags_10k_container.json",
            "interarrival": "0.36",
            "simulation_time": "4000",
        },
        # Larger DAG pool, heavier arrivals
        {
            "id": "W5_HEAVY_10K",
            "dag_file": dags_dir / "synthetic_dags_10k_container.json",
            "interarrival": "0.10",
            "simulation_time": "2000",
        },
    ]

    policies = [
        "REMOTE_RL",
        "GLOBAL_BEST_FIT",
    ]

    base_lines = load_lines(base_props)
    for w in workloads:
        for policy in policies:
            overrides = {
                "orchestrator_policies": policy,
                "dag_interarrival_rate": w["interarrival"],
                "dag_input_path": str(Path(w["dag_file"]).resolve()),
                "simulation_time": w["simulation_time"],
                # Keep runs reproducible and comparable per workload.
                "rng_seed": "42",
            }
            out_lines = apply_overrides(base_lines, overrides)
            out_name = f"DAG_APP_{w['id']}_{policy}.properties"
            out_path = config_dir / out_name
            out_path.write_text("".join(out_lines))
            print(f"Wrote {out_path}")

    # Emit a small CSV summary for paper/table use.
    summary_path = script_dir / "workload_matrix.csv"
    rows = ["workload_id,dag_file,interarrival_s,simulation_time_s"]
    for w in workloads:
        rows.append(
            f"{w['id']},{Path(w['dag_file']).name},{w['interarrival']},{w['simulation_time']}"
        )
    summary_path.write_text("\n".join(rows) + "\n")
    print(f"Wrote {summary_path}")


if __name__ == "__main__":
    main()
