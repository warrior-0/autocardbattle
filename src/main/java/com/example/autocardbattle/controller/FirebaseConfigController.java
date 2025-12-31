package com.example.autocardbattle.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/config")
@CrossOrigin(origins = "https://warrior-0.github.io")
public class FirebaseConfigController {

    private static final String ALLOWED_ORIGIN = "https://warrior-0.github.io";

    @Value("${FIREBASE_API_KEY_PLACEHOLDER:}") private String apiKey;
    @Value("${FIREBASE_AUTH_DOMAIN_PLACEHOLDER:}") private String authDomain;
    @Value("${FIREBASE_PROJECT_ID_PLACEHOLDER:}") private String projectId;
    @Value("${FIREBASE_STORAGE_BUCKET_PLACEHOLDER:}") private String storageBucket;
    @Value("${FIREBASE_SENDER_ID_PLACEHOLDER:}") private String messagingSenderId;
    @Value("${FIREBASE_APP_ID_PLACEHOLDER:}") private String appId;

    @GetMapping("/firebase")
    public ResponseEntity<?> getFirebaseConfig(
            @RequestHeader(value = "Origin", required = false) String origin
    ) {

        // ❌ 주소창 직접 접근 / Origin 없는 요청
        if (origin == null) {
            return ResponseEntity
                    .status(HttpStatus.FORBIDDEN)
                    .body("권한이 없습니다");
        }

        // ❌ 내 GitHub Pages가 아니면 차단
        if (!ALLOWED_ORIGIN.equals(origin)) {
            return ResponseEntity
                    .status(HttpStatus.FORBIDDEN)
                    .body("권한이 없습니다");
        }

        // ✅ 정상 요청
        Map<String, String> config = new HashMap<>();
        config.put("apiKey", apiKey);
        config.put("authDomain", authDomain);
        config.put("projectId", projectId);
        config.put("storageBucket", storageBucket);
        config.put("messagingSenderId", messagingSenderId);
        config.put("appId", appId);

        return ResponseEntity.ok(config);
    }
}
