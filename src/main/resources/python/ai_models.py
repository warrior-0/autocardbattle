from __future__ import annotations

from dataclasses import dataclass, field
from typing import Dict, List, Sequence, Tuple
import random

Action = Tuple[int, int]
State = Tuple[int, ...]


@dataclass
class TabularQPolicy:
    action_size: int
    q_table: Dict[State, List[float]] = field(default_factory=dict)

    def _ensure_state(self, state: State) -> List[float]:
        if state not in self.q_table:
            self.q_table[state] = [0.0] * self.action_size
        return self.q_table[state]

    def values(self, state: State) -> List[float]:
        return self._ensure_state(state)

    def best_action_index(self, state: State, valid_indices: Sequence[int]) -> int:
        values = self._ensure_state(state)
        return max(valid_indices, key=lambda idx: values[idx])

    def choose_action_index(self, state: State, valid_indices: Sequence[int], epsilon: float, rng: random.Random) -> int:
        if rng.random() < epsilon:
            return rng.choice(list(valid_indices))
        return self.best_action_index(state, valid_indices)
