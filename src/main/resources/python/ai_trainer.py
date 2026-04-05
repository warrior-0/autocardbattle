import os
import json
import time
import subprocess
import gc
import importlib.util
import numpy as np
import shutil
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

        self.replace_rate = 0.60
        self.total_trained_episodes = 0
        self.needs_evaluation = False  # 승급전 필요 여부 플래그

        self.gamma = 0.98
        self.gae_lambda = 0.95
        self.ppo_epochs = 4
        self.minibatch_size = 64

        self.previous_network = self._clone_network(self.network)
        self.best_network = self._clone_network(self.network)
        self.historical_networks = deque(maxlen=5)

        # 초기 상태(0번 학습된 모델)를 별도로 보관 (0~1999판 구간에서 사용)
        self.initial_network_state = self.network.state_dict()

        self.load_model(self.model_path)
        
        # 만약 로드 시점에 승급전이 필요하다고 되어있으면 (이전 실행 중단 등)
        # 훈련 시작 시점에 체크하여 처리할 예정입니다.
        
        self._load_historical_checkpoints()
        if len(self.historical_networks) == 0:
            for _ in range(5):
                self.historical_networks.append(self._clone_network(self.network))
        elif len(self.historical_networks) < 5:
            current_count = len(self.historical_networks)
            for _ in range(5 - current_count):
                self.historical_networks.append(self._clone_network(self.network))

    def _load_historical_checkpoints(self):
        checkpoint_dir = os.path.dirname(self.model_path)
        checkpoint_prefix = "checkpoint_ep_"
        checkpoint_suffix = ".json"
        if not os.path.isdir(checkpoint_dir): return
        checkpoints = []
        for name in os.listdir(checkpoint_dir):
            if name.startswith(checkpoint_prefix) and name.endswith(checkpoint_suffix):
                try:
                    ep = int(name[len(checkpoint_prefix):-len(checkpoint_suffix)])
                    checkpoints.append((ep, os.path.join(checkpoint_dir, name)))
                except ValueError: continue
        checkpoints.sort()
        for ep, path in checkpoints[-5:]:
            try:
                temp_net = self._clone_network(self.network)
                with open(path, 'r') as f:
                    cp_data = json.load(f)
                state_dict = cp_data.get("policy_state_dict") or cp_data.get("state_dict")
                if state_dict:
                    temp_net.load_state_dict(state_dict, strict=True)
                    self.historical_networks.append(temp_net)
            except Exception: pass

    def _clone_network(self, src_network):
        cloned = PPONetwork(self.state_size, self.action_size, learning_rate=src_network.learning_rate)
        cloned.load_state_dict(src_network.state_dict(), strict=True)
        return cloned

    def load_model(self, path):
        if os.path.exists(path):
            try:
                with open(path, 'r') as f:
                    checkpoint = json.load(f)
                if isinstance(checkpoint, dict):
                    state_dict = checkpoint.get("policy_state_dict") or checkpoint.get("state_dict")
                    if state_dict:
                        self.network.load_state_dict(state_dict, strict=True)
                    self.total_trained_episodes = int(checkpoint.get("total_trained_episodes", 0))
                    self.needs_evaluation = checkpoint.get("needs_evaluation", False)
                    print(f"[AITrainer] Model loaded. Total episodes: {self.total_trained_episodes}, Needs Eval: {self.needs_evaluation}", flush=True)
            except Exception as e:
                print(f"[AITrainer] Load failed: {e}", flush=True)

    def save_model(self, path):
        try:
            os.makedirs(os.path.dirname(path), exist_ok=True)
            checkpoint = {
                "policy_state_dict": self.network.state_dict(),
                "total_trained_episodes": int(self.total_trained_episodes),
                "needs_evaluation": self.needs_evaluation,
                "timestamp": time.time()
            }
            with open(path, 'w') as f:
                json.dump(checkpoint, f)
        except Exception as e:
            print(f"[AITrainer] Save failed: {e}", flush=True)

    def _action_to_index(self, action):
        if action == PASS_ACTION: return 0
        return 1 + (action[0] * 64) + action[1]

    def select_action(self, state, valid_actions, mode='train', network_to_use=None):
        net = network_to_use if network_to_use else self.network
        logits, values = net.forward(np.array(state, dtype=np.float32))
        logits = logits[0]
        valid_indices = np.array([self._action_to_index(a) for a in valid_actions], dtype=np.int32)
        action_mask = np.zeros(self.action_size, dtype=np.float32)
        if len(valid_indices) > 0: action_mask[valid_indices] = 1.0
        else: action_mask[0] = 1.0
        masked_logits = np.where(action_mask > 0.5, logits, -1e10)
        probs = np.exp(masked_logits - np.max(masked_logits))
        probs /= (np.sum(probs) + 1e-8)
        if mode == 'play': idx = int(np.argmax(probs))
        else: idx = int(np.random.choice(np.arange(self.action_size), p=probs))
        action = {self._action_to_index(a): a for a in valid_actions}.get(idx, PASS_ACTION)
        return action, idx, float(np.log(probs[idx] + 1e-8)), float(values[0]), action_mask

    def train_on_episode(self, trajectories):
        states, actions, old_log_probs, action_masks, returns, advantages = [], [], [], [], [], []
        for traj in trajectories:
            r, adv = _build_returns_advantages_numba(
                np.array(traj["rewards"], dtype=np.float32),
                np.array(traj["values"], dtype=np.float32),
                np.array(traj["dones"], dtype=np.float32),
                np.float32(self.gamma), np.float32(self.gae_lambda)
            )
            states.extend(traj["states"]); actions.extend(traj["actions"])
            old_log_probs.extend(traj["log_probs"]); action_masks.extend(traj["action_masks"])
            returns.extend(r.tolist()); advantages.extend(adv.tolist())
        
        advantages = np.array(advantages, dtype=np.float32)
        advantages = (advantages - np.mean(advantages)) / (np.std(advantages) + 1e-8)
        
        return self.network.update_ppo(
            np.array(states, dtype=np.float32), np.array(actions, dtype=np.int32),
            np.array(old_log_probs, dtype=np.float32), np.array(action_masks, dtype=np.float32),
            np.array(returns, dtype=np.float32), advantages,
            epochs=self.ppo_epochs, minibatch_size=self.minibatch_size
        )

    def _choose_enemy_network(self):
        r = np.random.rand()
        if r < 0.2: return self.best_network
        if r < 0.7: return self.previous_network
        old_candidates = list(self.historical_networks)[:-1]
        if old_candidates: return self.random_choice(old_candidates)
        return self.previous_network

    def random_choice(self, lst):
        return lst[np.random.randint(len(lst))]

    def _rotate_checkpoints(self):
        checkpoint_dir = os.path.dirname(self.model_path)
        p5000 = os.path.join(checkpoint_dir, "checkpoint_ep_5000.json")
        if os.path.exists(p5000): os.remove(p5000)
        for ep in [4000, 3000, 2000, 1000]:
            old_p = os.path.join(checkpoint_dir, f"checkpoint_ep_{ep}.json")
            new_p = os.path.join(checkpoint_dir, f"checkpoint_ep_{ep + 1000}.json")
            if os.path.exists(old_p): os.rename(old_p, new_p)

    def _save_checkpoint(self, ep):
        checkpoint_dir = os.path.dirname(self.model_path)
        self._rotate_checkpoints()
        checkpoint_path = os.path.join(checkpoint_dir, "checkpoint_ep_1000.json")

        # 0~1999판 사이에는 0번 학습된 초기 모델을 저장하여 과적합 방지
        if ep < 2000:
            print(f"[AITrainer] Under 2000 episodes ({ep}). Saving initial (0-trained) model as checkpoint.", flush=True)
            try:
                os.makedirs(os.path.dirname(checkpoint_path), exist_ok=True)
                checkpoint = {
                    "policy_state_dict": self.initial_network_state,
                    "total_trained_episodes": 0,
                    "needs_evaluation": False,
                    "timestamp": time.time()
                }
                with open(checkpoint_path, 'w') as f:
                    json.dump(checkpoint, f)
            except Exception as e:
                print(f"[AITrainer] Initial checkpoint save failed: {e}", flush=True)
        else:
            # 2000판 이상부터는 원래대로 현재 학습된 모델을 저장
            if os.path.exists(self.model_path):
                shutil.copy2(self.model_path, checkpoint_path)
            else:
                self.save_model(checkpoint_path)

    def evaluate_model(self, eval_episodes=1000):
        """별도의 승급전(Evaluation)을 수행합니다. (학습 없음)"""
        print(f"[AITrainer] Starting evaluation: Current vs Best for {eval_episodes} games...", flush=True)
        wins = 0
        for i in range(1, eval_episodes + 1):
            sim = GameSimulator()
            state, _ = sim.reset()
            done = False
            while not done:
                action, _, _, _, _ = self.select_action(state, sim.get_valid_actions_for("player"), mode='play')
                enemy_action, _, _, _, _ = self.select_action(state, sim.get_valid_actions_for("enemy"), mode='play', network_to_use=self.best_network)
                state, _, _, _, done, info = sim.step(action, enemy_action)
            if info.get("winner") == 1: wins += 1
            if i % 100 == 0: print(f"[Eval] Progress: {i}/{eval_episodes}, Current Wins: {wins}", flush=True)
        
        win_rate = wins / eval_episodes
        promoted = win_rate >= self.replace_rate
        if promoted:
            self.best_network = self._clone_network(self.network)
            print(f"[AITrainer] PROMOTED! New Best Model (Win Rate: {win_rate:.4f})", flush=True)
        else:
            print(f"[AITrainer] Promotion Failed. (Win Rate: {win_rate:.4f})", flush=True)
        
        # 승급전 완료 후 상태 업데이트 및 저장
        self.needs_evaluation = False
        self.save_model(self.model_path)
        return promoted

    def train(self, episodes=1000, log_interval=10):
        root_dir = os.path.abspath(os.path.join(os.path.dirname(self.model_path), "../../../.."))
        rel_model_path = os.path.relpath(self.model_path, root_dir)

        # 시작 전 미결된 승급전이 있는지 체크
        if self.needs_evaluation:
            print("[AITrainer] Unfinished evaluation found. Running evaluation first...", flush=True)
            self.evaluate_model()

        for ep in range(1, episodes + 1):
            # 매 에피소드 시작 시, 혹시 다른 경로로 needs_evaluation이 true가 되었다면 false로 초기화 (중복 방지)
            # 단, 1000판 단위가 아닐 때만 초기화하여 안전장치 마련
            total_episode = self.total_trained_episodes + 1
            if total_episode % 1000 != 0:
                self.needs_evaluation = False

            enemy_net = self._choose_enemy_network()
            sim = GameSimulator()
            obs_l, act_l, log_l, val_l, rew_l, don_l, msk_l = [], [], [], [], [], [], []
            state, _ = sim.reset()
            done = False
            ep_reward = 0.0

            while not done:
                action, act_idx, log_p, val, msk = self.select_action(state, sim.get_valid_actions_for("player"))
                obs_l.append(state); act_l.append(act_idx); log_l.append(log_p); val_l.append(val); msk_l.append(msk)
                enemy_action, _, _, _, _ = self.select_action(state, sim.get_valid_actions_for("enemy"), mode='play', network_to_use=enemy_net)
                state, _, rew, _, done, info = sim.step(action, enemy_action)
                rew_l.append(rew); don_l.append(done); ep_reward += rew

            self.train_on_episode([{"states": obs_l, "actions": act_l, "log_probs": log_l, "values": val_l, "rewards": rew_l, "dones": don_l, "action_masks": msk_l}])
            self.total_trained_episodes += 1
            
            # 1000판 단위 처리
            if self.total_trained_episodes % 1000 == 0:
                print(f"[AITrainer] Reached {self.total_trained_episodes} episodes. Saving and starting evaluation...", flush=True)
                
                # 1. 체크포인트 보관 (이전 1000판 모델 또는 초기 모델)
                self._save_checkpoint(self.total_trained_episodes)
                
                # 2. 현재 모델 저장 및 승급전 플래그 설정
                self.needs_evaluation = True
                self.save_model(self.model_path)
                
                # 3. 승급전 수행 (독립적 1000판)
                self.evaluate_model()
                
                # 4. historical_networks 갱신
                self._load_historical_checkpoints()
                if self.historical_networks:
                    self.previous_network = self._clone_network(self.historical_networks[-1])
                
                # 5. 최종 결과 저장 및 GitHub 푸시
                self.save_model(self.model_path)
                self.sync_to_github(root_dir, rel_model_path, self.total_trained_episodes)

            if ep % log_interval == 0:
                print(json.dumps({"episode": ep, "total": self.total_trained_episodes, "reward": round(ep_reward, 2)}), flush=True)
            
            if ep % 10 == 0: self.sync_to_github(root_dir, rel_model_path, self.total_trained_episodes)
            gc.collect()

    def sync_to_github(self, root_dir, rel_model_path, ep):
        try:
            token = os.getenv("GITHUB_TOKEN")
            if not token: return
            push_url = f"https://warrior-0:{token.strip()}@github.com/warrior-0/autocardbattle.git"
            subprocess.run(["git", "add", "."], cwd=root_dir, check=True)
            res = subprocess.run(["git", "commit", "-m", f"chore: update model at {ep}"], cwd=root_dir, capture_output=True)
            if res.returncode == 0:
                subprocess.run(["git", "push", push_url, "main"], cwd=root_dir, capture_output=True)
        except Exception: pass


if __name__ == "__main__":
    model_path = os.getenv("AUTOCARDBATTLE_MODEL_PATH", "src/main/resources/python/q_policy.json")
    episodes = int(os.getenv("AUTOCARDBATTLE_TRAIN_EPISODES", "50"))
    trainer = AITrainer(model_path)
    trainer.train(episodes=episodes)
