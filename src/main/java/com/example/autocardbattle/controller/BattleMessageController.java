package com.example.autocardbattle.controller;

import com.example.autocardbattle.dto.BattleMessage;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;

@Controller
public class BattleMessageController {

    // 클라이언트가 /app/battle/place로 보낸 메시지를 처리
    @MessageMapping("/battle/place")
    @SendTo("/topic/battle") // 결과를 구독자들에게 뿌림
    public BattleMessage handlePlacement(BattleMessage message) {
        // 실제로는 여기서 서버가 "양쪽 유저가 다 보냈는지" 체크 로직이 들어가야 함
        // 지금은 일단 받은 메시지를 그대로 다시 보내주는 테스트용입니다.
        System.out.println("배치 수신: " + message.getDiceType());
        
        // 3턴 체크 예시
        if (message.getTurn() >= 3) {
            message.setType("REVEAL");
        }
        
        return message; 
    }
}
