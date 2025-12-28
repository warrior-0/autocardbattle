package com.example.autocardbattle.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "inventory")
@Getter @Setter
@NoArgsConstructor // JPA를 위한 기본 생성자
public class InventoryEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String firebaseUid; 
    private String skillName;
    private int quantity = 1;   // 기본값 1
    private int skillLevel = 1; // 기본값 1

    // 편리한 생성을 위한 생성자
    public InventoryEntity(String firebaseUid, String skillName) {
        this.firebaseUid = firebaseUid;
        this.skillName = skillName;
        this.quantity = 1;
        this.skillLevel = 1;
    }
}
