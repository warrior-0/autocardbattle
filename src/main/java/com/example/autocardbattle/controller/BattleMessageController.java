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

    // 실시간으로 특정 유저나 방에 메시지를 보내기 위한 도구입니다.
    @Autowired
    private SimpMessageSendingOperations messagingTemplate;

    @MessageMapping("/battle/{roomId}/place")
    public void handlePlacement(@DestinationVariable String roomId, BattleMessage message) {
        // 1. 서비스를 호출하여 비즈니스 로직(배치 저장, 턴 체크 등)을 처리합니다.
        BattleMessage result = battleService.processBattle(roomId, message);

        // 2. 결과가 null이 아닐 때만(TURN_PROGRESS, REVEAL 등) 방 전체에 메시지를 보냅니다.
        // 유저가 그냥 주사위를 놓는 'PLACE' 단계에서는 서비스가 null을 반환하므로 상대에게 아무것도 전달되지 않습니다.
        if (result != null) {
            messagingTemplate.convertAndSend("/topic/battle/" + roomId, result);
        }
    }
}
