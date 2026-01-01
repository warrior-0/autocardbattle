package com.example.autocardbattle.entity;

import com.example.autocardbattle.constant.DiceType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "dice")
@Getter @Setter
@NoArgsConstructor
public class DiceEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    private DiceType type; // FIRE, WIND 등 (미리 만든 상수 연결)

    private String name;
    private int damage;
    private int range;
    private double aps;
    
    @Column(name = "description")
    private String desc;

    // 초기 데이터 생성을 위한 생성자
    public DiceEntity(DiceType type) {
        this.type = type;
        this.name = type.getName();
        this.damage = type.getDamage();
        this.range = type.getRange();
        this.aps = type.getAps();
        this.desc = type.getDesc();
    }
}
