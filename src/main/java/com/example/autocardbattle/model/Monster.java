package com.example.autocardbattle.model;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter @AllArgsConstructor
public class Monster {
    private String name;
    private int hp;
    private int damage;
    private int goldReward;
    private int expReward;
}
