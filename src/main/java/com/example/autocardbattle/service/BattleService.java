package com.example.autocardbattle.service;

import com.example.autocardbattle.dto.BattleMessage;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class BattleService {
    // 세션별 게임 상태 저장 (Key: 방ID 또는 유저ID조합)
    private Map<String, GameState> games = new ConcurrentHashMap<>();

    public static class GameState {
        public int turn = 1;
        public Map<String, Integer> health = new HashMap<>(); // 유저별 체력 (초기값 5)
        public Map<String, List<BattleMessage>> placements = new HashMap<>(); // 배치 정보
        public Set<String> readyUsers = new HashSet<>(); // 이번 턴 배치를 마친 유저
    }

    // 배치를 받았을 때 처리하는 핵심 로직
    public BattleMessage processPlacement(BattleMessage msg) {
        String roomId = "room1"; // 우선 단일 방으로 테스트
        GameState state = games.computeIfAbsent(roomId, k -> new GameState());

        // 1. 배치 정보 저장
        state.placements.computeIfAbsent(msg.getSender(), k -> new ArrayList<>()).add(msg);
        state.readyUsers.add(msg.getSender());

        // 2. 양쪽 유저(2명)가 모두 배치를 마쳤는지 확인
        if (state.readyUsers.size() >= 2) {
            state.readyUsers.clear();
            
            if (state.turn < 3) {
                // 다음 턴으로 진행
                state.turn++;
                msg.setType("TURN_PROGRESS");
                msg.setTurn(state.turn);
            } else {
                // 3턴 종료 -> 전투 및 체력 판정
                msg.setType("REVEAL");
                // [여기서 승패 로직 계산 후 체력 차감]
                // 임시로 한 명의 체력을 깎는 예시
                msg.setResult("PLAYER_B_LOST_HP"); 
                state.turn = 1; // 3턴 주기가 끝나면 다시 1턴으로 (또는 게임 종료)
            }
        } else {
            // 한 명만 보냈으면 대기 상태 알림
            msg.setType("WAIT_OPPONENT");
        }
        return msg;
    }
}
