package com.example.autocardbattle.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "dice_master") // 모든 유저가 공통으로 사용하는 주사위 정보
@Getter @Setter
@NoArgsConstructor
public class DiceEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String diceType; // FIRE, WIND, SWORD, ELECTRIC, SNIPER

    private String name;
    private int damage;
    private int range;
    private double aps; // 초당 공격 횟수 (Attacks Per Second)
    
    @Column(length = 500)
    private String description;

    private String color; // UI 표시용 색상 코드
}
