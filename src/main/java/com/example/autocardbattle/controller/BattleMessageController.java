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
        // 상대에게 실시간 배치/합성 정보를 노출하지 않기 위해
        // PLACE/MERGE 이벤트는 즉시 브로드캐스트하지 않고 서버 상태에만 반영합니다.
        // 실제 전체 배치 공개는 BattleService의 REVEAL 단계에서만 전송됩니다.
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
