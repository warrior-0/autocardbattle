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
        self.replace_rate = 0.60
        self.eval_interval = 1000
        self.eval_games = 0
        self.eval_wins = 0

        self.best_opponent_ratio = 0.2
        self.previous_opponent_ratio = 0.5
        self.random_old_opponent_ratio = 0.3
        self.best_eval_winrate = -1.0

        self.push_interval = 10
        self.checkpoint_interval = 1000
        self.max_checkpoints = 5

        self.total_trained_episodes = 0

        self.gamma = 0.98
        self.gae_lambda = 0.95
        self.ppo_epochs = 4
        self.minibatch_size = 64

        self.previous_network = self._clone_network(self.network)
        self.best_network = self._clone_network(self.network)
        self.historical_networks = deque(maxlen=5)

        self.load_model(self.model_path)
        self.previous_network = self._clone_network(self.network)
        self.best_network = self._clone_network(self.network)
        
        # 기존 체크포인트 파일들을 검색하여 historical_networks에 로드
        self._load_historical_checkpoints()
        
        # 만약 로드된 과거 모델이 없다면 현재 모델로 채움 (기존 로직 유지)
        if len(self.historical_networks) == 0:
            for _ in range(5):
                self.historical_networks.append(self._clone_network(self.network))
        elif len(self.historical_networks) < 5:
            # 부족한 만큼 현재 모델로 채움
            current_count = len(self.historical_networks)
            for _ in range(5 - current_count):
                self.historical_networks.append(self._clone_network(self.network))

    def _load_historical_checkpoints(self):
        """저장소에 있는 checkpoint_ep_*.json 파일들을 찾아 historical_networks에 로드합니다."""
        checkpoint_dir = os.path.dirname(self.model_path)
        checkpoint_prefix = "checkpoint_ep_"
        checkpoint_suffix = ".json"
        
        if not os.path.isdir(checkpoint_dir):
            return

        checkpoints = []
        for name in os.listdir(checkpoint_dir):
            if name.startswith(checkpoint_prefix) and name.endswith(checkpoint_suffix):
                try:
                    ep = int(name[len(checkpoint_prefix):-len(checkpoint_suffix)])
                    checkpoints.append((ep, os.path.join(checkpoint_dir, name)))
                except ValueError:
                    continue
        
        # 에피소드 순서대로 정렬 (오래된 것부터)
        checkpoints.sort()
        
        # 최대 5개까지 로드 (최신 순으로 5개 또는 전체 중 균등하게 선택할 수 있으나, 여기서는 최신 순 5개)
        # 만약 0번, 1000번 등 특정 모델을 꼭 포함하고 싶다면 로직을 조정할 수 있음
        # 현재는 deque maxlen=5에 맞춰 마지막 5개를 로드
        loaded_count = 0
        for ep, path in checkpoints[-5:]:
            try:
                # 임시 네트워크 생성하여 로드
                temp_net = self._clone_network(self.network)
                with open(path, 'r') as f:
                    cp_data = json.load(f)
                
                state_dict = None
                if "policy_state_dict" in cp_data:
                    state_dict = cp_data["policy_state_dict"]
                elif "state_dict" in cp_data:
                    state_dict = cp_data["state_dict"]
                
                if state_dict:
                    temp_net.load_state_dict(state_dict, strict=True)
                    self.historical_networks.append(temp_net)
                    loaded_count += 1
                    print(f"[AITrainer] Loaded historical checkpoint: {path} (ep {ep})", flush=True)
            except Exception as e:
                print(f"[AITrainer] Failed to load historical checkpoint {path}: {e}", flush=True)

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
        if r >= (self.best_opponent_ratio + self.previous_opponent_ratio + self.random_old_opponent_ratio):
            return self.previous_network

        old_candidates = list(self.historical_networks)[:-1]
        if old_candidates:
            return old_candidates[np.random.randint(len(old_candidates))]
        return self.previous_network

    def _trim_old_checkpoints(self):
        checkpoint_dir = os.path.dirname(self.model_path)
        checkpoint_prefix = "checkpoint_ep_"
        checkpoint_suffix = ".json"
        if not os.path.isdir(checkpoint_dir):
            return

        checkpoints = []
        for name in os.listdir(checkpoint_dir):
            if name.startswith(checkpoint_prefix) and name.endswith(checkpoint_suffix):
                try:
                    ep = int(name[len(checkpoint_prefix):-len(checkpoint_suffix)])
                    checkpoints.append((ep, os.path.join(checkpoint_dir, name)))
                except ValueError:
                    continue

        if len(checkpoints) <= self.max_checkpoints:
            return

        checkpoints.sort()
        to_remove = checkpoints[:-self.max_checkpoints]
        for ep, path in to_remove:
            try:
                os.remove(path)
                print(f"[AITrainer] Removed old checkpoint: {path}", flush=True)
            except Exception as e:
                print(f"[AITrainer] Failed to remove checkpoint {path}: {e}", flush=True)

    def _save_checkpoint(self, ep):
        checkpoint_dir = os.path.dirname(self.model_path)
        checkpoint_name = f"checkpoint_ep_{ep}.json"
        checkpoint_path = os.path.join(checkpoint_dir, checkpoint_name)
        self.save_model(checkpoint_path)
        self._trim_old_checkpoints()

    def train(self, episodes=1000, log_interval=10, eval_interval=1000):
        start_time = time.time()
        run_start_total_episode = self.total_trained_episodes
        print(f"[AITrainer] Starting PPO training for {episodes} episodes (total start: {run_start_total_episode})", flush=True)

        total_wins = 0
        total_losses = 0
        total_draws = 0
        total_loss = 0.0
        loss_count = 0
        reward_window_sum = 0.0
        last_log_time = start_time
        root_dir = os.path.abspath(os.path.join(os.path.dirname(self.model_path), "../../../.."))
        rel_model_path = os.path.relpath(self.model_path, root_dir)

        for ep in range(1, episodes + 1):
            enemy_network = self._choose_enemy_network()
            simulator = GameSimulator()
            
            obs_list = []
            action_list = []
            log_prob_list = []
            value_list = []
            reward_list = []
            done_list = []
            mask_list = []

            state = simulator.get_state()
            done = False
            ep_reward = 0.0

            while not done:
                valid_actions = simulator.get_valid_actions()
                action, action_idx, log_prob, val, mask = self.select_action(state, valid_actions, mode='train')
                
                obs_list.append(state)
                action_list.append(action_idx)
                log_prob_list.append(log_prob)
                value_list.append(val)
                mask_list.append(mask)

                # Enemy Turn
                enemy_valid = simulator.get_valid_actions()
                enemy_action, _, _, _, _ = self.select_action(state, enemy_valid, mode='play', network_to_use=enemy_network)
                
                next_state, reward, done, info = simulator.step(action, enemy_action)
                
                reward_list.append(reward)
                done_list.append(done)
                
                state = next_state
                ep_reward += reward

            trajectories = [{
                "states": obs_list,
                "actions": action_list,
                "log_probs": log_prob_list,
                "values": value_list,
                "rewards": reward_list,
                "dones": done_list,
                "action_masks": mask_list
            }]

            update_stats = self.train_on_episode(ep, episodes, trajectories)
            total_loss += update_stats["loss"]
            loss_count += 1
            reward_window_sum += ep_reward

            if info["winner"] == 1:
                total_wins += 1
                self.eval_wins += 1
            elif info["winner"] == -1:
                total_losses += 1
            else:
                total_draws += 1
            
            self.eval_games += 1

            eval_triggered = False
            promoted = False
            gate_win_rate = 0.0
            if self.eval_games >= self.eval_batch:
                eval_triggered = True
                gate_win_rate = self.eval_wins / self.eval_games
                if gate_win_rate >= self.replace_rate:
                    self.best_network = self._clone_network(self.network)
                    promoted = True
                
                eval_result = {"early_stopped": False, "early_stop_reason": ""}
                played_games = self.eval_games
                wins = self.eval_wins
                losses = played_games - wins # Simplification for log
                
                print(json.dumps({
                    "eval_batch_result": {
                        "batch_games": played_games,
                        "batch_games_target": self.eval_batch,
                        "batch_wins": wins,
                        "batch_losses": losses,
                        "batch_draws": played_games - wins - losses,
                        "gate_win_rate": round(gate_win_rate, 4),
                        "replace_rate": self.replace_rate,
                        "best_model_replaced": promoted,
                        "early_stopped": eval_result["early_stopped"],
                        "early_stop_reason": eval_result["early_stop_reason"],
                        "accum_games": self.eval_games,
                        "accum_wins": self.eval_wins,
                        "target_games": self.eval_total
                    }
                }), flush=True)
                self.best_eval_winrate = max(self.best_eval_winrate, gate_win_rate)
                self.eval_games = 0
                self.eval_wins = 0

            total_episode = run_start_total_episode + ep

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
                    "best_model_replaced": promoted,
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
                self._save_checkpoint(total_episode)
                if self.historical_networks:
                    self.previous_network = self._clone_network(self.historical_networks[-1])
                self.historical_networks.append(self._clone_network(self.network))
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
                subprocess.run(["git", "add", str(rel_model_path)], cwd=root_dir, check=True)
                commit_res = subprocess.run(["git", "commit", "-m", f"chore: update trained model at episode {ep}"], cwd=root_dir, capture_output=True)
                
                if commit_res.returncode == 0:
                    # 3. Pull with Rebase (충돌 시 로컬 모델 우선)
                    subprocess.run(["git", "pull", "--rebase", "-Xours", push_url, "main"], cwd=root_dir, capture_output=True)
                    
                    # 4. Push
                    push_res = subprocess.run(["git", "push", push_url, "main"], cwd=root_dir, capture_output=True, text=True)
                    
                    if push_res.returncode == 0:
                        print(f"[Git-Push] Successfully pushed model to GitHub.", flush=True)
                    else:
                        print(f"[Git-Push] Push failed: {push_res.stderr}. Aborting to avoid data loss.", flush=True)
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
