import os
import json
import time
import subprocess
import gc
import importlib.util
import numpy as np
from collections import deque
from ai_models import PPONetwork
from game_simulator import GameSimulator, PASS_ACTION, MAX_ACTIONS_PER_TURN

_numba_spec = importlib.util.find_spec("numba")
if _numba_spec is not None:
    njit = importlib.import_module("numba").njit
else:
    def njit(*args, **kwargs):
        def _decorator(func):
            return func
        return _decorator


@njit(cache=True)
def _build_returns_advantages_numba(rewards, values, dones, gamma, gae_lambda):
    n = rewards.shape[0]
    returns = np.zeros(n, dtype=np.float32)
    advantages = np.zeros(n, dtype=np.float32)
    last_gae = 0.0
    next_value = 0.0
    for t in range(n - 1, -1, -1):
        mask = 1.0 - dones[t]
        delta = rewards[t] + gamma * next_value * mask - values[t]
        last_gae = delta + gamma * gae_lambda * mask * last_gae
        advantages[t] = last_gae
        returns[t] = advantages[t] + values[t]
        next_value = values[t]
    return returns, advantages


class AITrainer:
    def __init__(self, model_path, state_size=333, action_size=129, learning_rate=0.0003):
        self.model_path = model_path
        self.state_size = state_size
        self.action_size = action_size
        self.base_lr = learning_rate

        self.network = PPONetwork(
            state_size,
            action_size,
            learning_rate=3e-4,
            clip_epsilon=0.2,
            entropy_coef=0.01,
            value_coef=0.5,
            target_kl=0.02,
        )
        self.base_lr = 3e-4
        self.min_lr = 1e-5
        self.base_entropy_coef = 0.01
        self.min_entropy_coef = 0.001
        self.entropy_decay = 0.9997
        self.training_step = 0

        self.eval_batch = 1000
        self.eval_total = 1000
        self.eval_progress_log_interval = 10
        self.replace_rate = 0.55
        self.eval_interval = 100
        self.eval_games = 0
        self.eval_wins = 0

        self.best_opponent_ratio = 0.2
        self.previous_opponent_ratio = 0.5
        self.random_old_opponent_ratio = 0.3

        self.best_eval_winrate = -1.0

        self.push_interval = 10
        self.checkpoint_interval = 100

        self.best_model_path = os.path.join(os.path.dirname(self.model_path), "best_model.json")
        self.prev_model_paths = {
            100: os.path.join(os.path.dirname(self.model_path), "model_prev_100.json"),
            200: os.path.join(os.path.dirname(self.model_path), "model_prev_200.json"),
            300: os.path.join(os.path.dirname(self.model_path), "model_prev_300.json"),
            400: os.path.join(os.path.dirname(self.model_path), "model_prev_400.json"),
            500: os.path.join(os.path.dirname(self.model_path), "model_prev_500.json"),
        }

        self.total_trained_episodes = 0

        self.gamma = 0.98
        self.gae_lambda = 0.95
        self.ppo_epochs = 4
        self.minibatch_size = 64

        self.load_model(self.model_path)
        self.best_network = self._clone_network(self.network)
        self.previous_network = self._clone_network(self.network)
        self.historical_networks = deque(maxlen=10)
        self._bootstrap_fixed_policy_files()
        self._refresh_fixed_networks_from_files()

    def _clone_network(self, src_network):
        cloned = PPONetwork(
            self.state_size,
            self.action_size,
            learning_rate=src_network.learning_rate,
            clip_epsilon=src_network.clip_epsilon,
            entropy_coef=src_network.entropy_coef,
            value_coef=src_network.value_coef,
        )
        cloned.load_state_dict(src_network.state_dict(), strict=True)
        return cloned

    def load_model(self, path):
        if os.path.exists(path):
            try:
                with open(path, 'r') as f:
                    checkpoint = json.load(f)

                if isinstance(checkpoint, dict):
                    if "policy_state_dict" in checkpoint:
                        self.network.load_state_dict(checkpoint["policy_state_dict"], strict=True)
                    elif "state_dict" in checkpoint:
                        self.network.load_state_dict(checkpoint["state_dict"], strict=True)
                    elif "net.0.weight" in checkpoint:
                        self.network.load_state_dict(checkpoint, strict=True)

                    if hasattr(self.network, 'w3'):
                        self.action_size = self.network.w3.shape[0]

                    loaded_total = checkpoint.get("total_trained_episodes")
                    if loaded_total is None:
                        loaded_total = checkpoint.get("training_episodes")
                    if isinstance(loaded_total, (int, float)):
                        self.total_trained_episodes = int(loaded_total)

                    print(f"[AITrainer] PPO model loaded from {path}", flush=True)
            except Exception as e:
                print(f"[AITrainer] Failed to load model: {e}", flush=True)

    def save_model(self, path):
        try:
            os.makedirs(os.path.dirname(path), exist_ok=True)
            checkpoint = {
                "algorithm": "PPO",
                "policy_state_dict": self.network.state_dict(),
                "state_dict": self.network.state_dict(),
                "timestamp": time.time(),
                "total_trained_episodes": int(self.total_trained_episodes)
            }
            with open(path, 'w') as f:
                json.dump(checkpoint, f)
            print(f"[AITrainer] PPO model saved to {path}", flush=True)
        except Exception as e:
            print(f"[AITrainer] Failed to save model: {e}", flush=True)

    def save_network_model(self, network, path, trained_episodes=None):
        try:
            os.makedirs(os.path.dirname(path), exist_ok=True)
            if trained_episodes is None:
                trained_episodes = int(self.total_trained_episodes)
            checkpoint = {
                "algorithm": "PPO",
                "policy_state_dict": network.state_dict(),
                "state_dict": network.state_dict(),
                "timestamp": time.time(),
                "total_trained_episodes": int(trained_episodes)
            }
            with open(path, 'w') as f:
                json.dump(checkpoint, f)
            print(f"[AITrainer] PPO network snapshot saved to {path}", flush=True)
        except Exception as e:
            print(f"[AITrainer] Failed to save network snapshot: {e}", flush=True)

    def _load_network_model(self, path, fallback_network):
        loaded = self._clone_network(fallback_network)
        if not os.path.exists(path):
            return loaded
        try:
            with open(path, "r") as f:
                checkpoint = json.load(f)
            if isinstance(checkpoint, dict):
                if "policy_state_dict" in checkpoint:
                    loaded.load_state_dict(checkpoint["policy_state_dict"], strict=True)
                elif "state_dict" in checkpoint:
                    loaded.load_state_dict(checkpoint["state_dict"], strict=True)
                elif "net.0.weight" in checkpoint:
                    loaded.load_state_dict(checkpoint, strict=True)
        except Exception as e:
            print(f"[AITrainer] Failed to load network snapshot from {path}: {e}", flush=True)
        return loaded

    def _bootstrap_fixed_policy_files(self):
        if not os.path.exists(self.best_model_path):
            self.save_network_model(self.network, self.best_model_path)
        if not os.path.exists(self.prev_model_paths[100]):
            initial_prev_total = max(0, int(self.total_trained_episodes) - 100)
            self.save_network_model(self.network, self.prev_model_paths[100], trained_episodes=initial_prev_total)

    def _refresh_fixed_networks_from_files(self):
        self.best_network = self._load_network_model(self.best_model_path, self.network)
        self.previous_network = self._load_network_model(self.prev_model_paths[100], self.network)

        self.historical_networks = deque(maxlen=10)
        self.historical_networks.append(self.previous_network)
        for delta in [200, 300, 400, 500]:
            p = self.prev_model_paths[delta]
            if os.path.exists(p):
                self.historical_networks.append(self._load_network_model(p, self.previous_network))

    def _rotate_previous_model_files(self):
        for src_delta, dst_delta in [(400, 500), (300, 400), (200, 300), (100, 200)]:
            src = self.prev_model_paths[src_delta]
            dst = self.prev_model_paths[dst_delta]
            if os.path.exists(src):
                with open(src, "r") as f:
                    data = json.load(f)
                with open(dst, "w") as f:
                    json.dump(data, f)

    def _action_to_index(self, action):
        if action == PASS_ACTION:
            return 0
        hi, tile = action
        return 1 + (hi * 64) + tile

    def select_action(self, state, valid_actions, mode='train', network_to_use=None):
        if network_to_use is None:
            network_to_use = self.network
        logits, values = network_to_use.forward(np.array(state, dtype=np.float32))
        logits = logits[0]
        value = float(values[0])

        valid_indices = np.array([self._action_to_index(a) for a in valid_actions], dtype=np.int32)
        valid_mask = valid_indices < self.action_size

        action_mask = np.zeros(self.action_size, dtype=np.float32)
        if np.any(valid_mask):
            action_mask[valid_indices[valid_mask]] = 1.0
        else:
            action_mask[0] = 1.0

        masked_logits = np.where(action_mask > 0.5, logits, -1e10)
        max_logit = np.max(masked_logits)
        exp_logits = np.exp(masked_logits - max_logit)
        probs = exp_logits / (np.sum(exp_logits) + 1e-8)

        if mode == 'play':
            action_index = int(np.argmax(probs))
        else:
            action_index = int(np.random.choice(np.arange(self.action_size), p=probs))

        valid_idx_to_action = {self._action_to_index(a): a for a in valid_actions}
        action = valid_idx_to_action.get(action_index, PASS_ACTION)
        log_prob = float(np.log(probs[action_index] + 1e-8))
        return action, action_index, log_prob, value, action_mask

    def _build_returns_advantages(self, rewards, values, dones):
        return _build_returns_advantages_numba(
            rewards.astype(np.float32),
            values.astype(np.float32),
            dones.astype(np.float32),
            np.float32(self.gamma),
            np.float32(self.gae_lambda),
        )

    def train_on_episode(self, current_episode, total_episodes, trajectories):
        if not trajectories:
            return {"loss": 0.0, "approx_kl": 0.0, "early_stop": False}

        self.training_step += 1
        step = max(1, self.training_step)
        self.network.learning_rate = max(self.min_lr, self.base_lr / np.sqrt(step))
        decayed_entropy = self.base_entropy_coef * (self.entropy_decay ** step)
        self.network.entropy_coef = max(self.min_entropy_coef, decayed_entropy)

        states = []
        actions = []
        old_log_probs = []
        action_masks = []
        returns = []
        advantages = []

        for traj in trajectories:
            r, a = self._build_returns_advantages(
                np.array(traj["rewards"], dtype=np.float32),
                np.array(traj["values"], dtype=np.float32),
                np.array(traj["dones"], dtype=np.float32),
            )
            states.extend(traj["states"])
            actions.extend(traj["actions"])
            old_log_probs.extend(traj["log_probs"])
            action_masks.extend(traj["action_masks"])
            returns.extend(r.tolist())
            advantages.extend(a.tolist())

        states = np.array(states, dtype=np.float32)
        actions = np.array(actions, dtype=np.int32)
        old_log_probs = np.array(old_log_probs, dtype=np.float32)
        action_masks = np.array(action_masks, dtype=np.float32)
        returns = np.array(returns, dtype=np.float32)
        advantages = np.array(advantages, dtype=np.float32)

        adv_mean = np.mean(advantages)
        adv_std = np.std(advantages) + 1e-8
        advantages = (advantages - adv_mean) / adv_std

        update_stats = self.network.update_ppo(
            states,
            actions,
            old_log_probs,
            action_masks,
            returns,
            advantages,
            epochs=self.ppo_epochs,
            minibatch_size=self.minibatch_size,
        )
        return update_stats

    def _choose_enemy_network(self):
        r = np.random.rand()
        if r < self.best_opponent_ratio:
            return self.best_network
        if r < (self.best_opponent_ratio + self.previous_opponent_ratio):
            return self.previous_network

        old_candidates = list(self.historical_networks)[:-1]
        if old_candidates:
            return old_candidates[np.random.randint(len(old_candidates))]

        if self.previous_network is not None:
            return self.previous_network
        return self.best_network

    def _save_relative_history_models(self, previous_fixed_network):
        self._rotate_previous_model_files()
        prev_100_total = max(0, int(self.total_trained_episodes) - 100)
        self.save_network_model(previous_fixed_network, self.prev_model_paths[100], trained_episodes=prev_100_total)

    def evaluate_against_best(self, eval_games=1000):
        eval_env = GameSimulator(dice_catalog=None, map_data=os.getenv("AUTOCARDBATTLE_MAP_DATA"))
        wins = 0
        losses = 0
        for game_index in range(1, eval_games + 1):
            p_state, e_state = eval_env.reset()
            done = False
            info = {}
            while not done:
                p_valid = eval_env.get_valid_actions_for("player")
                p_action, _, _, _, _ = self.select_action(
                    p_state,
                    p_valid,
                    mode='play',
                    network_to_use=self.network,
                )
                e_valid = eval_env.get_valid_actions_for("enemy")
                e_action, _, _, _, _ = self.select_action(
                    e_state,
                    e_valid,
                    mode='play',
                    network_to_use=self.best_network,
                )
                p_state, e_state, _, _, done, info = eval_env.step_self_play(p_action, e_action)

            winner = info.get("winner")
            if winner == 1:
                wins += 1
            elif winner == -1:
                losses += 1

            if game_index % self.eval_progress_log_interval == 0 or game_index == eval_games:
                print(json.dumps({
                    "eval_progress": {
                        "played": game_index,
                        "total": eval_games,
                        "wins": wins,
                        "losses": losses,
                        "draws": game_index - wins - losses,
                        "win_rate": round(wins / max(1, (wins + losses)), 4) if (wins + losses) > 0 else 0.0
                    }
                }), flush=True)

        return wins, losses

    def train(self, episodes=50, log_interval=10):
        start_time = time.time()
        map_data = os.getenv("AUTOCARDBATTLE_MAP_DATA")
        env = GameSimulator(dice_catalog=None, map_data=map_data)

        sample_state, _ = env.reset()
        current_state_size = len(sample_state)
        if current_state_size != self.state_size:
            print(f"[AITrainer] Adjusting state_size from {self.state_size} to {current_state_size}", flush=True)
            self.state_size = current_state_size
            self.network = PPONetwork(
                self.state_size,
                self.action_size,
                learning_rate=self.base_lr,
                clip_epsilon=0.2,
                entropy_coef=self.base_entropy_coef,
                value_coef=0.5,
                target_kl=0.02,
            )
            try:
                self.load_model(self.model_path)
            except Exception as e:
                print(f"[AITrainer] Could not load model with new state_size: {e}. Starting fresh.", flush=True)
            self.best_network = self._clone_network(self.network)
            self.previous_network = self._clone_network(self.network)
            self.historical_networks = deque(maxlen=10)
            self._bootstrap_fixed_policy_files()
            self._refresh_fixed_networks_from_files()

        root_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), "../../../.."))
        rel_model_path = os.path.relpath(self.model_path, root_dir)

        total_wins = 0
        total_losses = 0
        total_draws = 0
        total_loss = 0.0
        loss_count = 0

        reward_window_sum = 0.0
        run_start_total_episode = self.total_trained_episodes
        for ep in range(1, episodes + 1):
            p_state, e_state = env.reset()
            done = False
            ep_reward = 0.0

            p_traj = {"states": [], "actions": [], "log_probs": [], "action_masks": [], "rewards": [], "dones": [], "values": []}
            p_round_action_indices = []

            while not done:
                round_before = env.current_round
                p_valid = env.get_valid_actions_for("player")
                p_action, p_idx, p_logp, p_val, p_mask = self.select_action(p_state, p_valid, mode='train', network_to_use=self.network)

                e_valid = env.get_valid_actions_for("enemy")
                enemy_network = self._choose_enemy_network()
                e_action, e_idx, e_logp, e_val, e_mask = self.select_action(
                    e_state, e_valid, mode='play', network_to_use=enemy_network
                )

                next_p_state, next_e_state, p_reward, e_reward, done, info = env.step_self_play(p_action, e_action)
                round_after = info.get("current_round", round_before)
                round_resolved = (round_after > round_before) or done

                p_immediate_reward = float(p_reward)
                if round_resolved:
                    rr = info.get("round_result", 0)
                    round_reward_player = 1.0 if rr == 1 else (-1.0 if rr == -1 else -0.1)
                    p_immediate_reward -= round_reward_player

                p_traj["states"].append(p_state)
                p_traj["actions"].append(p_idx)
                p_traj["log_probs"].append(p_logp)
                p_traj["action_masks"].append(p_mask)
                p_traj["rewards"].append(p_immediate_reward)
                p_traj["dones"].append(1.0 if round_resolved else 0.0)
                p_traj["values"].append(float(p_val))

                step_index = len(p_traj["rewards"]) - 1
                if p_action != PASS_ACTION:
                    p_round_action_indices.append(step_index)

                if round_resolved:
                    rr = info.get("round_result", 0)
                    round_reward_player = 1.0 if rr == 1 else (-1.0 if rr == -1 else -0.1)

                    p_slot_reward = round_reward_player / float(MAX_ACTIONS_PER_TURN)

                    for idx in p_round_action_indices[:MAX_ACTIONS_PER_TURN]:
                        p_traj["rewards"][idx] += p_slot_reward

                    p_round_action_indices = []

                p_state = next_p_state
                e_state = next_e_state
                ep_reward += p_reward

            update_stats = self.train_on_episode(ep, episodes, [p_traj])
            reward_window_sum += ep_reward

            winner = info.get("winner")
            if winner == 1:
                total_wins += 1
            elif winner == -1:
                total_losses += 1
            else:
                total_draws += 1

            if update_stats["loss"] > 0:
                total_loss += update_stats["loss"]
                loss_count += 1

            total_episode = run_start_total_episode + ep
            promoted = False
            batch_promoted = False
            gate_win_rate = 0.0
            eval_triggered = (ep % self.eval_interval == 0)

            if ep % log_interval == 0:
                pending_gate_games = self.eval_games
                pending_gate_win_rate = (self.eval_wins / pending_gate_games) if pending_gate_games > 0 else 0.0
                if gate_win_rate == 0.0:
                    gate_win_rate = pending_gate_win_rate

                avg_loss = total_loss / loss_count if loss_count > 0 else 0.0
                now = time.time()
                log = {
                    "episode": ep,
                    "total_episode": total_episode,
                    "wins": total_wins,
                    "losses": total_losses,
                    "draws": total_draws,
                    "win_rate": round(total_wins / (total_wins + total_losses), 2) if (total_wins + total_losses) > 0 else 0.0,
                    "avg_reward": round(reward_window_sum / log_interval, 2),
                    "avg_loss": round(avg_loss, 6),
                    "elapsed_time": round(now - start_time, 2),
                    "log_interval_seconds": round(now - start_time, 2),
                    "lr": round(self.network.learning_rate, 6),
                    "entropy_coef": round(self.network.entropy_coef, 6),
                    "gate_win_rate": round(gate_win_rate, 4),
                    "eval_games": self.eval_games,
                    "eval_triggered": eval_triggered,
                    "batch_previous_model_promoted": batch_promoted,
                    "previous_model_promoted": promoted,
                    "approx_kl": round(update_stats["approx_kl"], 6),
                    "kl_early_stop": bool(update_stats["early_stop"]),
                    "best_opp_ratio": self.best_opponent_ratio,
                    "prev_opp_ratio": self.previous_opponent_ratio,
                    "random_old_opp_ratio": self.random_old_opponent_ratio,
                    "best_eval_winrate": round(self.best_eval_winrate, 4),
                    "algo": "PPO"
                }
                print(json.dumps(log), flush=True)
                last_log_time = now
                total_loss = 0.0
                loss_count = 0
                reward_window_sum = 0.0

            if total_episode % self.push_interval == 0:
                self.total_trained_episodes = total_episode
                self.save_model(self.model_path)
                self.sync_to_github(root_dir, rel_model_path, total_episode)

            if total_episode % self.checkpoint_interval == 0:
                self.total_trained_episodes = total_episode
                previous_fixed_network = self._clone_network(self.network)
                self.save_network_model(self.best_network, self.best_model_path)
                self._save_relative_history_models(previous_fixed_network)
                self._refresh_fixed_networks_from_files()
                self.sync_to_github(root_dir, rel_model_path, total_episode)

            if ep % self.eval_interval == 0:
                wins, losses = self.evaluate_against_best(self.eval_batch)
                self.eval_games += self.eval_batch
                self.eval_wins += wins
                batch_win_rate = wins / max(1, (wins + losses))
                print(json.dumps({
                    "eval_batch_result": {
                        "batch_games": self.eval_batch,
                        "batch_wins": wins,
                        "batch_losses": losses,
                        "batch_draws": self.eval_batch - wins - losses,
                        "batch_win_rate": round(batch_win_rate, 4),
                        "batch_previous_model_promoted": batch_promoted,
                        "accum_games": self.eval_games,
                        "accum_wins": self.eval_wins,
                        "target_games": self.eval_total
                    }
                }), flush=True)
                if self.eval_games >= self.eval_total:
                    gate_win_rate = self.eval_wins / max(1, self.eval_games)
                    if gate_win_rate > self.best_eval_winrate:
                        self.best_eval_winrate = gate_win_rate
                        self.save_network_model(self.best_network, self.best_model_path)
                    if gate_win_rate >= self.replace_rate:
                        self.best_network = self._clone_network(self.network)
                        self.save_network_model(self.best_network, self.best_model_path)
                        promoted = True
                    self.eval_games = 0
                    self.eval_wins = 0
            gc.collect()

        self.total_trained_episodes = run_start_total_episode + episodes

        duration = time.time() - start_time
        print(f"[AITrainer] PPO training completed in {duration:.2f} seconds.", flush=True)

    def sync_to_github(self, root_dir, rel_model_path, ep):
        try:
            print(f"[Git-Push] Attempting to push model at episode {ep}...", flush=True)
            git_dir = os.path.join(root_dir, ".git")
            if not os.path.isdir(git_dir):
                print(f"[Git-Push] Skip: not a git repository at {root_dir}", flush=True)
                return

            token = os.getenv("GITHUB_TOKEN")
            if token:
                clean_token = token.strip()
                push_url = f"https://warrior-0:{clean_token}@github.com/warrior-0/autocardbattle.git"
                
                # 1. Fetch 최신 상태
                subprocess.run(["git", "fetch", push_url, "main"], cwd=root_dir, capture_output=True)
                
                # 2. 모델 파일 추가 및 커밋
                model_dir = os.path.dirname(rel_model_path)
                extra_rel_paths = [
                    os.path.join(model_dir, "best_model.json"),
                    os.path.join(model_dir, "model_prev_100.json"),
                    os.path.join(model_dir, "model_prev_200.json"),
                    os.path.join(model_dir, "model_prev_300.json"),
                    os.path.join(model_dir, "model_prev_400.json"),
                    os.path.join(model_dir, "model_prev_500.json"),
                ]
                subprocess.run(["git", "add", str(rel_model_path)], cwd=root_dir, check=True)
                for p in extra_rel_paths:
                    abs_p = os.path.join(root_dir, p)
                    if os.path.exists(abs_p):
                        subprocess.run(["git", "add", p], cwd=root_dir, capture_output=True)
                commit_res = subprocess.run(["git", "commit", "-m", f"chore: update trained model at episode {ep}"], cwd=root_dir, capture_output=True)
                
                if commit_res.returncode == 0:
                    # 3. Rebase onto fetched remote main (충돌 시 안전 중단)
                    rebase_res = subprocess.run(
                        ["git", "rebase", "FETCH_HEAD"],
                        cwd=root_dir,
                        capture_output=True,
                        text=True
                    )
                    if rebase_res.returncode != 0:
                        print(f"[Git-Push] Rebase failed: {rebase_res.stderr}", flush=True)
                        subprocess.run(["git", "rebase", "--abort"], cwd=root_dir, capture_output=True)
                        return
                    
                    # 4. Push (Force push 제거)
                    push_res = subprocess.run(["git", "push", push_url, "main"], cwd=root_dir, capture_output=True, text=True)
                    
                    if push_res.returncode == 0:
                        print(f"[Git-Push] Successfully pushed model to GitHub.", flush=True)
                    else:
                        print(f"[Git-Push] Push failed: {push_res.stderr}. Aborting to avoid data loss.", flush=True)
                        # 실패 시 상태 되돌리기
                        subprocess.run(["git", "rebase", "--abort"], cwd=root_dir, capture_output=True)
                else:
                    print(f"[Git-Push] Nothing to commit (model might be unchanged).", flush=True)
            else:
                print(f"[Git-Push] Error: GITHUB_TOKEN not found in environment.", flush=True)
        except Exception as e:
            print(f"[Git-Push] Error during GitHub sync: {e}", flush=True)


if __name__ == "__main__":
    model_path = os.getenv("AUTOCARDBATTLE_MODEL_PATH", "src/main/resources/python/q_policy.json")
    episodes = int(os.getenv("AUTOCARDBATTLE_TRAIN_EPISODES", "50"))
    log_interval = int(os.getenv("AUTOCARDBATTLE_TRAIN_LOG_INTERVAL", "10"))
    learning_rate = float(os.getenv("AUTOCARDBATTLE_LEARNING_RATE", "0.0003"))

    trainer = AITrainer(model_path, learning_rate=learning_rate)
    trainer.train(episodes=episodes, log_interval=log_interval)
