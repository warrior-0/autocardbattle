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


class PPONetwork:
    """
    NumPy 기반 PPO Actor-Critic 네트워크.

    Hybrid 구조:
    - Spatial 입력: map + own/opp unit stat channels -> (N, 8, 8) CNN
    - Non-spatial 입력: common + hand_stat_summary + result -> MLP
    - 두 경로를 concat 후 policy/value head 출력

    PPO update 루프/인터페이스는 기존과 동일하게 유지.
    """

    SPATIAL_CHANNELS = 11  # map + own(5 stats) + opp(5 stats)
    MAP_TILES = 64
    SPATIAL_SIZE = SPATIAL_CHANNELS * MAP_TILES
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

        # CNN branch: (N,8,8) -> conv -> conv -> flatten -> fc
        self.conv1_w = np.random.randn(16, self.SPATIAL_CHANNELS, 3, 3) * np.sqrt(2.0 / (self.SPATIAL_CHANNELS * 3 * 3))
        self.conv1_b = np.zeros((16,), dtype=np.float32)
        self.conv2_w = np.random.randn(32, 16, 3, 3) * np.sqrt(2.0 / (16 * 3 * 3))
        self.conv2_b = np.zeros((32,), dtype=np.float32)
        self.spatial_fc_w = np.random.randn(128, 32 * 8 * 8) * np.sqrt(2.0 / (32 * 8 * 8))
        self.spatial_fc_b = np.zeros((128, 1), dtype=np.float32)

        # Non-spatial branch: MLP
        self.non_fc1_w = np.random.randn(64, self.non_spatial_size) * np.sqrt(2.0 / self.non_spatial_size)
        self.non_fc1_b = np.zeros((64, 1), dtype=np.float32)
        self.non_fc2_w = np.random.randn(64, 64) * np.sqrt(2.0 / 64)
        self.non_fc2_b = np.zeros((64, 1), dtype=np.float32)

        # Fusion + heads
        self.fuse_w = np.random.randn(128, 128 + 64) * np.sqrt(2.0 / (128 + 64))
        self.fuse_b = np.zeros((128, 1), dtype=np.float32)
        self.policy_w = np.random.randn(action_size, 128) * np.sqrt(2.0 / 128)
        self.policy_b = np.zeros((action_size, 1), dtype=np.float32)
        self.value_w = np.random.randn(1, 128) * np.sqrt(2.0 / 128)
        self.value_b = np.zeros((1, 1), dtype=np.float32)

        self.cache: Dict[str, np.ndarray] = {}

    @staticmethod
    def _relu(x: np.ndarray) -> np.ndarray:
        return np.maximum(0.0, x)

    @staticmethod
    def _conv2d_same_forward(x: np.ndarray, w: np.ndarray, b: np.ndarray) -> np.ndarray:
        bs, in_ch, h, wid = x.shape
        out_ch, _, k, _ = w.shape
        pad = k // 2
        x_pad = np.pad(x, ((0, 0), (0, 0), (pad, pad), (pad, pad)), mode="constant")
        out = np.zeros((bs, out_ch, h, wid), dtype=np.float32)
        for i in range(h):
            for j in range(wid):
                region = x_pad[:, :, i:i + k, j:j + k]  # (bs,in_ch,k,k)
                out[:, :, i, j] = np.tensordot(region, w, axes=([1, 2, 3], [1, 2, 3])) + b
        return out

    @staticmethod
    def _conv2d_same_backward(
        x: np.ndarray,
        w: np.ndarray,
        dout: np.ndarray,
    ) -> Tuple[np.ndarray, np.ndarray, np.ndarray]:
        bs, in_ch, h, wid = x.shape
        out_ch, _, k, _ = w.shape
        pad = k // 2

        x_pad = np.pad(x, ((0, 0), (0, 0), (pad, pad), (pad, pad)), mode="constant")
        dx_pad = np.zeros_like(x_pad, dtype=np.float32)
        dw = np.zeros_like(w, dtype=np.float32)
        db = np.sum(dout, axis=(0, 2, 3))

        for i in range(h):
            for j in range(wid):
                region = x_pad[:, :, i:i + k, j:j + k]  # (bs,in_ch,k,k)
                for oc in range(out_ch):
                    grad = dout[:, oc, i, j].reshape(bs, 1, 1, 1)
                    dw[oc] += np.sum(region * grad, axis=0)
                for n in range(bs):
                    dx_pad[n, :, i:i + k, j:j + k] += np.sum(w * dout[n, :, i, j][:, None, None, None], axis=0)

        if pad > 0:
            dx = dx_pad[:, :, pad:-pad, pad:-pad]
        else:
            dx = dx_pad
        return dx, dw, db

    def _split_state(self, x: np.ndarray) -> Tuple[np.ndarray, np.ndarray]:
        if x.ndim == 1:
            x2 = x.reshape(1, -1)
        else:
            x2 = x

        common = x2[:, :self.COMMON_SIZE]
        spatial_start = self.COMMON_SIZE
        spatial_end = spatial_start + self.SPATIAL_SIZE
        spatial_flat = x2[:, spatial_start:spatial_end]
        hand_and_result = x2[:, spatial_end:spatial_end + self.NON_SPATIAL_SIZE]
        non_spatial = np.concatenate([common, hand_and_result], axis=1)

        spatial = spatial_flat.reshape(x2.shape[0], self.SPATIAL_CHANNELS, 8, 8)
        return spatial.astype(np.float32), non_spatial.astype(np.float32)

    def _forward_internal(self, x: np.ndarray) -> Tuple[np.ndarray, np.ndarray]:
        spatial, non_spatial = self._split_state(x)

        c1 = self._conv2d_same_forward(spatial, self.conv1_w, self.conv1_b)
        a_c1 = self._relu(c1)
        c2 = self._conv2d_same_forward(a_c1, self.conv2_w, self.conv2_b)
        a_c2 = self._relu(c2)

        bs = spatial.shape[0]
        spatial_flat = a_c2.reshape(bs, -1).T  # (2048,bs)
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
            "spatial": spatial,
            "c1": c1,
            "a_c1": a_c1,
            "c2": c2,
            "a_c2": a_c2,
            "spatial_flat": spatial_flat,
            "z_sp": z_sp,
            "a_sp": a_sp,
            "non_in": non_in,
            "z_n1": z_n1,
            "a_n1": a_n1,
            "z_n2": z_n2,
            "a_n2": a_n2,
            "fused": fused,
            "z_f": z_f,
            "a_f": a_f,
            "logits": logits,
            "values": values,
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

                active = unclipped <= clipped
                dL_dlogp = np.zeros(bs, dtype=np.float32)
                dL_dlogp[active] = -(adv[active] * ratio[active]) / bs

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
                da_c2 = dspatial_flat.T.reshape(bs, 32, 8, 8)
                dc2 = da_c2 * (self.cache["c2"] > 0)

                da_c1, dw_conv2, db_conv2 = self._conv2d_same_backward(self.cache["a_c1"], self.conv2_w, dc2)
                dc1 = da_c1 * (self.cache["c1"] > 0)
                _, dw_conv1, db_conv1 = self._conv2d_same_backward(self.cache["spatial"], self.conv1_w, dc1)

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
                self.conv2_w -= lr * dw_conv2
                self.conv2_b -= lr * db_conv2
                self.conv1_w -= lr * dw_conv1
                self.conv1_b -= lr * db_conv1

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
            def to_numpy(v):
                if hasattr(v, "detach"):
                    return v.detach().cpu().numpy()
                return np.array(v, dtype=np.float32)

            # New hybrid format
            if "conv1.weight" in state_dict:
                self.conv1_w = to_numpy(state_dict["conv1.weight"])
                self.conv1_b = to_numpy(state_dict["conv1.bias"])
                self.conv2_w = to_numpy(state_dict["conv2.weight"])
                self.conv2_b = to_numpy(state_dict["conv2.bias"])
                self.spatial_fc_w = to_numpy(state_dict["spatial_fc.weight"])
                self.spatial_fc_b = to_numpy(state_dict["spatial_fc.bias"]).reshape(-1, 1)

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
            "conv1.weight": self.conv1_w.tolist(),
            "conv1.bias": self.conv1_b.tolist(),
            "conv2.weight": self.conv2_w.tolist(),
            "conv2.bias": self.conv2_b.tolist(),
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
