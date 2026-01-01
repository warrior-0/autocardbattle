package com.example.autocardbattle.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "map_tiles")
@Getter @Setter
@NoArgsConstructor
public class MapTileEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String mapId;    // 유저 ID 대신 맵 세트(64칸)를 구분하는 고유 ID
    
    @Column(columnDefinition = "TEXT")
    private String mapHash;  // 맵의 중복 여부를 가리는 배치 데이터

    private int x; 
    private int y; 
    private String tileType; 

    // 수정된 생성자
    public MapTileEntity(String mapId, String mapHash, int x, int y, String type) {
        this.mapId = mapId;
        this.mapHash = mapHash;
        this.x = x;
        this.y = y;
        this.tileType = type;
    }
}
