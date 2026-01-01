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

    private String firebaseUid; // 맵을 소유한 유저 ID

    private int x; // 0~7 (가로)
    private int y; // 0~7 (세로)

    private String tileType; // "WALL"(벽), "BUFF"(강화), "EMPTY"(빈칸) 등

    // 생성자: 좌표와 타입을 쉽게 설정하기 위함
    public MapTileEntity(String uid, int x, int y, String type) {
        this.firebaseUid = uid;
        this.x = x;
        this.y = y;
        this.tileType = type;
    }
}
