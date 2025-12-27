package com.example.autocardbattle.model;

import java.util.List;

public class Monster {
    private String name;
    private int maxHp;
    private int hp;
    private List<Skill> skillChain;

    public Monster(String name, int maxHp, List<Skill> skillChain) {
        this.name = name;
        this.maxHp = maxHp;
        this.hp = maxHp;
        this.skillChain = skillChain;
    }

    public void takeDamage(int dmg) {
        hp -= dmg;
        if (hp < 0) hp = 0;
    }

    public boolean isAlive() {
        return hp > 0;
    }

    // Getter
    public String getName() { return name; }
    public int getHp() { return hp; }
    public List<Skill> getSkillChain() { return skillChain; }
}
