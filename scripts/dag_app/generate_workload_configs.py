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

    # Generate edge XML variants first
    from subprocess import run
    run(["python3", str(script_dir / "generate_edge_device_variants.py")], check=True)

    repo_root = script_dir.parent.parent
    dags_dir = repo_root / "src" / "edu" / "boun" / "edgecloudsim" / "dagsim"

    workloads = [
        # 1K DAGs only
        {
            "id": "AMPLE_1K",
            "dag_file": dags_dir / "synthetic_dags_1k_container.json",
            "interarrival": "1.0",
            "simulation_time_min": "60",
        },
        {
            "id": "NORMAL_1K",
            "dag_file": dags_dir / "synthetic_dags_1k_container.json",
            "interarrival": "0.36",
            "simulation_time_min": "30",
        },
        {
            "id": "PEAK_1K",
            "dag_file": dags_dir / "synthetic_dags_1k_container.json",
            "interarrival": "0.10",
            "simulation_time_min": "30",
        },
    ]

    policies = [
        "REMOTE_RL",
        "GLOBAL_BEST_FIT",
        "EDGE_FIRST_GLOBAL",
    ]

    capacities = [
        # capacity_id, edge_xml, cloud_scale
        ("BALANCED", "edge_ai_devices_scaled.xml", 1.0),
        ("EDGE_EXCESS", "edge_ai_devices_edge_excess.xml", 1.0),
        ("CLOUD_EXCESS", "edge_ai_devices_scaled.xml", 2.0),
    ]

    vm_types = [
        ("HOMO", None),  # use edge_xml as-is
        ("HETERO", {
            "edge_ai_devices_scaled.xml": "edge_ai_devices_scaled_hetero.xml",
            "edge_ai_devices_edge_excess.xml": "edge_ai_devices_edge_excess_hetero.xml",
        }),
    ]

    base_lines = load_lines(base_props)
    matrix_rows = ["scenario,edge_devices_file,applications_file,policy,workload,capacity,vm_type"]

    for w in workloads:
        for cap_id, edge_xml, cloud_scale in capacities:
            for vm_type_id, hetero_map in vm_types:
                edge_xml_resolved = edge_xml
                if hetero_map and edge_xml in hetero_map:
                    edge_xml_resolved = hetero_map[edge_xml]
                for policy in policies:
                    overrides = {
                        "orchestrator_policies": policy,
                        "dag_interarrival_rate": w["interarrival"],
                        "dag_input_path": str(Path(w["dag_file"]).resolve()),
                        "simulation_time": w["simulation_time_min"],
                        # Keep runs reproducible and comparable per workload.
                        "rng_seed": "42",
                    }

                    if cloud_scale != 1.0:
                        # Scale cloud capacity for CLOUD_EXCESS
                        overrides.update({
                            "number_of_vm_on_cloud_host": str(int(round(4 * cloud_scale))),
                            "core_for_cloud_vm": str(int(round(4 * cloud_scale))),
                            "mips_for_cloud_vm": str(int(round(10000 * cloud_scale))),
                            "ram_for_cloud_vm": str(int(round(32000 * cloud_scale))),
                        })

                    out_lines = apply_overrides(base_lines, overrides)
                    scenario_name = f"DAG_APP_{w['id']}_{cap_id}_{vm_type_id}_{policy}"
                    out_path = config_dir / f"{scenario_name}.properties"
                    out_path.write_text("".join(out_lines))
                    print(f"Wrote {out_path}")
                    matrix_rows.append(
                        f"{scenario_name},{edge_xml_resolved},applications_dag_stable_diffusion.xml,{policy},{w['id']},{cap_id},{vm_type_id}"
                    )

    summary_path = script_dir / "workload_matrix.csv"
    summary_path.write_text("\n".join(matrix_rows) + "\n")
    print(f"Wrote {summary_path}")


if __name__ == "__main__":
    main()
