package com.example.autocardbattle.service;

import com.example.autocardbattle.entity.MapTileEntity;
import com.example.autocardbattle.repository.MapRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MapService {
    @Autowired 
    private MapRepository mapRepository;

    @Transactional
    public void saveMap(String creatorUid, String mapData) {
        // 1. 중복 체크: 이미 같은 맵 데이터가 있는지 확인
        if (mapRepository.existsByMapData(mapData)) {
            throw new RuntimeException("이미 동일한 구조의 전장이 존재합니다!");
        }

        // 2. 중복이 없으면 저장
        MapTileEntity map = new MapTileEntity();
        map.setCreatorUid(creatorUid);
        map.setMapData(mapData);
        mapRepository.save(map);
    }
}
