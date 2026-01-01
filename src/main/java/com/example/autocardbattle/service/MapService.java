package com.example.autocardbattle.service;

import com.example.autocardbattle.entity.MapTileEntity;
import com.example.autocardbattle.repository.MapRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Optional;

@Service
public class MapService {
    @Autowired 
    private MapRepository mapRepository;

    // 맵 저장 로직
    public void saveMap(String creatorUid, String mapData) {
        MapTileEntity map = new MapTileEntity();
        map.setCreatorUid(creatorUid);
        map.setMapData(mapData);
        mapRepository.save(map);
    }

    // 전투를 위한 랜덤 맵 선택 로직
    public MapTileEntity getRandomBattleMap() {
        List<MapTileEntity> maps = mapRepository.findRandomMap();
        if (maps.isEmpty()) {
            return null;
        }
        // 쿼리에서 이미 랜덤하게 1개를 가져오도록 설정되어 있습니다
        return maps.get(0);
    }
}
