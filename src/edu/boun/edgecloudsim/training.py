import argparse
import os
import numpy as np

from stable_baselines3.common.monitor import Monitor

from rl_environment import SchedulingEnvironment
from rl_agent import SchedulingAgent

MINMAX_UPPER_BOUND_INFLATION = 0.10


def get_normalization_mode(config_path, command_line_mode):
    if command_line_mode is not None:
        return command_line_mode.strip().lower()

    if config_path is None:
        return "minmax"

    with open(config_path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            if key.strip() == "rl_reward_normalization":
                return value.strip().lower()

    return "minmax"


def get_percentile(values, pct):
    ordered = sorted(values)
    k = (len(ordered) - 1) * (pct / 100.0)
    lo = int(k)
    hi = min(lo + 1, len(ordered) - 1)
    frac = k - lo
    return ordered[lo] * (1.0 - frac) + ordered[hi] * frac


def get_mean_and_standard_deviation(values):
    mean = sum(values) / len(values)
    variance = sum((value - mean) ** 2 for value in values) / len(values)
    return mean, variance ** 0.5


def inflate_upper_bound(low, high):
    return high + (high - low) * MINMAX_UPPER_BOUND_INFLATION


def choose_warmup_action(env):
    mask = np.asarray(env.action_masks(), dtype=bool)
    valid_actions = np.flatnonzero(mask)
    if len(valid_actions) == 0:
        return int(env.action_space.sample())
    return int(np.random.choice(valid_actions))


def collect_warmup_samples(env, warmup_steps):
    latencies = []
    costs = []
    done = False

    # Warm-up advances EdgeCloudSim with valid random actions, but PPO is not created yet.
    for _ in range(warmup_steps):
        action = choose_warmup_action(env)
        _, _, terminated, truncated, info = env.step(action)
        done = terminated or truncated

        if "actualLatency" in info:
            latencies.append(float(info["actualLatency"]))
        if "actualCost" in info:
            costs.append(float(info["actualCost"]))

        if done:
            print("Warm start ended because the simulation finished")
            break

    return latencies, costs, done


def minmax_warm_start(env, latencies, costs, low_pct, high_pct):
    # Freeze percentile bounds before PPO begins, so reward scaling is stable during training.
    latency_low = get_percentile(latencies, low_pct)
    latency_high = get_percentile(latencies, high_pct)
    cost_low = get_percentile(costs, low_pct)
    cost_high = get_percentile(costs, high_pct)

    if latency_high <= latency_low:
        latency_low = min(latencies)
        latency_high = max(latencies)
    if cost_high <= cost_low:
        cost_low = min(costs)
        cost_high = max(costs)

    raw_latency_high = latency_high
    raw_cost_high = cost_high
    latency_high = inflate_upper_bound(latency_low, latency_high)
    cost_high = inflate_upper_bound(cost_low, cost_high)

    env.set_reward_bounds(
        latency_low,
        latency_high,
        cost_low,
        cost_high,
        normalization="minmax",
    )

    print("Warm start reward normalization: minmax")
    print(f"  latency p{low_pct:g}/p{high_pct:g}: {latency_low:.6f} / {raw_latency_high:.6f}")
    print(f"  cost p{low_pct:g}/p{high_pct:g}: {cost_low:.12f} / {raw_cost_high:.12f}")
    print(f"  inflated latency high: {latency_high:.6f}")
    print(f"  inflated cost high: {cost_high:.12f}")


def zscore_warm_start(env, latencies, costs):
    latency_mean, latency_std = get_mean_and_standard_deviation(latencies)
    cost_mean, cost_std = get_mean_and_standard_deviation(costs)

    env.set_reward_bounds(
        min(latencies),
        max(latencies),
        min(costs),
        max(costs),
        normalization="zscore",
        latency_mean=latency_mean,
        latency_std=latency_std,
        cost_mean=cost_mean,
        cost_std=cost_std,
    )

    print("Warm start reward normalization: zscore")
    print(f"  latency mean/std: {latency_mean:.6f} / {latency_std:.6f}")
    print(f"  cost mean/std: {cost_mean:.12f} / {cost_std:.12f}")


def run_warm_start(env, warmup_steps, low_pct, high_pct, normalization):
    if warmup_steps <= 0:
        return False

    print(f"Warm start: collecting {warmup_steps} samples before PPO training")
    env.reset()

    latencies, costs, done = collect_warmup_samples(env, warmup_steps)

    if not latencies or not costs:
        print("Warm start did not collect latency/cost samples; PPO will use Java rewards")
        return done

    if normalization == "zscore":
        zscore_warm_start(env, latencies, costs)
    else:
        minmax_warm_start(env, latencies, costs, low_pct, high_pct)

    env.continue_from_current_state_on_next_reset = not done
    return done


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", type=str, default=None)
    parser.add_argument("--total-timesteps", type=int, default=1000000)
    parser.add_argument("--warmup-steps", type=int, default=0)
    parser.add_argument("--warmup-low-pct", type=float, default=0.0)
    parser.add_argument("--warmup-high-pct", type=float, default=99.0)
    parser.add_argument(
        "--reward-normalization",
        "--warmup-normalization",
        dest="reward_normalization",
        default=None,
    )
    args = parser.parse_args()

    reward_normalization = get_normalization_mode(args.config, args.reward_normalization)

    print("Training start, make sure redis server and edgecloudsim server are active")
    if args.config is not None:
        print(f"Training config: {args.config}")
    print(f"Reward normalization mode: {reward_normalization}")

    raw_env = SchedulingEnvironment()
    warmup_done = run_warm_start(
        raw_env,
        args.warmup_steps,
        args.warmup_low_pct,
        args.warmup_high_pct,
        reward_normalization,
    )
    if warmup_done:
        print("Simulation finished during warm start; skipping PPO training")
        os._exit(0)

    # Monitor wraps the same environment after warm-up, then PPO starts from the current state.
    env = Monitor(raw_env)
    agent = SchedulingAgent(env)

    try:
        print("Start training")
        agent.train(total_timesteps=args.total_timesteps)
    except RuntimeError as e:
        print(f"Training ended (simulation finished): {e}")
    except Exception as e:
        print(f"Training exception: {e}")
        raise
    finally:
        print("Training finished, saving model")
        agent.save("ppo_model")
        print("Shutting down")
        os._exit(0)

if __name__ == "__main__":
    main()
