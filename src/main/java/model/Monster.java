package com.example.autocardbattle.model;

public class Monster {
    private String name;
    private int hp;
    private int attack;

    public Monster(String name, int hp, int attack) {
        this.name = name;
        this.hp = hp;
        this.attack = attack;
    }

    public boolean isDead() {
        return hp <= 0;
    }

    public void takeDamage(int dmg) {
        hp -= dmg;
        if (hp < 0) hp = 0;
    }

    // Getter
    public String getName() { return name; }
    public int getHp() { return hp; }
    public int getAttack() { return attack; }
}
