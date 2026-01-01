package com.example.autocardbattle.config;

import com.example.autocardbattle.controller.BattleController;
import com.example.autocardbattle.dto.BattleMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.Map;

@Component
public class WebSocketEventListener {

    @Autowired
    private SimpMessageSendingOperations messagingTemplate;

    @EventListener
    public void handleWebSocketDisconnectListener(SessionDisconnectEvent event) {
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        
        // 세션에서 유저 UID를 가져오는 로직 (로그인 시 세션에 저장했다고 가정)
        // 만약 세션에 없다면 다른 방식으로 유저를 식별해야 합니다.
        String userUid = (String) headerAccessor.getSessionAttributes().get("userUid");

        if (userUid != null) {
            // 1. BattleController의 공유 맵에서 방 ID 확인
            String roomId = BattleController.getUserRooms().get(userUid);
            
            if (roomId != null) {
                // 2. 상대방에게 알림 전송
                BattleMessage leaveMessage = new BattleMessage();
                leaveMessage.setType("OPPONENT_LEFT");
                leaveMessage.setSender(userUid);
                
                messagingTemplate.convertAndSend("/topic/battle/" + roomId, leaveMessage);
                
                // 3. 방 정보 정리
                BattleController.getUserRooms().remove(userUid);
                // 추가적인 게임 상태 정리 로직이 필요할 수 있습니다.
            }
        }
    }
}
