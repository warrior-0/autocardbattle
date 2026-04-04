package com.example.autocardbattle.repository;

import com.example.autocardbattle.entity.MapTileEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface MapRepository extends JpaRepository<MapTileEntity, Long> {
    
    // 핵심: mapData 문자열이 이미 존재하는지 확인하는 메서드
    boolean existsByMapData(String mapData);

    @Query(value = "SELECT * FROM maps ORDER BY RAND() LIMIT 1", nativeQuery = true)
    List<MapTileEntity> findRandomMap();
}
