package com.example.autocardbattle.model;
import lombok.Getter;

// DB에 저장되지 않는 전투 로직용 객체
@Getter
public class Skill {
    private String name;
    private int cooldown;
    private int order;
    private String type;
    private int value;

    private int nextReadyTime = 0;
    private int readySince = 0;

    public Skill(String name, int cooldown, int order, String type, int value) {
        this.name = name; this.cooldown = cooldown; 
        this.order = order; this.type = type; this.value = value;
    }

    public boolean isReady(int currentTime) {
        if (currentTime >= nextReadyTime && readySince == 0) {
            readySince = currentTime;
        }
        return currentTime >= nextReadyTime;
    }

    public void use(int currentTime) {
        this.nextReadyTime = currentTime + cooldown;
        this.readySince = 0;
    }
}
