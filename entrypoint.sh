#!/bin/bash
set -e

# 1) Render가 제공하는 PORT를 우선 사용하도록 서버 포트 정규화
if [ -n "$PORT" ]; then
    export SERVER_PORT="$PORT"
else
    export SERVER_PORT="${SERVER_PORT:-10000}"
fi

# 2) Git 설정 및 저장소 초기화 (실패해도 서버는 계속 기동)
if command -v git >/dev/null 2>&1; then
    echo "[Git] Initializing repository and setting up remote..."
    
    # Git 사용자 정보 설정
    git config --global user.email "render-bot@example.com" || true
    git config --global user.name "Render Auto Bot" || true
    git config --global pull.rebase true || true
    git config --global --add safe.directory /app || true

    # /app 디렉토리를 Git 저장소로 초기화 (기존 .git이 없는 경우)
    if [ ! -d ".git" ]; then
        git init
        git remote add origin https://github.com/warrior-0/autocardbattle.git || true
        
        # GITHUB_TOKEN이 있으면 인증된 URL로 업데이트
        if [ -n "$GITHUB_TOKEN" ]; then
            git remote set-url origin "https://warrior-0:${GITHUB_TOKEN}@github.com/warrior-0/autocardbattle.git"
        fi
        
        # 최신 메인 브랜치 정보를 가져오기 (학습 결과 푸시 시 충돌 방지)
        git fetch origin main || true
        # 현재 상태를 main 브랜치로 설정 (브랜치 이름 불일치 방지)
        git checkout -b main || true
        git branch --set-upstream-to=origin/main main || true
    fi
fi

# 3) Java 애플리케이션 실행
# -Dserver.port 시스템 프로퍼티를 추가하여 Spring Boot 등에서 포트를 인식하도록 함
echo "[Server] Starting Java application on port ${SERVER_PORT}..."
exec java -Xms64m -Xmx200m -Dserver.port=${SERVER_PORT} -jar app.jar
