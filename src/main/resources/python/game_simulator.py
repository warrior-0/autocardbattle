from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Literal, Sequence, Tuple, Optional
from collections import deque
import json
import os
import random
import time
import numpy as np

# =========================
# Grid / Game constants
# =========================
GRID_SIZE = 8
TOTAL_TILES = GRID_SIZE * GRID_SIZE

MAX_ACTIONS_PER_TURN = 3
MAX_ROUNDS_PER_GAME = 20
STARTING_HP = 5
MAX_DECK_SIZE = 5
HAND_SIZE = 2
MAX_UNIT_LEVEL = 7

# Compatibility alias for older imports.
LANES = GRID_SIZE

Action = Tuple[int, int]  # (hand_index, tile_index), (-1, -1) == PASS
PASS_ACTION: Action = (-1, -1)
Side = Literal["player", "enemy"]


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


@dataclass(frozen=True)
class Placement:
    dice_type: str
    tile: int
    level: int = 1


@dataclass
class CombatLogEntry:
    attacker_x: int
    attacker_y: int
    target_x: int
    target_y: int
    damage: int
    skill: str
    time_delay: int


class DiceCatalogLoader:
    @staticmethod
    def _to_int(value, default: int = 0) -> int:
        try:
            return int(value)
        except (TypeError, ValueError):
            return default

    @staticmethod
    def _to_float(value, default: float = 0.0) -> float:
        try:
            return float(value)
        except (TypeError, ValueError):
            return default

    _cached_catalog: Optional[List[DiceStat]] = None

    @staticmethod
    def load_from_catalog_file(path: str | Path) -> List[DiceStat]:
        if DiceCatalogLoader._cached_catalog is not None:
            return DiceCatalogLoader._cached_catalog
            
        file_path = Path(path)
        if not file_path.exists():
            raise RuntimeError(f"카탈로그 파일을 찾을 수 없습니다: {file_path}")

        payload = json.loads(file_path.read_text(encoding="utf-8"))
        if not isinstance(payload, list) or not payload:
            raise RuntimeError("카탈로그 파일 형식이 올바르지 않거나 비어 있습니다.")

        dice_rows: List[DiceStat] = []
        for item in payload:
            if not isinstance(item, dict):
                continue
            dice_type = item.get("dice_type") or item.get("diceType")
            if not dice_type:
                continue

            dice_rows.append(
                DiceStat(
                    str(dice_type),
                    str(item.get("name", dice_type)),
                    DiceCatalogLoader._to_int(item.get("hp"), 1),
                    DiceCatalogLoader._to_int(item.get("damage"), 1),
                    DiceCatalogLoader._to_int(item.get("attack_range", item.get("range")), 1),
                    DiceCatalogLoader._to_float(item.get("aps"), 1.0),
                    str(item.get("description", "")),
                    str(item.get("color", "#6b7280")),
                )
            )

        if not dice_rows:
            raise RuntimeError("카탈로그 파일에서 유효한 주사위 데이터를 찾지 못했습니다.")
        DiceCatalogLoader._cached_catalog = dice_rows
        return dice_rows

    @staticmethod
    def load_from_seed_file(seed_file: str | Path | None = None) -> List[DiceStat]:
        return [
            DiceStat("WATER", "Water", 120, 15, 2, 1.0, "fallback", "#3b82f6"),
            DiceStat("FIRE", "Fire", 100, 18, 2, 1.1, "fallback", "#ef4444"),
            DiceStat("SNIPER", "Sniper", 85, 26, 4, 0.7, "fallback", "#a78bfa"),
            DiceStat("ELECTRIC", "Electric", 90, 20, 3, 0.9, "fallback", "#f59e0b"),
        ]


class GameSimulator:
    def __init__(
        self,
        seed: int | None = None,
        dice_catalog: Sequence[DiceStat] | None = None,
        map_data: str | Sequence[str] | None = None,
    ):
        if seed is None:
            seed = int(time.time() * 1000)
        self.random = random.Random(seed)

        if dice_catalog is None:
            catalog_file = os.getenv("AUTOCARDBATTLE_DICE_CATALOG_FILE")
            if not catalog_file:
                raise RuntimeError("AUTOCARDBATTLE_DICE_CATALOG_FILE 환경변수가 필요합니다.")
            try:
                self.dice_catalog = DiceCatalogLoader.load_from_catalog_file(catalog_file)
                self.catalog_source = "catalog_file"
            except Exception as e:
                print(f"카탈로그 파일 로딩 실패: {e}. 기본 주사위 사용")
                self.dice_catalog = DiceCatalogLoader.load_from_seed_file()
                self.catalog_source = "seed_file"
        else:
            self.dice_catalog = list(dice_catalog)
            self.catalog_source = "provided"

        if len(self.dice_catalog) < 2:
            raise ValueError("At least two dice are required")

        self.dice_by_type: Dict[str, DiceStat] = {d.dice_type: d for d in self.dice_catalog}
        self.dice_types = tuple(d.dice_type for d in self.dice_catalog)
        self.dice_type_to_index = {dt: i for i, dt in enumerate(self.dice_types)}

        self.map_data = self._load_map_data(map_data)
        if len(self.map_data) != TOTAL_TILES:
            raise ValueError(f"map_data must have {TOTAL_TILES} tiles, got {len(self.map_data)}")
        self.encoded_map = np.array(self._encode_map(), dtype=np.float32)
        self.allowed_tiles = {
            "player": [i for i, kind in enumerate(self.map_data) if self._tile_is_allowed_for_side(kind, "player")],
            "enemy": [i for i, kind in enumerate(self.map_data) if self._tile_is_allowed_for_side(kind, "enemy")],
        }

        self.reset()

    def _load_map_data(self, map_data: str | Sequence[str] | None) -> List[str]:
        if map_data is None:
            env_map = os.getenv("AUTOCARDBATTLE_MAP_DATA")
            if env_map:
                map_data = env_map
            else:
                left = ["MY_TILE"] * 32
                right = ["ENEMY_TILE"] * 32
                return left + right

        if isinstance(map_data, str):
            parsed = [token.strip() for token in map_data.split(",") if token.strip()]
            return parsed

        return [str(t).strip() for t in map_data]

    @staticmethod
    def _tile_xy(tile: int) -> Tuple[int, int]:
        return tile % GRID_SIZE, tile // GRID_SIZE

    @staticmethod
    def _distance(tile_a: int, tile_b: int) -> int:
        x1, y1 = GameSimulator._tile_xy(tile_a)
        x2, y2 = GameSimulator._tile_xy(tile_b)
        return max(abs(x1 - x2), abs(y1 - y2))

    @staticmethod
    def _tile_is_allowed_for_side(tile_kind: str, side: Side) -> bool:
        t = (tile_kind or "").strip().upper()
        if side == "player":
            return t in {"MY_TILE", "PLAYER_TILE", "ALLY_TILE", "BOTH_TILE", "SHARED_TILE", "ANY_TILE"}
        return t in {"ENEMY_TILE", "PLAYER_TILE", "ALLY_TILE", "BOTH_TILE", "SHARED_TILE", "ANY_TILE"}

    def get_allowed_tiles_for(self, side: Side) -> List[int]:
        return self.allowed_tiles[side]

    def reset(self) -> Tuple[Tuple[int, ...], Tuple[int, ...]]:
        self.turn = 1
        self.done = False
        self.player_hp = STARTING_HP
        self.enemy_hp = STARTING_HP
        self.current_round = 0
        self.last_round_result = 0

        self.player_board: Dict[int, Placement] = {}
        self.enemy_board: Dict[int, Placement] = {}
        self.player_actions_used = 0
        self.enemy_actions_used = 0

        self.player_deck, self.enemy_deck = self._build_self_play_decks()

        self.revealed_enemy_tiles = set() # 전투 전까지 적 유닛 정보를 숨기기 위한 세트

        self.player_hand: List[str] = []
        self.enemy_hand: List[str] = []
        self._fill_hand(self.player_hand, self.player_deck)
        self._fill_hand(self.enemy_hand, self.enemy_deck)

        return self.get_state_for("player"), self.get_state_for("enemy")

    def _build_self_play_decks(self) -> Tuple[List[str], List[str]]:
        if not self.dice_catalog:
            fallback = ["WATER", "FIRE", "SNIPER", "ELECTRIC", "IRON"]
            return list(fallback), list(fallback)
        
        all_dice_types = [d.dice_type for d in self.dice_catalog]
        
        if len(all_dice_types) >= 10:
            sampled = self.random.sample(all_dice_types, 10)
            player_deck = sampled[:5]
            enemy_deck = sampled[5:]
        else:
            player_deck = [self.random.choice(all_dice_types) for _ in range(5)]
            enemy_deck = [self.random.choice(all_dice_types) for _ in range(5)]
            
        return player_deck, enemy_deck

    def _draw_from_deck(self, deck: Sequence[str]) -> str:
        if not deck: return ""
        return self.random.choice(list(deck))

    def _fill_hand(self, hand: List[str], deck: Sequence[str]) -> None:
        while len(hand) < HAND_SIZE:
            drawn = self._draw_from_deck(deck)
            if not drawn: break
            hand.append(drawn)

    def _entities_for_side(self, side: Side):
        if side == "player":
            return self.player_board, self.player_hand, self.player_deck, self.player_actions_used
        return self.enemy_board, self.enemy_hand, self.enemy_deck, self.enemy_actions_used

    def get_valid_actions_for(self, side: Side) -> List[Action]:
        if self.done: return [PASS_ACTION]
        board, hand, _, actions_used = self._entities_for_side(side)
        if actions_used >= MAX_ACTIONS_PER_TURN: return [PASS_ACTION]
        
        actions: List[Action] = [PASS_ACTION]
        allowed_tiles = self.get_allowed_tiles_for(side)
        
        # [최적화] 보드 상태를 미리 파악하여 루프 내 사전 조회 최소화
        board_dice = {t: p.dice_type for t, p in board.items()}
        board_levels = {t: p.level for t, p in board.items()}
        
        for hi, dice_type in enumerate(hand):
            for tile in allowed_tiles:
                if tile not in board_dice:
                    actions.append((hi, tile))
                elif board_dice[tile] == dice_type and board_levels[tile] < MAX_UNIT_LEVEL:
                    actions.append((hi, tile))
        return actions

    def _apply_action(self, board: Dict[int, Placement], hand: List[str], deck: Sequence[str], action: Action) -> bool:
        hi, tile = action
        if action == PASS_ACTION: return True
        if hi < 0 or hi >= len(hand) or tile < 0 or tile >= TOTAL_TILES: return False
        dice_type = hand[hi]
        existing = board.get(tile)
        if existing is None:
            board[tile] = Placement(dice_type=dice_type, tile=tile, level=1)
        elif existing.dice_type == dice_type and existing.level < MAX_UNIT_LEVEL:
            board[tile] = Placement(dice_type=dice_type, tile=tile, level=existing.level + 1)
        else:
            return False
        hand.pop(hi)
        self._fill_hand(hand, deck)
        return True

    def _simulate_combat(self, player_board: Dict[int, Placement], enemy_board: Dict[int, Placement]):
        logs = []
        placements = list(player_board.values()) + list(enemy_board.values())
        if not placements:
            return 0, {"player": 0, "enemy": 0}, [], {"player": 0, "enemy": 0}, {"player": 0, "enemy": 0}
            
        num_units = len(placements)
        owners = np.array([0] * len(player_board) + [1] * len(enemy_board), dtype=np.int32)
        tiles = np.array([p.tile for p in placements], dtype=np.int32)
        levels = np.array([p.level for p in placements], dtype=np.int32)
        types = [p.dice_type for p in placements]
        
        # 유닛 스탯 벡터화 로딩
        hp = np.zeros(num_units, dtype=np.int32)
        damage = np.zeros(num_units, dtype=np.int32)
        base_aps = np.zeros(num_units, dtype=np.float32)
        ranges = np.zeros(num_units, dtype=np.int32)
        
        # 미리 주사위 스탯을 배열로 변환하여 루프 내 사전 조회를 최소화
        for i, p in enumerate(placements):
            s = self.dice_by_type[p.dice_type]
            n = p.level - 1
            hp[i] = int(s.hp * (1.0 + 0.7 * n))
            damage[i] = int(s.damage * (1.0 + 0.7 * n))
            base_aps[i] = s.aps * (1.0 + 0.2 * n)
            ranges[i] = s.attack_range

        aps = base_aps.copy()
        next_attack_time = np.zeros(num_units, dtype=np.float32)
        current_target_idx = np.full(num_units, -1, dtype=np.int32)
        water_stacks = np.zeros(num_units, dtype=np.int32)
        water_debuff_end = np.zeros(num_units, dtype=np.float32)
        
        pos = np.stack([tiles % GRID_SIZE, tiles // GRID_SIZE], axis=1)
        # 거리 행렬 미리 계산
        diff = np.abs(pos[:, np.newaxis, :] - pos[np.newaxis, :, :])
        dist_matrix = np.max(diff, axis=2)
        
        initial_hp_sum = {"player": int(np.sum(hp[owners == 0])), "enemy": int(np.sum(hp[owners == 1]))}

        # [지연 반영] 데미지 큐 (도착 시간, 대상 인덱스, 데미지 양)
        damage_queue = deque()
        PROJECTILE_DELAY = 300 # 300ms 지연

        for time_ms in range(0, 30000, 100):
            # 1. 지연된 데미지 반영 (큐에서 현재 시간에 도달한 데미지 처리)
            while damage_queue and damage_queue[0][0] <= time_ms:
                _, t_idx, dmg = damage_queue.popleft()
                hp[t_idx] = max(0, hp[t_idx] - dmg)

            alive = hp > 0
            # 양측 생존 확인 (벡터화)
            player_alive = alive & (owners == 0)
            enemy_alive = alive & (owners == 1)
            if not np.any(player_alive) or not np.any(enemy_alive):
                break
                
            # [최적화] 교착 상태 감지 벡터화
            can_attack_mask = (dist_matrix <= ranges[:, np.newaxis]) & (owners[:, np.newaxis] != owners[np.newaxis, :]) & alive[np.newaxis, :] & alive[:, np.newaxis]
            if not np.any(can_attack_mask):
                break
                
            # 워터 디버프 종료 처리 (벡터화)
            water_expired = (water_stacks > 0) & (time_ms > water_debuff_end)
            if np.any(water_expired):
                water_stacks[water_expired] = 0
                aps[water_expired] = base_aps[water_expired]
                
            # 공격 가능 유닛 선별 (벡터화)
            can_attack = alive & (time_ms >= next_attack_time)
            if not np.any(can_attack):
                continue
                
            attacker_indices = np.where(can_attack)[0]
            self.random.shuffle(attacker_indices)
            
            for i in attacker_indices:
                u_owner = owners[i]
                u_range = ranges[i]
                u_type = types[i]
                
                # 타겟팅 로직 (벡터화된 거리 행렬 활용)
                curr_target = current_target_idx[i]
                if curr_target == -1 or not alive[curr_target] or dist_matrix[i, curr_target] > u_range:
                    # 사거리 내 적군 필터링
                    enemies_in_range = np.where(alive & (owners != u_owner) & (dist_matrix[i] <= u_range))[0]
                    if enemies_in_range.size > 0:
                        dists = dist_matrix[i, enemies_in_range]
                        min_dist = np.min(dists)
                        closest = enemies_in_range[dists == min_dist]
                        curr_target = self.random.choice(closest)
                        current_target_idx[i] = curr_target
                    else:
                        curr_target = -1
                        current_target_idx[i] = -1
                        
                if curr_target != -1:
                    t_idx = curr_target
                    dmg = damage[i]
                    u_level = levels[i]
                    u_n = u_level - 1
                    arrival_time = time_ms + PROJECTILE_DELAY
                    
                    # 30,000ms를 넘어서 도달하는 투사체는 무효화 (반영하지 않음)
                    if arrival_time >= 30000:
                        next_attack_time[i] = time_ms + 1000.0 / aps[i]
                        continue

                    # 타입별 특수 효과 처리 (데미지를 즉시 깎지 않 큐에 삽입)
                    if u_type == "FIRE":
                        damage_queue.append((arrival_time, t_idx, dmg))
                        splash_dmg = 20 + 20 * u_level
                        in_splash = np.where(alive & (owners != u_owner) & (dist_matrix[t_idx] <= 1))[0]
                        for s_idx in in_splash:
                            damage_queue.append((arrival_time, s_idx, splash_dmg))
                    elif u_type == "SNIPER":
                        dist = dist_matrix[i, t_idx]
                        final_dmg = int(dmg * (dist * 0.3 * (1.0 + 0.1 * u_n) + 1))
                        damage_queue.append((arrival_time, t_idx, final_dmg))
                    elif u_type == "ELECTRIC":
                        damage_queue.append((arrival_time, t_idx, dmg))
                        chain_dmg = 25 + 25 * u_level
                        potential_chains = np.where(alive & (owners != u_owner) & (np.arange(num_units) != t_idx) & (dist_matrix[t_idx] <= 1))[0]
                        if potential_chains.size > 0:
                            c_idx = potential_chains[np.argmin(dist_matrix[t_idx, potential_chains])]
                            damage_queue.append((arrival_time, c_idx, chain_dmg))
                    elif u_type == "WATER":
                        damage_queue.append((arrival_time, t_idx, dmg))
                        # 디버프는 투사체가 '도달'했을 때 걸리도록 처리
                        # (단, 구현 단순화를 위해 큐에 디버프 이벤트를 넣는 대신 
                        #  도달 시점에 맞춰 워터 스택을 계산하는 로직은 복잡하므로 
                        #  여기서는 데미지만 지연시키고 디버프는 즉시 적용하거나, 
                        #  디버프 종료 시간을 arrival_time 기준으로 설정)
                        water_stacks[t_idx] = min(3, water_stacks[t_idx] + 1)
                        water_debuff_end[t_idx] = arrival_time + 3000
                        reduction = min(0.9, (0.12 * (1.0 + 0.1 * u_n)) * water_stacks[t_idx])
                        aps[t_idx] = base_aps[t_idx] * (1.0 - reduction)
                    elif u_type == "IRON":
                        bonus = int(hp[t_idx] * 0.1 * (1.0 + 0.1 * u_n))
                        damage_queue.append((arrival_time, t_idx, dmg + bonus))
                    elif u_type == "SHIELD":
                        in_taunt_range = alive & (owners != u_owner) & (dist_matrix[i] <= 2)
                        current_target_idx[in_taunt_range] = i
                    else:
                        damage_queue.append((arrival_time, t_idx, dmg))
                        
                    next_attack_time[i] = time_ms + 1000.0 / aps[i]

        alive_final = hp > 0
        survivors = {"player": int(np.sum(alive_final[owners == 0])), "enemy": int(np.sum(alive_final[owners == 1]))}
        remaining_hp = {"player": int(np.sum(hp[owners == 0])), "enemy": int(np.sum(hp[owners == 1]))}
        winner = 1 if survivors["player"] > survivors["enemy"] else -1 if survivors["enemy"] > survivors["player"] else 0
        return winner, survivors, logs, remaining_hp, initial_hp_sum

    def _resolve_round(self) -> Tuple[int, Dict[str, float]]:
        # 전투가 발생했으므로, 적 보드의 모든 유닛을 공개 상태로 전환
        self.revealed_enemy_tiles.update(self.enemy_board.keys())
        winner, survivors, logs, remaining_hp, initial_hp_sum = self._simulate_combat(self.player_board, self.enemy_board)
        if winner == 1: self.enemy_hp -= 1
        elif winner == -1: self.player_hp -= 1
        
        # [수정] 라운드 승패 보상만 남김 (승리 +1.0, 패배 -1.0)
        player_reward = 0.0
        enemy_reward = 0.0
        
        if winner == 1:
            player_reward = 1.0
            enemy_reward = -1.0
        elif winner == -1:
            player_reward = -1.0
            enemy_reward = 1.0
        else:
            # 무승부 시 소폭 페널티 (적극적인 플레이 유도)
            player_reward = -0.1
            enemy_reward = -0.1
            
        return winner, {"player": player_reward, "enemy": enemy_reward}

    def _start_next_round(self) -> None:
        self.current_round += 1
        self.turn += 1
        # [수정] 라운드가 지나도 보드 배치는 유지됩니다.
              # [수정] 라운드 간 보드 배치를 유지합니다. (게임 종료 시 reset()에서만 초기화됨)
        # self.player_board.clear()
        # self.enemy_board.clear()
        self.player_actions_used = 0
        self.enemy_actions_used = 0
        # [수정] 손패는 매 라운드 새로 뽑습니다.
        self.player_hand.clear()
        self.enemy_hand.clear()
        self.enemy_hand.clear()
        self._fill_hand(self.player_hand, self.player_deck)
        self._fill_hand(self.enemy_hand, self.enemy_deck)
        if self.player_hp <= 0 or self.enemy_hp <= 0 or self.current_round >= MAX_ROUNDS_PER_GAME:
            self.done = True

    def step_self_play(self, player_action: Action, enemy_action: Action) -> Tuple[Tuple[int, ...], Tuple[int, ...], float, float, bool, Dict[str, int | str]]:
        if self.done:
            return self.get_state_for("player"), self.get_state_for("enemy"), 0.0, 0.0, True, {"winner": 0, "catalog_source": self.catalog_source}

        player_reward, enemy_reward = 0.0, 0.0
        
        # [수정] 턴 패스(PASS) 시 페널티 부여
        if player_action == PASS_ACTION:
            player_reward -= 0.1
        if enemy_action == PASS_ACTION:
            enemy_reward -= 0.1

        if player_action not in self.get_valid_actions_for("player"):
            player_action = PASS_ACTION
        if enemy_action not in self.get_valid_actions_for("enemy"):
            enemy_action = PASS_ACTION

        # [수정] 배치 영역이 서로 다르므로 무작위화 대신 순차적으로 처리 (Enemy 우선)
        if enemy_action != PASS_ACTION:
            if self._apply_action(self.enemy_board, self.enemy_hand, self.enemy_deck, enemy_action):
                self.enemy_actions_used += 1
        if player_action != PASS_ACTION:
            if self._apply_action(self.player_board, self.player_hand, self.player_deck, player_action):
                self.player_actions_used += 1
                
        player_done_turn = player_action == PASS_ACTION or self.player_actions_used >= MAX_ACTIONS_PER_TURN
        enemy_done_turn = enemy_action == PASS_ACTION or self.enemy_actions_used >= MAX_ACTIONS_PER_TURN

        if player_done_turn and enemy_done_turn:
            round_result, extra_rewards = self._resolve_round()
            self.last_round_result = round_result
            player_reward += extra_rewards["player"]
            enemy_reward += extra_rewards["enemy"]
            self._start_next_round()

        done = self.done
        info = {"catalog_source": self.catalog_source, "player_hp": self.player_hp, "enemy_hp": self.enemy_hp, "round_result": self.last_round_result, "current_round": self.current_round}
        if done: info["winner"] = 1 if self.player_hp > self.enemy_hp else -1 if self.enemy_hp > self.player_hp else 0
        return self.get_state_for("player"), self.get_state_for("enemy"), player_reward, enemy_reward, done, info

    def _encode_map(self) -> List[int]:
        encoded: List[int] = []
        for kind in self.map_data:
            t = (kind or "").strip().upper()
            if t in {"BOTH_TILE", "SHARED_TILE", "ANY_TILE"}: encoded.append(3)
            elif t in {"MY_TILE", "PLAYER_TILE", "ALLY_TILE"}: encoded.append(1)
            elif t in {"ENEMY_TILE"}: encoded.append(2)
            else: encoded.append(0)
        return encoded

    def _encode_board_types(self, board: Dict[int, Placement], is_enemy_board: bool = False) -> np.ndarray:
        # [최적화] numpy 벡터화 (IndexError 방지)
        arr = np.zeros(TOTAL_TILES, dtype=np.int32)
        if board:
            indices = np.array(list(board.keys()))
            # 유효한 인덱스만 필터링
            valid_mask = (indices >= 0) & (indices < TOTAL_TILES)
            valid_indices = indices[valid_mask]
            
            if len(valid_indices) > 0:
                if is_enemy_board:
                    # 공개된 적 유닛만 포함
                    revealed_indices = [idx for idx in valid_indices if idx in self.revealed_enemy_tiles]
                    types = [board[idx].dice_type for idx in revealed_indices]
                    valid_indices = np.array(revealed_indices, dtype=np.int32)
                else:
                    types = [board[idx].dice_type for idx in valid_indices]
                    valid_indices = np.array(valid_indices, dtype=np.int32)
                type_indices = []
                for t in types:
                    try: type_indices.append(self.dice_types.index(t) + 1)
                    except ValueError: type_indices.append(0)
                arr[valid_indices] = type_indices
        return arr

    def _encode_board_levels(self, board: Dict[int, Placement], is_enemy_board: bool = False) -> np.ndarray:
        # [최적화] numpy 벡터화 (IndexError 방지)
        arr = np.zeros(TOTAL_TILES, dtype=np.int32)
        if board:
            indices = np.array(list(board.keys()))
            # 유효한 인덱스만 필터링
            valid_mask = (indices >= 0) & (indices < TOTAL_TILES)
            valid_indices = indices[valid_mask]
            
            if len(valid_indices) > 0:
                if is_enemy_board:
                    # 공개된 적 유닛만 포함
                    revealed_indices = [idx for idx in valid_indices if idx in self.revealed_enemy_tiles]
                    levels = [board[idx].level for idx in revealed_indices]
                    valid_indices = np.array(revealed_indices, dtype=np.int32)
                else:
                    levels = [board[idx].level for idx in valid_indices]
                    valid_indices = np.array(valid_indices, dtype=np.int32)
                arr[valid_indices] = levels
        return arr

    def get_state_for(self, side: Side) -> np.ndarray:
        # [최적화] 상태 벡터 생성 완전 벡터화
        is_player = (side == "player")
        own_hp = self.player_hp if is_player else self.enemy_hp
        opp_hp = self.enemy_hp if is_player else self.player_hp
        actions_used = self.player_actions_used if is_player else self.enemy_actions_used
        
        # 1. 공통 정보 (4개)
        common = np.array([
            own_hp / STARTING_HP, 
            opp_hp / STARTING_HP, 
            actions_used / MAX_ACTIONS_PER_TURN, 
            self.current_round / MAX_ROUNDS_PER_GAME
        ], dtype=np.float32)
        
        # 2. 맵 정보 (64개)
        map_info = self.encoded_map
        
        # 3. 보드 정보 (256개)
        if is_player:
            own_types = self._encode_board_types(self.player_board, False)
            own_lvls = self._encode_board_levels(self.player_board, False)
            opp_types = self._encode_board_types(self.enemy_board, True)
            opp_lvls = self._encode_board_levels(self.enemy_board, True)
        else:
            own_types = self._encode_board_types(self.enemy_board, False)
            own_lvls = self._encode_board_levels(self.enemy_board, False)
            opp_types = self._encode_board_types(self.player_board, True)
            opp_lvls = self._encode_board_levels(self.player_board, True)
            
        # 4. 핸드 정보 (가변적이지만 벡터화)
        hand = self.player_hand if is_player else self.enemy_hand
        hand_counts = np.zeros(len(self.dice_types), dtype=np.float32)
        for dt in hand:
            idx = self.dice_type_to_index.get(dt)
            if idx is not None:
                hand_counts[idx] += 1.0
                
        # 5. 라운드 결과
        res = self.last_round_result if is_player else -self.last_round_result
        res_val = np.array([1.0 if res == 1 else (-1.0 if res == -1 else -0.1)], dtype=np.float32)
        
        return np.concatenate([common, map_info, own_types, own_lvls, opp_types, opp_lvls, hand_counts, res_val])
