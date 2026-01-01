package com.example.autocardbattle.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "dice_master") 
@Getter @Setter
@NoArgsConstructor
public class DiceEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String diceType; 

    private String name;
    private int damage;

    // 'range'는 MySQL 예약어이므로 컬럼 이름을 'dice_range'로 지정합니다.
    @Column(name = "dice_range") 
    private int range;

    private double aps; 
    
    @Column(length = 500)
    private String description;

    private String color; 
}
