package com.example.autocardbattle.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic"); // 메시지를 받을 때 (구독)
        config.setApplicationDestinationPrefixes("/app"); // 메시지를 보낼 때
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws") // 웹소켓 접속 주소: ws://.../ws
                .setAllowedOriginPatterns("*")
                .withSockJS(); // 낮은 버전 브라우저 지원
    }
    
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .addInterceptors(new HttpSessionHandshakeInterceptor() {
                    @Override
                    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, 
                                                   WebSocketHandler wsHandler, Map<String, Object> attributes) throws Exception {
                        if (request instanceof ServletServerHttpRequest) {
                            ServletServerHttpRequest servletRequest = (ServletServerHttpRequest) request;
                            // URL 파라미터에서 userUid를 추출하여 웹소켓 세션에 저장
                            String userUid = servletRequest.getServletRequest().getParameter("userUid");
                            attributes.put("userUid", userUid);
                        }
                        return true;
                    }
                })
                .withSockJS();
    }
}
