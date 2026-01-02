// src/main/java/com/example/autocardbattle/dto/BattleMessage.java

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

    private int damageToP1;
    private int damageToP2;
    private int remainingMyHp;    // 나의 남은 체력
    private int remainingEnemyHp; // 적의 남은 체력

    // ✅ [추가] 맵 데이터 (공평한 시작을 위해 여기서 전송)
    private String mapData;

    private List<CombatLogEntry> combatLogs;

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
