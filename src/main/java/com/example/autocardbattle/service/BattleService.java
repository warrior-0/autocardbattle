package com.example.autocardbattle.service;

import com.example.autocardbattle.controller.BattleController;
import com.example.autocardbattle.dto.BattleMessage;
import com.example.autocardbattle.dto.BattleMessage.CombatLogEntry;
import com.example.autocardbattle.entity.DiceEntity;
import com.example.autocardbattle.entity.MapTileEntity;
import com.example.autocardbattle.repository.DiceRepository;
import com.example.autocardbattle.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class BattleService {
    private static final int GRID_WIDTH = 8;
    private static final int HAND_SIZE = 2;
    private static final int AI_DECK_SIZE = 5;
    private static final int MAX_ACTIONS_PER_TURN = 3;

    @Autowired private UserRepository userRepository;
    @Autowired private DiceRepository diceRepository;
    @Autowired private SimpMessageSendingOperations messagingTemplate;
    @Autowired private RlAiService rlAiService;

    private Map<String, GameState> games = new ConcurrentHashMap<>();
    private final Map<String, AbilityHandler> abilityHandlers = new HashMap<>();

    // ✅ [최적화] 주사위 정보를 메모리에 캐싱하여 DB 반복 조회를 방지
    private List<DiceEntity> cachedDiceList = new ArrayList<>();
    private Map<String, DiceEntity> cachedDiceMap = new HashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final Map<String, ScheduledFuture<?>> roomTimers = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        refreshDiceCache();
        initStrategies();
    }

    // ✅ [추가] 주사위 캐시 갱신 메서드
    public void refreshDiceCache() {
        this.cachedDiceList = diceRepository.findAll();
        this.cachedDiceMap = cachedDiceList.stream()
                .collect(Collectors.toMap(DiceEntity::getDiceType, d -> d));
    }

    private void scheduleTurnTimeout(String roomId, int currentTurn, long extraDelayMs) {
        ScheduledFuture<?> existingTask = roomTimers.get(roomId);
        if (existingTask != null && !existingTask.isDone()) {
            existingTask.cancel(false);
        }

        long totalDelay = 60000 + extraDelayMs;

        ScheduledFuture<?> task = scheduler.schedule(() -> {
            GameState state = games.get(roomId);
            if (state == null) return;

            synchronized (state) {
                if (state.turn != currentTurn) return;

                ensureParticipantsInitialized(roomId, state);
                if (state.isAiMatch() && state.turnActionCounts.getOrDefault(state.aiUid, 0) < MAX_ACTIONS_PER_TURN) {
                    executeAiTurn(roomId, state);
                    return;
                }
                Set<String> allUsers = BattleController.roomReadyStatus.get(roomId);
                if (allUsers != null) {
                    state.readyUsers.addAll(allUsers);
                }
                processBattleResult(state, roomId);
            }
        }, totalDelay, TimeUnit.MILLISECONDS);

        roomTimers.put(roomId, task);
    }

    public static class GameState {
        public Map<String, List<BattleMessage>> placements = new HashMap<>();
        // 라운드 시작 시점(이번 라운드 행동 전)의 배치 정보 스냅샷
        public Map<String, List<BattleMessage>> roundStartPlacements = new HashMap<>();
        public Set<String> readyUsers = new HashSet<>();
        public int turn = 1;
        public Map<String, Integer> playerHps = new HashMap<>();
        public Map<String, Integer> turnActionCounts = new HashMap<>();
        public Map<String, List<String>> playerDecks = new HashMap<>();
        public Map<String, List<String>> currentHands = new HashMap<>();
        public String aiUid;
        public String humanUid;
        public String aiType;
        public int lastRoundResult = 0;

        public boolean isAiMatch() {
            return aiUid != null && humanUid != null;
        }
    }

    private static class SimulationResult {
        List<CombatLogEntry> logs;
        Map<String, Integer> survivorCounts;

        SimulationResult(List<CombatLogEntry> logs, Map<String, Integer> survivorCounts) {
            this.logs = logs;
            this.survivorCounts = survivorCounts;
        }
    }
    
    public static class SimUnit {
        String uid; int x, y; String type; int hp; int maxHp; 
        double nextAttackTime;
        DiceEntity stats;
        SimUnit currentTarget;

        int damage; 
        double aps;
        double baseAps;
        int level;
        int n;

        int waterStacks = 0;
        long waterDebuffEndTime = 0;

        SimUnit(BattleMessage p, DiceEntity diceStats) {
            this.uid = p.getSender();
            this.x = p.getX();
            this.y = p.getY();
            this.type = p.getDiceType();
            this.stats = diceStats;

            this.level = p.getLevel() > 0 ? p.getLevel() : 1;
            this.n = this.level - 1; 

            double hpMultiplier = 1.0 + (0.7 * n);
            this.hp = (int) (diceStats.getHp() * hpMultiplier);
            this.maxHp = this.hp;

            double dmgMultiplier = 1.0 + (0.7 * n);
            this.damage = (int) (diceStats.getDamage() * dmgMultiplier);

            double apsMultiplier = 1.0 + (0.2 * n);
            this.baseAps = diceStats.getAps() * apsMultiplier; 
            this.aps = this.baseAps;
                    
            double attackCycle = 1000.0 / this.aps;
            this.nextAttackTime = attackCycle;
            this.currentTarget = null;
        }
        
        void applyWaterDebuff(long currentTime, int attackerN) {
            double reductionPerStack = 0.12 * (1.0 + 0.1 * attackerN);
            if (this.waterStacks < 3) this.waterStacks++;
            this.waterDebuffEndTime = currentTime + 3000;

            double totalReduction = reductionPerStack * this.waterStacks;
            if (totalReduction > 0.9) totalReduction = 0.9;
            this.aps = this.baseAps * (1.0 - totalReduction);
        }

        void updateStatus(long currentTime) {
            if (waterStacks > 0 && currentTime > waterDebuffEndTime) {
                waterStacks = 0;
                this.aps = this.baseAps;
            }
        }
    }

    @FunctionalInterface
    interface AbilityHandler {
        void execute(SimUnit attacker, SimUnit target, List<SimUnit> allUnits, List<CombatLogEntry> logs, long time, Map<SimUnit, Integer> damageQueue);
    }

    private void initStrategies() {
        abilityHandlers.put("FIRE", (attacker, target, allUnits, logs, time, damageQueue) -> {
            int dmg = attacker.damage;
            damageQueue.merge(target, dmg, Integer::sum);
            logs.add(new CombatLogEntry(attacker.x, attacker.y, target.x, target.y, dmg, "FIRE", time));

            final int splashDmg = 20 + 20 * attacker.level;
            allUnits.stream()
                .filter(u -> !u.uid.equals(attacker.uid) && u.hp > 0 && u != target)
                .filter(u -> getDistance(target.x, target.y, u.x, u.y) <= 1)
                .forEach(splashTarget -> {
                    damageQueue.merge(splashTarget, splashDmg, Integer::sum);
                    logs.add(new CombatLogEntry(target.x, target.y, splashTarget.x, splashTarget.y, splashDmg, "FIRE_SPLASH", time));
                });
        });

        abilityHandlers.put("SNIPER", (attacker, target, allUnits, logs, time, damageQueue) -> {
            int dist = getDistance(attacker.x, attacker.y, target.x, target.y);
            int finalDmg = (int) (attacker.damage * (dist * 0.3 * (1.0 + 0.1 * attacker.n) + 1));
            
            damageQueue.merge(target, finalDmg, Integer::sum);
            logs.add(new CombatLogEntry(attacker.x, attacker.y, target.x, target.y, finalDmg, "SNIPER", time));
        });

        abilityHandlers.put("ELECTRIC", (attacker, target, allUnits, logs, time, damageQueue) -> {
            int dmg = attacker.damage;
            int chaindmg = 25 + 25 * attacker.level;
            damageQueue.merge(target, dmg, Integer::sum);
            logs.add(new CombatLogEntry(attacker.x, attacker.y, target.x, target.y, dmg, "ELECTRIC", time));

            SimUnit chainTarget = allUnits.stream()
                .filter(u -> !u.uid.equals(attacker.uid) && u.hp > 0 && u != target)
                .min(Comparator.comparingInt(u -> getDistance(target.x, target.y, u.x, u.y)))
                .orElse(null);

            if (chainTarget != null && getDistance(target.x, target.y, chainTarget.x, chainTarget.y) <= 1) {
                damageQueue.merge(chainTarget, chaindmg, Integer::sum);
                logs.add(new CombatLogEntry(target.x, target.y, chainTarget.x, chainTarget.y, chaindmg, "ELECTRIC_CHAIN", time));
            }
        });

        AbilityHandler normalHandler = (attacker, target, allUnits, logs, time, damageQueue) -> {
            int dmg = attacker.damage;
            damageQueue.merge(target, dmg, Integer::sum);
            logs.add(new CombatLogEntry(attacker.x, attacker.y, target.x, target.y, dmg, "NORMAL", time));
        };
        
        abilityHandlers.put("SWORD", normalHandler);
        abilityHandlers.put("WIND", normalHandler);
        
        abilityHandlers.put("SHIELD", (attacker, target, allUnits, logs, time, damageQueue) -> {
            logs.add(new CombatLogEntry(attacker.x, attacker.y, attacker.x, attacker.y, 0, "SHIELD_TAUNT", time));
            allUnits.stream()
                .filter(u -> !u.uid.equals(attacker.uid) && u.hp > 0)
                .filter(u -> Math.max(Math.abs(attacker.x - u.x), Math.abs(attacker.y - u.y)) <= 2)
                .forEach(enemy -> enemy.currentTarget = attacker);
        });

        abilityHandlers.put("WATER", (attacker, target, allUnits, logs, time, damageQueue) -> {
            int dmg = attacker.damage;
            damageQueue.merge(target, dmg, Integer::sum);
            target.applyWaterDebuff(time, attacker.n);
            logs.add(new CombatLogEntry(attacker.x, attacker.y, target.x, target.y, dmg, "WATER", time));
        });

        abilityHandlers.put("IRON", (attacker, target, allUnits, logs, time, damageQueue) -> {
            int baseDmg = attacker.damage;
            int currentHpDmg = (int) (target.hp * 0.1 * (1.0 + 0.1 * attacker.n));
            int totalDmg = baseDmg + currentHpDmg;
            damageQueue.merge(target, totalDmg, Integer::sum);
            logs.add(new CombatLogEntry(attacker.x, attacker.y, target.x, target.y, totalDmg, "IRON", time));
        });
    }

    private final AbilityHandler defaultHandler = (attacker, target, allUnits, logs, time, damageQueue) -> {
        int dmg = attacker.damage;
        damageQueue.merge(target, dmg, Integer::sum);
        logs.add(new CombatLogEntry(attacker.x, attacker.y, target.x, target.y, dmg, "NORMAL", time));
    };

    public BattleMessage processBattle(String roomId, BattleMessage msg) {
        GameState state = games.computeIfAbsent(roomId, k -> new GameState());

        synchronized (state) {
            if (msg.getTurn() != state.turn) return null;

            ensureParticipantsInitialized(roomId, state);

            if ("PLACE".equals(msg.getType()) || "MERGE".equals(msg.getType())) {
                handlePlacementOrMerge(roomId, state, msg, !isAiSender(state, msg.getSender()));
            } else if ("COMPLETE".equals(msg.getType())) {
                state.readyUsers.add(msg.getSender());
            }

            if (state.readyUsers.size() >= 2) {
                processBattleResult(state, roomId);
            } else if (state.readyUsers.contains(msg.getSender()) && !isAiSender(state, msg.getSender())) {
                BattleMessage waitMsg = new BattleMessage();
                waitMsg.setType("WAIT_OPPONENT");
                messagingTemplate.convertAndSend("/topic/battle/" + roomId + "/" + msg.getSender(), waitMsg);
            }
        }
        return null;
    }

    private void ensureParticipantsInitialized(String roomId, GameState state) {
        if (state.isAiMatch()) {
            return;
        }

        BattleController.AiMatchContext aiMatchContext = BattleController.getAiMatchContext(roomId);
        if (aiMatchContext != null) {
            state.humanUid = aiMatchContext.humanUid();
            state.aiUid = aiMatchContext.aiUid();
            state.aiType = aiMatchContext.aiType();
        }
    }

    private boolean isAiSender(GameState state, String uid) {
        return state.aiUid != null && state.aiUid.equals(uid);
    }

    private void handlePlacementOrMerge(String roomId, GameState state, BattleMessage msg, boolean sendRefillToPlayer) {
        List<BattleMessage> userPlacements = state.placements.computeIfAbsent(msg.getSender(), k -> new ArrayList<>());

        if ("MERGE".equals(msg.getType())) {
            userPlacements.stream()
                    .filter(p -> p.getX() == msg.getX() && p.getY() == msg.getY())
                    .findFirst()
                    .ifPresent(p -> p.setLevel(msg.getLevel()));
        } else {
            boolean exists = userPlacements.stream().anyMatch(p -> p.getX() == msg.getX() && p.getY() == msg.getY());
            if (!exists) {
                userPlacements.add(msg);
            }
        }

        List<String> nextHand = consumeAndRefillHand(state, msg.getSender(), msg.getDiceType());
        int currentActions = state.turnActionCounts.merge(msg.getSender(), 1, Integer::sum);

        if (currentActions >= MAX_ACTIONS_PER_TURN) {
            state.readyUsers.add(msg.getSender());
            return;
        }

        if (sendRefillToPlayer) {
            BattleMessage refillMsg = new BattleMessage();
            refillMsg.setType("DICE_REFILL");
            refillMsg.setNextHand(nextHand);
            messagingTemplate.convertAndSend("/topic/battle/" + roomId + "/" + msg.getSender(), refillMsg);
        }
    }

    private List<String> consumeAndRefillHand(GameState state, String userUid, String consumedDiceType) {
        // [수정] 배치할 때마다 기존 핸드를 유지하지 않고, 덱에서 완전히 새로 2장을 뽑아 전달합니다.
        // 이전 핸드와의 중복은 허용하되, 새로 뽑는 2장 사이의 중복은 drawUniqueCards 로직에 따라 방지될 수 있습니다.
        List<String> hand = drawNewHand(state, userUid);
        
        state.currentHands.put(userUid, hand);
        return new ArrayList<>(hand);
    }

    private List<String> drawNewHand(GameState state, String userUid) {
        List<String> deck = getOrLoadDeck(state, userUid);
        // [수정] 덱에서 중복 없이 무작위로 2장을 뽑습니다.
        List<String> hand = drawUniqueCards(deck, HAND_SIZE, Collections.emptySet());
        state.currentHands.put(userUid, hand);
        return new ArrayList<>(hand);
    }

    // ✅ [최적화] 게임 내에서 최초 1회만 DB 조회 후 메모리(state.playerDecks)에 캐싱
    private List<String> getOrLoadDeck(GameState state, String userUid) {
        return state.playerDecks.computeIfAbsent(userUid, uid -> {
            if (isAiSender(state, uid)) {
                return buildRandomAiDeck();
            }
            // 일반 플레이어는 DB에서 본인의 덱을 가져옵니다.
            return userRepository.findById(uid)
                    .map(user -> parseDeck(user.getSelectedDeck()))
                    .orElseGet(this::buildRandomAiDeck);
        });
    }

    private List<String> drawUniqueCards(List<String> deck, int count, Set<String> excludedTypes) {
        if (count <= 0 || deck == null || deck.isEmpty()) {
            return new ArrayList<>();
        }

        List<String> candidates = deck.stream()
                .filter(type -> type != null && !type.isBlank())
                .filter(type -> excludedTypes == null || !excludedTypes.contains(type))
                .distinct()
                .collect(Collectors.toList());

        if (candidates.isEmpty()) {
            return new ArrayList<>();
        }

        Collections.shuffle(candidates);
        return new ArrayList<>(candidates.subList(0, Math.min(count, candidates.size())));
    }

    private List<String> parseDeck(String deckStr) {
        if (deckStr == null || deckStr.isBlank()) {
            return new ArrayList<>();
        }
        return Arrays.stream(deckStr.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toCollection(ArrayList::new));
    }

    // ✅ [최적화] DB 조회 대신 메모리에 캐싱된 주사위 목록 사용
    private List<String> buildRandomAiDeck() {
        List<DiceEntity> allDice = new ArrayList<>(this.cachedDiceList);
        Collections.shuffle(allDice);
        return allDice.stream()
                .limit(Math.min(AI_DECK_SIZE, allDice.size()))
                .map(DiceEntity::getDiceType)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private void executeAiTurn(String roomId, GameState state) {
        int actionsUsed = state.turnActionCounts.getOrDefault(state.aiUid, 0);
        
        // 행동 전용 캐시 리스트 (반복 조회 방지)
        List<String> diceTypes = this.cachedDiceList.stream()
                .map(DiceEntity::getDiceType)
                .distinct()
                .collect(Collectors.toList());

        while (actionsUsed < MAX_ACTIONS_PER_TURN) {
            BattleMessage aiAction = chooseAiAction(roomId, state, diceTypes);
            
            if (aiAction == null) {
                break;
            }
            
            handlePlacementOrMerge(roomId, state, aiAction, false);
            actionsUsed = state.turnActionCounts.getOrDefault(state.aiUid, 0);
        }
        
        state.readyUsers.add(state.aiUid);
        
        if (state.readyUsers.size() >= 2) {
            processBattleResult(state, roomId);
        }
    }

    // ✅ [최적화] 인자로 전달받은 diceTypes를 사용하여 DB 조회를 제거
    private BattleMessage chooseAiAction(String roomId, GameState state, List<String> diceTypes) {
        List<String> existingHand = state.currentHands.get(state.aiUid);
        if (existingHand == null) {
            existingHand = drawNewHand(state, state.aiUid);
        }
        List<String> hand = new ArrayList<>(existingHand);
        List<int[]> aiTiles = getAiTiles(roomId);
        String mapDataStr = getRoomMapData(roomId);
        List<BattleMessage> aiPlacements = state.placements.computeIfAbsent(state.aiUid, uid -> new ArrayList<>());

        if (hand.isEmpty()) {
            return null;
        }

        String opponentUid = state.roundStartPlacements.keySet().stream()
                .filter(uid -> !uid.equals(state.aiUid))
                .findFirst()
                .orElse(null);
        if (opponentUid == null) {
            opponentUid = state.placements.keySet().stream()
                    .filter(uid -> !uid.equals(state.aiUid))
                    .findFirst()
                    .orElse(null);
        }
        if (opponentUid == null) {
            opponentUid = state.playerHps.keySet().stream()
                    .filter(uid -> !uid.equals(state.aiUid))
                    .findFirst()
                    .orElse(null);
        }
        if (opponentUid == null && state.humanUid != null && !state.humanUid.equals(state.aiUid)) {
            opponentUid = state.humanUid;
        }

        // 핵심: AI는 "이번 라운드에 새로 배치된 인간 정보"를 보지 못하고,
        // 라운드 시작 시점(이전 라운드까지 확정된) 배치 정보만 참조합니다.
        List<BattleMessage> opponentPlacements = opponentUid == null
                ? List.of()
                : state.roundStartPlacements.getOrDefault(opponentUid, List.of());
        int aiActionsUsed = state.turnActionCounts.getOrDefault(state.aiUid, 0);
        int aiHp = state.playerHps.getOrDefault(state.aiUid, 5);
        int opponentHp = opponentUid == null ? 5 : state.playerHps.getOrDefault(opponentUid, 5);
        
        return rlAiService.chooseAction(
            state,
            roomId,
            mapDataStr,
            hand,
            aiPlacements,
            opponentPlacements,
            aiTiles,
            aiActionsUsed,
            aiHp,
            opponentHp,
            diceTypes
        ).orElse(null);
    }

    private List<int[]> getAiTiles(String roomId) {
        List<MapTileEntity> roomMap = BattleController.getRoomMap(roomId);
        if (roomMap == null || roomMap.isEmpty() || roomMap.get(0).getMapData() == null) {
            return new ArrayList<>();
        }

        String[] tiles = roomMap.get(0).getMapData().split(",");
        List<int[]> aiTiles = new ArrayList<>();
        for (int index = 0; index < tiles.length; index++) {
            String t = tiles[index] == null ? "" : tiles[index].trim().toUpperCase();
            // game_simulator.py의 _tile_is_allowed_for_side("enemy") 로직과 일치시킵니다.
            if (t.equals("ENEMY_TILE") || t.equals("PLAYER_TILE") || t.equals("ALLY_TILE") || 
                t.equals("BOTH_TILE") || t.equals("SHARED_TILE") || t.equals("ANY_TILE")) {
                aiTiles.add(new int[]{index % GRID_WIDTH, index / GRID_WIDTH});
            }
        }
        return aiTiles;
    }

    private String getRoomMapData(String roomId) {
        List<MapTileEntity> roomMap = BattleController.getRoomMap(roomId);
        if (roomMap == null || roomMap.isEmpty() || roomMap.get(0).getMapData() == Null) {
            return "";
        }
        return roomMap.get(0).getMapData();
    }
    
    private void processBattleResult(GameState state, String roomId) {
        List<BattleMessage> allPlacements = new ArrayList<>();
        state.placements.values().forEach(allPlacements::addAll);

        // ✅ [최적화] DB 조회 대신 메모리에 캐싱된 주사위 스탯 맵 사용
        SimulationResult simResult = simulateCombat(state, this.cachedDiceMap);

        Set<String> userUids = new HashSet<>(state.readyUsers);
        Map<String, Integer> damages = new HashMap<>();
        String gameOverLoser = "NONE";

        if (userUids.size() >= 2) {
            List<String> users = new ArrayList<>(userUids);
            String p1 = users.get(0);
            String p2 = users.get(1);

            state.playerHps.putIfAbsent(p1, 5);
            state.playerHps.putIfAbsent(p2, 5);

            int p1Survivors = simResult.survivorCounts.getOrDefault(p1, 0);
            int p2Survivors = simResult.survivorCounts.getOrDefault(p2, 0);

            if (p1Survivors < p2Survivors) {
                damages.put(p1, 1); damages.put(p2, 0);
                state.playerHps.put(p1, state.playerHps.get(p1) - 1);
            } else if (p2Survivors < p1Survivors) {
                damages.put(p1, 0); damages.put(p2, 1);
                state.playerHps.put(p2, state.playerHps.get(p2) - 1);
            } else {
                damages.put(p1, 0); damages.put(p2, 0);
            }

            if (state.playerHps.get(p1) <= 0) gameOverLoser = p1;
            else if (state.playerHps.get(p2) <= 0) gameOverLoser = p2;
            
            if (state.isAiMatch()) {
                int aiSurvivors = simResult.survivorCounts.getOrDefault(state.aiUid, 0);
                int humanSurvivors = simResult.survivorCounts.getOrDefault(state.humanUid, 0);
                state.lastRoundResult = Integer.compare(aiSurvivors, humanSurvivors);
            }
        }

        for (String myUid : userUids) {
            String opponentUid = userUids.stream().filter(u -> !u.equals(myUid)).findFirst().orElse(null);
            
            BattleMessage msg = new BattleMessage();
            msg.setType("REVEAL");
            msg.setAllPlacements(allPlacements);
            msg.setCombatLogs(simResult.logs);
            msg.setDamageToP1(damages.getOrDefault(myUid, 0));
            msg.setDamageToP2(damages.getOrDefault(opponentUid, 0));
            msg.setRemainingMyHp(state.playerHps.getOrDefault(myUid, 5));
            msg.setRemainingEnemyHp(state.playerHps.getOrDefault(opponentUid, 5));
            msg.setTurn(state.turn + 1);

            if (!"NONE".equals(gameOverLoser)) {
                msg.setLoserUid(gameOverLoser);
            } else {
                msg.setNextHand(drawNewHand(state, myUid));
            }

            if (!isAiSender(state, myUid)) {
                messagingTemplate.convertAndSend("/topic/battle/" + roomId + "/" + myUid, msg);
            }
        }

        if (!"NONE".equals(gameOverLoser)) {
            BattleController.removeRoomData(roomId);
            games.remove(roomId);
            ScheduledFuture<?> timer = roomTimers.remove(roomId);
            if (timer != null) timer.cancel(false);
        } else {
            state.placements = copyPlacementsByOwner(allPlacements);
            // 다음 라운드 시작 시점에 참조할 스냅샷(이전 라운드까지의 확정 정보)
            state.roundStartPlacements = copyPlacementsByOwner(allPlacements);
            state.readyUsers.clear();
            state.turnActionCounts.clear();
            state.turn++;

            long lastLogTime = 0;
            if (simResult.logs != null && !simResult.logs.isEmpty()) {
                lastLogTime = simResult.logs.get(simResult.logs.size() - 1).getTimeDelay();
            }

            long animationDuration = lastLogTime + 2000;
            if (animationDuration > 30000) {
                animationDuration = 30000;
            }

            // AI전은 새 라운드 시작과 동시에 AI 턴을 즉시 수행
            if (state.isAiMatch()) {
                executeAiTurn(roomId, state);
            }
            scheduleTurnTimeout(roomId, state.turn, animationDuration);
        }
    }

    private SimulationResult simulateCombat(GameState state, Map<String, DiceEntity> statMap) {
        List<CombatLogEntry> logs = new ArrayList<>();
        List<SimUnit> units = new ArrayList<>();
        
        state.placements.values().forEach(list -> list.forEach(p -> {
            DiceEntity diceStats = statMap.get(p.getDiceType());
            units.add(new SimUnit(p, diceStats));
        }));

        for (long time = 0; time < 30000; time += 100) {
            long livingTeams = units.stream().filter(u -> u.hp > 0).map(u -> u.uid).distinct().count();
            if (livingTeams <= 1) break;

            // ✅ [추가] 교착 상태 감지: 살아있는 유닛들 중 누구도 사거리 내에 적이 없으면 즉시 종료
            boolean canAnyoneAttack = units.stream()
                .filter(u -> u.hp > 0)
                .anyMatch(attacker -> units.stream()
                    .filter(target -> !target.uid.equals(attacker.uid) && target.hp > 0)
                    .anyMatch(target -> getDistance(attacker.x, attacker.y, target.x, target.y) <= attacker.stats.getRange()));

            if (!canAnyoneAttack) {
                break;
            }

            // [지연 반영] 데미지 큐를 관리하기 위한 리스트 (도착 시간, 대상, 데미지 양)
            final int PROJECTILE_DELAY = 300;
            
            // AbilityHandler에서 공통으로 사용하는 임시 데미지 누적기 (매 틱 초기화)
            Map<SimUnit, Integer> tickDamageAccumulator = new HashMap<>();
            
            for (SimUnit attacker : units) {
                if (attacker.hp <= 0) continue;

                attacker.updateStatus(time);

                if (time >= attacker.nextAttackTime) {

                    if ("SHIELD".equals(attacker.type)) {
                        abilityHandlers.get("SHIELD").execute(attacker, null, units, logs, time, tickDamageAccumulator);
                        attacker.nextAttackTime += 1000.0 / attacker.aps;
                        continue;
                    }
    
                    // 타겟팅 시 '이미 들어온 데미지'만 고려하도록 수정
                    if (attacker.currentTarget == null || 
                        attacker.currentTarget.hp <= 0 || 
                        getDistance(attacker.x, attacker.y, attacker.currentTarget.x, attacker.currentTarget.y) > attacker.stats.getRange()) {
                        
                        List<SimUnit> validTargets = units.stream()
                            .filter(u -> !u.uid.equals(attacker.uid) && u.hp > 0)
                            .filter(u -> getDistance(attacker.x, attacker.y, u.x, u.y) <= attacker.stats.getRange())
                            .collect(Collectors.toList());
                
                        if (!validTargets.isEmpty()) {
                            int minDist = validTargets.stream()
                                .mapToInt(u -> getDistance(attacker.x, attacker.y, u.x, u.y))
                                .min()
                                .getAsInt();
                
                            List<SimUnit> closestTargets = validTargets.stream()
                                .filter(u -> getDistance(attacker.x, attacker.y, u.x, u.y) == minDist)
                                .collect(Collectors.toList());
                
                            attacker.currentTarget = closestTargets.get(new Random().nextInt(closestTargets.size()));
                        } else {
                            attacker.currentTarget = null;
                        }
                    }

                    if (attacker.currentTarget != null) {
                        // [핵심] 투사체 도달 시간이 30,000ms를 넘으면 발사하지 않음 (무효화)
                        if (time + PROJECTILE_DELAY < 30000) {
                            AbilityHandler handler = abilityHandlers.getOrDefault(attacker.type, defaultHandler);
                            // tickDamageAccumulator 대신 '미래 시점 반영'을 위해 handler를 호출하거나 
                            // 내부에서 직접 큐를 관리하도록 구조를 변경해야 하지만, 
                            // 현재 구조를 최소한으로 수정하기 위해 logs의 timeDelay를 기준으로 지연 반영합니다.
                            handler.execute(attacker, attacker.currentTarget, units, logs, time, tickDamageAccumulator);
                        }
                        
                        if (attacker.nextAttackTime < time) {
                            attacker.nextAttackTime = time;
                        }
                        attacker.nextAttackTime += 1000.0 / attacker.aps;
                    }
                }
            }

            // [지연 반영] logs를 순회하며 '현재 시점'에 도착해야 하는 데미지만 hp에서 차감
            for (CombatLogEntry log : logs) {
                if (log.getTimeDelay() + PROJECTILE_DELAY == time) {
                    // 로그에 기록된 좌표를 기반으로 타겟 유닛을 찾아 데미지 적용
                    units.stream()
                        .filter(u -> u.x == log.getTargetX() && u.y == log.getTargetY() && u.hp > 0)
                        .findFirst()
                        .ifPresent(u -> u.hp -= log.getDamage());
                }
            }
        }

        Map<String, Integer> survivors = new HashMap<>();
        units.stream().filter(u -> u.hp > 0).forEach(u -> {
            survivors.put(u.uid, survivors.getOrDefault(u.uid, 0) + 1);
        });

        return new SimulationResult(logs, survivors);
    }

    private Map<String, List<BattleMessage>> copyPlacementsByOwner(List<BattleMessage> placements) {
        Map<String, List<BattleMessage>> copied = new HashMap<>();
        for (BattleMessage original : placements) {
            BattleMessage clone = new BattleMessage();
            clone.setType("PLACE");
            clone.setSender(original.getSender());
            clone.setX(original.getX());
            clone.setY(original.getY());
            clone.setDiceType(original.getDiceType());
            clone.setLevel(original.getLevel());
            clone.setTurn(original.getTurn());
            copied.computeIfAbsent(clone.getSender(), k -> new ArrayList<>()).add(clone);
        }
        return copied;
    }

    private int getDistance(int x1, int y1, int x2, int y2) {
        return Math.max(Math.abs(x1 - x2), Math.abs(y1 - y2));
    }

    public void initiateGameStart(String roomId) {
        Set<String> readyUsers = BattleController.roomReadyStatus.get(roomId);
        if (readyUsers == null || readyUsers.size() < 2) return;
        GameState state = games.computeIfAbsent(roomId, k -> new GameState());
        ensureParticipantsInitialized(roomId, state);

        List<String> users = state.isAiMatch()
                ? Arrays.asList(state.humanUid, state.aiUid)
                : new ArrayList<>(readyUsers);

        // ✅ [수정] 플레이어는 본인의 덱을 로드하고, AI(또는 AI 대전 시)에게만 무작위 덱을 분배합니다.
        for (String uid : users) {
            if (isAiSender(state, uid)) {
                // AI는 캐싱된 전체 주사위 중 무작위 5개를 중복 없이 선택
                state.playerDecks.put(uid, buildRandomAiDeck());
            } else {
                // 일반 플레이어는 DB에서 본인이 설정한 덱을 로드 (최초 1회)
                getOrLoadDeck(state, uid);
            }
        }

        List<MapTileEntity> roomMap = BattleController.getRoomMap(roomId);
        String mapDataStr = (roomMap != null && !roomMap.isEmpty()) ? roomMap.get(0).getMapData() : "";
        
        for (int i = 0; i < users.size(); i++) {
            String uid = users.get(i);
            List<String> firstHand = drawNewHand(state, uid);
            
            BattleMessage startMsg = new BattleMessage();
            startMsg.setType("GAME_START");
            startMsg.setTurn(1);
            startMsg.setNextHand(firstHand);
            startMsg.setSender(String.valueOf(i));
            startMsg.setMapData(mapDataStr);
            if (!isAiSender(state, uid)) {
                messagingTemplate.convertAndSend("/topic/battle/" + roomId + "/" + uid, startMsg);
            }
        }

        // 1라운드 시작 시점에는 서로 배치 정보가 없도록 초기화
        state.roundStartPlacements.clear();

        // AI전은 라운드 시작 즉시 AI 턴 실행
        if (state.isAiMatch()) {
            executeAiTurn(roomId, state);
        }
        scheduleTurnTimeout(roomId, 1, 0);
    }
}
