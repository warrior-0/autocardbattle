package com.example.autocardbattle.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.net.HttpURLConnection;
import java.net.URL;

@Service
public class RenderKeepAliveService {

    // Render 서버가 휴면 상태로 들어가지 않도록 20초마다 자기 자신에게 요청을 보냅니다.
    @Scheduled(fixedRate = 20000)
    public void keepAlive() {
        String renderUrl = System.getenv("RENDER_EXTERNAL_URL");
        if (renderUrl == null || renderUrl.isEmpty()) {
            // RENDER_EXTERNAL_URL 환경 변수가 없으면 기본 URL을 시도하거나 건너뜁니다.
            return;
        }

        try {
            URL url = new URL(renderUrl + "/api/dice/list"); // 가벼운 API 엔드포인트 호출
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            
            int responseCode = conn.getResponseCode();
            System.out.println("[Keep-Alive] Sent request to " + renderUrl + ", Response: " + responseCode);
        } catch (Exception e) {
            System.err.println("[Keep-Alive] Failed to send request: " + e.getMessage());
        }
    }
}
