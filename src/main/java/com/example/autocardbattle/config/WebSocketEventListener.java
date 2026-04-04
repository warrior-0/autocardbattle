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
        Map<String, Object> sessionAttributes = headerAccessor.getSessionAttributes();
        if (sessionAttributes == null) {
            return;
        }
        
        String userUid = (String) sessionAttributes.get("userUid");

        if (userUid != null) {
            String roomId = BattleController.getUserRooms().get(userUid);
            
            if (roomId != null) {
                BattleMessage leaveMessage = new BattleMessage();
                leaveMessage.setType("OPPONENT_LEFT");
                leaveMessage.setSender(userUid);
                
                messagingTemplate.convertAndSend("/topic/battle/" + roomId, leaveMessage);
                BattleController.getUserRooms().remove(userUid);
                BattleController.removeRoomData(roomId);
            }
        }
    }
}
