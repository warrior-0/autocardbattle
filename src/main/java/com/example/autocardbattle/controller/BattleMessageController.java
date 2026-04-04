package com.example.autocardbattle.controller;

import com.example.autocardbattle.dto.BattleMessage;
import com.example.autocardbattle.service.BattleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.stereotype.Controller;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Controller
public class BattleMessageController {

    @Autowired
    private BattleService battleService;

    @Autowired
    private SimpMessageSendingOperations messagingTemplate;

    @MessageMapping("/battle/{roomId}/place")
    public void handlePlacement(@DestinationVariable String roomId, BattleMessage message) {
        if ("PLACE".equals(message.getType())) {
            messagingTemplate.convertAndSend("/topic/battle/" + roomId, message);
        }
        battleService.processBattle(roomId, message);
    }
    
    @MessageMapping("/battle/{roomId}/ready")
    public void handleReady(@DestinationVariable String roomId, BattleMessage message) {
        String userUid = message.getSender();
        BattleController.roomReadyStatus.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet()).add(userUid);

        if (BattleController.isAiRoom(roomId)) {
            BattleController.AiMatchContext aiMatchContext = BattleController.getAiMatchContext(roomId);
            if (aiMatchContext != null) {
                BattleController.roomReadyStatus.get(roomId).add(aiMatchContext.aiUid());
            }
        }
        
        if (BattleController.roomReadyStatus.get(roomId).size() >= 2) {
            battleService.initiateGameStart(roomId);
        }
    }
}
