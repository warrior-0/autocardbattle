package com.example.autocardbattle.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "users")
@Getter @Setter
public class UserEntity {
    @Id
    private String firebaseUid;

    @Column(unique = true)
    private String username;

    private int level = 1;
    private int exp = 0;
    private int maxHp = 100;
    private int gold = 0;

    // 경험치 추가 및 레벨업 로직
    public void addExp(int amount) {
        this.exp += amount;
        int requiredExp = (int) (100 * Math.pow(1.2, this.level - 1));
        
        while (this.exp >= requiredExp) {
            this.exp -= requiredExp;
            this.level++;
            this.maxHp += 20; // 레벨업 시 최대 체력 증가
            requiredExp = (int) (100 * Math.pow(1.2, this.level - 1));
        }
    }
}
