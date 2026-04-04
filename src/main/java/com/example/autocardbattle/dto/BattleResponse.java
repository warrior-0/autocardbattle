package com.example.autocardbattle.dto;

import com.example.autocardbattle.entity.MapTileEntity;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.Data;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BattleResponse {
    private List<MapTileEntity> mapData;
    private List<String> hand;
    private int turn;
}
