package com.example.autocardbattle.service;

import com.example.autocardbattle.dto.BattleMessage;
import com.example.autocardbattle.controller.BattleController;
import com.example.autocardbattle.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class BattleService {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SimpMessageSendingOperations messagingTemplate; // 개별 유저 전송을 위해 필요

    // 방 ID별로 게임 상태를 관리합니다.
    private Map<String, GameState> games = new ConcurrentHashMap<>();

    public static class GameState {
        // 누적된 모든 배치 정보 (전투 종료 후에도 유지됨)
        public Map<String, List<BattleMessage>> placements = new HashMap<>();
        // 현재 턴에 완료 신호를 보낸 유저들
        public Set<String> readyUsers = new HashSet<>();
        public int turn = 1;
    }

    public BattleMessage processBattle(String roomId, BattleMessage msg) {
        GameState state = games.computeIfAbsent(roomId, k -> new GameState());

        // 1. 유저의 개별 배치 처리 (PLACE)
        if ("PLACE".equals(msg.getType())) {
            // 해당 위치에 이미 배치가 있는지 서버에서도 검증 (보안)
            List<BattleMessage> userPlacements = state.placements.computeIfAbsent(msg.getSender(), k -> new ArrayList<>());
            boolean alreadyExists = userPlacements.stream()
                    .anyMatch(p -> p.getX() == msg.getX() && p.getY() == msg.getY());

            if (!alreadyExists) {
                userPlacements.add(msg);
            }
            // PLACE 메시지는 상대에게 전달하지 않기 위해 null 반환 (컨트롤러에서 처리)
            return null;
        }

        // 2. 턴 완료 혹은 시간 초과 신호 처리 (COMPLETE)
        if ("COMPLETE".equals(msg.getType())) {
            state.readyUsers.add(msg.getSender());

            // 양쪽 유저가 모두 완료 신호를 보냈을 때
            if (state.readyUsers.size() >= 2) {
                if (state.turn < 3) {
                    // 다음 턴으로 진행
                    state.turn++;
                    state.readyUsers.clear();

                    // ✅ 핵심: 각 유저별로 본인의 덱에서 랜덤 주사위 2개를 뽑아 개별 채널로 전송
                    for (String userUid : state.placements.keySet()) {
                        List<String> nextHand = generateRandomHand(userUid);
                        
                        BattleMessage personalMsg = new BattleMessage();
                        personalMsg.setType("TURN_PROGRESS");
                        personalMsg.setTurn(state.turn);
                        personalMsg.setNextHand(nextHand); // BattleMessage DTO에 nextHand 필드 필요
                        
                        // /topic/battle/{roomId}/{userUid} 경로로 개별 전송
                        messagingTemplate.convertAndSend("/topic/battle/" + roomId + "/" + userUid, personalMsg);
                    }
                    
                    // 공통 브로드캐스팅 채널로는 턴 숫자만 전달하거나 null을 반환하여 중복 전송 방지
                    return null; 
                } else {
                    // 3턴 종료: 전체 공개 및 결과 판정
                    return judgeWinner(state, msg, roomId);
                }
            } else {
                // 한 명만 완료했을 경우 대기 메시지 전송
                BattleMessage waitMsg = new BattleMessage();
                waitMsg.setType("WAIT_OPPONENT");
                return waitMsg;
            }
        }

        return null;
    }

    // 유저의 덱에서 랜덤하게 주사위 2개를 뽑는 헬퍼 메서드
    private List<String> generateRandomHand(String userUid) {
        return userRepository.findById(userUid).map(user -> {
            String deckStr = user.getSelectedDeck(); // UserEntity의 덱 정보
            if (deckStr == null || deckStr.isEmpty()) {
                return Arrays.asList("FIRE", "WIND"); // 덱이 없을 경우 기본값
            }
            
            List<String> fullDeck = new ArrayList<>(Arrays.asList(deckStr.split(",")));
            Collections.shuffle(fullDeck);
            // 덱에서 최대 2개 추출
            return fullDeck.subList(0, Math.min(2, fullDeck.size()));
        }).orElse(Arrays.asList("FIRE", "WIND"));
    }

    private BattleMessage judgeWinner(GameState state, BattleMessage msg, String roomId) {
        BattleMessage response = new BattleMessage();
        response.setType("REVEAL");
        
        // 모든 유저의 누적 배치 정보를 하나로 모음 (이때 비로소 상대에게 공개됨)
        List<BattleMessage> allPlacements = new ArrayList<>();
        state.placements.values().forEach(allPlacements::addAll);
        response.setAllPlacements(allPlacements);

        // 승패 판정 로직 (주사위 개수 비교)
        List<String> userIds = new ArrayList<>(state.placements.keySet());
        if (userIds.size() < 2) {
            response.setLoserUid("NONE");
        } else {
            String user1 = userIds.get(0);
            String user2 = userIds.get(1);

            int count1 = state.placements.get(user1).size();
            int count2 = state.placements.get(user2).size();

            if (count1 > count2) response.setLoserUid(user2);
            else if (count2 > count1) response.setLoserUid(user1);
            else response.setLoserUid("NONE");
        }

        // 다음 라운드를 위해 준비 상태만 초기화 (placements는 유지하여 주사위가 남게 함)
        state.readyUsers.clear();
        state.turn = 1; 
        
        return response;
    }
}
