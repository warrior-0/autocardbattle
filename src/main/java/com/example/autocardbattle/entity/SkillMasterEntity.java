package com.example.autocardbattle.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "skill_master")
@Getter @Setter
public class SkillMasterEntity {
    @Id
    private String skillName;
    private int cooldown;
    private String effectType;
    private int effectValue;
}
