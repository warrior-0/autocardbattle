package com.example.autocardbattle.controller;

import com.example.autocardbattle.dto.*;
import com.example.autocardbattle.entity.*;
import com.example.autocardbattle.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/battle")
@CrossOrigin(origins = "https://warrior-0.github.io")
public class BattleController {
    
    @Autowired private MapRepository mapRepository;
    @Autowired private DiceRepository diceRepository;

    // 1. 전투 시작: 랜덤 맵과 무작위 주사위 2개 지급
    @PostMapping("/start")
    public BattleResponse startBattle(@RequestParam String userUid, @RequestBody List<String> userDeck) {
        // DB에서 랜덤 맵 하나 선택
        List<MapTileEntity> randomMap = mapRepository.findRandomMap();
        
        // 덱 5개 중 랜덤 2개 셔플
        List<String> mutableDeck = new ArrayList<>(userDeck);
        Collections.shuffle(mutableDeck);
        List<String> hand = mutableDeck.subList(0, 2);

        return new BattleResponse(randomMap, hand, 1);
    }

    // 2. 배치 정보 수신 및 다음 턴 주사위 지급
    @PostMapping("/place")
    public Map<String, Object> placeDice(@RequestBody PlacementRequest request) {
        Map<String, Object> response = new HashMap<>();
        
        // TODO: 서버 메모리나 DB에 유저별 배치 정보를 임시 저장하는 로직 필요
        
        if (request.getTurn() < 3) {
            // 다음 턴을 위한 새로운 랜덤 주사위 2개
            List<String> mutableDeck = new ArrayList<>(request.getUserDeck());
            Collections.shuffle(mutableDeck);
            
            response.put("hand", mutableDeck.subList(0, 2));
            response.put("turn", request.getTurn() + 1);
            response.put("status", "CONTINUE");
        } else {
            // 3턴 종료 시 공개 준비
            response.put("status", "REVEAL");
        }
        return response;
    }
}
