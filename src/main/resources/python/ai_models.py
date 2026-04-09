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


def _masked_softmax_numba(logits: np.ndarray, mask: np.ndarray) -> np.ndarray:
    valid = (mask > 0.5).astype(np.float32)
    masked = logits.astype(np.float32).copy()
    masked[valid <= 0.5] = -1e10
    m = np.max(masked, axis=1, keepdims=True)
    exp_x = np.exp(masked - m) * valid
    return exp_x / (np.sum(exp_x, axis=1, keepdims=True) + 1e-8)


def _randn_f32(shape: tuple[int, ...], scale: float) -> np.ndarray:
    return (np.random.randn(*shape) * scale).astype(np.float32)


@njit(cache=True, fastmath=True)
def _conv2d_same_dilated_forward_numba(x, w, b, dilation):
    bs, in_ch, h, wid = x.shape
    out_ch, _, k, _ = w.shape
    pad = (k // 2) * dilation
    x_pad = np.zeros((bs, in_ch, h + 2 * pad, wid + 2 * pad), dtype=np.float32)
    x_pad[:, :, pad:pad + h, pad:pad + wid] = x

    out = np.zeros((bs, out_ch, h, wid), dtype=np.float32)

    for n in range(bs):
        for oc in range(out_ch):
            for i in range(h):
                for j in range(wid):
                    out[n, oc, i, j] = b[oc]
            for ic in range(in_ch):
                for ki in range(k):
                    for kj in range(k):
                        wv = w[oc, ic, ki, kj]
                        i_off = ki * dilation
                        j_off = kj * dilation
                        for i in range(h):
                            ii = i + i_off
                            for j in range(wid):
                                jj = j + j_off
                                out[n, oc, i, j] += x_pad[n, ic, ii, jj] * wv
    return out


@njit(cache=True, fastmath=True)
def _conv2d_same_dilated_backward_numba(x, w, dout, dilation):
    bs, in_ch, h, wid = x.shape
    out_ch, _, k, _ = w.shape
    pad = (k // 2) * dilation

    x_pad = np.zeros((bs, in_ch, h + 2 * pad, wid + 2 * pad), dtype=np.float32)
    x_pad[:, :, pad:pad + h, pad:pad + wid] = x
    dx_pad = np.zeros_like(x_pad, dtype=np.float32)
    dw = np.zeros_like(w, dtype=np.float32)
    db = np.zeros((out_ch,), dtype=np.float32)

    for n in range(bs):
        for oc in range(out_ch):
            db[oc] += np.sum(dout[n, oc])
            for ic in range(in_ch):
                for ki in range(k):
                    for kj in range(k):
                        wv = w[oc, ic, ki, kj]
                        i_off = ki * dilation
                        j_off = kj * dilation
                        for i in range(h):
                            ii = i + i_off
                            for j in range(wid):
                                jj = j + j_off
                                g = dout[n, oc, i, j]
                                dw[oc, ic, ki, kj] += x_pad[n, ic, ii, jj] * g
                                dx_pad[n, ic, ii, jj] += wv * g

    dx = dx_pad[:, :, pad:pad + h, pad:pad + wid]
    return dx, dw, db


@njit(cache=True)
def _min_enemy_distance_channel_numba(spatial: np.ndarray) -> np.ndarray:
    bs = spatial.shape[0]
    out = np.ones((bs, 1, 8, 8), dtype=np.float32)

    own_y = np.zeros((64,), dtype=np.int32)
    own_x = np.zeros((64,), dtype=np.int32)
    opp_y = np.zeros((64,), dtype=np.int32)
    opp_x = np.zeros((64,), dtype=np.int32)

    for n in range(bs):
        own_cnt = 0
        opp_cnt = 0

        for i in range(8):
            for j in range(8):
                own_p = 0
                opp_p = 0
                for c in range(1, 6):
                    if abs(spatial[n, c, i, j]) > 1e-6:
                        own_p = 1
                        break
                for c in range(6, 11):
                    if abs(spatial[n, c, i, j]) > 1e-6:
                        opp_p = 1
                        break
                if own_p == 1:
                    own_y[own_cnt] = i
                    own_x[own_cnt] = j
                    own_cnt += 1
                if opp_p == 1:
                    opp_y[opp_cnt] = i
                    opp_x[opp_cnt] = j
                    opp_cnt += 1

        if own_cnt == 0 or opp_cnt == 0:
            continue

        min_dist = 1000
        for oi in range(own_cnt):
            for ej in range(opp_cnt):
                d = abs(own_y[oi] - opp_y[ej]) + abs(own_x[oi] - opp_x[ej])
                if d < min_dist:
                    min_dist = d

        v = np.float32(min_dist / 14.0)
        for i in range(8):
            for j in range(8):
                out[n, 0, i, j] = v

    return out


class PPONetwork:
    """
    NumPy 기반 PPO Actor-Critic 네트워크.

    Hybrid 구조:
    - Spatial 입력: map + own/opp unit stat channels -> CNN
      * conv1: 일반 same conv
      * conv2: dilated conv(dilation=2)로 먼 거리 문맥 포착
    - Non-spatial 입력: common + hand_stat_summary + result -> MLP
    - 두 경로를 concat 후 policy/value head 출력
    """

    SPATIAL_CHANNELS = 11
    EXTRA_SPATIAL_CHANNELS = 1  # min_enemy_distance map
    MAP_TILES = 64
    SPATIAL_SIZE = SPATIAL_CHANNELS * MAP_TILES
    COMMON_SIZE = 4
    NON_SPATIAL_SIZE = 6

    def __init__(
        self,
        state_size: int,
        action_size: int,
        learning_rate: float = 0.0004,
        clip_epsilon: float = 0.2,
        entropy_coef: float = 0.02,
        value_coef: float = 0.5,
        target_kl: float = 0.05,
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
        # Spatial CNN branch
        spatial_in_ch = self.SPATIAL_CHANNELS + self.EXTRA_SPATIAL_CHANNELS
        self.conv1_w = _randn_f32((16, spatial_in_ch, 3, 3), np.sqrt(2.0 / (spatial_in_ch * 3 * 3)))
        self.conv1_b = np.zeros((16,), dtype=np.float32)
        self.conv2_w = _randn_f32((32, 16, 3, 3), np.sqrt(2.0 / (16 * 3 * 3)))
        self.conv2_b = np.zeros((32,), dtype=np.float32)
        # global average pooling(32) -> projection(128)
        self.spatial_fc_w = _randn_f32((128, 32), np.sqrt(2.0 / 32))
        self.spatial_fc_b = np.zeros((128, 1), dtype=np.float32)

        # Non-spatial branch
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

    def _min_enemy_distance_channel(self, spatial: np.ndarray) -> np.ndarray:
        return _min_enemy_distance_channel_numba(spatial)

    @staticmethod
    def _conv2d_same_forward(x: np.ndarray, w: np.ndarray, b: np.ndarray, dilation: int = 1) -> np.ndarray:
        return _conv2d_same_dilated_forward_numba(x, w, b, dilation)

    @staticmethod
    def _conv2d_same_backward(x: np.ndarray, w: np.ndarray, dout: np.ndarray, dilation: int = 1) -> Tuple[np.ndarray, np.ndarray, np.ndarray]:
        return _conv2d_same_dilated_backward_numba(x, w, dout, dilation)

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
        hand_and_result = x2[:, spatial_end:spatial_end + self.NON_SPATIAL_SIZE]

        spatial = spatial_flat.reshape(x2.shape[0], self.SPATIAL_CHANNELS, 8, 8).astype(np.float32)
        min_enemy_ch = self._min_enemy_distance_channel(spatial)
        spatial_aug = np.concatenate([spatial, min_enemy_ch], axis=1)
        non_spatial = np.concatenate([common, hand_and_result], axis=1)

        return spatial_aug.astype(np.float32), non_spatial.astype(np.float32)

    def _forward_internal(self, x: np.ndarray) -> Tuple[np.ndarray, np.ndarray]:
        spatial, non_spatial = self._split_state(x)

        c1 = self._conv2d_same_forward(spatial, self.conv1_w, self.conv1_b, dilation=1)
        a_c1 = self._relu(c1)
        c2 = self._conv2d_same_forward(a_c1, self.conv2_w, self.conv2_b, dilation=2)
        a_c2 = self._relu(c2)

        pooled = np.mean(a_c2, axis=(2, 3))  # (bs,32)
        spatial_pool_t = pooled.T  # (32,bs)
        z_sp = np.dot(self.spatial_fc_w, spatial_pool_t) + self.spatial_fc_b
        a_sp = self._relu(z_sp)

        non_in = non_spatial.T
        z_n1 = np.dot(self.non_fc1_w, non_in) + self.non_fc1_b
        a_n1 = self._relu(z_n1)
        z_n2 = np.dot(self.non_fc2_w, a_n1) + self.non_fc2_b
        a_n2 = self._relu(z_n2)

        fused = np.empty((128 + 64, a_sp.shape[1]), dtype=np.float32)
        fused[:128] = a_sp
        fused[128:] = a_n2
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
            "spatial_pool_t": spatial_pool_t,
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

    def update_ppo(
        self,
        states: np.ndarray,
        action_indices: np.ndarray,
        old_log_probs: np.ndarray,
        action_masks: np.ndarray,
        returns: np.ndarray,
        advantages: np.ndarray,
        epochs: int = 6,
        minibatch_size: int = 128,
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
                adv = (adv - np.mean(adv)) / (np.std(adv) + 1e-8)
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

                # Early stop check moved to end of epoch to allow more learning
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
                dw_spatial_fc = np.dot(dz_sp, self.cache["spatial_pool_t"].T)
                db_spatial_fc = np.sum(dz_sp, axis=1, keepdims=True)
                dpool = np.dot(self.spatial_fc_w.T, dz_sp)  # (32,bs)
                da_c2 = np.broadcast_to((dpool.T[:, :, None, None] / 64.0).astype(np.float32), self.cache["a_c2"].shape)
                dc2 = da_c2 * (self.cache["c2"] > 0)

                da_c1, dw_conv2, db_conv2 = self._conv2d_same_backward(self.cache["a_c1"], self.conv2_w, dc2, dilation=2)
                dc1 = da_c1 * (self.cache["c1"] > 0)
                _, dw_conv1, db_conv1 = self._conv2d_same_backward(self.cache["spatial"], self.conv1_w, dc1, dilation=1)

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
            
            # Check average KL after each full epoch instead of per-minibatch
            avg_kl_this_epoch = total_kl / max(1, kl_batches)
            if avg_kl_this_epoch > self.target_kl:
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

            # CNN format (current)
            if "conv1.weight" in state_dict:
                self.conv1_w = to_numpy(state_dict["conv1.weight"])
                self.conv1_b = to_numpy(state_dict["conv1.bias"])
                self.conv2_w = to_numpy(state_dict["conv2.weight"])
                self.conv2_b = to_numpy(state_dict["conv2.bias"])
                spatial_fc_w = to_numpy(state_dict["spatial_fc.weight"])
                spatial_fc_b = to_numpy(state_dict["spatial_fc.bias"]).reshape(-1, 1)
                if spatial_fc_w.shape == self.spatial_fc_w.shape:
                    self.spatial_fc_w = spatial_fc_w
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

            # MLP format fallback (best-effort for shared layers)
            if "spatial_mlp1.weight" in state_dict:
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

            # Legacy MLP format
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
