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
        // 상대방에게 배치 정보 브로드캐스팅
        if ("PLACE".equals(message.getType())) {
            messagingTemplate.convertAndSend("/topic/battle/" + roomId, message);
        }
        battleService.processBattle(roomId, message);
    }
    
    // ✅ [추가/수정] READY 핸들러
    @MessageMapping("/battle/{roomId}/ready")
    public void handleReady(@DestinationVariable String roomId, BattleMessage message) {
        String userUid = message.getSender();
        
        // 준비 명단에 추가
        BattleController.roomReadyStatus.computeIfAbsent(roomId, k -> new HashSet<>()).add(userUid);
        
        // 두 명이 모이면 게임 시작 (싱크 맞춤)
        if (BattleController.roomReadyStatus.get(roomId).size() >= 2) {
            battleService.initiateGameStart(roomId);
        }
    }
}
