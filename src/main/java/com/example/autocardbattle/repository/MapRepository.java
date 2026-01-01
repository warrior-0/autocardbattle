package com.example.autocardbattle.repository;

import com.example.autocardbattle.entity.MapTileEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface MapRepository extends JpaRepository<MapTileEntity, Long> {
    // 특정 해시값을 가진 맵이 이미 존재하는지 확인
    boolean existsByMapHash(String mapHash);

    @Query(value = "SELECT * FROM map_tiles WHERE map_id = (SELECT map_id FROM map_tiles GROUP BY map_id ORDER BY RAND() LIMIT 1)", nativeQuery = true)
    List<MapTileEntity> findRandomMap();
}
