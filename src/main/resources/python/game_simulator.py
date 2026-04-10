from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Literal, Sequence, Tuple, Optional
import json
import os
import random
import time
import numpy as np
try:
    from numba import njit
except Exception:  # pragma: no cover - numba가 없는 환경 호환
    def njit(*args, **kwargs):  # type: ignore
        def _decorator(func):
            return func
        return _decorator

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

# Fixed state spec (dice 종류 수와 무관)
COMMON_FEATURES = 4
UNIT_STAT_FEATURES = 5  # hp, damage, range, aps, level
SPATIAL_CHANNELS = 1 + (UNIT_STAT_FEATURES * 2)  # map + own(5) + opp(5) = 11
NON_SPATIAL_FEATURES = 5 + 1  # hand_stat_agg(5) + last_result(1)
STATE_SIZE = COMMON_FEATURES + (SPATIAL_CHANNELS * TOTAL_TILES) + NON_SPATIAL_FEATURES

TYPE_NORMAL = 0
TYPE_FIRE = 1
TYPE_SNIPER = 2
TYPE_ELECTRIC = 3
TYPE_WATER = 4
TYPE_IRON = 5
TYPE_SHIELD = 6


@njit(cache=True)
def _compute_dist_matrix_numba(tiles: np.ndarray) -> np.ndarray:
    n = tiles.shape[0]
    dist = np.zeros((n, n), dtype=np.int32)
    xs = np.empty(n, dtype=np.int32)
    ys = np.empty(n, dtype=np.int32)
    for i in range(n):
        t = int(tiles[i])
        xs[i] = t % GRID_SIZE
        ys[i] = t // GRID_SIZE
    for i in range(n):
        for j in range(n):
            dx = xs[i] - xs[j]
            if dx < 0:
                dx = -dx
            dy = ys[i] - ys[j]
            if dy < 0:
                dy = -dy
            dist[i, j] = dx if dx > dy else dy
    return dist


@njit(cache=True)
def _build_scaled_stats_numba(
    levels: np.ndarray,
    type_indices: np.ndarray,
    catalog_hp: np.ndarray,
    catalog_damage: np.ndarray,
    catalog_range: np.ndarray,
    catalog_aps: np.ndarray,
) -> Tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
    n = levels.shape[0]
    hp = np.zeros(n, dtype=np.int32)
    damage = np.zeros(n, dtype=np.int32)
    base_aps = np.zeros(n, dtype=np.float32)
    ranges = np.zeros(n, dtype=np.int32)
    for i in range(n):
        level = levels[i]
        idx = type_indices[i]
        lv_scale = 1.0 + 0.7 * (level - 1)
        atk_scale = 1.0 + 0.2 * (level - 1)
        hp[i] = int(catalog_hp[idx] * lv_scale)
        damage[i] = int(catalog_damage[idx] * lv_scale)
        base_aps[i] = np.float32(catalog_aps[idx] * atk_scale)
        ranges[i] = catalog_range[idx]
    return hp, damage, base_aps, ranges


@njit(cache=True)
def _simulate_combat_loop_numba(
    owners: np.ndarray,
    levels: np.ndarray,
    type_codes: np.ndarray,
    hp: np.ndarray,
    damage: np.ndarray,
    base_aps: np.ndarray,
    ranges: np.ndarray,
    dist_matrix: np.ndarray,
) -> np.ndarray:
    num_units = hp.shape[0]
    aps = base_aps.copy()
    next_attack_time = np.zeros(num_units, dtype=np.float32)
    current_target_idx = np.full(num_units, -1, dtype=np.int32)
    water_stacks = np.zeros(num_units, dtype=np.int32)
    water_debuff_end = np.zeros(num_units, dtype=np.float32)

    # 지연 데미지 큐 (FIFO): time, target_idx, damage
    max_events = 300000
    q_time = np.zeros(max_events, dtype=np.int32)
    q_target = np.zeros(max_events, dtype=np.int32)
    q_dmg = np.zeros(max_events, dtype=np.int32)
    q_head = 0
    q_tail = 0
    projectile_delay = 300

    alive = np.zeros(num_units, dtype=np.bool_)
    player_alive_count = 0
    enemy_alive_count = 0
    attacker_indices = np.empty(num_units, dtype=np.int32)
    enemies_in_range = np.empty(num_units, dtype=np.int32)
    closest_candidates = np.empty(num_units, dtype=np.int32)
    splash_targets = np.empty(num_units, dtype=np.int32)
    chain_targets = np.empty(num_units, dtype=np.int32)
    taunt_targets = np.empty(num_units, dtype=np.int32)

    for time_ms in range(0, 30000, 100):
        while q_head < q_tail and q_time[q_head] <= time_ms:
            t_idx = q_target[q_head]
            dmg = q_dmg[q_head]
            new_hp = hp[t_idx] - dmg
            hp[t_idx] = new_hp if new_hp > 0 else 0
            q_head += 1

        player_alive_count = 0
        enemy_alive_count = 0
        for i in range(num_units):
            alive[i] = hp[i] > 0
            if alive[i]:
                if owners[i] == 0:
                    player_alive_count += 1
                else:
                    enemy_alive_count += 1
        if player_alive_count == 0 or enemy_alive_count == 0:
            break

        can_attack_any = False
        for i in range(num_units):
            if not alive[i]:
                continue
            for j in range(num_units):
                if alive[j] and owners[i] != owners[j] and dist_matrix[i, j] <= ranges[i]:
                    can_attack_any = True
                    break
            if can_attack_any:
                break
        if not can_attack_any:
            break

        for i in range(num_units):
            if water_stacks[i] > 0 and time_ms > water_debuff_end[i]:
                water_stacks[i] = 0
                aps[i] = base_aps[i]

        attacker_count = 0
        for i in range(num_units):
            if alive[i] and time_ms >= next_attack_time[i]:
                attacker_indices[attacker_count] = i
                attacker_count += 1
        if attacker_count == 0:
            continue

        for k in range(attacker_count - 1, 0, -1):
            r = np.random.randint(0, k + 1)
            tmp = attacker_indices[k]
            attacker_indices[k] = attacker_indices[r]
            attacker_indices[r] = tmp

        for a in range(attacker_count):
            i = attacker_indices[a]
            if not alive[i]:
                continue
            u_owner = owners[i]
            u_range = ranges[i]
            u_type = type_codes[i]

            curr_target = current_target_idx[i]
            if curr_target == -1 or (not alive[curr_target]) or dist_matrix[i, curr_target] > u_range:
                e_count = 0
                min_dist = 1_000_000
                for j in range(num_units):
                    if alive[j] and owners[j] != u_owner and dist_matrix[i, j] <= u_range:
                        enemies_in_range[e_count] = j
                        e_count += 1
                        d = dist_matrix[i, j]
                        if d < min_dist:
                            min_dist = d
                if e_count > 0:
                    c_count = 0
                    for idx in range(e_count):
                        j = enemies_in_range[idx]
                        if dist_matrix[i, j] == min_dist:
                            closest_candidates[c_count] = j
                            c_count += 1
                    pick = closest_candidates[np.random.randint(0, c_count)]
                    curr_target = pick
                    current_target_idx[i] = pick
                else:
                    curr_target = -1
                    current_target_idx[i] = -1

            if curr_target == -1:
                continue

            t_idx = curr_target
            dmg = damage[i]
            u_level = levels[i]
            u_n = u_level - 1
            arrival_time = time_ms + projectile_delay

            if arrival_time >= 30000:
                next_attack_time[i] = time_ms + 1000.0 / aps[i]
                continue

            if u_type == TYPE_FIRE:
                if q_tail < max_events:
                    q_time[q_tail] = arrival_time
                    q_target[q_tail] = t_idx
                    q_dmg[q_tail] = dmg
                    q_tail += 1
                splash_dmg = 20 + 20 * u_level
                s_count = 0
                for j in range(num_units):
                    if alive[j] and owners[j] != u_owner and dist_matrix[t_idx, j] <= 1:
                        splash_targets[s_count] = j
                        s_count += 1
                for s in range(s_count):
                    if q_tail < max_events:
                        q_time[q_tail] = arrival_time
                        q_target[q_tail] = splash_targets[s]
                        q_dmg[q_tail] = splash_dmg
                        q_tail += 1
            elif u_type == TYPE_SNIPER:
                dist = dist_matrix[i, t_idx]
                final_dmg = int(dmg * (dist * 0.3 * (1.0 + 0.1 * u_n) + 1.0))
                if q_tail < max_events:
                    q_time[q_tail] = arrival_time
                    q_target[q_tail] = t_idx
                    q_dmg[q_tail] = final_dmg
                    q_tail += 1
            elif u_type == TYPE_ELECTRIC:
                if q_tail < max_events:
                    q_time[q_tail] = arrival_time
                    q_target[q_tail] = t_idx
                    q_dmg[q_tail] = dmg
                    q_tail += 1
                chain_dmg = 25 + 25 * u_level
                c_count = 0
                for j in range(num_units):
                    if alive[j] and owners[j] != u_owner and j != t_idx and dist_matrix[t_idx, j] <= 1:
                        chain_targets[c_count] = j
                        c_count += 1
                if c_count > 0:
                    best = chain_targets[0]
                    best_dist = dist_matrix[t_idx, best]
                    for c in range(1, c_count):
                        j = chain_targets[c]
                        d = dist_matrix[t_idx, j]
                        if d < best_dist:
                            best = j
                            best_dist = d
                    if q_tail < max_events:
                        q_time[q_tail] = arrival_time
                        q_target[q_tail] = best
                        q_dmg[q_tail] = chain_dmg
                        q_tail += 1
            elif u_type == TYPE_WATER:
                if q_tail < max_events:
                    q_time[q_tail] = arrival_time
                    q_target[q_tail] = t_idx
                    q_dmg[q_tail] = dmg
                    q_tail += 1
                ws = water_stacks[t_idx] + 1
                water_stacks[t_idx] = ws if ws < 3 else 3
                water_debuff_end[t_idx] = arrival_time + 3000
                reduction = (0.12 * (1.0 + 0.1 * u_n)) * water_stacks[t_idx]
                if reduction > 0.9:
                    reduction = 0.9
                aps[t_idx] = base_aps[t_idx] * (1.0 - reduction)
            elif u_type == TYPE_IRON:
                bonus = int(hp[t_idx] * 0.1 * (1.0 + 0.1 * u_n))
                if q_tail < max_events:
                    q_time[q_tail] = arrival_time
                    q_target[q_tail] = t_idx
                    q_dmg[q_tail] = dmg + bonus
                    q_tail += 1
            elif u_type == TYPE_SHIELD:
                tt_count = 0
                for j in range(num_units):
                    if alive[j] and owners[j] != u_owner and dist_matrix[i, j] <= 2:
                        taunt_targets[tt_count] = j
                        tt_count += 1
                for t in range(tt_count):
                    current_target_idx[taunt_targets[t]] = i
            else:
                if q_tail < max_events:
                    q_time[q_tail] = arrival_time
                    q_target[q_tail] = t_idx
                    q_dmg[q_tail] = dmg
                    q_tail += 1

            next_attack_time[i] = time_ms + 1000.0 / aps[i]

    return hp


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
        self.catalog_hp = np.array([d.hp for d in self.dice_catalog], dtype=np.int32)
        self.catalog_damage = np.array([d.damage for d in self.dice_catalog], dtype=np.int32)
        self.catalog_range = np.array([d.attack_range for d in self.dice_catalog], dtype=np.int32)
        self.catalog_aps = np.array([d.aps for d in self.dice_catalog], dtype=np.float32)
        self.max_hp = max(1.0, max(float(d.hp) for d in self.dice_catalog))
        self.max_damage = max(1.0, max(float(d.damage) for d in self.dice_catalog))
        self.max_range = max(1.0, max(float(d.attack_range) for d in self.dice_catalog))
        self.max_aps = max(1.0, max(float(d.aps) for d in self.dice_catalog))

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

        # 전투 전까지 상대의 신규 배치를 숨기기 위한 시야 정보(라운드 경계에서만 갱신)
        self.revealed_enemy_tiles = set()
        self.revealed_player_tiles = set()

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
        type_indices = np.array([self.dice_type_to_index[p.dice_type] for p in placements], dtype=np.int32)
        type_codes = np.array([self._type_to_code(t) for t in types], dtype=np.int32)

        # Numba JIT: 전투 시작 시 스탯 스케일링과 거리 행렬 계산 고속화
        hp, damage, base_aps, ranges = _build_scaled_stats_numba(
            levels,
            type_indices,
            self.catalog_hp,
            self.catalog_damage,
            self.catalog_range,
            self.catalog_aps,
        )

        # 거리 행렬 미리 계산 (Numba JIT)
        dist_matrix = _compute_dist_matrix_numba(tiles)
        
        initial_hp_sum = {"player": int(np.sum(hp[owners == 0])), "enemy": int(np.sum(hp[owners == 1]))}
        # 전투 루프 자체를 Numba로 실행
        hp = _simulate_combat_loop_numba(
            owners,
            levels,
            type_codes,
            hp,
            damage,
            base_aps,
            ranges,
            dist_matrix,
        )

        alive_final = hp > 0
        survivors = {"player": int(np.sum(alive_final[owners == 0])), "enemy": int(np.sum(alive_final[owners == 1]))}
        remaining_hp = {"player": int(np.sum(hp[owners == 0])), "enemy": int(np.sum(hp[owners == 1]))}
        winner = 1 if survivors["player"] > survivors["enemy"] else -1 if survivors["enemy"] > survivors["player"] else 0
        return winner, survivors, logs, remaining_hp, initial_hp_sum

    @staticmethod
    def _type_to_code(dice_type: str) -> int:
        if dice_type == "FIRE":
            return TYPE_FIRE
        if dice_type == "SNIPER":
            return TYPE_SNIPER
        if dice_type == "ELECTRIC":
            return TYPE_ELECTRIC
        if dice_type == "WATER":
            return TYPE_WATER
        if dice_type == "IRON":
            return TYPE_IRON
        if dice_type == "SHIELD":
            return TYPE_SHIELD
        return TYPE_NORMAL

    def _resolve_round(self) -> Tuple[int, Dict[str, float]]:
        # 전투가 발생했으므로, 양측 보드의 상태를 라운드 종료 시점 정보로 공개
        self.revealed_enemy_tiles.update(self.enemy_board.keys())
        self.revealed_player_tiles.update(self.player_board.keys())
        winner, survivors, logs, remaining_hp, initial_hp_sum = self._simulate_combat(self.player_board, self.enemy_board)
        
        if winner == 1: self.enemy_hp -= 1
        elif winner == -1: self.player_hp -= 1
        
        # [수정] 상세 보상 체계 도입 (유닛 데미지 차이, 유닛 체력 잔량 차이, 생존 유닛 수 차이)
        # 1. 데미지 차이 (적 유닛에게 준 데미지 - 아군 유닛이 받은 데미지)
        player_dmg_dealt = initial_hp_sum["enemy"] - remaining_hp["enemy"]
        enemy_dmg_dealt = initial_hp_sum["player"] - remaining_hp["player"]
        dmg_diff = (player_dmg_dealt - enemy_dmg_dealt) / 500.0 # 스케일 조정
        dmg_diff *= 0.2
        
        # 2. 유닛 체력 잔량 차이 (아군 유닛 남은 HP 합산 - 적군 유닛 남은 HP 합산)
        hp_diff = (remaining_hp["player"] - remaining_hp["enemy"]) / 500 # 스케일 조정
        hp_diff *= 0.2

        # 3. 생존 유닛 수 차이 (아군 생존 수 - 적군 생존 수)
        survivor_diff = (survivors["player"] - survivors["enemy"]) * 0.05 # 스케일 조정
        
        # 기본 승패 보상
        win_reward = 5.0 if winner == 1 else (-5.0 if winner == -1 else -0.1)
        
        # 최종 보상 합산
        player_reward = win_reward + dmg_diff + hp_diff + survivor_diff
        enemy_reward = -player_reward # 제로섬 게임 가정
            
        return winner, {"player": float(player_reward), "enemy": float(enemy_reward)}

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

    def _encode_board_stat(self, board: Dict[int, Placement], stat_name: str, revealed_tiles: Optional[set[int]] = None) -> np.ndarray:
        arr = np.zeros(TOTAL_TILES, dtype=np.float32)
        if not board:
            return arr

        for tile, placement in board.items():
            if tile < 0 or tile >= TOTAL_TILES:
                continue
            if revealed_tiles is not None and tile not in revealed_tiles:
                continue
            stat = self.dice_by_type.get(placement.dice_type)
            if stat is None:
                continue

            if stat_name == "hp":
                base = float(stat.hp) / self.max_hp
            elif stat_name == "damage":
                base = float(stat.damage) / self.max_damage
            elif stat_name == "range":
                base = float(stat.attack_range) / self.max_range
            elif stat_name == "aps":
                base = float(stat.aps) / self.max_aps
            elif stat_name == "level":
                base = float(placement.level) / max(1.0, float(MAX_UNIT_LEVEL))
            else:
                base = 0.0
            arr[tile] = np.float32(base)
        return arr

    def _encode_hand_stat_summary(self, hand: List[str]) -> np.ndarray:
        feats = np.zeros(5, dtype=np.float32)  # hp, damage, range, aps, level(=1)
        if not hand:
            return feats

        for dt in hand:
            stat = self.dice_by_type.get(dt)
            if stat is None:
                continue
            feats[0] += float(stat.hp) / self.max_hp
            feats[1] += float(stat.damage) / self.max_damage
            feats[2] += float(stat.attack_range) / self.max_range
            feats[3] += float(stat.aps) / self.max_aps
            feats[4] += 1.0 / max(1.0, float(MAX_UNIT_LEVEL))

        denom = max(1.0, float(HAND_SIZE))
        return (feats / denom).astype(np.float32)

    def get_state_for(self, side: Side) -> np.ndarray:
        # [최적화] 상태 벡터 생성 완전 벡터화
        is_player = (side == "player")
        own_hp_scalar = self.player_hp if is_player else self.enemy_hp
        opp_hp_scalar = self.enemy_hp if is_player else self.player_hp
        actions_used = self.player_actions_used if is_player else self.enemy_actions_used
        
        # 1. 공통 정보 (4개)
        common = np.array([
            own_hp_scalar / STARTING_HP,
            opp_hp_scalar / STARTING_HP,
            actions_used / MAX_ACTIONS_PER_TURN, 
            self.current_round / MAX_ROUNDS_PER_GAME
        ], dtype=np.float32)
        
        # 2. 맵 정보 (64개)
        map_info = self.encoded_map
        
        # 3. 보드 정보 (stat 기반 spatial 채널)
        if is_player:
            own_hp = self._encode_board_stat(self.player_board, "hp")
            own_damage = self._encode_board_stat(self.player_board, "damage")
            own_range = self._encode_board_stat(self.player_board, "range")
            own_aps = self._encode_board_stat(self.player_board, "aps")
            own_lvl = self._encode_board_stat(self.player_board, "level")
            opp_hp = self._encode_board_stat(self.enemy_board, "hp", self.revealed_enemy_tiles)
            opp_damage = self._encode_board_stat(self.enemy_board, "damage", self.revealed_enemy_tiles)
            opp_range = self._encode_board_stat(self.enemy_board, "range", self.revealed_enemy_tiles)
            opp_aps = self._encode_board_stat(self.enemy_board, "aps", self.revealed_enemy_tiles)
            opp_lvl = self._encode_board_stat(self.enemy_board, "level", self.revealed_enemy_tiles)
        else:
            own_hp = self._encode_board_stat(self.enemy_board, "hp")
            own_damage = self._encode_board_stat(self.enemy_board, "damage")
            own_range = self._encode_board_stat(self.enemy_board, "range")
            own_aps = self._encode_board_stat(self.enemy_board, "aps")
            own_lvl = self._encode_board_stat(self.enemy_board, "level")
            opp_hp = self._encode_board_stat(self.player_board, "hp", self.revealed_player_tiles)
            opp_damage = self._encode_board_stat(self.player_board, "damage", self.revealed_player_tiles)
            opp_range = self._encode_board_stat(self.player_board, "range", self.revealed_player_tiles)
            opp_aps = self._encode_board_stat(self.player_board, "aps", self.revealed_player_tiles)
            opp_lvl = self._encode_board_stat(self.player_board, "level", self.revealed_player_tiles)
            
        # 4. 핸드 정보 (dice type index 제거, stat 요약 고정 길이)
        hand = self.player_hand if is_player else self.enemy_hand
        hand_stats = self._encode_hand_stat_summary(hand)
                
        # 5. 라운드 결과
        res = self.last_round_result if is_player else -self.last_round_result
        res_val = np.array([1.0 if res == 1 else (-1.0 if res == -1 else -0.1)], dtype=np.float32)

        spatial = np.concatenate([
            map_info,
            own_hp, own_damage, own_range, own_aps, own_lvl,
            opp_hp, opp_damage, opp_range, opp_aps, opp_lvl
        ]).astype(np.float32)

        state = np.concatenate([common, spatial, hand_stats, res_val]).astype(np.float32)
        if state.shape[0] != STATE_SIZE:
            raise RuntimeError(f"Invalid state size: expected {STATE_SIZE}, got {state.shape[0]}")
        return state
