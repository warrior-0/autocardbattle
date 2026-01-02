package com.example.autocardbattle.controller;

import com.example.autocardbattle.dto.BattleMessage;
import com.example.autocardbattle.service.BattleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.stereotype.Controller;

@Controller
public class BattleMessageController {

    @Autowired
    private BattleService battleService;

    @Autowired
    private SimpMessageSendingOperations messagingTemplate;

    @MessageMapping("/battle/{roomId}/place")
    public void handlePlacement(@DestinationVariable String roomId, BattleMessage message) {
        // 1. PLACE 타입일 때: 한 명이 주사위를 놓으면 상대방도 알아야 하므로 방 전체에 브로드캐스팅합니다.
        if ("PLACE".equals(message.getType())) {
            messagingTemplate.convertAndSend("/topic/battle/" + roomId, message);
        }

        // 2. 비즈니스 로직 처리 (배치 저장, COMPLETE 확인, 턴 전환 등)
        // battleService.processBattle 안에서 개별 유저 전송(nextHand 등)까지 처리하게 하는 것이 가장 깔끔합니다.
        battleService.processBattle(roomId, message);
    }
    
    @MessageMapping("/battle/{roomId}/ready")
    public void handleReady(@DestinationVariable String roomId, BattleMessage message) {
        // 유저가 게임 방에 들어와서 준비되었을 때 처리
        battleService.processBattle(roomId, message);
    }
}
