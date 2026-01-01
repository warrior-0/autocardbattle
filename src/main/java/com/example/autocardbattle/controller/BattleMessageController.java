package com.example.autocardbattle.controller;

import com.example.autocardbattle.dto.BattleMessage;
import com.example.autocardbattle.service.BattleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;

@Controller
public class BattleMessageController {

    @Autowired
    private BattleService battleService;

    // 경로에 {roomId}를 추가하여 방마다 메시지를 격리합니다.
    @MessageMapping("/battle/{roomId}/place")
    @SendTo("/topic/battle/{roomId}")
    public BattleMessage handlePlacement(@DestinationVariable String roomId, BattleMessage message) {
        // 서비스 호출 시 roomId를 전달합니다.
        return battleService.processBattle(roomId, message);
    }
}
