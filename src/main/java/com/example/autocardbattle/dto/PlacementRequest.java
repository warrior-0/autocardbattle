package com.example.autocardbattle.dto;

import java.util.List;
import lombok.Data;

@Data
public class PlacementRequest {
    private String userUid;
    private String diceType;
    private int x;
    private int y;
    private int turn;
    private List<String> userDeck; // 유저의 전체 덱 (5개)
}
