package com.example.autocardbattle.repository;

import com.example.autocardbattle.entity.MapTileEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface MapRepository extends JpaRepository<MapTileEntity, Long> {
    // 특정 유저의 전체 맵 데이터를 가져옴
    List<MapTileEntity> findByFirebaseUid(String firebaseUid);

    // 새 설계를 저장하기 전 기존 맵 데이터를 지우기 위함
    void deleteByFirebaseUid(String firebaseUid);
}
