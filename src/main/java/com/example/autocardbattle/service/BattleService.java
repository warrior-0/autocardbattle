package com.example.autocardbattle.service;

import com.example.autocardbattle.dto.BattleMessage;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class BattleService {
    // 방 ID별로 게임 상태를 관리합니다.
    private Map<String, GameState> games = new ConcurrentHashMap<>();

    public static class GameState {
        public Map<String, List<BattleMessage>> placements = new HashMap<>();
        public Set<String> readyUsers = new HashSet<>();
        public int turn = 1;
    }

    // 호출 시 roomId를 함께 전달받도록 설계합니다.
    public BattleMessage processBattle(String roomId, BattleMessage msg) {
        GameState state = games.computeIfAbsent(roomId, k -> new GameState());

        // 유저의 실제 UID를 키로 사용하여 배치를 누적합니다.
        state.placements.computeIfAbsent(msg.getSender(), k -> new ArrayList<>()).add(msg);
        state.readyUsers.add(msg.getSender());

        // 방에 참여한 인원(2명)이 모두 준비되었는지 확인합니다.
        if (state.readyUsers.size() >= 2) {
            if (state.turn < 3) {
                state.turn++;
                state.readyUsers.clear();
                msg.setType("TURN_PROGRESS");
                msg.setTurn(state.turn);
                return msg;
            } else {
                return judgeWinner(state, msg);
            }
        } else {
            msg.setType("WAIT_OPPONENT");
            return msg;
        }
    }

    private BattleMessage judgeWinner(GameState state, BattleMessage msg) {
        msg.setType("REVEAL");
        
        List<BattleMessage> allPlacements = new ArrayList<>();
        state.placements.values().forEach(allPlacements::addAll);
        msg.setAllPlacements(allPlacements);

        // 하드코딩 대신 현재 placements에 저장된 유저 UID들을 동적으로 가져옵니다.
        List<String> userIds = new ArrayList<>(state.placements.keySet());
        if (userIds.size() < 2) {
            msg.setLoserUid("NONE");
            return msg;
        }

        String user1 = userIds.get(0);
        String user2 = userIds.get(1);

        int count1 = state.placements.get(user1).size();
        int count2 = state.placements.get(user2).size();

        // 살아남은 주사위 수를 비교하여 패배자를 결정합니다.
        if (count1 > count2) msg.setLoserUid(user2);
        else if (count2 > count1) msg.setLoserUid(user1);
        else msg.setLoserUid("NONE");

        state.readyUsers.clear();
        state.turn = 1; 

        return msg;
    }
}
