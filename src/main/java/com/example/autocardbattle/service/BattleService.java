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

    @Autowired private UserRepository userRepository;
    @Autowired private DiceRepository diceRepository;
    @Autowired private SimpMessageSendingOperations messagingTemplate;

    private Map<String, GameState> games = new ConcurrentHashMap<>();
    private final Map<String, AbilityHandler> abilityHandlers = new HashMap<>();

    // ✅ [추가] 턴 제한시간을 관리할 스케줄러
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final Map<String, ScheduledFuture<?>> roomTimers = new ConcurrentHashMap<>();

    // [수정] 62초(기본) + 전투 애니메이션 시간(extraDelayMs) 만큼 기다리는 메서드
    private void scheduleTurnTimeout(String roomId, int currentTurn, long extraDelayMs) {
        ScheduledFuture<?> existingTask = roomTimers.get(roomId);
        if (existingTask != null && !existingTask.isDone()) {
            existingTask.cancel(false);
        }

        // 총 대기 시간 = 기본 60초 + 애니메이션 시간
        long totalDelay = 60000 + extraDelayMs;

        ScheduledFuture<?> task = scheduler.schedule(() -> {
            GameState state = games.get(roomId);
            if (state == null) return;

            synchronized (state) {
                if (state.turn != currentTurn) return;

                Set<String> allUsers = BattleController.roomReadyStatus.get(roomId);
                if (allUsers != null) {
                    state.readyUsers.addAll(allUsers);
                }
                processBattleResult(state, roomId);
            }
        }, totalDelay, TimeUnit.MILLISECONDS); // [중요] 단위를 MILLISECONDS로 변경

        roomTimers.put(roomId, task);
    }

    public static class GameState {
        public Map<String, List<BattleMessage>> placements = new HashMap<>();
        public Set<String> readyUsers = new HashSet<>();
        public int turn = 1;
        public Map<String, Integer> playerHps = new HashMap<>();
        
        // ✅ [추가] 이번 턴에 유저가 몇 번 행동했는지 체크 (합치기도 행동으로 인정)
        public Map<String, Integer> turnActionCounts = new HashMap<>();
    }

    private static class SimulationResult {
        List<CombatLogEntry> logs;
        Map<String, Integer> survivorCounts;

        SimulationResult(List<CombatLogEntry> logs, Map<String, Integer> survivorCounts) {
            this.logs = logs;
            this.survivorCounts = survivorCounts;
        }
    }
    
    // ✅ [수정] 시뮬레이션 유닛 클래스: 레벨별 스탯 계산 로직 추가
    public static class SimUnit {
        String uid; int x, y; String type; int hp; int maxHp; 
        double nextAttackTime;
        DiceEntity stats;     // 기본 스탯 정보 (참조용)
        SimUnit currentTarget;

        // ✅ [추가] 레벨 보정이 적용된 실제 전투 스탯
        int damage; 
        double aps;
        double baseAps;
        int level;
        int n;

        // ✅ 물 주사위 디버프 상태
        int waterStacks = 0;
        long waterDebuffEndTime = 0;

        SimUnit(BattleMessage p, DiceEntity diceStats) {
            this.uid = p.getSender();
            this.x = p.getX();
            this.y = p.getY();
            this.type = p.getDiceType();
            this.stats = diceStats;

            // 1. 합친 횟수(n) 계산: 레벨이 0이면 1로 간주
            this.level = p.getLevel() > 0 ? p.getLevel() : 1;
            this.n = this.level - 1; 

            // 2. 체력(HP) 계산: 기본 * (1 + 0.7 * n)
            double hpMultiplier = 1.0 + (0.7 * n);
            this.hp = (int) (diceStats.getHp() * hpMultiplier);
            this.maxHp = this.hp;

            // 3. 공격력(Damage) 계산: 기본 * (1 + 0.7 * n)
            double dmgMultiplier = 1.0 + (0.7 * n);
            this.damage = (int) (diceStats.getDamage() * dmgMultiplier);

            // 4. 공격속도(APS) 계산: 기본 * (1 + 0.2 * n)
            double apsMultiplier = 1.0 + (0.2 * n);
            // ✅ [수정 후] baseAps에 먼저 저장하고, 이를 aps에 대입
            this.baseAps = diceStats.getAps() * apsMultiplier; 
            this.aps = this.baseAps;
                    
            // 공격 주기 설정 (1초 = 1000ms)
            double attackCycle = 1000.0 / this.aps;
            this.nextAttackTime = attackCycle;
            this.currentTarget = null;
        }
        
        // ✅ 물 디버프 적용 로직
        void applyWaterDebuff(long currentTime, int attackerN) {
            double reductionPerStack = 0.12 * (1.0 + 0.1 * attackerN);
            if (this.waterStacks < 3) this.waterStacks++;
            this.waterDebuffEndTime = currentTime + 3000;

            double totalReduction = reductionPerStack * this.waterStacks;
            if (totalReduction > 0.9) totalReduction = 0.9;
            this.aps = this.baseAps * (1.0 - totalReduction);
        }

        // ✅ 매 틱마다 디버프 만료 체크
        void updateStatus(long currentTime) {
            if (waterStacks > 0 && currentTime > waterDebuffEndTime) {
                waterStacks = 0;
                this.aps = this.baseAps;
            }
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
            int dmg = attacker.damage;
            // target.hp -= dmg; 대신 damageQueue에 추가
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

        // 2. 🎯 SNIPER
        abilityHandlers.put("SNIPER", (attacker, target, allUnits, logs, time, damageQueue) -> {
            int dist = getDistance(attacker.x, attacker.y, target.x, target.y);
            int finalDmg = (int) (attacker.damage * (dist * 0.3 * (1.0 + 0.1 * attacker.n) + 1));
            
            damageQueue.merge(target, finalDmg, Integer::sum);
            logs.add(new CombatLogEntry(attacker.x, attacker.y, target.x, target.y, finalDmg, "SNIPER", time));
        });

        // 3. ⚡ ELECTRIC
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

        // 4. ⚔️ NORMAL
        AbilityHandler normalHandler = (attacker, target, allUnits, logs, time, damageQueue) -> {
            int dmg = attacker.damage;
            damageQueue.merge(target, dmg, Integer::sum);
            logs.add(new CombatLogEntry(attacker.x, attacker.y, target.x, target.y, dmg, "NORMAL", time));
        };
        
        abilityHandlers.put("SWORD", normalHandler);
        abilityHandlers.put("WIND", normalHandler);
        
        // 5. SHIELD (방패): 도발
        abilityHandlers.put("SHIELD", (attacker, target, allUnits, logs, time, damageQueue) -> {
            logs.add(new CombatLogEntry(attacker.x, attacker.y, attacker.x, attacker.y, 0, "SHIELD_TAUNT", time));
            allUnits.stream()
                .filter(u -> !u.uid.equals(attacker.uid) && u.hp > 0)
                .filter(u -> Math.max(Math.abs(attacker.x - u.x), Math.abs(attacker.y - u.y)) <= 2)
                .forEach(enemy -> enemy.currentTarget = attacker);
        });

        // 6. WATER (물): 공속 감소
        abilityHandlers.put("WATER", (attacker, target, allUnits, logs, time, damageQueue) -> {
            int dmg = attacker.damage;
            damageQueue.merge(target, dmg, Integer::sum);
            target.applyWaterDebuff(time, attacker.n);
            logs.add(new CombatLogEntry(attacker.x, attacker.y, target.x, target.y, dmg, "WATER", time));
        });

        // 7. IRON (쇠): 현재 체력 비례 피해
        abilityHandlers.put("IRON", (attacker, target, allUnits, logs, time, damageQueue) -> {
            double ratio = 0.10 * (1.0 + 0.1 * attacker.n);
            int bonusDmg = (int) (target.hp * ratio);
            int totalDmg = attacker.damage + bonusDmg;
            damageQueue.merge(target, totalDmg, Integer::sum);
            logs.add(new CombatLogEntry(attacker.x, attacker.y, target.x, target.y, totalDmg, "IRON", time));
        });
    }

    private final AbilityHandler defaultHandler = (attacker, target, allUnits, logs, time, damageQueue) -> {
        int dmg = attacker.damage;
        damageQueue.merge(target, dmg, Integer::sum);
        logs.add(new CombatLogEntry(attacker.x, attacker.y, target.x, target.y, dmg, "NORMAL", time));
    };

    // 메인 로직 처리
    // ✅ [핵심 변경] 로직 처리
    public BattleMessage processBattle(String roomId, BattleMessage msg) {
        GameState state = games.computeIfAbsent(roomId, k -> new GameState());

        synchronized (state) {
            if (msg.getTurn() != state.turn) return null;

            // PLACE 또는 MERGE 모두 처리
            if ("PLACE".equals(msg.getType()) || "MERGE".equals(msg.getType())) {
                List<BattleMessage> userPlacements = state.placements.computeIfAbsent(msg.getSender(), k -> new ArrayList<>());

                if ("MERGE".equals(msg.getType())) {
                    // 합치기: 기존 유닛을 찾아 레벨 업데이트
                    userPlacements.stream()
                        .filter(p -> p.getX() == msg.getX() && p.getY() == msg.getY())
                        .findFirst()
                        .ifPresent(p -> p.setLevel(msg.getLevel()));
                } else {
                    // 배치: 중복 없으면 추가
                    boolean exists = userPlacements.stream().anyMatch(p -> p.getX() == msg.getX() && p.getY() == msg.getY());
                    if (!exists) {
                        userPlacements.add(msg);
                    }
                }

                // ✅ 행동 횟수 카운트 (배치된 유닛 수가 아니라, 유저가 카드를 낸 횟수를 기준으로 함)
                int currentActions = state.turnActionCounts.merge(msg.getSender(), 1, Integer::sum);

                // 3번 행동했으면 준비 완료 (합치기도 포함됨!)
                if (currentActions >= 3) {
                    state.readyUsers.add(msg.getSender());
                } else {
                    // 아직 덜 냈으면 리필
                    List<String> nextHand = generateRandomHand(msg.getSender());
                    BattleMessage refillMsg = new BattleMessage();
                    refillMsg.setType("DICE_REFILL");
                    refillMsg.setNextHand(nextHand);
                    messagingTemplate.convertAndSend("/topic/battle/" + roomId + "/" + msg.getSender(), refillMsg);
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
            // ✅ [추가] 게임 종료 시 타이머 제거
            ScheduledFuture<?> timer = roomTimers.remove(roomId);
            if (timer != null) timer.cancel(false);
        } else {
            state.readyUsers.clear();
            state.turnActionCounts.clear();
            state.turn++;

            // ✅ [추가] 전투 애니메이션 시간 계산 (클라이언트 script.js 로직과 동기화)
            long lastLogTime = 0;
            if (simResult.logs != null && !simResult.logs.isEmpty()) {
                lastLogTime = simResult.logs.get(simResult.logs.size() - 1).getTimeDelay();
            }

            // 실제 종료 시간 = 마지막 공격 시간 + 2초(여유)
            long animationDuration = lastLogTime + 2000;
            
            // 클라이언트는 최대 30초까지만 애니메이션을 보여주므로 서버도 30초로 제한 (캡핑)
            if (animationDuration > 30000) {
                animationDuration = 30000;
            }
            // [수정] 다음 턴 타이머 예약 (애니메이션 시간만큼 더 기다려줌)
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
            // 조기 종료 체크
            long livingTeams = units.stream().filter(u -> u.hp > 0).map(u -> u.uid).distinct().count();
            if (livingTeams <= 1) break;

            Map<SimUnit, Integer> tickDamageAccumulator = new HashMap<>();

            for (SimUnit attacker : units) {
                if (attacker.hp <= 0) continue;

                attacker.updateStatus(time);

                if (time >= attacker.nextAttackTime) {

                    // ✅ 방패 전용 로직: 타겟 없어도 도발 발동
                    if ("SHIELD".equals(attacker.type)) {
                        abilityHandlers.get("SHIELD").execute(attacker, null, units, logs, time, tickDamageAccumulator);
                        attacker.nextAttackTime += 1000.0 / attacker.aps;
                        continue;
                    }
    
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
                        attacker.nextAttackTime += 1000.0 / attacker.aps;
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
        // ✅ [추가] 1턴 타임아웃 예약
        scheduleTurnTimeout(roomId, 1, 0);
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
