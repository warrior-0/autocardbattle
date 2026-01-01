package com.example.autocardbattle.dto;

import com.example.autocardbattle.entity.MapTileEntity;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;

@NoArgsConstructor
@Data
@AllArgsConstructor
public class BattleResponse {
    private List<MapTileEntity> mapData; // 랜덤하게 선택된 맵 정보
    private List<String> hand;           // 이번 턴에 배치할 주사위 2개 (타입명)
    private int turn;                    // 현재 턴 (1~3)
}
