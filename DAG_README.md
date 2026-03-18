# DAG Simulation in EdgeCloudSim

## Quick Start

### 1. Set DAG Input Path
Edit `scripts/dag_app/config/default_config.properties`:

```properties
dag_input_path=/ABSOLUTE/PATH/TO/synthetic_dags_1k.json
```

Important:
- `synthetic_dags_1k.json` means **1000 DAG requests**.
- It does **not** mean a single DAG with 1000 task nodes.

### 2. Compile
```bash
cd scripts/dag_app
./compile.sh
```

### 3. Run
```bash
./runner.sh ../../scripts/output DAG_APP edge_ai_devices.xml applications_dag.xml 1
```

### 4. Verify
Check `ite*.log` for lines like:
- `Loaded 1000 DAG(s) for simulation from ...synthetic_dags_1k.json`

## Input Behavior

DAG loader now supports both:
- a single JSON file (single DAG object or `{ "dags": [...] }` container)
- a directory containing `*.json` DAG files

Resolution order:
1. `dag_input_path` from properties (recommended)
2. legacy fallback directory search (if property is empty)

## Core Config

In `scripts/dag_app/config/default_config.properties`:

```properties
orchestrator_policies=REMOTE_RL
rl_service_url=http://127.0.0.1:8000

# DAG source
dag_input_path=/ABSOLUTE/PATH/TO/synthetic_dags_1k.json

# Arrival process (applies after loading DAGs)
dag_interarrival_rate=30
```

## Notes

- If your DAG JSON has 1000 entries in `dags`, simulator ingests 1000 DAGs.
- Total task count depends on tasks per DAG (often much larger than 1000).
- Use `applications_dag.xml` so task types like `sampler`, `unet_denoise`, `vae_encode` are recognized.

## Troubleshooting

### Repeated "Task type ... not found"
Run with:
```bash
./runner.sh ../../scripts/output DAG_APP edge_ai_devices.xml applications_dag.xml 1
```

### No DAGs loaded
- Verify `dag_input_path` is absolute and file exists.
- Verify JSON root is either one DAG object or `{ "dags": [...] }`.

### Remote policy not active
- Set `orchestrator_policies=REMOTE_RL`
- Start RL service before running simulation.
