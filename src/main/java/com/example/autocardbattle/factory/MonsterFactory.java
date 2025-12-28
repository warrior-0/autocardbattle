package com.example.autocardbattle.factory;
import com.example.autocardbattle.model.Monster;

public class MonsterFactory {
    public static Monster createMonster(String type) {
        if ("INFINITE".equals(type)) {
            return new Monster("무한의 허수아비", 999999, 0, 0, 0);
        }
        return new Monster("허수아비", 50, 0, 5, 10);
    }
}
