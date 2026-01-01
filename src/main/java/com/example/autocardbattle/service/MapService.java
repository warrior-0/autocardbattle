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
    public void saveSymmetricMap(String uid, List<MapTileEntity> halfTiles) {
        // 1. 규칙 검사: 내 진영(4x8 중 타일이 배치된 칸)이 16칸을 넘는지 확인
        long placementCount = halfTiles.stream()
                .filter(t -> !t.getTileType().equals("EMPTY"))
                .count();

        if (placementCount > 16) {
            throw new RuntimeException("배치 가능한 타일 수는 최대 16칸입니다.");
        }

        // 2. 기존 맵 삭제 (새로운 설계를 적용하기 위해 초기화)
        mapRepository.deleteByFirebaseUid(uid);

        List<MapTileEntity> fullMap = new ArrayList<>();

        for (MapTileEntity tile : halfTiles) {
            // [왼쪽 영역] 유저가 직접 설계한 4x8 칸 (x: 0~3)
            fullMap.add(new MapTileEntity(uid, tile.getX(), tile.getY(), tile.getTileType()));

            // [오른쪽 영역] 자동 대칭 복사 (x: 7-x 로 거울 반사)
            // 예: 0번 칸에 벽을 놓으면 7번 칸에도 벽 생성 / 3번 칸에 놓으면 4번 칸에 생성
            int symmetricX = 7 - tile.getX();
            fullMap.add(new MapTileEntity(uid, symmetricX, tile.getY(), tile.getTileType()));
        }

        // 3. 전체 8x8 데이터(총 64칸 정보) 저장
        mapRepository.saveAll(fullMap);
    }

    public List<MapTileEntity> getMap(String uid) {
        return mapRepository.findByFirebaseUid(uid);
    }
}
