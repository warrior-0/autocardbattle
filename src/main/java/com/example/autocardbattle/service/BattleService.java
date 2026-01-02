package com.example.autocardbattle.service;

import com.example.autocardbattle.controller.BattleController;
import com.example.autocardbattle.dto.BattleMessage;
import com.example.autocardbattle.dto.BattleMessage.CombatLogEntry;
import com.example.autocardbattle.entity.DiceEntity;
import com.example.autocardbattle.repository.DiceRepository;
import com.example.autocardbattle.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class BattleService {

    @Autowired private UserRepository userRepository;
    @Autowired private DiceRepository diceRepository; // ✅ DB 연동을 위해 추가
    @Autowired private SimpMessageSendingOperations messagingTemplate;

    private Map<String, GameState> games = new ConcurrentHashMap<>();

    public static class GameState {
        public Map<String, List<BattleMessage>> placements = new HashMap<>();
        public Set<String> readyUsers = new HashSet<>();
        public int turn = 1;
        // ✅ 서버에서 유저 체력을 관리 (게임 종료 판정용)
        public Map<String, Integer> playerHps = new HashMap<>();
    }

    // 시뮬레이션 결과를 담을 내부 클래스
    private static class SimulationResult {
        List<CombatLogEntry> logs;
        Map<String, Integer> survivorCounts;

        SimulationResult(List<CombatLogEntry> logs, Map<String, Integer> survivorCounts) {
            this.logs = logs;
            this.survivorCounts = survivorCounts;
        }
    }

    public BattleMessage processBattle(String roomId, BattleMessage msg) {
        GameState state = games.computeIfAbsent(roomId, k -> new GameState());

        // 1. 유저의 개별 배치 처리 (PLACE)
        if ("PLACE".equals(msg.getType())) {
            List<BattleMessage> userPlacements = state.placements.computeIfAbsent(msg.getSender(), k -> new ArrayList<>());
            boolean alreadyExists = userPlacements.stream()
                    .anyMatch(p -> p.getX() == msg.getX() && p.getY() == msg.getY());

            if (!alreadyExists) {
                userPlacements.add(msg);

                // 배치 시 리필 (기존 로직 유지)
                List<String> nextHand = generateRandomHand(msg.getSender());
                BattleMessage refillMsg = new BattleMessage();
                refillMsg.setType("DICE_REFILL");
                refillMsg.setNextHand(nextHand);
                messagingTemplate.convertAndSend("/topic/battle/" + roomId + "/" + msg.getSender(), refillMsg);
            }
            return null;
        }

        // 2. 턴 완료 신호 처리 (COMPLETE)
        if ("COMPLETE".equals(msg.getType())) {
            state.readyUsers.add(msg.getSender());

            if (state.readyUsers.size() >= 2) {
                if (state.turn < 3) {
                    // 다음 턴 진행
                    state.turn++;
                    state.readyUsers.clear();

                    for (String userUid : state.placements.keySet()) {
                        BattleMessage personalMsg = new BattleMessage();
                        personalMsg.setType("TURN_PROGRESS");
                        personalMsg.setTurn(state.turn);
                        personalMsg.setNextHand(generateRandomHand(userUid));
                        messagingTemplate.convertAndSend("/topic/battle/" + roomId + "/" + userUid, personalMsg);
                    }
                    return null;
                } else {
                    // ✅ 3턴 종료: 전투 시뮬레이션 실행 및 결과 전송
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

    // ✅ 전투 결과 처리 (핵심 로직 통합)
    private void processBattleResult(GameState state, String roomId) {
        List<BattleMessage> allPlacements = new ArrayList<>();
        state.placements.values().forEach(allPlacements::addAll);

        // 1. DB에서 모든 주사위 스탯 가져오기
        List<DiceEntity> allDiceInfo = diceRepository.findAll();
        Map<String, DiceEntity> statMap = allDiceInfo.stream()
                .collect(Collectors.toMap(DiceEntity::getDiceType, d -> d));

        // 2. 30초 전투 시뮬레이션 실행
        SimulationResult simResult = simulateCombat(state, statMap);

        // 3. 승패 판정 (살아남은 유닛 수 비교 -> 진 쪽 체력 -1)
        Set<String> userUids = state.placements.keySet();
        Map<String, Integer> damages = new HashMap<>(); // 이번 라운드에 각 유저가 입을 피해량
        String gameOverLoser = "NONE";

        if (userUids.size() >= 2) {
            List<String> users = new ArrayList<>(userUids);
            String p1 = users.get(0);
            String p2 = users.get(1);

            // 초기 체력 5 설정
            state.playerHps.putIfAbsent(p1, 5);
            state.playerHps.putIfAbsent(p2, 5);

            int p1Survivors = simResult.survivorCounts.getOrDefault(p1, 0);
            int p2Survivors = simResult.survivorCounts.getOrDefault(p2, 0);

            // 유닛이 더 적게 남은 쪽이 1 데미지
            if (p1Survivors < p2Survivors) {
                damages.put(p1, 1);
                damages.put(p2, 0);
                state.playerHps.put(p1, state.playerHps.get(p1) - 1);
            } else if (p2Survivors < p1Survivors) {
                damages.put(p1, 0);
                damages.put(p2, 1);
                state.playerHps.put(p2, state.playerHps.get(p2) - 1);
            } else {
                // 무승부 (아무도 데미지 안 입음)
                damages.put(p1, 0);
                damages.put(p2, 0);
            }

            // 4. 게임 종료 체크
            if (state.playerHps.get(p1) <= 0) gameOverLoser = p1;
            else if (state.playerHps.get(p2) <= 0) gameOverLoser = p2;
        }

        // 5. 결과 메시지 개별 전송
        for (String myUid : userUids) {
            // 상대방 UID 찾기
            String opponentUid = userUids.stream().filter(u -> !u.equals(myUid)).findFirst().orElse(null);
            
            BattleMessage msg = new BattleMessage();
            msg.setType("REVEAL");
            msg.setAllPlacements(allPlacements);
            msg.setCombatLogs(simResult.logs); // 전투 로그 포함
            
            // 내가 입을 피해
            msg.setDamageToP1(damages.getOrDefault(myUid, 0));
            // 적이 입을 피해
            msg.setDamageToP2(damages.getOrDefault(opponentUid, 0));

            if (!"NONE".equals(gameOverLoser)) {
                msg.setLoserUid(gameOverLoser); // 게임 종료 알림
            } else {
                // 다음 라운드 진행 시 새 손패 지급
                msg.setNextHand(generateRandomHand(myUid));
            }

            messagingTemplate.convertAndSend("/topic/battle/" + roomId + "/" + myUid, msg);
        }

        // 6. 상태 정리
        if (!"NONE".equals(gameOverLoser)) {
            BattleController.removeRoomData(roomId); // 게임 종료
        } else {
            state.readyUsers.clear();
            state.turn = 1;
            // placements는 유지 (살아남은 유닛을 다음 판에도 쓰려면 유지, 아니면 clear)
            // 현재 로직상 placements를 유지하면 다음 판에 유닛이 누적됨.
            // 매 판 리셋하려면 아래 주석 해제:
            // state.placements.clear(); 
        }
    }

    // ✅ 전투 시뮬레이션 로직
    private SimulationResult simulateCombat(GameState state, Map<String, DiceEntity> statMap) {
        List<CombatLogEntry> logs = new ArrayList<>();
        
        // 시뮬레이션용 임시 유닛 객체 정의
        class SimUnit {
            String uid; int x, y; String type; int hp; long nextAttackTime; DiceEntity stats;
            SimUnit(BattleMessage p) {
                this.uid = p.getSender(); this.x = p.getX(); this.y = p.getY(); this.type = p.getDiceType();
                this.stats = statMap.get(this.type);
                // DB 데이터가 없을 경우 기본값 방어 코드
                if (this.stats == null) {
                    this.stats = new DiceEntity(); 
                    this.stats.setHp(100); this.stats.setDamage(10); 
                    this.stats.setRange(1); this.stats.setAps(1.0);
                }
                this.hp = this.stats.getHp();
                // 첫 공격 딜레이 랜덤 (0~0.5초)
                this.nextAttackTime = (long)(Math.random() * 500); 
            }
        }

        List<SimUnit> units = new ArrayList<>();
        state.placements.values().forEach(list -> list.forEach(p -> units.add(new SimUnit(p))));

        // 30초(30000ms) 동안 0.1초(100ms) 단위로 시뮬레이션
        for (long time = 0; time < 30000; time += 100) {
            for (SimUnit attacker : units) {
                if (attacker.hp <= 0) continue; // 죽은 유닛 제외

                if (time >= attacker.nextAttackTime) {
                    // 사거리 내 적 찾기 (체비쇼프 거리)
                    List<SimUnit> targets = units.stream()
                        .filter(u -> !u.uid.equals(attacker.uid) && u.hp > 0)
                        .filter(u -> getDistance(attacker.x, attacker.y, u.x, u.y) <= attacker.stats.getRange())
                        .collect(Collectors.toList());

                    if (!targets.isEmpty()) {
                        // 기본 타겟: 랜덤
                        SimUnit target = targets.get(new Random().nextInt(targets.size()));
                        int dmg = attacker.stats.getDamage();
                        String attackType = "NORMAL";

                        // --- 특수 능력 적용 ---
                        
                        // 1. SNIPER: 거리가 멀수록 데미지 증가
                        if ("SNIPER".equals(attacker.type)) {
                            int dist = getDistance(attacker.x, attacker.y, target.x, target.y);
                            dmg += (dist * 10);
                            attackType = "SNIPER";
                        }

                        // 2. FIRE: 스플래시 (타겟 주위 1칸)
                        if ("FIRE".equals(attacker.type)) {
                            attackType = "FIRE";
                            target.hp -= dmg;
                            logs.add(new CombatLogEntry(attacker.x, attacker.y, target.x, target.y, dmg, "FIRE", time));
                            
                            // 스플래시 타겟 찾기
                            int finalDmg = dmg;
                            units.stream()
                                .filter(u -> !u.uid.equals(attacker.uid) && u.hp > 0 && u != target)
                                .filter(u -> getDistance(target.x, target.y, u.x, u.y) <= 1)
                                .forEach(splashTarget -> {
                                    splashTarget.hp -= (finalDmg / 2);
                                    logs.add(new CombatLogEntry(target.x, target.y, splashTarget.x, splashTarget.y, (finalDmg/2), "FIRE_SPLASH", time));
                                });
                            // 메인 타겟은 이미 처리했으므로 continue 아님 (쿨타임 적용 위해 아래로 진행)
                        } 
                        // 3. ELECTRIC: 체인 (가장 가까운 적 1명 전이)
                        else if ("ELECTRIC".equals(attacker.type)) {
                            attackType = "ELECTRIC";
                            target.hp -= dmg;
                            logs.add(new CombatLogEntry(attacker.x, attacker.y, target.x, target.y, dmg, "ELECTRIC", time));
                            
                            SimUnit chainTarget = units.stream()
                                .filter(u -> !u.uid.equals(attacker.uid) && u.hp > 0 && u != target)
                                .min(Comparator.comparingInt(u -> getDistance(target.x, target.y, u.x, u.y)))
                                .orElse(null);
                                
                            if(chainTarget != null && getDistance(target.x, target.y, chainTarget.x, chainTarget.y) <= 2) {
                                chainTarget.hp -= dmg;
                                logs.add(new CombatLogEntry(target.x, target.y, chainTarget.x, chainTarget.y, dmg, "ELECTRIC_CHAIN", time));
                            }
                        }
                        // 그 외 일반 공격
                        else {
                            target.hp -= dmg;
                            logs.add(new CombatLogEntry(attacker.x, attacker.y, target.x, target.y, dmg, attackType, time));
                        }

                        // 공격 후 쿨타임 적용 (1000ms / 공속)
                        attacker.nextAttackTime = time + (long)(1000 / attacker.stats.getAps());
                    }
                }
            }
        }

        // 생존자 집계
        Map<String, Integer> survivors = new HashMap<>();
        units.stream().filter(u -> u.hp > 0).forEach(u -> {
            survivors.put(u.uid, survivors.getOrDefault(u.uid, 0) + 1);
        });

        return new SimulationResult(logs, survivors);
    }

    // 거리 계산 유틸리티 (대각선 포함 1칸)
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
