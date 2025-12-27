package com.example.autocardbattle.model;

public class Skill {
    private String name;
    private float cooldown; // float 가능
    private int chainOrder;

    private float nextReadyTime;
    private float readySinceTime; // READY 상태가 된 시점

    public Skill(String name, float cooldown, int chainOrder) {
        this.name = name;
        this.cooldown = cooldown;
        this.chainOrder = chainOrder;
        this.nextReadyTime = cooldown;
        this.readySinceTime = -1;
    }

    public boolean isReady(float currentTime) {
        if (currentTime >= nextReadyTime) {
            if (readySinceTime == -1) {
                readySinceTime = currentTime;
            }
            return true;
        }
        return false;
    }

    public void use(float currentTime) {
        nextReadyTime = currentTime + cooldown;
        readySinceTime = -1;
    }

    // Getter & Setter
    public String getName() { return name; }
    public float getReadySinceTime() { return readySinceTime; }
    public int getChainOrder() { return chainOrder; }
}
