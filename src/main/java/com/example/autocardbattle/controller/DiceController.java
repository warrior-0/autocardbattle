package com.example.autocardbattle.controller;

import com.example.autocardbattle.entity.DiceEntity;
import com.example.autocardbattle.repository.DiceRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    // Keep-alive 전용 경량 엔드포인트: limit 1 조회로 DB ping
    @GetMapping("/ping")
    public ResponseEntity<Map<String, Object>> pingDice() {
        Long firstDiceId = diceRepository.findTopByOrderByIdAsc()
                .map(DiceEntity::getId)
                .orElse(null);

        Map<String, Object> payload = new HashMap<>();
        payload.put("ok", true);
        payload.put("firstDiceId", firstDiceId);
        return ResponseEntity.ok(payload);
    }
}
