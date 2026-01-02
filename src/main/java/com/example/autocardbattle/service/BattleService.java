package com.example.autocardbattle.service;

import com.example.autocardbattle.dto.BattleMessage;
import com.example.autocardbattle.controller.BattleController;
import com.example.autocardbattle.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class BattleService {

    @Autowired
    private UserRepository userRepository;

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

                    BattleMessage progressMsg = new BattleMessage();
                    progressMsg.setType("TURN_PROGRESS");
                    progressMsg.setTurn(state.turn);
                    
                    // 각 유저의 덱 정보를 바탕으로 다음 손패를 생성하는 로직은 
                    // 컨트롤러나 별도 헬퍼 메서드에서 덱을 참조하여 넣어주어야 합니다.
                    return progressMsg;
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

    private BattleMessage judgeWinner(GameState state, BattleMessage msg, String roomId) {
        BattleMessage response = new BattleMessage();
        response.setType("REVEAL");
        
        // 모든 유저의 누적 배치 정보를 하나로 모음 (이때 비로소 상대에게 공개됨)
        List<BattleMessage> allPlacements = new ArrayList<>();
        state.placements.values().forEach(allPlacements::addAll);
        response.setAllPlacements(allPlacements);

        // 승패 판정 로직
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
        
        // 게임이 완전히 끝난 것이 아니므로 removeRoomData는 호출하지 않거나 
        // 최종 승패(HP 0) 시점에 컨트롤러에서 호출하도록 합니다.
        
        return response;
    }
}
