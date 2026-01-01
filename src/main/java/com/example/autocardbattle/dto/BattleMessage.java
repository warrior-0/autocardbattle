package com.example.autocardbattle.dto;

import lombok.Data;
import java.util.List; // List를 사용하기 위해 임포트 추가

@Data
public class BattleMessage {
    private String type;    // "PLACE", "REVEAL", "TURN_PROGRESS", "WAIT_OPPONENT"
    private String sender;  // 보낸 사람 (UID)
    private int x;
    private int y;
    private String diceType;
    private int turn;

    // ✅ 추가된 필드: 승패 판정 결과와 전체 배치 정보를 담기 위함
    private String loserUid; 
    private List<BattleMessage> allPlacements; 
}
