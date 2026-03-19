from __future__ import annotations

from pathlib import Path
from typing import Tuple

from ai_trainer import ACTION_SPACE, ACTION_TO_INDEX, AITrainer
from game_simulator import GameSimulator


class GameAI:
    def __init__(self, model_path: str | None = None):
        self.trainer = AITrainer()
        path = Path(model_path) if model_path else Path(__file__).with_name('q_policy.json')
        if path.exists():
            self.trainer.load_model(path)
        self.model_path = path

    def choose_action(self, env: GameSimulator, state: Tuple[int, ...]):
        valid_actions = env.get_valid_actions()
        valid_indices = [ACTION_TO_INDEX[action] for action in valid_actions]
        best_index = self.trainer.policy.best_action_index(state, valid_indices)
        return ACTION_SPACE[best_index]
