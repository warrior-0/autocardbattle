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

                // ✅ [핵심] 배치할 때마다 유저에게 새로운 주사위 2개를 실시간으로 리필 전송
                List<String> nextHand = generateRandomHand(msg.getSender());
                
                BattleMessage refillMsg = new BattleMessage();
                refillMsg.setType("DICE_REFILL");
                refillMsg.setNextHand(nextHand);
                
                // 해당 유저의 개인 채널로만 전송
                messagingTemplate.convertAndSend("/topic/battle/" + roomId + "/" + msg.getSender(), refillMsg);
            }
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

                    // 각 유저별로 다음 턴의 첫 손패 2개를 개별 채널로 전송
                    for (String userUid : state.placements.keySet()) {
                        List<String> nextHand = generateRandomHand(userUid);
                        
                        BattleMessage personalMsg = new BattleMessage();
                        personalMsg.setType("TURN_PROGRESS");
                        personalMsg.setTurn(state.turn);
                        personalMsg.setNextHand(nextHand);
                        
                        messagingTemplate.convertAndSend("/topic/battle/" + roomId + "/" + userUid, personalMsg);
                    }
                    return null; 
                } else {
                    // 3턴 종료: 전체 공개 및 결과 판정
                    return judgeWinner(state, msg, roomId);
                }
            } else {
                BattleMessage waitMsg = new BattleMessage();
                waitMsg.setType("WAIT_OPPONENT");
                return waitMsg;
            }
        }
        return null;
    }

    /**
     * ✅ [추가] 입장 싱크 맞추기: 두 유저가 모두 READY일 때 게임을 동시에 시작시킵니다.
     */
    public void initiateGameStart(String roomId) {
        Set<String> readyUsers = BattleController.roomReadyStatus.get(roomId);
        if (readyUsers == null || readyUsers.size() < 2) return;
        
        List<String> users = new ArrayList<>(readyUsers);

        for (int i = 0; i < users.size(); i++) {
            String uid = users.get(i);
            // 시작 시 첫 주사위 2개를 뽑음
            List<String> firstHand = generateRandomHand(uid);
            
            BattleMessage startMsg = new BattleMessage();
            startMsg.setType("GAME_START");
            startMsg.setTurn(1);
            startMsg.setNextHand(firstHand);
            // 유저에게 진영 정보(0: 왼쪽, 1: 오른쪽)를 할당하여 전송
            startMsg.setSender(String.valueOf(i)); 

            messagingTemplate.convertAndSend("/topic/battle/" + roomId + "/" + uid, startMsg);
        }
    }

    // 유저의 덱에서 랜덤하게 주사위 2개를 뽑는 헬퍼 메서드 (재사용)
    private List<String> generateRandomHand(String userUid) {
            return userRepository.findById(userUid).map(user -> {
                String deckStr = user.getSelectedDeck();
                
                // 만약의 상황을 대비한 방어 코드 (빈 리스트 반환)
                if (deckStr == null || deckStr.isEmpty()) {
                    return new ArrayList<String>();
                }
                
                List<String> fullDeck = new ArrayList<>(Arrays.asList(deckStr.split(",")));
                Collections.shuffle(fullDeck);
                
                // 덱에서 최대 2개 추출
                return fullDeck.subList(0, Math.min(2, fullDeck.size()));
            }).orElseGet(ArrayList::new); // 유저가 없을 경우 빈 리스트 반환
    }

    private BattleMessage judgeWinner(GameState state, BattleMessage msg, String roomId) {
        BattleMessage response = new BattleMessage();
        response.setType("REVEAL");
        
        List<BattleMessage> allPlacements = new ArrayList<>();
        state.placements.values().forEach(allPlacements::addAll);
        response.setAllPlacements(allPlacements);

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

        state.readyUsers.clear();
        state.turn = 1; 
        
        return response;
    }
}
