package com.example.autocardbattle.controller;

import com.example.autocardbattle.service.BattleService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BattleController {

    private final BattleService battleService;

    public BattleController(BattleService battleService) {
        this.battleService = battleService;
    }

    @GetMapping("/battle")
    public String battle(@RequestParam String userId, @RequestParam(defaultValue = "30") int duration) {
        return battleService.simulateBattle(userId, duration);
    }
}
