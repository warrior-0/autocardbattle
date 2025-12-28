package com.example.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**") // 1. 모든 경로(API)에 대해 설정 적용
                .allowedOrigins("https://warrior-0.github.io") // 2. 허용할 프론트엔드 주소 (슬래시 없이!)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS") // 3. 허용할 HTTP 메서드
                .allowedHeaders("*") // 4. 모든 헤더 허용
                .allowCredentials(true) // 5. 쿠키나 인증 정보를 포함한 요청 허용
                .maxAge(3600); // 6. 프리플라이트(Preflight) 요청 캐싱 시간 (1시간)
    }
}
