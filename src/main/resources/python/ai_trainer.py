import os
import json
import time
import subprocess
import gc
import importlib.util
import numpy as np
import shutil
import glob
from collections import deque
from ai_models import PPONetwork
from game_simulator import GameSimulator, PASS_ACTION, MAX_ACTIONS_PER_TURN, STATE_SIZE

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
    def __init__(self, model_path, state_size=STATE_SIZE, action_size=129, learning_rate=0.0003):
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
        self.lr_decay = 0.9997
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
        self.pending_network = self._clone_network(self.network)
        
        # historical_networks는 체크포인트 1000~5000 (최소 1000판 격차 확보된 모델들)
        self.historical_networks = deque(maxlen=5)

        # 0번 학습된 초기 모델 상태 보관 (0~1999판 구에서 적으로 사용)
        self.initial_network = self._clone_network(self.network)

        self.load_model(self.model_path)
        self._apply_hyperparam_schedule()
        self._load_best_model_if_exists()
        self._ensure_best_model_initialized()
        self.training_map_pool = self._load_training_map_pool()
        self.map_rotation_index = self.total_trained_episodes % max(1, len(self.training_map_pool))
        self._load_or_init_pending_checkpoint()
        
        # 체크포인트 로드 (기존 파일들 로드)
        self._load_historical_checkpoints()

        # 대전 상대로 쓸 '격차가 유지된 모델'들을 설정
        self._update_enemy_candidates()

    def _best_model_path(self):
        checkpoint_dir = os.path.dirname(self.model_path)
        return os.path.join(checkpoint_dir, "best_model.json")

    def _load_best_model_if_exists(self):
        best_path = self._best_model_path()
        if not os.path.exists(best_path):
            return
        try:
            with open(best_path, 'r') as f:
                cp_data = json.load(f)
            state_dict = cp_data.get("policy_state_dict") or cp_data.get("state_dict")
            if state_dict:
                self.best_network.load_state_dict(state_dict, strict=True)
                print("[AITrainer] Loaded best_model.json into best_network.", flush=True)
        except Exception as e:
            print(f"[AITrainer] Failed to load best_model.json: {e}", flush=True)

    def _save_best_model(self):
        best_path = self._best_model_path()
        try:
            os.makedirs(os.path.dirname(best_path), exist_ok=True)
            checkpoint = {
                "policy_state_dict": self.best_network.state_dict(),
                "total_trained_episodes": int(self.total_trained_episodes),
                "timestamp": time.time()
            }
            with open(best_path, 'w') as f:
                json.dump(checkpoint, f)
            print(f"[AITrainer] Saved best model: {best_path}", flush=True)
            return True
        except Exception as e:
            print(f"[AITrainer] Failed to save best model: {e}", flush=True)
            return False


    def _ensure_best_model_initialized(self):
        best_path = self._best_model_path()
        if os.path.exists(best_path):
            return

        self.best_network = self._clone_network(self.network)
        if self._save_best_model():
            print("[AITrainer] Initialized best_model.json from current model (episode 0 baseline).", flush=True)

    def _load_training_map_pool(self):
        """
        학습용 맵 풀 로드 규칙:
        1) AUTOCARDBATTLE_MAP_POOL_JSON이 있으면 JSON 배열로 로드
        2) 없으면 AUTOCARDBATTLE_MAP_DATA 단일 맵 사용
        3) 둘 다 없으면 GameSimulator 기본 맵 사용
        """
        env_pool = os.getenv("AUTOCARDBATTLE_MAP_POOL_JSON")
        if env_pool:
            try:
                parsed = json.loads(env_pool)
                if isinstance(parsed, list):
                    cleaned = [str(m).strip() for m in parsed if str(m).strip()]
                    if cleaned:
                        print(f"[AITrainer] Loaded map pool: {len(cleaned)} maps.", flush=True)
                        return cleaned
            except Exception as e:
                print(f"[AITrainer] Failed to parse AUTOCARDBATTLE_MAP_POOL_JSON: {e}", flush=True)

        single_map = os.getenv("AUTOCARDBATTLE_MAP_DATA")
        if single_map and single_map.strip():
            print("[AITrainer] Loaded single map from AUTOCARDBATTLE_MAP_DATA.", flush=True)
            return [single_map.strip()]

        print("[AITrainer] No map pool provided. Using GameSimulator default map.", flush=True)
        return []

    def _next_training_map_data(self):
        if not self.training_map_pool:
            return None
        selected = self.training_map_pool[np.random.randint(len(self.training_map_pool))]
        return selected

    def _has_formal_checkpoints(self):
        return len(self.historical_networks) > 0

    def _load_or_init_pending_checkpoint(self):
        checkpoint_dir = os.path.dirname(self.model_path)
        pending_path = os.path.join(checkpoint_dir, "checkpoint_ep_pending.json")
        if not os.path.exists(pending_path):
            # 0판 시점: 자기 자신을 pending으로 초기화
            self.save_model(pending_path)
            print("[AITrainer] Initialized pending checkpoint from current model.", flush=True)
        try:
            with open(pending_path, 'r') as f:
                cp_data = json.load(f)
            state_dict = cp_data.get("policy_state_dict") or cp_data.get("state_dict")
            if state_dict:
                self.pending_network.load_state_dict(state_dict, strict=True)
        except Exception as e:
            print(f"[AITrainer] Pending checkpoint load failed: {e}", flush=True)
            self.pending_network = self._clone_network(self.network)

    def _load_historical_checkpoints(self):
        """
        체크포인트 1000~5000 파일을 로드합니다.
        이 모델들은 이미 1000판 이상의 격차가 확보된 검증된 과거 모델들입니다.
        """
        checkpoint_dir = os.path.dirname(self.model_path)
        checkpoint_prefix = "checkpoint_ep_"
        checkpoint_suffix = ".json"
        if not os.path.isdir(checkpoint_dir): return
        
        checkpoints = []
        for name in os.listdir(checkpoint_dir):
            if name.startswith(checkpoint_prefix) and name.endswith(checkpoint_suffix):
                try:
                    # pending_checkpoint는 로드하지 않음 (격차 유지를 위해 대기 중인 모델)
                    if "pending" in name: continue
                    ep = int(name[len(checkpoint_prefix):-len(checkpoint_suffix)])
                    checkpoints.append((ep, os.path.join(checkpoint_dir, name)))
                except ValueError: continue
        
        # 에피소드 순서대로 정렬 (1000, 2000, 3000, 4000, 5000)
        checkpoints.sort()
        
        self.historical_networks.clear()
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

    def _update_enemy_candidates(self):
        """
        대전 상대로 쓸 모델들을 현재 시점보다 최소 1000판 이전의 것으로 고정하여 업데이트니다.
        """
        # 0~1999판 구간: 적은 항상 0번 학습된 초기 모델
        if not self._has_formal_checkpoints():
            # 정식 체크포인트가 없으면 pending 모델만 상대
            self.previous_network = self._clone_network(self.pending_network)
            self.other_historical_candidates = []
        elif self.total_trained_episodes < 2000:
            self.previous_network = self._clone_network(self.initial_network)
            self.other_historical_candidates = []
        else:
            # 1000판 이상: historical_networks의 가장 최신 모델([-1])을 previous로 사용
            # historical_networks에는 이미 1000판 격차가 확보된 모델들만 들어있음 (pending 제외)
            if len(self.historical_networks) >= 1:
                self.previous_network = self._clone_network(self.historical_networks[-1])
                self.other_historical_candidates = [self._clone_network(net) for net in list(self.historical_networks)[:-1]]
            else:
                self.previous_network = self._clone_network(self.initial_network)
                self.other_historical_candidates = []
        
        print(f"[AITrainer] Enemy candidates updated for episode {self.total_trained_episodes}. Gap maintained.", flush=True)

    def _clone_network(self, src_network):
        cloned = PPONetwork(self.state_size, self.action_size, learning_rate=src_network.learning_rate)
        cloned.load_state_dict(src_network.state_dict(), strict=True)
        return cloned

    def _network_l2_norm(self):
        sq_sum = 0.0

        params = [
            self.network.conv1_w, self.network.conv1_b,
            self.network.conv2_w, self.network.conv2_b,
            self.network.spatial_fc_w, self.network.spatial_fc_b,
            self.network.non_fc1_w, self.network.non_fc1_b,
            self.network.non_fc2_w, self.network.non_fc2_b,
            self.network.fuse_w, self.network.fuse_b,
            self.network.policy_w, self.network.policy_b,
            self.network.value_w, self.network.value_b,
        ]

        for p in params:
            sq_sum += float(np.sum(p * p))

        return float(np.sqrt(sq_sum))

    def _apply_hyperparam_schedule(self):
        decay_steps = max(0, int(self.total_trained_episodes))
        scheduled_lr = self.base_lr * (self.lr_decay ** decay_steps)
        scheduled_entropy = self.base_entropy_coef * (self.entropy_decay ** decay_steps)
        self.network.learning_rate = float(max(self.min_lr, scheduled_lr))
        self.network.entropy_coef = float(max(self.min_entropy_coef, scheduled_entropy))

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
        if not self._has_formal_checkpoints():
            return self.pending_network

        r = np.random.rand()
        
        # 1. Best Network (20%)
        if r < 0.2: 
            return self.best_network
            
        # 2. Previous Network (50%) - 최소 1000판 격차가 확보된 최신 체크포인트
        if r < 0.7:
            return self.previous_network
                
        # 3. Historical Networks (30%) - 더 과거의 모델들 중에서 랜덤 선택
        if self.other_historical_candidates:
            return self.random_choice(self.other_historical_candidates)
            
        return self.initial_network

    def random_choice(self, lst):
        return lst[np.random.randint(len(lst))]

    def _rotate_checkpoints(self):
        """
        체크포인트 회전 로직:
        1. 대기 중이던 pending_checkpoint를 checkpoint_ep_1000으로 정식 편입시킵니다.
        2. 기존 1000~4000은 2000~5000으로 밀려납니다.
        3. 5000은 삭제됩니다.
        """
        checkpoint_dir = os.path.dirname(self.model_path)
        pending_p = os.path.join(checkpoint_dir, "checkpoint_ep_pending.json")
        
        # 1. 기존 체크포인트 회전 (5000 삭제, 4000->5000, ...)
        p5000 = os.path.join(checkpoint_dir, "checkpoint_ep_5000.json")
        if os.path.exists(p5000): os.remove(p5000)
        for ep in [4000, 3000, 2000, 1000]:
            old_p = os.path.join(checkpoint_dir, f"checkpoint_ep_{ep}.json")
            new_p = os.path.join(checkpoint_dir, f"checkpoint_ep_{ep + 1000}.json")
            if os.path.exists(old_p): os.rename(old_p, new_p)
            
        # 2. 대기 중이던 pending 모델을 checkpoint_ep_1000으로 승격
        # (이 모델은 1000판 전에 저장된 것이므로 이제 1000판 격차가 확보됨)
        if os.path.exists(pending_p):
            os.rename(pending_p, os.path.join(checkpoint_dir, "checkpoint_ep_1000.json"))
            print("[AITrainer] Pending checkpoint promoted to checkpoint_ep_1000.", flush=True)

    def _save_checkpoint(self, ep):
        """
        체크포인트 저장 로직:
        1. 먼저 기존 체크포인트들을 회전시켜 pending 모델을 정식 편입시킵니다.
        2. 현재 모델을 '반영 예정인 체크포인트(pending)'로 새롭게 저장합니다.
        """
        checkpoint_dir = os.path.dirname(self.model_path)
        pending_path = os.path.join(checkpoint_dir, "checkpoint_ep_pending.json")
        
        # 1. 회전 수행 (pending -> 1000 승격 포함)
        self._rotate_checkpoints()
        
        # 2. 현재 모델을 pending으로 저장 (다음 1000판 동안 대기)
        if ep < 1000:
            # 0~999판 사이에는 0번 학습된 초기 모델을 pending으로 저장
            print(f"[AITrainer] Episode {ep}: Saving initial model as pending.", flush=True)
            try:
                os.makedirs(os.path.dirname(pending_path), exist_ok=True)
                checkpoint = {
                    "policy_state_dict": self.initial_network.state_dict(),
                    "total_trained_episodes": 0,
                    "needs_evaluation": False,
                    "timestamp": time.time()
                }
                with open(pending_path, 'w') as f:
                    json.dump(checkpoint, f)
                self.pending_network = self._clone_network(self.initial_network)
            except Exception as e:
                print(f"[AITrainer] Pending save failed: {e}", flush=True)
        else:
            # 1000판 이상부터는 현재 학습된 최신 모델을 pending으로 저장
            if os.path.exists(self.model_path):
                shutil.copy2(self.model_path, pending_path)
            else:
                self.save_model(pending_path)
            self.pending_network = self._clone_network(self.network)
            print(f"[AITrainer] Episode {ep}: Current model saved as pending.", flush=True)

    def evaluate_model(self, eval_episodes=1000):
        """별도의 승급전(Evaluation)을 수행합니다. (학습 없음)"""
        self._load_best_model_if_exists()
        print(f"[AITrainer] Starting evaluation: Current vs Best for {eval_episodes} games...", flush=True)
        wins = 0
        draws = 0
        losses = 0
        for i in range(1, eval_episodes + 1):
            map_data = self._next_training_map_data()
            sim = GameSimulator(map_data=map_data)
            state, _ = sim.reset()
            done = False
            while not done:
                action, _, _, _, _ = self.select_action(state, sim.get_valid_actions_for("player"), mode='train')
                enemy_action, _, _, _, _ = self.select_action(state, sim.get_valid_actions_for("enemy"), mode='train', network_to_use=self.best_network)
                state, _, _, _, done, info = sim.step_self_play(action, enemy_action)
            winner = info.get("winner")
            if winner == 1:
                wins += 1
            elif winner == 0:
                draws += 1
            else:
                losses += 1
            if i % 10 == 0:
                print(
                    f"[Eval] Progress: {i}/{eval_episodes}, Wins: {wins}, Draws: {draws}, Losses: {losses}",
                    flush=True
                )
        
        win_rate = wins / eval_episodes
        promoted = win_rate >= self.replace_rate
        if promoted:
            self.best_network = self._clone_network(self.network)
            print(f"[AITrainer] PROMOTED! New Best Model (Win Rate: {win_rate:.4f})", flush=True)
            self._save_best_model()
        else:
            print(f"[AITrainer] Promotion Failed. (Win Rate: {win_rate:.4f})", flush=True)
        
        # 승급전 완료 후 상태 업데이트 및 저장
        self.needs_evaluation = False
        self.save_model(self.model_path)
        return promoted

    def train(self, episodes=1000, log_interval=10):
        start_time = time.time()
        root_dir = os.path.abspath(os.path.join(os.path.dirname(self.model_path), "../../../.."))
        rel_model_path = os.path.relpath(self.model_path, root_dir)
        update_batch_episodes = max(1, int(os.getenv("AUTOCARDBATTLE_UPDATE_BATCH_EPISODES", "50")))
        total_wins = 0
        total_draws = 0
        total_losses = 0
        interval_wins = 0
        interval_draws = 0
        interval_losses = 0
        reward_window_sum = 0.0
        total_loss = 0.0
        loss_count = 0
        total_kl = 0.0
        kl_count = 0
        weight_delta_sum = 0.0
        pending_trajectories = []
        episodes_since_update = 0
        window_start_norm = self._network_l2_norm()

        # 학습 0판 기준 best_model을 먼저 GitHub에 반영
        if self.total_trained_episodes == 0:
            self.sync_to_github(root_dir, rel_model_path, 0, include_best_model=True)

        # 시작 전 미결된 승급전이 있는지 체크
        if self.needs_evaluation:
            print("[AITrainer] Unfinished evaluation found. Running evaluation first...", flush=True)
            promoted = self.evaluate_model()
            if promoted:
                self.sync_to_github(root_dir, rel_model_path, self.total_trained_episodes, include_best_model=True)

        def flush_batch_if_needed(force=False):
            nonlocal total_loss, loss_count, total_kl, kl_count, weight_delta_sum, pending_trajectories, episodes_since_update
            if not pending_trajectories:
                return
            if not force and episodes_since_update < update_batch_episodes:
                return

            before_norm = self._network_l2_norm()
            update_stats = self.train_on_episode(pending_trajectories)
            after_norm = self._network_l2_norm()
            weight_delta_sum += abs(after_norm - before_norm)

            if isinstance(update_stats, dict):
                total_loss += float(update_stats.get("loss", 0.0))
                loss_count += 1
                total_kl += float(update_stats.get("approx_kl", 0.0))
                kl_count += 1

            pending_trajectories = []
            episodes_since_update = 0

        for ep in range(1, episodes + 1):
            total_episode = self.total_trained_episodes + 1
            if total_episode % 1000 != 0:
                self.needs_evaluation = False
            eval_triggered = False

            enemy_net = self._choose_enemy_network()
            map_data = self._next_training_map_data()
            sim = GameSimulator(map_data=map_data)
            obs_l, act_l, log_l, val_l, rew_l, don_l, msk_l = [], [], [], [], [], [], []
            state, _ = sim.reset()
            done = False
            ep_reward = 0.0

            while not done:
                action, act_idx, log_p, val, msk = self.select_action(state, sim.get_valid_actions_for("player"))
                obs_l.append(state); act_l.append(act_idx); log_l.append(log_p); val_l.append(val); msk_l.append(msk)
                enemy_action, _, _, _, _ = self.select_action(state, sim.get_valid_actions_for("enemy"), mode='train', network_to_use=enemy_net)
                state, _, rew, _, done, info = sim.step_self_play(action, enemy_action)
                rew_l.append(rew); don_l.append(done); ep_reward += rew

            pending_trajectories.append({
                "states": obs_l,
                "actions": act_l,
                "log_probs": log_l,
                "values": val_l,
                "rewards": rew_l,
                "dones": don_l,
                "action_masks": msk_l
            })
            episodes_since_update += 1
            self.total_trained_episodes += 1
            self._apply_hyperparam_schedule()
            reward_window_sum += ep_reward

            winner = info.get("winner", 0)
            if winner == 1:
                total_wins += 1
                interval_wins += 1
            elif winner == 0:
                total_draws += 1
                interval_draws += 1
            else:
                total_losses += 1
                interval_losses += 1

            eval_triggered = (self.total_trained_episodes % 1000 == 0)

            is_update_step = (ep % update_batch_episodes == 0)
            is_log_step = (ep % log_interval == 0)
            
            if is_update_step and self.total_trained_episodes % 1000 != 0:
                flush_batch_if_needed(force=True)
                
            if is_log_step and self.total_trained_episodes % 1000 != 0:
                # ✅ 실제 구간 길이 계산
                total_games = max(1, total_wins + total_draws + total_losses)
                avg_loss = total_loss / max(1, loss_count)
                avg_kl = total_kl / max(1, kl_count)
                current_norm = self._network_l2_norm()
                avg_weight_delta = abs(current_norm - window_start_norm)

                print(json.dumps({
                    "episode": ep,
                    "total_episode": self.total_trained_episodes,
                    "avg_reward": round(reward_window_sum / max(1, log_interval), 2),
                    "avg_loss": round(avg_loss, 6),
                    "avg_kl": round(avg_kl, 6),
                    "avg_weight_delta_l2": round(avg_weight_delta, 8),
                    "wins": total_wins,
                    "losses": total_losses,
                    "draws": total_draws,
                    "win_rate": round(total_wins / total_games, 4),
                    "elapsed_time": round(time.time() - start_time, 2),
                    "learning_rate": round(float(self.network.learning_rate), 8),
                    "entropy_coef": round(float(self.network.entropy_coef), 8),
                    "eval_triggered": self.needs_evaluation,
                    "log_type": "rolling",
                    "algo": "PPO",
                    "update_batch_episodes": update_batch_episodes
                }), flush=True)
                
                interval_wins = interval_draws = interval_losses = 0
                reward_window_sum = 0.0
                total_loss = 0.0
                loss_count = 0
                total_kl = 0.0
                kl_count = 0
                window_start_norm = current_norm

            # 1000판 단위 처리
            if self.total_trained_episodes % 1000 == 0:
                flush_batch_if_needed(force=True)
                self.needs_evaluation = True
                
                total_games = max(1, total_wins + total_draws + total_losses)
                avg_loss = total_loss / max(1, loss_count)
                avg_kl = total_kl / max(1, kl_count)
                current_norm = self._network_l2_norm()
                avg_weight_delta = abs(current_norm - window_start_norm)

                print(json.dumps({
                    "episode": ep,
                    "total_episode": self.total_trained_episodes,
                    "avg_reward": round(reward_window_sum / max(1, log_interval), 2),
                    "avg_loss": round(avg_loss, 6),
                    "avg_kl": round(avg_kl, 6),
                    "avg_weight_delta_l2": round(avg_weight_delta, 8),
                    "wins": total_wins,
                    "losses": total_losses,
                    "draws": total_draws,
                    "win_rate": round(total_wins / total_games, 4),
                    "elapsed_time": round(time.time() - start_time, 2),
                    "learning_rate": round(float(self.network.learning_rate), 8),
                    "entropy_coef": round(float(self.network.entropy_coef), 8),
                    "eval_triggered": self.needs_evaluation,
                    "log_type": "final_pre_eval",
                    "algo": "PPO",
                    "update_batch_episodes": update_batch_episodes
                }), flush=True)
                
                print(f"[AITrainer] Reached {self.total_trained_episodes} episodes. Saving and starting evaluation...", flush=True)

                # 1. 체크포인트 보관 (회전 및 pending 저장)
                self._save_checkpoint(self.total_trained_episodes)

                # 2. 현재 모델 저장 및 승급전 플래그 설정
                self.save_model(self.model_path)
                self.sync_to_github(root_dir, rel_model_path, self.total_trained_episodes, include_best_model=False)

                # 3. 승급전 행 (독립적 1000판)
                promoted = self.evaluate_model()

                # 4. historical_networks 갱신 및 적 후보군 업데이트
                # (이제 historical_networks에는 pending을 제외한 1000~5000 모델만 로드됨)
                self._load_historical_checkpoints()
                self._update_enemy_candidates()

                # 5. 최종 결과 저장 및 GitHub 푸시
                self.save_model(self.model_path)
                self.sync_to_github(root_dir, rel_model_path, self.total_trained_episodes, include_best_model=promoted)

            if ep % update_batch_episodes == 0:
                self.save_model(self.model_path)
                self.sync_to_github(root_dir, rel_model_path, self.total_trained_episodes, include_best_model=False)
            gc.collect()

        flush_batch_if_needed(force=True)

    def sync_to_github(self, root_dir, rel_model_path, ep, include_best_model=False):
        try:
            token = os.getenv("GITHUB_TOKEN")
            if not token:
                print(f"[Git-Push] Skipped at episode {ep}: GITHUB_TOKEN not set.", flush=True)
                return

            print(f"[Git-Push] Preparing sync at episode {ep}...", flush=True)
            push_url = f"https://warrior-0:{token.strip()}@github.com/warrior-0/autocardbattle.git"
            model_abs_path = os.path.join(root_dir, rel_model_path)
            if os.path.exists(model_abs_path):
                subprocess.run(["git", "add", rel_model_path], cwd=root_dir, check=True)
                print(f"[Git-Push] Staged model: {rel_model_path}", flush=True)

            checkpoint_dir = os.path.dirname(model_abs_path)
            for cp in sorted(glob.glob(os.path.join(checkpoint_dir, "checkpoint_ep_*.json"))):
                rel_cp = os.path.relpath(cp, root_dir)
                subprocess.run(["git", "add", rel_cp], cwd=root_dir, check=True)
                print(f"[Git-Push] Staged checkpoint: {rel_cp}", flush=True)

            if include_best_model:
                best_model_abs = self._best_model_path()
                if os.path.exists(best_model_abs):
                    rel_best = os.path.relpath(best_model_abs, root_dir)
                    subprocess.run(["git", "add", rel_best], cwd=root_dir, check=True)
                    print(f"[Git-Push] Staged promoted best model: {rel_best}", flush=True)

            res = subprocess.run(["git", "commit", "-m", f"chore: update model at {ep}"], cwd=root_dir, capture_output=True)
            if res.returncode == 0:
                push_res = subprocess.run(["git", "push", push_url, "main"], cwd=root_dir, capture_output=True, text=True)
                if push_res.returncode == 0:
                    print(f"[Git-Push] Success at episode {ep}.", flush=True)
                else:
                    err = (push_res.stderr or "").strip()
                    print(f"[Git-Push] Failed at episode {ep}: {err}", flush=True)
            else:
                out = (res.stdout or b"").decode("utf-8", errors="ignore").strip()
                err = (res.stderr or b"").decode("utf-8", errors="ignore").strip()
                msg = err if err else out if out else "Nothing to commit."
                print(f"[Git-Push] Commit skipped at episode {ep}: {msg}", flush=True)
        except Exception as e:
            print(f"[Git-Push] Error at episode {ep}: {e}", flush=True)


if __name__ == "__main__":
    model_path = os.getenv("AUTOCARDBATTLE_MODEL_PATH", "src/main/resources/python/q_policy.json")
    episodes = int(os.getenv("AUTOCARDBATTLE_TRAIN_EPISODES", "50"))
    trainer = AITrainer(model_path)
    trainer.train(episodes=episodes)
