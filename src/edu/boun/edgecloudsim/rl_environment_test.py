# from gymnasium.utils.env_checker import check_env
# from rl_environment import SchedulingEnvironment

# env = SchedulingEnvironment(num_edge_dc=8, num_cloud_dc=1)

# check_env(env)

# obv, info = env.reset()
# print(obv.shape)
# print("observation: ", obv)
# print("info: ", info)

# for step in range(5):
#     action = env.action_space.sample()
#     obs, reward, terminated, truncated, info = env.step(action)
#     print(f"step {step}: action={action}, reward={reward:.3f}, terminated={terminated}")


# env.close()
