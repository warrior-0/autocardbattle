package com.example.autocardbattle.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Entity
@Table(name = "maps")
@Getter @Setter
public class MapTileEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String creatorUid;

    @Column(columnDefinition = "TEXT", nullable = false, unique = true)
    private String mapData; // "MY_TILE,MY_TILE,WALL..." 가독성 좋은 데이터

    private LocalDateTime createdAt = LocalDateTime.now();
}
