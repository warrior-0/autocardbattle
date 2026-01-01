package com.example.autocardbattle.service;

import com.example.autocardbattle.entity.MapTileEntity;
import com.example.autocardbattle.repository.MapRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class MapService {
    @Autowired private MapRepository mapRepository;

    @Transactional
    public void uploadNewMap(List<MapTileEntity> leftSideTiles) {
        // 1. 맵 식별용 UUID 생성
        String mapId = UUID.randomUUID().toString();
        
        // 2. 중복 체크용 해시(구조 문자열) 생성
        String mapHash = leftSideTiles.stream()
                .sorted(Comparator.comparing(MapTileEntity::getY).thenComparing(MapTileEntity::getX))
                .map(MapTileEntity::getTileType)
                .collect(Collectors.joining(","));

        if (mapRepository.existsByMapHash(mapHash)) {
            throw new RuntimeException("이미 동일한 구조의 맵이 존재합니다.");
        }

        List<MapTileEntity> fullMap = new ArrayList<>();
        for (MapTileEntity tile : leftSideTiles) {
            // 원본 (왼쪽)
            fullMap.add(new MapTileEntity(mapId, mapHash, tile.getX(), tile.getY(), tile.getTileType()));

            // 대칭 복사 (오른쪽)
            int mirrorX = 7 - tile.getX();
            String swapType = tile.getTileType();
            if ("MY_TILE".equals(swapType)) swapType = "ENEMY_TILE";
            else if ("ENEMY_TILE".equals(swapType)) swapType = "MY_TILE";

            fullMap.add(new MapTileEntity(mapId, mapHash, mirrorX, tile.getY(), swapType));
        }
        mapRepository.saveAll(fullMap);
    }

    public List<MapTileEntity> getRandomBattleMap() {
        return mapRepository.findRandomMap();
    }
}
