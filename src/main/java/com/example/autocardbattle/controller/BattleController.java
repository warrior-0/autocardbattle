package com.example.autocardbattle.controller;
import com.example.autocardbattle.model.BattleResult;
import com.example.autocardbattle.service.BattleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/battle")
public class BattleController {
    @Autowired private BattleService battleService;

    @GetMapping("/start")
    public BattleResult start(@RequestParam String uid, @RequestParam String monsterType) {
        return battleService.startBattle(uid, monsterType);
    }
}
