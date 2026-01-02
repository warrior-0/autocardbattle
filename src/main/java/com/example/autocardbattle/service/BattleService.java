package com.example.autocardbattle.service;

import com.example.autocardbattle.controller.BattleController;
import com.example.autocardbattle.dto.BattleMessage;
import com.example.autocardbattle.dto.BattleMessage.CombatLogEntry; // CombatLogEntry 임포트 필수
import com.example.autocardbattle.entity.DiceEntity;
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

    // 게임 상태 관리
    private Map<String, GameState> games = new ConcurrentHashMap<>();

    // 전략 패턴을 위한 핸들러 맵
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
        String uid; int x, y; String type; int hp; long nextAttackTime; DiceEntity stats;
        SimUnit(BattleMessage p) {
            this.uid = p.getSender(); this.x = p.getX(); this.y = p.getY(); this.type = p.getDiceType();
            this.stats = statMap.get(this.type);
            if (this.stats == null) {
                // DB 데이터 누락 시 기본값 (방어 코드)
                this.stats = new DiceEntity(); 
                this.stats.setHp(100); this.stats.setDamage(10); 
                this.stats.setRange(1); this.stats.setAps(1.0);
            }
            this.hp = this.stats.getHp();
            this.nextAttackTime = (long)(Math.random() * 500); 
        }
    }

    // 전략 패턴 인터페이스
    @FunctionalInterface
    interface AbilityHandler {
        void execute(SimUnit attacker, SimUnit target, List<SimUnit> allUnits, List<CombatLogEntry> logs, long time);
    }

    // ✅ 서버 시작 시 주사위별 특수 능력 로직 초기화
    @PostConstruct
    public void initStrategies() {
        // 1. 🔥 FIRE: 타겟 + 주변 1칸 스플래시 (데미지 절반)
        abilityHandlers.put("FIRE", (attacker, target, allUnits, logs, time) -> {
            int dmg = attacker.stats.getDamage();
            target.hp -= dmg;
            logs.add(new CombatLogEntry(attacker.x, attacker.y, target.x, target.y, dmg, "FIRE", time));

            // 람다 내부에서 사용할 변수 (final 효과)
            final int splashDmg = dmg / 2;
            
            allUnits.stream()
                .filter(u -> !u.uid.equals(attacker.uid) && u.hp > 0 && u != target)
                .filter(u -> getDistance(target.x, target.y, u.x, u.y) <= 1)
                .forEach(splashTarget -> {
                    splashTarget.hp -= splashDmg;
                    logs.add(new CombatLogEntry(target.x, target.y, splashTarget.x, splashTarget.y, splashDmg, "FIRE_SPLASH", time));
                });
        });

        // 2. 🎯 SNIPER: 거리가 멀수록 데미지 증가
        abilityHandlers.put("SNIPER", (attacker, target, allUnits, logs, time) -> {
            int dist = getDistance(attacker.x, attacker.y, target.x, target.y);
            int finalDmg = attacker.stats.getDamage() + (dist * 10);
            
            target.hp -= finalDmg;
            logs.add(new CombatLogEntry(attacker.x, attacker.y, target.x, target.y, finalDmg, "SNIPER", time));
        });

        // 3. ⚡ ELECTRIC: 타겟 + 가장 가까운 적 1명 전이
        abilityHandlers.put("ELECTRIC", (attacker, target, allUnits, logs, time) -> {
            int dmg = attacker.stats.getDamage();
            target.hp -= dmg;
            logs.add(new CombatLogEntry(attacker.x, attacker.y, target.x, target.y, dmg, "ELECTRIC", time));

            SimUnit chainTarget = allUnits.stream()
                .filter(u -> !u.uid.equals(attacker.uid) && u.hp > 0 && u != target)
                .min(Comparator.comparingInt(u -> getDistance(target.x, target.y, u.x, u.y)))
                .orElse(null);

            if (chainTarget != null && getDistance(target.x, target.y, chainTarget.x, chainTarget.y) <= 2) {
                chainTarget.hp -= dmg;
                logs.add(new CombatLogEntry(target.x, target.y, chainTarget.x, chainTarget.y, dmg, "ELECTRIC_CHAIN", time));
            }
        });

        // 4. ⚔️ 기본 공격 (SWORD, WIND 등)
        AbilityHandler normalHandler = (attacker, target, allUnits, logs, time) -> {
            int dmg = attacker.stats.getDamage();
            target.hp -= dmg;
            logs.add(new CombatLogEntry(attacker.x, attacker.y, target.x, target.y, dmg, "NORMAL", time));
        };
        
        abilityHandlers.put("SWORD", normalHandler);
        abilityHandlers.put("WIND", normalHandler); // WIND는 DB의 높은 APS(공속)로 차별화됨
    }

    // 등록되지 않은 주사위를 위한 기본 핸들러
    private final AbilityHandler defaultHandler = (attacker, target, allUnits, logs, time) -> {
        int dmg = attacker.stats.getDamage();
        target.hp -= dmg;
        logs.add(new CombatLogEntry(attacker.x, attacker.y, target.x, target.y, dmg, "NORMAL", time));
    };

    // 메인 로직 처리
    public BattleMessage processBattle(String roomId, BattleMessage msg) {
        GameState state = games.computeIfAbsent(roomId, k -> new GameState());

        // 배치 처리
        if ("PLACE".equals(msg.getType())) {
            List<BattleMessage> userPlacements = state.placements.computeIfAbsent(msg.getSender(), k -> new ArrayList<>());
            boolean alreadyExists = userPlacements.stream()
                    .anyMatch(p -> p.getX() == msg.getX() && p.getY() == msg.getY());

            if (!alreadyExists) {
                userPlacements.add(msg);
                
                // 리필
                List<String> nextHand = generateRandomHand(msg.getSender());
                BattleMessage refillMsg = new BattleMessage();
                refillMsg.setType("DICE_REFILL");
                refillMsg.setNextHand(nextHand);
                messagingTemplate.convertAndSend("/topic/battle/" + roomId + "/" + msg.getSender(), refillMsg);
            }
            return null;
        }

        // 턴 완료 처리
        if ("COMPLETE".equals(msg.getType())) {
            state.readyUsers.add(msg.getSender());

            if (state.readyUsers.size() >= 2) {
                if (state.turn < 3) {
                    state.turn++;
                    state.readyUsers.clear();
                    
                    // 다음 턴 손패 지급
                    for (String userUid : state.placements.keySet()) {
                        BattleMessage personalMsg = new BattleMessage();
                        personalMsg.setType("TURN_PROGRESS");
                        personalMsg.setTurn(state.turn);
                        personalMsg.setNextHand(generateRandomHand(userUid));
                        messagingTemplate.convertAndSend("/topic/battle/" + roomId + "/" + userUid, personalMsg);
                    }
                    return null;
                } else {
                    // ✅ 3턴 종료: 전투 시뮬레이션 및 결과 처리
                    processBattleResult(state, roomId);
                    return null;
                }
            } else {
                BattleMessage waitMsg = new BattleMessage();
                waitMsg.setType("WAIT_OPPONENT");
                return waitMsg;
            }
        }
        return null;
    }

    // 전투 결과 처리 및 전송
    private void processBattleResult(GameState state, String roomId) {
        List<BattleMessage> allPlacements = new ArrayList<>();
        state.placements.values().forEach(allPlacements::addAll);

        // 1. DB에서 주사위 스탯 로드
        List<DiceEntity> allDiceInfo = diceRepository.findAll();
        Map<String, DiceEntity> statMap = allDiceInfo.stream()
                .collect(Collectors.toMap(DiceEntity::getDiceType, d -> d));

        // 2. 전투 시뮬레이션
        SimulationResult simResult = simulateCombat(state, statMap);

        // 3. 결과 판정 (남은 유닛 수 비교)
        Set<String> userUids = state.placements.keySet();
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

            // 적게 남은 쪽 1 데미지
            if (p1Survivors < p2Survivors) {
                damages.put(p1, 1);
                damages.put(p2, 0);
                state.playerHps.put(p1, state.playerHps.get(p1) - 1);
            } else if (p2Survivors < p1Survivors) {
                damages.put(p1, 0);
                damages.put(p2, 1);
                state.playerHps.put(p2, state.playerHps.get(p2) - 1);
            } else {
                damages.put(p1, 0);
                damages.put(p2, 0);
            }

            // 게임 종료 체크
            if (state.playerHps.get(p1) <= 0) gameOverLoser = p1;
            else if (state.playerHps.get(p2) <= 0) gameOverLoser = p2;
        }

        // 4. 결과 전송
        for (String myUid : userUids) {
            String opponentUid = userUids.stream().filter(u -> !u.equals(myUid)).findFirst().orElse(null);
            
            BattleMessage msg = new BattleMessage();
            msg.setType("REVEAL");
            msg.setAllPlacements(allPlacements);
            msg.setCombatLogs(simResult.logs);
            msg.setDamageToP1(damages.getOrDefault(myUid, 0)); // 나에게
            msg.setDamageToP2(damages.getOrDefault(opponentUid, 0)); // 적에게

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
            state.turn = 1;
        }
    }

    // 시뮬레이션 내부 로직
    private SimulationResult simulateCombat(GameState state, Map<String, DiceEntity> statMap) {
        List<CombatLogEntry> logs = new ArrayList<>();

        List<SimUnit> units = new ArrayList<>();
        state.placements.values().forEach(list -> list.forEach(p -> units.add(new SimUnit(p))));

        // 30초(30000ms) 시뮬레이션
        for (long time = 0; time < 30000; time += 100) {
            for (SimUnit attacker : units) {
                if (attacker.hp <= 0) continue;

                if (time >= attacker.nextAttackTime) {
                    List<SimUnit> targets = units.stream()
                        .filter(u -> !u.uid.equals(attacker.uid) && u.hp > 0)
                        .filter(u -> getDistance(attacker.x, attacker.y, u.x, u.y) <= attacker.stats.getRange())
                        .collect(Collectors.toList());

                    if (!targets.isEmpty()) {
                        SimUnit target = targets.get(new Random().nextInt(targets.size()));

                        // ✅ 전략 패턴으로 능력 실행 (깔끔!)
                        AbilityHandler handler = abilityHandlers.getOrDefault(attacker.type, defaultHandler);
                        handler.execute(attacker, target, units, logs, time);

                        // 쿨타임 적용
                        attacker.nextAttackTime = time + (long)(1000 / attacker.stats.getAps());
                    }
                }
            }
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

        for (int i = 0; i < users.size(); i++) {
            String uid = users.get(i);
            List<String> firstHand = generateRandomHand(uid);
            BattleMessage startMsg = new BattleMessage();
            startMsg.setType("GAME_START");
            startMsg.setTurn(1);
            startMsg.setNextHand(firstHand);
            startMsg.setSender(String.valueOf(i)); 
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
