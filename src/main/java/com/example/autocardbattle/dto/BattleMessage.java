package com.example.autocardbattle.dto;

import lombok.Data;

@Data
public class BattleMessage {
    private String type;    // "PLACE" (배치), "REVEAL" (공개), "CHAT" (채팅)
    private String sender;  // 보낸 사람 (UID)
    private int x;
    private int y;
    private String diceType;
    private int turn;
}
