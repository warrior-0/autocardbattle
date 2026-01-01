package com.example.autocardbattle.repository;

import com.example.autocardbattle.entity.MapTileEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface MapRepository extends JpaRepository<MapTileEntity, Long> {
    // 맵 데이터 전체를 랜덤하게 하나 가져오기
    @Query(value = "SELECT * FROM maps ORDER BY RAND() LIMIT 1", nativeQuery = true)
    List<MapTileEntity> findRandomMap();
}
