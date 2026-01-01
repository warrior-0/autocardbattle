package com.example.autocardbattle.service;

import com.example.autocardbattle.entity.MapTileEntity;
import com.example.autocardbattle.repository.MapRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;
import java.util.List;

@Service
public class MapService {
    @Autowired private MapRepository mapRepository;

    @Transactional
    public void saveMirrorSwapMap(String uid, List<MapTileEntity> leftSideTiles) {
        // 1. 기존 유저 맵 삭제
        mapRepository.deleteByFirebaseUid(uid);

        List<MapTileEntity> fullMap = new ArrayList<>();

        for (MapTileEntity tile : leftSideTiles) {
            // [왼쪽 4x8] 원본 데이터 저장
            fullMap.add(new MapTileEntity(uid, tile.getX(), tile.getY(), tile.getTileType()));

            // [오른쪽 4x8] 좌우 반전(7-x) 및 타일 치환(Swap)
            int mirrorX = 7 - tile.getX();
            String swapType = tile.getTileType();

            // 기획대로 내 발판은 상대의 적 발판으로, 내 적 발판은 상대의 내 발판으로 변경
            if ("MY_TILE".equals(swapType)) {
                swapType = "ENEMY_TILE";
            } else if ("ENEMY_TILE".equals(swapType)) {
                swapType = "MY_TILE";
            }
            // WALL 등은 그대로 복사

            fullMap.add(new MapTileEntity(uid, mirrorX, tile.getY(), swapType));
        }
        mapRepository.saveAll(fullMap);
    }
}
