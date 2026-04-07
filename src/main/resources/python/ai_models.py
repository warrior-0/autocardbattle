from __future__ import annotations

from typing import Dict, Any, Tuple
import importlib.util
import numpy as np

_numba_spec = importlib.util.find_spec("numba")
if _numba_spec is not None:
    njit = importlib.import_module("numba").njit
else:
    def njit(*args, **kwargs):
        def _decorator(func):
            return func
        return _decorator


@njit(cache=True)
def _masked_softmax_numba(logits: np.ndarray, mask: np.ndarray) -> np.ndarray:
    bs, action_size = logits.shape
    out = np.zeros((bs, action_size), dtype=np.float32)
    for i in range(bs):
        max_logit = -1e10
        for j in range(action_size):
            v = logits[i, j] if mask[i, j] > 0.5 else -1e10
            if v > max_logit:
                max_logit = v
        s = 0.0
        for j in range(action_size):
            v = logits[i, j] if mask[i, j] > 0.5 else -1e10
            e = np.exp(v - max_logit)
            out[i, j] = e
            s += e
        denom = s + 1e-8
        for j in range(action_size):
            out[i, j] = out[i, j] / denom
    return out


def _randn_f32(shape: tuple[int, ...], scale: float) -> np.ndarray:
    return (np.random.randn(*shape) * scale).astype(np.float32)


class PPONetwork:
    """
    NumPy 기반 PPO Actor-Critic 네트워크.

    Hybrid 구조:
    - Spatial 입력: map + own/opp unit stat channels(주사위별 정보 포함) -> flattened MLP
    - Non-spatial 입력: common + hand_stat_summary + result -> MLP
    - 두 경로를 concat 후 policy/value head 출력

    PPO update 루프/인터페이스는 기존과 동일하게 유지.
    """

    SPATIAL_CHANNELS = 11  # map + own(5 stats) + opp(5 stats)
    EXTRA_SPATIAL_CHANNELS = 4  # x, y, nearest_own_dist, nearest_opp_dist
    MAP_TILES = 64
    SPATIAL_SIZE = SPATIAL_CHANNELS * MAP_TILES
    SPATIAL_HIDDEN1 = 256
    SPATIAL_HIDDEN2 = 512
    COMMON_SIZE = 4
    NON_SPATIAL_SIZE = 6  # hand_stat_summary(5) + result(1)

    def __init__(
        self,
        state_size: int,
        action_size: int,
        learning_rate: float = 0.0003,
        clip_epsilon: float = 0.2,
        entropy_coef: float = 0.01,
        value_coef: float = 0.5,
        target_kl: float = 0.02,
    ):
        self.state_size = state_size
        self.action_size = action_size
        self.learning_rate = learning_rate
        self.clip_epsilon = clip_epsilon
        self.entropy_coef = entropy_coef
        self.value_coef = value_coef
        self.target_kl = target_kl

        expected = self.COMMON_SIZE + self.SPATIAL_SIZE + self.NON_SPATIAL_SIZE
        if state_size != expected:
            raise ValueError(f"state_size({state_size}) must be exactly {expected} for fixed hybrid split")

        # common(4) + hand_stat_summary(5) + result(1)
        self.non_spatial_size = self.COMMON_SIZE + self.NON_SPATIAL_SIZE
        self.spatial_input_size = (self.SPATIAL_CHANNELS + self.EXTRA_SPATIAL_CHANNELS) * self.MAP_TILES
        grid_y, grid_x = np.indices((8, 8), dtype=np.float32)
        self._x_channel = ((grid_x / 7.0) * 2.0 - 1.0).astype(np.float32)
        self._y_channel = ((grid_y / 7.0) * 2.0 - 1.0).astype(np.float32)
        self._grid_coords = np.stack([grid_y.reshape(-1), grid_x.reshape(-1)], axis=1).astype(np.int32)

        # Spatial branch: 완전 MLP (CNN 연산/구조 미사용)
        self.spatial_mlp1_w = _randn_f32((self.SPATIAL_HIDDEN1, self.spatial_input_size), np.sqrt(2.0 / self.spatial_input_size))
        self.spatial_mlp1_b = np.zeros((self.SPATIAL_HIDDEN1, 1), dtype=np.float32)
        self.spatial_mlp2_w = _randn_f32((self.SPATIAL_HIDDEN2, self.SPATIAL_HIDDEN1), np.sqrt(2.0 / self.SPATIAL_HIDDEN1))
        self.spatial_mlp2_b = np.zeros((self.SPATIAL_HIDDEN2, 1), dtype=np.float32)
        self.spatial_fc_w = _randn_f32((128, self.SPATIAL_HIDDEN2), np.sqrt(2.0 / self.SPATIAL_HIDDEN2))
        self.spatial_fc_b = np.zeros((128, 1), dtype=np.float32)

        # Non-spatial branch: MLP
        self.non_fc1_w = _randn_f32((64, self.non_spatial_size), np.sqrt(2.0 / self.non_spatial_size))
        self.non_fc1_b = np.zeros((64, 1), dtype=np.float32)
        self.non_fc2_w = _randn_f32((64, 64), np.sqrt(2.0 / 64))
        self.non_fc2_b = np.zeros((64, 1), dtype=np.float32)

        # Fusion + heads
        self.fuse_w = _randn_f32((128, 128 + 64), np.sqrt(2.0 / (128 + 64)))
        self.fuse_b = np.zeros((128, 1), dtype=np.float32)
        self.policy_w = _randn_f32((action_size, 128), np.sqrt(2.0 / 128))
        self.policy_b = np.zeros((action_size, 1), dtype=np.float32)
        self.value_w = _randn_f32((1, 128), np.sqrt(2.0 / 128))
        self.value_b = np.zeros((1, 1), dtype=np.float32)

        self.cache: Dict[str, np.ndarray] = {}

    @staticmethod
    def _relu(x: np.ndarray) -> np.ndarray:
        return np.maximum(0.0, x)

    def _nearest_distance_channel(self, mask_2d: np.ndarray) -> np.ndarray:
        occupied = np.argwhere(mask_2d > 0.5)
        if occupied.shape[0] == 0:
            return np.ones((8, 8), dtype=np.float32)
        d = np.abs(self._grid_coords[:, None, :] - occupied[None, :, :]).sum(axis=2)
        nearest = np.min(d, axis=1).astype(np.float32) / 14.0
        return nearest.reshape(8, 8)

    def _augment_spatial_features(self, spatial: np.ndarray) -> np.ndarray:
        bs = spatial.shape[0]
        x_ch = np.broadcast_to(self._x_channel, (bs, 8, 8))
        y_ch = np.broadcast_to(self._y_channel, (bs, 8, 8))

        own_presence = (np.max(np.abs(spatial[:, 1:6, :, :]), axis=1) > 1e-6).astype(np.float32)
        opp_presence = (np.max(np.abs(spatial[:, 6:11, :, :]), axis=1) > 1e-6).astype(np.float32)

        own_dist = np.zeros((bs, 8, 8), dtype=np.float32)
        opp_dist = np.zeros((bs, 8, 8), dtype=np.float32)
        for i in range(bs):
            own_dist[i] = self._nearest_distance_channel(own_presence[i])
            opp_dist[i] = self._nearest_distance_channel(opp_presence[i])

        extra = np.stack([x_ch, y_ch, own_dist, opp_dist], axis=1)
        return np.concatenate([spatial, extra], axis=1).astype(np.float32)

    def _split_state(self, x: np.ndarray) -> Tuple[np.ndarray, np.ndarray]:
        if x.ndim == 1:
            x2 = x.reshape(1, -1)
        else:
            x2 = x
        if x2.shape[1] != self.state_size:
            raise ValueError(f"Invalid input state width: expected {self.state_size}, got {x2.shape[1]}")

        common = x2[:, :self.COMMON_SIZE]
        spatial_start = self.COMMON_SIZE
        spatial_end = spatial_start + self.SPATIAL_SIZE
        spatial_flat = x2[:, spatial_start:spatial_end]
        spatial = spatial_flat.reshape(x2.shape[0], self.SPATIAL_CHANNELS, 8, 8).astype(np.float32)
        spatial_aug = self._augment_spatial_features(spatial)
        spatial_flat_aug = spatial_aug.reshape(x2.shape[0], -1)
        hand_and_result = x2[:, spatial_end:spatial_end + self.NON_SPATIAL_SIZE]
        non_spatial = np.concatenate([common, hand_and_result], axis=1)
        return spatial_flat_aug.astype(np.float32), non_spatial.astype(np.float32)

    def _forward_internal(self, x: np.ndarray) -> Tuple[np.ndarray, np.ndarray]:
        spatial_flat_input, non_spatial = self._split_state(x)
        spatial_in = spatial_flat_input.T  # (spatial_input_size, bs)
        z_sm1 = np.dot(self.spatial_mlp1_w, spatial_in) + self.spatial_mlp1_b
        a_sm1 = self._relu(z_sm1)
        z_sm2 = np.dot(self.spatial_mlp2_w, a_sm1) + self.spatial_mlp2_b
        a_sm2 = self._relu(z_sm2)

        spatial_flat = a_sm2
        z_sp = np.dot(self.spatial_fc_w, spatial_flat) + self.spatial_fc_b
        a_sp = self._relu(z_sp)

        non_in = non_spatial.T
        z_n1 = np.dot(self.non_fc1_w, non_in) + self.non_fc1_b
        a_n1 = self._relu(z_n1)
        z_n2 = np.dot(self.non_fc2_w, a_n1) + self.non_fc2_b
        a_n2 = self._relu(z_n2)

        fused = np.vstack([a_sp, a_n2])
        z_f = np.dot(self.fuse_w, fused) + self.fuse_b
        a_f = self._relu(z_f)

        logits = np.dot(self.policy_w, a_f) + self.policy_b
        values = np.dot(self.value_w, a_f) + self.value_b

        self.cache = {
            "spatial_in": spatial_in,
            "z_sm1": z_sm1,
            "a_sm1": a_sm1,
            "z_sm2": z_sm2,
            "spatial_flat": spatial_flat,
            "z_sp": z_sp,
            "non_in": non_in,
            "z_n1": z_n1,
            "a_n1": a_n1,
            "z_n2": z_n2,
            "fused": fused,
            "z_f": z_f,
            "a_f": a_f,
        }
        return logits.T, values.T

    def forward(self, x: np.ndarray) -> Tuple[np.ndarray, np.ndarray]:
        logits, values = self._forward_internal(x)
        return logits, values.squeeze(-1)

    @staticmethod
    def _softmax(logits: np.ndarray) -> np.ndarray:
        m = np.max(logits, axis=1, keepdims=True)
        exp_x = np.exp(logits - m)
        return exp_x / (np.sum(exp_x, axis=1, keepdims=True) + 1e-8)

    def update_ppo(
        self,
        states: np.ndarray,
        action_indices: np.ndarray,
        old_log_probs: np.ndarray,
        action_masks: np.ndarray,
        returns: np.ndarray,
        advantages: np.ndarray,
        epochs: int = 4,
        minibatch_size: int = 64,
    ) -> dict:
        n = len(states)
        if n == 0:
            return {"loss": 0.0, "approx_kl": 0.0, "early_stop": False}

        total_loss = 0.0
        total_kl = 0.0
        kl_batches = 0
        early_stop = False
        for _ in range(epochs):
            perm = np.random.permutation(n)
            for start in range(0, n, minibatch_size):
                idx = perm[start:start + minibatch_size]
                s = states[idx]
                a_idx = action_indices[idx]
                old_lp = old_log_probs[idx]
                mask = action_masks[idx]
                ret = returns[idx]
                adv = advantages[idx]
                bs = len(idx)

                logits, values = self._forward_internal(s)
                values = values.squeeze(-1)
                probs = _masked_softmax_numba(logits.astype(np.float32), mask.astype(np.float32))

                chosen_probs = probs[np.arange(bs), a_idx]
                new_logp = np.log(chosen_probs + 1e-8)
                ratio = np.exp(new_logp - old_lp)
                approx_kl = float(np.mean(old_lp - new_logp))
                total_kl += approx_kl
                kl_batches += 1

                unclipped = ratio * adv
                clipped_ratio = np.clip(ratio, 1.0 - self.clip_epsilon, 1.0 + self.clip_epsilon)
                clipped = clipped_ratio * adv
                min_obj = np.minimum(unclipped, clipped)

                policy_loss = -np.mean(min_obj)
                value_loss = 0.5 * np.mean((values - ret) ** 2)
                entropy = -np.mean(np.sum(probs * np.log(probs + 1e-8), axis=1))
                loss = policy_loss + self.value_coef * value_loss - self.entropy_coef * entropy

                use_unclipped = unclipped <= clipped
                dL_dlogp = -np.where(use_unclipped, adv * ratio, adv * clipped_ratio).astype(np.float32) / bs

                one_hot = np.zeros_like(probs)
                one_hot[np.arange(bs), a_idx] = 1.0
                dlogits = dL_dlogp[:, None] * (one_hot - probs)

                log_probs = np.log(probs + 1e-8)
                entropy_grad = probs * (log_probs + np.sum(probs * log_probs, axis=1, keepdims=True))
                dlogits += self.entropy_coef * entropy_grad / bs

                dvalues = self.value_coef * (values - ret) / bs

                a_f = self.cache["a_f"]
                dlogits_t = dlogits.T
                dw_policy = np.dot(dlogits_t, a_f.T)
                db_policy = np.sum(dlogits_t, axis=1, keepdims=True)

                dv_col = dvalues.reshape(1, -1)
                dw_value = np.dot(dv_col, a_f.T)
                db_value = np.sum(dv_col, axis=1, keepdims=True)

                da_f = np.dot(self.policy_w.T, dlogits_t) + np.dot(self.value_w.T, dv_col)
                dz_f = da_f * (self.cache["z_f"] > 0)

                fused = self.cache["fused"]
                dw_fuse = np.dot(dz_f, fused.T)
                db_fuse = np.sum(dz_f, axis=1, keepdims=True)
                dfused = np.dot(self.fuse_w.T, dz_f)

                da_sp = dfused[:128, :]
                da_n2 = dfused[128:, :]

                dz_sp = da_sp * (self.cache["z_sp"] > 0)
                dw_spatial_fc = np.dot(dz_sp, self.cache["spatial_flat"].T)
                db_spatial_fc = np.sum(dz_sp, axis=1, keepdims=True)
                dspatial_flat = np.dot(self.spatial_fc_w.T, dz_sp)
                dz_sm2 = dspatial_flat * (self.cache["z_sm2"] > 0)
                dw_spatial_mlp2 = np.dot(dz_sm2, self.cache["a_sm1"].T)
                db_spatial_mlp2 = np.sum(dz_sm2, axis=1, keepdims=True)
                da_sm1 = np.dot(self.spatial_mlp2_w.T, dz_sm2)
                dz_sm1 = da_sm1 * (self.cache["z_sm1"] > 0)
                dw_spatial_mlp1 = np.dot(dz_sm1, self.cache["spatial_in"].T)
                db_spatial_mlp1 = np.sum(dz_sm1, axis=1, keepdims=True)

                dz_n2 = da_n2 * (self.cache["z_n2"] > 0)
                dw_non_fc2 = np.dot(dz_n2, self.cache["a_n1"].T)
                db_non_fc2 = np.sum(dz_n2, axis=1, keepdims=True)

                da_n1 = np.dot(self.non_fc2_w.T, dz_n2)
                dz_n1 = da_n1 * (self.cache["z_n1"] > 0)
                dw_non_fc1 = np.dot(dz_n1, self.cache["non_in"].T)
                db_non_fc1 = np.sum(dz_n1, axis=1, keepdims=True)

                lr = self.learning_rate
                self.policy_w -= lr * dw_policy
                self.policy_b -= lr * db_policy
                self.value_w -= lr * dw_value
                self.value_b -= lr * db_value
                self.fuse_w -= lr * dw_fuse
                self.fuse_b -= lr * db_fuse

                self.spatial_fc_w -= lr * dw_spatial_fc
                self.spatial_fc_b -= lr * db_spatial_fc
                self.spatial_mlp2_w -= lr * dw_spatial_mlp2
                self.spatial_mlp2_b -= lr * db_spatial_mlp2
                self.spatial_mlp1_w -= lr * dw_spatial_mlp1
                self.spatial_mlp1_b -= lr * db_spatial_mlp1

                self.non_fc2_w -= lr * dw_non_fc2
                self.non_fc2_b -= lr * db_non_fc2
                self.non_fc1_w -= lr * dw_non_fc1
                self.non_fc1_b -= lr * db_non_fc1

                total_loss += float(loss)
                if approx_kl > self.target_kl:
                    early_stop = True
                    break
            if early_stop:
                break

        steps = max(1, epochs * int(np.ceil(n / minibatch_size)))
        return {
            "loss": total_loss / steps,
            "approx_kl": total_kl / max(1, kl_batches),
            "early_stop": early_stop,
        }

    def load_state_dict(self, state_dict: Dict[str, Any], strict: bool = True) -> None:
        try:
            def copy_overlap(dst: np.ndarray, src: np.ndarray) -> np.ndarray:
                out = np.array(dst, copy=True)
                rows = min(out.shape[0], src.shape[0])
                cols = min(out.shape[1], src.shape[1]) if out.ndim == 2 else 1
                if out.ndim == 2:
                    out[:rows, :cols] = src[:rows, :cols]
                else:
                    out[:rows] = src[:rows]
                return out

            def to_numpy(v):
                if hasattr(v, "detach"):
                    return v.detach().cpu().numpy()
                return np.array(v, dtype=np.float32)

            # Converted spatial MLP format
            if "spatial_mlp1.weight" in state_dict:
                self.spatial_mlp1_w = copy_overlap(self.spatial_mlp1_w, to_numpy(state_dict["spatial_mlp1.weight"]))
                self.spatial_mlp1_b = copy_overlap(self.spatial_mlp1_b, to_numpy(state_dict["spatial_mlp1.bias"]).reshape(-1, 1))
                self.spatial_mlp2_w = copy_overlap(self.spatial_mlp2_w, to_numpy(state_dict["spatial_mlp2.weight"]))
                self.spatial_mlp2_b = copy_overlap(self.spatial_mlp2_b, to_numpy(state_dict["spatial_mlp2.bias"]).reshape(-1, 1))
                self.spatial_fc_w = copy_overlap(self.spatial_fc_w, to_numpy(state_dict["spatial_fc.weight"]))
                self.spatial_fc_b = copy_overlap(self.spatial_fc_b, to_numpy(state_dict["spatial_fc.bias"]).reshape(-1, 1))

                self.non_fc1_w = to_numpy(state_dict["non_fc1.weight"])
                self.non_fc1_b = to_numpy(state_dict["non_fc1.bias"]).reshape(-1, 1)
                self.non_fc2_w = to_numpy(state_dict["non_fc2.weight"])
                self.non_fc2_b = to_numpy(state_dict["non_fc2.bias"]).reshape(-1, 1)

                self.fuse_w = to_numpy(state_dict["fuse.weight"])
                self.fuse_b = to_numpy(state_dict["fuse.bias"]).reshape(-1, 1)
                self.policy_w = to_numpy(state_dict["policy_head.weight"])
                self.policy_b = to_numpy(state_dict["policy_head.bias"]).reshape(-1, 1)
                self.value_w = to_numpy(state_dict["value_head.weight"])
                self.value_b = to_numpy(state_dict["value_head.bias"]).reshape(1, 1)
                self.action_size = self.policy_w.shape[0]
                return

            # Legacy hybrid(CNN) format:
            # conv 계층은 구조가 달라 직접 로드하지 않고, 나머지 공용 계층은 최대한 재사용
            if "conv1.weight" in state_dict:
                if "spatial_fc.weight" in state_dict:
                    spatial_fc_w = to_numpy(state_dict["spatial_fc.weight"])
                    if spatial_fc_w.shape == self.spatial_fc_w.shape:
                        self.spatial_fc_w = spatial_fc_w
                if "spatial_fc.bias" in state_dict:
                    spatial_fc_b = to_numpy(state_dict["spatial_fc.bias"]).reshape(-1, 1)
                    if spatial_fc_b.shape == self.spatial_fc_b.shape:
                        self.spatial_fc_b = spatial_fc_b

                self.non_fc1_w = to_numpy(state_dict["non_fc1.weight"])
                self.non_fc1_b = to_numpy(state_dict["non_fc1.bias"]).reshape(-1, 1)
                self.non_fc2_w = to_numpy(state_dict["non_fc2.weight"])
                self.non_fc2_b = to_numpy(state_dict["non_fc2.bias"]).reshape(-1, 1)

                self.fuse_w = to_numpy(state_dict["fuse.weight"])
                self.fuse_b = to_numpy(state_dict["fuse.bias"]).reshape(-1, 1)
                self.policy_w = to_numpy(state_dict["policy_head.weight"])
                self.policy_b = to_numpy(state_dict["policy_head.bias"]).reshape(-1, 1)
                self.value_w = to_numpy(state_dict["value_head.weight"])
                self.value_b = to_numpy(state_dict["value_head.bias"]).reshape(1, 1)
                self.action_size = self.policy_w.shape[0]
                return

            # Legacy MLP format fallback (for old checkpoints)
            if all(k in state_dict for k in ["net.0.weight", "net.0.bias", "net.2.weight", "net.2.bias", "net.4.weight", "net.4.bias"]):
                legacy_w1 = to_numpy(state_dict["net.0.weight"])
                legacy_b1 = to_numpy(state_dict["net.0.bias"]).reshape(-1, 1)
                legacy_w2 = to_numpy(state_dict["net.2.weight"])
                legacy_b2 = to_numpy(state_dict["net.2.bias"]).reshape(-1, 1)
                legacy_w3 = to_numpy(state_dict["net.4.weight"])
                legacy_b3 = to_numpy(state_dict["net.4.bias"]).reshape(-1, 1)

                # hybrid의 non-spatial branch에 legacy 초반 일부를 이식
                rows = min(self.non_fc1_w.shape[0], legacy_w1.shape[0])
                cols = min(self.non_fc1_w.shape[1], legacy_w1.shape[1])
                self.non_fc1_w[:rows, :cols] = legacy_w1[:rows, :cols]
                self.non_fc1_b[:rows, :] = legacy_b1[:rows, :]

                rows2 = min(self.non_fc2_w.shape[0], legacy_w2.shape[0])
                cols2 = min(self.non_fc2_w.shape[1], legacy_w2.shape[1])
                self.non_fc2_w[:rows2, :cols2] = legacy_w2[:rows2, :cols2]
                self.non_fc2_b[:rows2, :] = legacy_b2[:rows2, :]

                p_rows = min(self.policy_w.shape[0], legacy_w3.shape[0])
                p_cols = min(self.policy_w.shape[1], legacy_w3.shape[1])
                self.policy_w[:p_rows, :p_cols] = legacy_w3[:p_rows, :p_cols]
                self.policy_b[:p_rows, :] = legacy_b3[:p_rows, :]
                self.action_size = self.policy_w.shape[0]

                if "value_net.0.weight" in state_dict:
                    val_w = to_numpy(state_dict["value_net.0.weight"])
                    rows_v = min(self.value_w.shape[0], val_w.shape[0])
                    cols_v = min(self.value_w.shape[1], val_w.shape[1])
                    self.value_w[:rows_v, :cols_v] = val_w[:rows_v, :cols_v]
                if "value_net.0.bias" in state_dict:
                    self.value_b = to_numpy(state_dict["value_net.0.bias"]).reshape(1, 1)
                return

            if strict:
                raise RuntimeError("Unknown state_dict format for PPONetwork")

        except KeyError as e:
            if strict:
                raise RuntimeError(f"Missing key in state_dict: {e}")
        except Exception as e:
            raise RuntimeError(f"Error loading state_dict: {e}")

    def state_dict(self) -> Dict[str, Any]:
        return {
            "spatial_mlp1.weight": self.spatial_mlp1_w.tolist(),
            "spatial_mlp1.bias": self.spatial_mlp1_b.flatten().tolist(),
            "spatial_mlp2.weight": self.spatial_mlp2_w.tolist(),
            "spatial_mlp2.bias": self.spatial_mlp2_b.flatten().tolist(),
            "spatial_fc.weight": self.spatial_fc_w.tolist(),
            "spatial_fc.bias": self.spatial_fc_b.flatten().tolist(),
            "non_fc1.weight": self.non_fc1_w.tolist(),
            "non_fc1.bias": self.non_fc1_b.flatten().tolist(),
            "non_fc2.weight": self.non_fc2_w.tolist(),
            "non_fc2.bias": self.non_fc2_b.flatten().tolist(),
            "fuse.weight": self.fuse_w.tolist(),
            "fuse.bias": self.fuse_b.flatten().tolist(),
            "policy_head.weight": self.policy_w.tolist(),
            "policy_head.bias": self.policy_b.flatten().tolist(),
            "value_head.weight": self.value_w.tolist(),
            "value_head.bias": self.value_b.flatten().tolist(),
        }
