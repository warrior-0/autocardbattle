package com.example.autocardbattle.service;

import com.example.autocardbattle.dto.BattleMessage;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class BattleService {
    // 게임방 관리를 위한 Map (방ID : 게임상태)
    private Map<String, GameState> games = new ConcurrentHashMap<>();

    public static class GameState {
        public Map<String, List<BattleMessage>> placements = new HashMap<>();
        public Set<String> readyUsers = new HashSet<>();
        public int turn = 1;
    }

    public BattleMessage processBattle(BattleMessage msg) {
        String roomId = "room_1"; // 임시 단일 방ID
        GameState state = games.computeIfAbsent(roomId, k -> new GameState());

        // 1. 유저의 배치 정보 저장
        state.placements.computeIfAbsent(msg.getSender(), k -> new ArrayList<>()).add(msg);
        state.readyUsers.add(msg.getSender());

        // 2. 두 명의 유저가 모두 제출했는지 확인
        if (state.readyUsers.size() >= 2) {
            if (state.turn < 3) {
                // 아직 3턴 전이면 다음 턴으로 진행
                state.turn++;
                state.readyUsers.clear();
                msg.setType("TURN_PROGRESS");
                msg.setTurn(state.turn);
                return msg;
            } else {
                // 3. 3턴이 완료되었으면 승패 판정 실행
                return judgeWinner(state, msg);
            }
        } else {
            // 상대방 대기 모드
            msg.setType("WAIT_OPPONENT");
            return msg;
        }
    }

    public BattleMessage processBattle(BattleMessage msg) {
        String roomId = "room_1";
        GameState state = games.computeIfAbsent(roomId, k -> new GameState());
    
        // 1. 모든 배치는 계속 누적해서 저장 (지우지 않음)
        state.placements.computeIfAbsent(msg.getSender(), k -> new ArrayList<>()).add(msg);
        
        // 이번 턴에 이 유저가 행동을 마쳤음을 표시
        state.readyUsers.add(msg.getSender());
    
        if (state.readyUsers.size() >= 2) {
            if (state.turn < 3) {
                state.turn++;
                state.readyUsers.clear();
                msg.setType("TURN_PROGRESS");
                msg.setTurn(state.turn);
                return msg;
            } else {
                // 3턴이 됐을 때 판정하지만, 데이터는 지우지 않음!
                return judgeWinner(state, msg);
            }
        } else {
            msg.setType("WAIT_OPPONENT");
            return msg;
        }
    }

    private BattleMessage judgeWinner(GameState state, BattleMessage msg) {
        msg.setType("REVEAL");
        
        // 2. 전체 필드의 모든 주사위 정보를 담아서 보냄
        List<BattleMessage> allPlacements = new ArrayList<>();
        state.placements.values().forEach(allPlacements::addAll);
        msg.setAllPlacements(allPlacements);
    
        // 3. 누적된 주사위 총합으로 승패 판정
        int countA = state.placements.get("userA").size();
        int countB = state.placements.get("userB").size();
    
        if (countA > countB) msg.setLoserUid("userB");
        else if (countB > countA) msg.setLoserUid("userA");
        else msg.setLoserUid("NONE");
    
        // [중요] placements.clear()를 하지 않습니다! 
        // 다음 라운드를 위해 준비 상태와 턴수만 리셋합니다.
        state.readyUsers.clear();
        state.turn = 1; 
    
        return msg;
    }
}
