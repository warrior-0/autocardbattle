package com.example.autocardbattle.dto;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
public class BattleMessage {
    private String type;
    private String sender;
    private int x;
    private int y;
    private String diceType;
    private int turn;

    private String loserUid; 
    private List<BattleMessage> allPlacements;
    private List<String> nextHand;

    // 전투 데미지 필드
    private int damageToP1;
    private int damageToP2;

    // 전투 로그 리스트
    private List<CombatLogEntry> combatLogs;

    // 전투 로그 엔트리 클래스 정의
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CombatLogEntry {
        private int attackerX;
        private int attackerY;
        private int targetX;
        private int targetY;
        private int damage;
        private String attackType;
        private long timeDelay;
    }
}
