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
    - Shared trunk: state -> 256 -> 128
    - Policy head: 128 -> action logits
    - Value head: 128 -> state value
    """

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

        self.w1 = np.random.randn(256, state_size) * np.sqrt(2.0 / state_size)
        self.b1 = np.zeros((256, 1))
        self.w2 = np.random.randn(128, 256) * np.sqrt(2.0 / 256)
        self.b2 = np.zeros((128, 1))

        self.w3 = np.random.randn(action_size, 128) * np.sqrt(2.0 / 128)  # policy head
        self.b3 = np.zeros((action_size, 1))
        self.vw = np.random.randn(1, 128) * np.sqrt(2.0 / 128)             # value head
        self.vb = np.zeros((1, 1))

        self.cache: Dict[str, np.ndarray] = {}

    def _forward_internal(self, x: np.ndarray) -> Tuple[np.ndarray, np.ndarray]:
        if x.ndim == 1:
            x_in = x.reshape(-1, 1)
        else:
            x_in = x.T

        z1 = np.dot(self.w1, x_in) + self.b1
        a1 = np.maximum(0, z1)
        z2 = np.dot(self.w2, a1) + self.b2
        a2 = np.maximum(0, z2)

        logits = np.dot(self.w3, a2) + self.b3
        values = np.dot(self.vw, a2) + self.vb

        self.cache = {
            "x": x_in,
            "z1": z1,
            "a1": a1,
            "z2": z2,
            "a2": a2,
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
                logits = logits
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

                # dL/dlogp for unclipped branch only (clipped 영역은 gradient 0)
                active = unclipped <= clipped
                dL_dlogp = np.zeros(bs, dtype=np.float32)
                dL_dlogp[active] = -(adv[active] * ratio[active]) / bs

                # logp_a 미분: d log pi(a) / d logits = one_hot - probs
                one_hot = np.zeros_like(probs)
                one_hot[np.arange(bs), a_idx] = 1.0
                dlogits = dL_dlogp[:, None] * (one_hot - probs)

                # entropy 항 미분 (loss에 -entropy_coef*H 추가)
                log_probs = np.log(probs + 1e-8)
                entropy_grad = probs * (log_probs + np.sum(probs * log_probs, axis=1, keepdims=True))
                dlogits += self.entropy_coef * entropy_grad / bs

                dvalues = self.value_coef * (values - ret) / bs

                a2 = self.cache["a2"]
                dw3 = np.dot(dlogits.T, a2.T)
                db3 = np.sum(dlogits.T, axis=1, keepdims=True)

                dv_col = dvalues.reshape(1, -1)
                dvw = np.dot(dv_col, a2.T)
                dvb = np.sum(dv_col, axis=1, keepdims=True)

                da2 = np.dot(self.w3.T, dlogits.T) + np.dot(self.vw.T, dv_col)
                dz2 = da2 * (self.cache["z2"] > 0)
                dw2 = np.dot(dz2, self.cache["a1"].T)
                db2 = np.sum(dz2, axis=1, keepdims=True)

                da1 = np.dot(self.w2.T, dz2)
                dz1 = da1 * (self.cache["z1"] > 0)
                dw1 = np.dot(dz1, self.cache["x"].T)
                db1 = np.sum(dz1, axis=1, keepdims=True)

                lr = self.learning_rate
                self.w1 -= lr * dw1
                self.b1 -= lr * db1
                self.w2 -= lr * dw2
                self.b2 -= lr * db2
                self.w3 -= lr * dw3
                self.b3 -= lr * db3
                self.vw -= lr * dvw
                self.vb -= lr * dvb

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
                return np.array(v)

            self.w1 = to_numpy(state_dict["net.0.weight"])
            self.b1 = to_numpy(state_dict["net.0.bias"]).reshape(-1, 1)
            self.w2 = to_numpy(state_dict["net.2.weight"])
            self.b2 = to_numpy(state_dict["net.2.bias"]).reshape(-1, 1)
            self.w3 = to_numpy(state_dict["net.4.weight"])
            self.b3 = to_numpy(state_dict["net.4.bias"]).reshape(-1, 1)
            self.action_size = self.w3.shape[0]

            if "value_net.0.weight" in state_dict:
                self.vw = to_numpy(state_dict["value_net.0.weight"])
            if "value_net.0.bias" in state_dict:
                self.vb = to_numpy(state_dict["value_net.0.bias"]).reshape(1, 1)

        except KeyError as e:
            if strict:
                raise RuntimeError(f"Missing key in state_dict: {e}")
        except Exception as e:
            raise RuntimeError(f"Error loading state_dict: {e}")

    def state_dict(self) -> Dict[str, Any]:
        return {
            "net.0.weight": self.w1.tolist(),
            "net.0.bias": self.b1.flatten().tolist(),
            "net.2.weight": self.w2.tolist(),
            "net.2.bias": self.b2.flatten().tolist(),
            "net.4.weight": self.w3.tolist(),
            "net.4.bias": self.b3.flatten().tolist(),
            "value_net.0.weight": self.vw.tolist(),
            "value_net.0.bias": self.vb.flatten().tolist(),
        }


# Backward compatibility
DQNNetwork = PPONetwork
