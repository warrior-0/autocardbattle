package com.example.autocardbattle.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "skill_chain")
@Getter @Setter
public class SkillChainEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String firebaseUid;
    private String skillName;
    
    private int cooldown;
    private String effectType; // DAMAGE, HEAL, BUFF
    private int effectValue;
    
    private int chainOrder;    // 0, 1, 2... 순서
}
