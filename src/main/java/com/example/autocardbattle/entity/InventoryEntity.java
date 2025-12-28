package com.example.autocardbattle.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "inventory")
@Getter @Setter
public class InventoryEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String firebaseUid; // 유저 연결용
    private String skillName;
    private int quantity;       // 보유 개수
}
