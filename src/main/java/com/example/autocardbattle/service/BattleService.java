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
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class BattleService {

    @Autowired private UserRepository userRepository;
    @Autowired private DiceRepository diceRepository;
    @Autowired private SimpMessageSendingOperations messagingTemplate;

    private Map<String, GameState> games = new ConcurrentHashMap<>();
    private final Map<String, AbilityHandler> abilityHandlers = new HashMap<>();

    public static class GameState {
        public Map<String, List<BattleMessage>> placements = new HashMap<>();
        public Set<String> readyUsers = new HashSet<>();
        public int turn = 1;
        public Map<String, Integer> playerHps = new HashMap<>();
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
        double nextAttackTime; // 정밀한 계산을 위해 double 유지
        DiceEntity stats;
        SimUnit currentTarget; // ✅ [추가] 현재 추적 중인 타겟
        
        SimUnit(BattleMessage p, DiceEntity diceStats) {
            this.uid = p.getSender();
            this.x = p.getX();
            this.y = p.getY();
            this.type = p.getDiceType();
            this.stats = diceStats;
            this.hp = diceStats.getHp();
            this.maxHp = diceStats.getHp();
            
            double attackCycle = 1000.0 / this.stats.getAps();
            this.nextAttackTime = attackCycle;
            this.currentTarget = null;
        }
    }

    // ✅ [핵심 2] 핸들러 인터페이스 변경: 데미지를 즉시 입히지 않고 Map에 담습니다.
    @FunctionalInterface
    interface AbilityHandler {
        void execute(SimUnit attacker, SimUnit target, List<SimUnit> allUnits, List<CombatLogEntry> logs, long time, Map<SimUnit, Integer> damageQueue);
    }

    @PostConstruct
    public void initStrategies() {
        // 1. 🔥 FIRE
        abilityHandlers.put("FIRE", (attacker, target, allUnits, logs, time, damageQueue) -> {
            int dmg = attacker.stats.getDamage();
            // target.hp -= dmg; 대신 damageQueue에 추가
            damageQueue.merge(target, dmg, Integer::sum);
            logs.add(new CombatLogEntry(attacker.x, attacker.y, target.x, target.y, dmg, "FIRE", time));

            final int splashDmg = dmg*2/5;
            allUnits.stream()
                .filter(u -> !u.uid.equals(attacker.uid) && u.hp > 0 && u != target)
                .filter(u -> getDistance(target.x, target.y, u.x, u.y) <= 1)
                .forEach(splashTarget -> {
                    damageQueue.merge(splashTarget, splashDmg, Integer::sum);
                    logs.add(new CombatLogEntry(target.x, target.y, splashTarget.x, splashTarget.y, splashDmg, "FIRE_SPLASH", time));
                });
        });

        // 2. 🎯 SNIPER
        abilityHandlers.put("SNIPER", (attacker, target, allUnits, logs, time, damageQueue) -> {
            int dist = getDistance(attacker.x, attacker.y, target.x, target.y);
            int finalDmg = attacker.stats.getDamage() + (dist * attacker.stats.getDamage() * 3 / 10);
            
            damageQueue.merge(target, finalDmg, Integer::sum);
            logs.add(new CombatLogEntry(attacker.x, attacker.y, target.x, target.y, finalDmg, "SNIPER", time));
        });

        // 3. ⚡ ELECTRIC
        abilityHandlers.put("ELECTRIC", (attacker, target, allUnits, logs, time, damageQueue) -> {
            int dmg = attacker.stats.getDamage();
            int chaindmg = dmg*5/7;
            damageQueue.merge(target, dmg, Integer::sum);
            logs.add(new CombatLogEntry(attacker.x, attacker.y, target.x, target.y, dmg, "ELECTRIC", time));

            SimUnit chainTarget = allUnits.stream()
                .filter(u -> !u.uid.equals(attacker.uid) && u.hp > 0 && u != target)
                .min(Comparator.comparingInt(u -> getDistance(target.x, target.y, u.x, u.y)))
                .orElse(null);

            if (chainTarget != null && getDistance(target.x, target.y, chainTarget.x, chainTarget.y) <= 2) {
                damageQueue.merge(chainTarget, chaindmg, Integer::sum);
                logs.add(new CombatLogEntry(target.x, target.y, chainTarget.x, chainTarget.y, chaindmg, "ELECTRIC_CHAIN", time));
            }
        });

        // 4. ⚔️ NORMAL
        AbilityHandler normalHandler = (attacker, target, allUnits, logs, time, damageQueue) -> {
            int dmg = attacker.stats.getDamage();
            damageQueue.merge(target, dmg, Integer::sum);
            logs.add(new CombatLogEntry(attacker.x, attacker.y, target.x, target.y, dmg, "NORMAL", time));
        };
        
        abilityHandlers.put("SWORD", normalHandler);
        abilityHandlers.put("WIND", normalHandler);
    }

    private final AbilityHandler defaultHandler = (attacker, target, allUnits, logs, time, damageQueue) -> {
        int dmg = attacker.stats.getDamage();
        damageQueue.merge(target, dmg, Integer::sum);
        logs.add(new CombatLogEntry(attacker.x, attacker.y, target.x, target.y, dmg, "NORMAL", time));
    };

    // 메인 로직 처리
    public BattleMessage processBattle(String roomId, BattleMessage msg) {
        GameState state = games.computeIfAbsent(roomId, k -> new GameState());

        synchronized (state) {
            // 턴 검증: 이전 라운드 메시지 차단
            if (msg.getTurn() != state.turn) {
                return null;
            }

            if ("PLACE".equals(msg.getType())) {
                List<BattleMessage> userPlacements = state.placements.computeIfAbsent(msg.getSender(), k -> new ArrayList<>());
                boolean alreadyExists = userPlacements.stream()
                        .anyMatch(p -> p.getX() == msg.getX() && p.getY() == msg.getY());

                if (!alreadyExists) {
                    userPlacements.add(msg);
                    if (userPlacements.size() >= state.turn * 3) {
                        state.readyUsers.add(msg.getSender());
                    } else {
                        List<String> nextHand = generateRandomHand(msg.getSender());
                        BattleMessage refillMsg = new BattleMessage();
                        refillMsg.setType("DICE_REFILL");
                        refillMsg.setNextHand(nextHand);
                        messagingTemplate.convertAndSend("/topic/battle/" + roomId + "/" + msg.getSender(), refillMsg);
                    }
                }
            }

            if ("COMPLETE".equals(msg.getType())) {
                state.readyUsers.add(msg.getSender());
            }

            if (state.readyUsers.size() >= 2) {
                processBattleResult(state, roomId);
            } else if (state.readyUsers.contains(msg.getSender())) {
                BattleMessage waitMsg = new BattleMessage();
                waitMsg.setType("WAIT_OPPONENT");
                messagingTemplate.convertAndSend("/topic/battle/" + roomId + "/" + msg.getSender(), waitMsg);
            }
        }
        return null;
    }

    private void processBattleResult(GameState state, String roomId) {
        List<BattleMessage> allPlacements = new ArrayList<>();
        state.placements.values().forEach(allPlacements::addAll);

        List<DiceEntity> allDiceInfo = diceRepository.findAll();
        Map<String, DiceEntity> statMap = allDiceInfo.stream()
                .collect(Collectors.toMap(DiceEntity::getDiceType, d -> d));

        SimulationResult simResult = simulateCombat(state, statMap);

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
                msg.setNextHand(generateRandomHand(myUid));
            }

            messagingTemplate.convertAndSend("/topic/battle/" + roomId + "/" + myUid, msg);
        }

        if (!"NONE".equals(gameOverLoser)) {
            BattleController.removeRoomData(roomId);
        } else {
            state.readyUsers.clear();
            state.turn++;
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
            // 조기 종료 체크
            long livingTeams = units.stream().filter(u -> u.hp > 0).map(u -> u.uid).distinct().count();
            if (livingTeams <= 1) break;

            Map<SimUnit, Integer> tickDamageAccumulator = new HashMap<>();

            for (SimUnit attacker : units) {
                if (attacker.hp <= 0) continue;

                if (time >= attacker.nextAttackTime) {
    
                    // 현재 타겟 유효성 검사 (죽을 예정인 적 포함)
                    int pendingDamage = tickDamageAccumulator.getOrDefault(attacker.currentTarget, 0);
                    boolean isTargetDeadOrDying = attacker.currentTarget != null && (attacker.currentTarget.hp - pendingDamage <= 0);
                
                    if (attacker.currentTarget == null || 
                        isTargetDeadOrDying || 
                        getDistance(attacker.x, attacker.y, attacker.currentTarget.x, attacker.currentTarget.y) > attacker.stats.getRange()) {
                        
                        // 1. [필터링] 사거리 내에 있고, 아직 살아있으며, 이번 턴에 죽지 않을 적들을 찾음
                        List<SimUnit> validTargets = units.stream()
                            .filter(u -> !u.uid.equals(attacker.uid) && u.hp > 0) // 적군이고 생존함
                            .filter(u -> (u.hp - tickDamageAccumulator.getOrDefault(u, 0)) > 0) // 이번 턴에 안 죽을 놈
                            .filter(u -> getDistance(attacker.x, attacker.y, u.x, u.y) <= attacker.stats.getRange()) // 사거리 내
                            .collect(Collectors.toList());
                
                        if (!validTargets.isEmpty()) {
                            // 2. [거리 계산] 후보들 중 '최소 거리'가 몇인지 찾음
                            int minDist = validTargets.stream()
                                .mapToInt(u -> getDistance(attacker.x, attacker.y, u.x, u.y))
                                .min()
                                .getAsInt();
                
                            // 3. [최종 후보] 최소 거리와 똑같은 거리에 있는 적들만 추려냄
                            List<SimUnit> closestTargets = validTargets.stream()
                                .filter(u -> getDistance(attacker.x, attacker.y, u.x, u.y) == minDist)
                                .collect(Collectors.toList());
                
                            // 4. [랜덤 선택] 가장 가까운 적들 중에서 무작위로 하나 선택
                            attacker.currentTarget = closestTargets.get(new Random().nextInt(closestTargets.size()));
                        } else {
                            attacker.currentTarget = null;
                        }
                    }

                    // 공격 실행
                    if (attacker.currentTarget != null) {
                        AbilityHandler handler = abilityHandlers.getOrDefault(attacker.type, defaultHandler);
                        handler.execute(attacker, attacker.currentTarget, units, logs, time, tickDamageAccumulator);
                        
                        // ✅ [머신건 버그 수정] 
                        // 공격 쿨타임이 현재 시간보다 너무 뒤쳐졌다면(Idle 상태였다면), 현재 시간 기준으로 재조정
                        if (attacker.nextAttackTime < time) {
                            attacker.nextAttackTime = time;
                        }
                        
                        // 다음 공격 시간 예약
                        attacker.nextAttackTime += 1000.0 / attacker.stats.getAps();
                    }
                }
            }

            // 데미지 일괄 적용
            tickDamageAccumulator.forEach((unit, damage) -> {
                unit.hp -= damage;
            });
        }

        Map<String, Integer> survivors = new HashMap<>();
        units.stream().filter(u -> u.hp > 0).forEach(u -> {
            survivors.put(u.uid, survivors.getOrDefault(u.uid, 0) + 1);
        });

        return new SimulationResult(logs, survivors);
    }

    private int getDistance(int x1, int y1, int x2, int y2) {
        return Math.max(Math.abs(x1 - x2), Math.abs(y1 - y2));
    }

    public void initiateGameStart(String roomId) {
        Set<String> readyUsers = BattleController.roomReadyStatus.get(roomId);
        if (readyUsers == null || readyUsers.size() < 2) return;
        List<String> users = new ArrayList<>(readyUsers);

        // ✅ 방에 할당된 맵 데이터 가져오기
        List<MapTileEntity> roomMap = BattleController.getRoomMap(roomId);
        String mapDataStr = (roomMap != null && !roomMap.isEmpty()) ? roomMap.get(0).getMapData() : "";
        
        for (int i = 0; i < users.size(); i++) {
            String uid = users.get(i);
            List<String> firstHand = generateRandomHand(uid);
            
            BattleMessage startMsg = new BattleMessage();
            startMsg.setType("GAME_START");
            startMsg.setTurn(1);
            startMsg.setNextHand(firstHand);
            startMsg.setSender(String.valueOf(i));
            
            // ✅ [추가] 맵 데이터를 이때 동시에 전송합니다.
            startMsg.setMapData(mapDataStr);
            messagingTemplate.convertAndSend("/topic/battle/" + roomId + "/" + uid, startMsg);
        }
    }

    private List<String> generateRandomHand(String userUid) {
        return userRepository.findById(userUid).map(user -> {
            String deckStr = user.getSelectedDeck();
            if (deckStr == null || deckStr.isEmpty()) return new ArrayList<String>();
            List<String> fullDeck = new ArrayList<>(Arrays.asList(deckStr.split(",")));
            Collections.shuffle(fullDeck);
            return fullDeck.subList(0, Math.min(2, fullDeck.size()));
        }).orElseGet(ArrayList::new);
    }
}
