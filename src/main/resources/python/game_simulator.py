from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Sequence, Tuple
import json
import os
import random
import re
from urllib.error import URLError
from urllib.request import urlopen

LANES = 3
MAX_ACTIONS_PER_TURN = 3
STARTING_HP = 5
MAX_TURNS = 10
MAX_DECK_SIZE = 5
DEFAULT_SERVER_URL = os.getenv("AUTOCARDBATTLE_SERVER_URL", "http://localhost:8080")

Action = Tuple[int, int]  # (hand_index, lane_index), (-1, -1) == COMPLETE
SAVE_DICE_PATTERN = re.compile(
    r'saveDice\("(?P<diceType>[^\"]+)",\s*"(?P<name>[^\"]+)",\s*(?P<hp>\d+),\s*(?P<damage>\d+),\s*(?P<range>\d+),\s*(?P<aps>[\d.]+),\s*"(?P<description>[^\"]+)",\s*"(?P<color>[^\"]+)"\)'
)


@dataclass(frozen=True)
class DiceStat:
    dice_type: str
    name: str
    hp: int
    damage: int
    attack_range: int
    aps: float
    description: str
    color: str

    @property
    def base_power(self) -> float:
        return (self.damage * self.aps) + (self.hp / 500.0) + (self.attack_range * 0.35)


@dataclass(frozen=True)
class Placement:
    dice_type: str
    lane: int
    level: int = 1


class DiceCatalogLoader:
    @staticmethod
    def load(seed_file: str | Path | None = None, server_url: str | None = None) -> Tuple[List[DiceStat], str]:
        api_rows = DiceCatalogLoader._load_from_java_api(server_url or DEFAULT_SERVER_URL)
        if api_rows:
            return api_rows, "java_api"
        return DiceCatalogLoader._load_from_seed_file(seed_file), "seed_fallback"

    @staticmethod
    def _load_from_java_api(server_url: str) -> List[DiceStat]:
        endpoint = f"{server_url.rstrip('/')}/api/dice/list"
        try:
            with urlopen(endpoint, timeout=2.5) as response:
                payload = json.loads(response.read().decode("utf-8"))
        except (URLError, TimeoutError, json.JSONDecodeError, ValueError):
            return []

        if not isinstance(payload, list):
            return []

        dice: List[DiceStat] = []
        for row in payload:
            if not isinstance(row, dict) or "diceType" not in row:
                continue
            dice.append(
                DiceStat(
                    dice_type=row["diceType"],
                    name=row.get("name") or row["diceType"],
                    hp=int(row.get("hp", 0)),
                    damage=int(row.get("damage", 0)),
                    attack_range=int(row.get("range", 0)),
                    aps=float(row.get("aps", 0.0)),
                    description=row.get("description") or "",
                    color=row.get("color") or "#ffffff",
                )
            )
        return dice

    @staticmethod
    def _load_from_seed_file(seed_file: str | Path | None = None) -> List[DiceStat]:
        file_path = Path(seed_file) if seed_file else Path(__file__).resolve().parents[2] / "java" / "com" / "example" / "autocardbattle" / "service" / "DataInitialize.java"
        source = file_path.read_text(encoding="utf-8")
        dice: List[DiceStat] = []
        for match in SAVE_DICE_PATTERN.finditer(source):
            dice.append(
                DiceStat(
                    dice_type=match.group("diceType"),
                    name=match.group("name"),
                    hp=int(match.group("hp")),
                    damage=int(match.group("damage")),
                    attack_range=int(match.group("range")),
                    aps=float(match.group("aps")),
                    description=match.group("description"),
                    color=match.group("color"),
                )
            )
        if not dice:
            raise RuntimeError(f"No dice catalog entries could be loaded from {file_path}")
        return dice


class GameSimulator:
    """A lightweight turn-based environment for rough RL experimentation.

    Dice data follows the current Java application flow first by reading the
    `/api/dice/list` endpoint, which is already backed by Spring + DB. If the
    Java server is not available in the training environment, the simulator
    falls back to the same Spring seed definitions used to initialize dice.
    """

    def __init__(self, seed: int | None = None, dice_catalog: Sequence[DiceStat] | None = None, server_url: str | None = None):
        self.random = random.Random(seed)
        loaded_catalog, catalog_source = DiceCatalogLoader.load(server_url=server_url) if dice_catalog is None else (list(dice_catalog), "provided")
        self.dice_catalog = list(loaded_catalog)
        self.catalog_source = catalog_source
        self.server_url = server_url or DEFAULT_SERVER_URL
        if len(self.dice_catalog) < 2:
            raise ValueError("At least two dice entries are required to simulate battles")
        self.dice_by_type: Dict[str, DiceStat] = {dice.dice_type: dice for dice in self.dice_catalog}
        self.dice_types = tuple(dice.dice_type for dice in self.dice_catalog)
        self.reset()

    def reset(self) -> Tuple[int, ...]:
        self.turn = 1
        self.done = False
        self.player_hp = STARTING_HP
        self.enemy_hp = STARTING_HP
        self.player_board: List[Placement] = []
        self.enemy_board: List[Placement] = []
        self.player_actions_used = 0
        self.enemy_actions_used = 0
        self.last_round_result = 0
        self.player_deck = self._build_random_deck()
        self.enemy_deck = self._build_random_deck()
        self.player_hand: List[str] = []
        self.enemy_hand: List[str] = []
        self._fill_hand(self.player_hand, self.player_deck)
        self._fill_hand(self.enemy_hand, self.enemy_deck)
        return self.get_state()

    def _build_random_deck(self) -> List[str]:
        deck_size = min(MAX_DECK_SIZE, len(self.dice_catalog))
        return [dice.dice_type for dice in self.random.sample(self.dice_catalog, deck_size)]

    def _draw_from_deck(self, deck: Sequence[str]) -> str:
        return self.random.choice(list(deck))

    def _fill_hand(self, hand: List[str], deck: Sequence[str]) -> None:
        while len(hand) < 2:
            hand.append(self._draw_from_deck(deck))

    def get_valid_actions(self) -> List[Action]:
        if self.done:
            return [(-1, -1)]

        actions: List[Action] = [(-1, -1)]
        if self.player_actions_used >= MAX_ACTIONS_PER_TURN:
            return [(-1, -1)]

        occupied = {unit.lane for unit in self.player_board}
        for hand_index, dice_type in enumerate(self.player_hand):
            for lane in range(LANES):
                if lane not in occupied:
                    actions.append((hand_index, lane))
                else:
                    existing = next(unit for unit in self.player_board if unit.lane == lane)
                    if existing.dice_type == dice_type and existing.level < 3:
                        actions.append((hand_index, lane))
        return actions

    def _apply_action(self, board: List[Placement], hand: List[str], deck: Sequence[str], action: Action) -> bool:
        hand_index, lane = action
        if action == (-1, -1):
            return True
        if hand_index < 0 or hand_index >= len(hand):
            return False
        if lane < 0 or lane >= LANES:
            return False

        dice_type = hand[hand_index]
        existing = next((unit for unit in board if unit.lane == lane), None)
        if existing is None:
            board.append(Placement(dice_type=dice_type, lane=lane, level=1))
        elif existing.dice_type == dice_type and existing.level < 3:
            board.remove(existing)
            board.append(Placement(dice_type=dice_type, lane=lane, level=existing.level + 1))
        else:
            return False

        hand.pop(hand_index)
        self._fill_hand(hand, deck)
        return True

    def _board_power(self, board: Sequence[Placement], opponent: Sequence[Placement]) -> float:
        power = 0.0
        opposing_lanes = {unit.lane for unit in opponent}
        for unit in board:
            stats = self.dice_by_type[unit.dice_type]
            unit_power = stats.base_power * (1 + 0.7 * (unit.level - 1))
            if unit.lane == 0:
                unit_power += max(0.2, (stats.hp / 1000.0) * 0.6)
            if unit.lane == 2:
                unit_power += max(0.2, stats.attack_range * 0.18)
            if stats.attack_range >= 3 and unit.lane == 2:
                unit_power += 0.35
            if stats.dice_type in {"FIRE", "ELECTRIC", "WATER"} and unit.lane in opposing_lanes:
                unit_power += 0.25 + (stats.aps * 0.1)
            power += unit_power
        return power

    def _enemy_policy_action(self) -> Action:
        valid_actions: List[Action] = [(-1, -1)]
        if self.enemy_actions_used < MAX_ACTIONS_PER_TURN:
            occupied = {unit.lane for unit in self.enemy_board}
            for hand_index, dice_type in enumerate(self.enemy_hand):
                for lane in range(LANES):
                    if lane not in occupied:
                        valid_actions.append((hand_index, lane))
                    else:
                        existing = next(unit for unit in self.enemy_board if unit.lane == lane)
                        if existing.dice_type == dice_type and existing.level < 3:
                            valid_actions.append((hand_index, lane))

        if self.random.random() < 0.55:
            return self.random.choice(valid_actions)

        scored: List[Tuple[float, Action]] = []
        for action in valid_actions:
            if action == (-1, -1):
                score = -0.2 if self.enemy_actions_used == 0 else 0.0
            else:
                hand_index, lane = action
                stats = self.dice_by_type[self.enemy_hand[hand_index]]
                score = stats.base_power
                if lane == 0:
                    score += (stats.hp / 1000.0) * 0.75
                if lane == 2:
                    score += stats.attack_range * 0.2
                if stats.dice_type == "SNIPER" and lane == 2:
                    score += 0.9
                if stats.dice_type in {"FIRE", "ELECTRIC"} and lane == 1:
                    score += 0.45
            scored.append((score + self.random.random() * 0.05, action))
        scored.sort(reverse=True)
        return scored[0][1]

    def _resolve_enemy_turn(self) -> None:
        while self.enemy_actions_used < MAX_ACTIONS_PER_TURN:
            action = self._enemy_policy_action()
            if action == (-1, -1):
                break
            applied = self._apply_action(self.enemy_board, self.enemy_hand, self.enemy_deck, action)
            if applied:
                self.enemy_actions_used += 1
            else:
                break

    def _resolve_round(self) -> int:
        player_power = self._board_power(self.player_board, self.enemy_board)
        enemy_power = self._board_power(self.enemy_board, self.player_board)
        delta = player_power - enemy_power
        if delta > 0.75:
            self.enemy_hp -= 1
            return 1
        if delta < -0.75:
            self.player_hp -= 1
            return -1
        return 0

    def _start_next_round(self) -> None:
        self.turn += 1
        self.player_board.clear()
        self.enemy_board.clear()
        self.player_actions_used = 0
        self.enemy_actions_used = 0
        self.player_hand = []
        self.enemy_hand = []
        self._fill_hand(self.player_hand, self.player_deck)
        self._fill_hand(self.enemy_hand, self.enemy_deck)
        if self.turn > MAX_TURNS or self.player_hp <= 0 or self.enemy_hp <= 0:
            self.done = True

    def step(self, action: Action) -> Tuple[Tuple[int, ...], float, bool, Dict[str, int | str]]:
        if self.done:
            return self.get_state(), 0.0, True, {"winner": self._winner(), "catalog_source": self.catalog_source}

        reward = -0.01
        info: Dict[str, int | str] = {"catalog_source": self.catalog_source}
        valid_actions = self.get_valid_actions()
        if action not in valid_actions:
            reward -= 0.25
            action = (-1, -1)

        if action != (-1, -1):
            applied = self._apply_action(self.player_board, self.player_hand, self.player_deck, action)
            if applied:
                self.player_actions_used += 1
                reward += 0.03
            else:
                reward -= 0.15
                action = (-1, -1)

        if action == (-1, -1) or self.player_actions_used >= MAX_ACTIONS_PER_TURN:
            self._resolve_enemy_turn()
            round_result = self._resolve_round()
            self.last_round_result = round_result
            reward += round_result * 0.35
            if self.enemy_hp <= 0:
                reward += 1.0
            elif self.player_hp <= 0:
                reward -= 1.0
            info.update({
                "player_hp": self.player_hp,
                "enemy_hp": self.enemy_hp,
                "round_result": round_result,
            })
            self._start_next_round()
        else:
            info.update({
                "player_hp": self.player_hp,
                "enemy_hp": self.enemy_hp,
                "round_result": 99,
            })

        done = self.done
        if done:
            info["winner"] = self._winner()
        return self.get_state(), reward, done, info

    def _winner(self) -> int:
        if self.player_hp > self.enemy_hp:
            return 1
        if self.enemy_hp > self.player_hp:
            return -1
        return 0

    def get_state(self) -> Tuple[int, ...]:
        player_counts = [0] * len(self.dice_types)
        for dice_type in self.player_hand:
            player_counts[self.dice_types.index(dice_type)] += 1

        board_types = [0] * LANES
        board_levels = [0] * LANES
        for unit in self.player_board:
            board_types[unit.lane] = self.dice_types.index(unit.dice_type) + 1
            board_levels[unit.lane] = unit.level

        enemy_presence = [0] * LANES
        for unit in self.enemy_board:
            enemy_presence[unit.lane] = self.dice_types.index(unit.dice_type) + 1

        return (
            self.turn,
            self.player_hp,
            self.enemy_hp,
            self.player_actions_used,
            len(self.player_deck),
            *player_counts,
            *board_types,
            *board_levels,
            *enemy_presence,
            self.last_round_result + 1,
        )
