package com.example.autocardbattle.model;
import lombok.AllArgsConstructor;
import lombok.Getter;
import java.util.List;

@Getter @AllArgsConstructor
public class BattleResult {
    private String result;
    private List<String> logs;
}
