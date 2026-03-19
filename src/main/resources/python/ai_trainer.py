from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Tuple
import json
import random

from ai_models import TabularQPolicy
from game_simulator import GameSimulator, LANES, DiceCatalogLoader

Action = Tuple[int, int]
COMPLETE_ACTION: Action = (-1, -1)
ACTION_SPACE: List[Action] = [COMPLETE_ACTION] + [(hand_index, lane) for hand_index in range(2) for lane in range(LANES)]
ACTION_TO_INDEX: Dict[Action, int] = {action: idx for idx, action in enumerate(ACTION_SPACE)}


@dataclass
class TrainingSummary:
    episodes: int
    wins: int
    losses: int
    draws: int
    average_reward: float
    evaluation_win_rate: float
    catalog_source: str


class AITrainer:
    def __init__(self, seed: int = 7):
        self.rng = random.Random(seed)
        self.policy = TabularQPolicy(action_size=len(ACTION_SPACE))
        self.learning_rate = 0.18
        self.discount_factor = 0.95
        self.epsilon = 1.0
        self.epsilon_decay = 0.995
        self.min_epsilon = 0.05
        _, self.catalog_source = DiceCatalogLoader.load()

    def choose_action(self, env: GameSimulator, state: Tuple[int, ...]) -> Action:
        valid_actions = env.get_valid_actions()
        valid_indices = [ACTION_TO_INDEX[action] for action in valid_actions]
        action_index = self.policy.choose_action_index(state, valid_indices, self.epsilon, self.rng)
        return ACTION_SPACE[action_index]

    def train(self, episodes: int = 3000) -> TrainingSummary:
        wins = losses = draws = 0
        total_reward = 0.0

        for _ in range(episodes):
            env = GameSimulator(seed=self.rng.randint(0, 10_000_000))
            state = env.reset()
            done = False

            while not done:
                action = self.choose_action(env, state)
                next_state, reward, done, info = env.step(action)

                valid_next_indices = [ACTION_TO_INDEX[a] for a in env.get_valid_actions()]
                next_best = 0.0 if done else max(self.policy.values(next_state)[idx] for idx in valid_next_indices)
                current_values = self.policy.values(state)
                action_index = ACTION_TO_INDEX[action]
                old_value = current_values[action_index]
                updated = (1 - self.learning_rate) * old_value + self.learning_rate * (reward + self.discount_factor * next_best)
                current_values[action_index] = updated

                total_reward += reward
                state = next_state

            winner = int(info.get("winner", 0))
            if winner > 0:
                wins += 1
            elif winner < 0:
                losses += 1
            else:
                draws += 1
            self.epsilon = max(self.min_epsilon, self.epsilon * self.epsilon_decay)

        evaluation = self.evaluate(games=250)
        return TrainingSummary(
            episodes=episodes,
            wins=wins,
            losses=losses,
            draws=draws,
            average_reward=total_reward / max(episodes, 1),
            evaluation_win_rate=evaluation["win_rate"],
            catalog_source=str(evaluation["catalog_source"]),
        )

    def evaluate(self, games: int = 200) -> Dict[str, float | int | str]:
        wins = losses = draws = 0
        total_round_margin = 0
        original_epsilon = self.epsilon
        self.epsilon = 0.0
        try:
            for _ in range(games):
                env = GameSimulator(seed=self.rng.randint(0, 10_000_000))
                state = env.reset()
                done = False
                info: Dict[str, float | int | str] = {}
                while not done:
                    valid_actions = env.get_valid_actions()
                    valid_indices = [ACTION_TO_INDEX[action] for action in valid_actions]
                    best_index = self.policy.best_action_index(state, valid_indices)
                    state, _, done, info = env.step(ACTION_SPACE[best_index])
                total_round_margin += int(info.get("enemy_hp", 0)) - int(info.get("player_hp", 0))
                winner = int(info.get("winner", 0))
                if winner > 0:
                    wins += 1
                elif winner < 0:
                    losses += 1
                else:
                    draws += 1
        finally:
            self.epsilon = original_epsilon

        return {
            "games": games,
            "wins": wins,
            "losses": losses,
            "draws": draws,
            "win_rate": wins / games,
            "average_hp_margin": total_round_margin / games,
            "catalog_source": env.catalog_source,
        }

    def save_model(self, filename: str | Path) -> None:
        path = Path(filename)
        payload = {
            "action_space": [list(action) for action in ACTION_SPACE],
            "catalog_source": self.catalog_source,
            "q_table": [{"state": list(state), "values": values} for state, values in self.policy.q_table.items()],
        }
        path.write_text(json.dumps(payload), encoding="utf-8")

    def load_model(self, filename: str | Path) -> None:
        payload = json.loads(Path(filename).read_text(encoding="utf-8"))
        self.catalog_source = payload.get("catalog_source", self.catalog_source)
        self.policy.q_table.clear()
        for row in payload.get("q_table", []):
            self.policy.q_table[tuple(row["state"])] = list(row["values"])


if __name__ == '__main__':
    trainer = AITrainer()
    summary = trainer.train(episodes=2500)
    model_path = Path(__file__).with_name('q_policy.json')
    trainer.save_model(model_path)
    print(json.dumps({
        "episodes": summary.episodes,
        "wins": summary.wins,
        "losses": summary.losses,
        "draws": summary.draws,
        "average_reward": round(summary.average_reward, 4),
        "evaluation_win_rate": round(summary.evaluation_win_rate, 4),
        "catalog_source": summary.catalog_source,
        "saved_model": str(model_path),
    }, ensure_ascii=False, indent=2))
