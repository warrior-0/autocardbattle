package com.example.autocardbattle.model;

public class Skill {
    private String name;
    private int cooldown;
    private int chainOrder;

    private int nextReadyTime;
    private int readySinceTime; // READY 상태가 된 시점

    public Skill(String name, int cooldown, int chainOrder) {
        this.name = name;
        this.cooldown = cooldown;
        this.chainOrder = chainOrder;
        this.nextReadyTime = cooldown;
        this.readySinceTime = -1;
    }

    public boolean isReady(int currentTime) {
        if (currentTime >= nextReadyTime) {
            if (readySinceTime == -1) {
                readySinceTime = currentTime;
            }
            return true;
        }
        return false;
    }

    public void use(int currentTime) {
        nextReadyTime = currentTime + cooldown;
        readySinceTime = -1;
    }

    // Getter & Setter
    public String getName() { return name; }
    public int getReadySinceTime() { return readySinceTime; }
    public int getChainOrder() { return chainOrder; }
}
