package com.example.autocardbattle.controller;

import com.example.autocardbattle.entity.DiceEntity;
import com.example.autocardbattle.repository.DiceRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/dice")
@CrossOrigin(origins = "https://warrior-0.github.io")
public class DiceController {

    @Autowired
    private DiceRepository diceRepository;

    // 1. 모든 주사위 목록 가져오기
    @GetMapping("/list")
    public List<DiceEntity> getAllDice() {
        return diceRepository.findAll();
    }
}
