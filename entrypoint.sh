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

    # /app 디렉토리를 Git 저장소로 초기화 및 동기화
    if [ ! -d ".git" ]; then
        echo "[Git] Initializing new repository..."
        git init
        git remote add origin https://github.com/warrior-0/autocardbattle.git || true
    fi

    # GITHUB_TOKEN이 있으면 인증된 URL로 업데이트
    if [ -n "$GITHUB_TOKEN" ]; then
        git remote set-url origin "https://warrior-0:${GITHUB_TOKEN}@github.com/warrior-0/autocardbattle.git"
    fi

    # 원격 상태 강제 동기화 (untracked files 충돌 방지)
    echo "[Git] Fetching and resetting to origin/main..."
    git fetch origin main || true
    
    # 현재 브랜치가 main이 아니면 main으로 전환 시도
    CURRENT_BRANCH=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "")
    if [ "$CURRENT_BRANCH" != "main" ]; then
        git checkout -B main || true
    fi

    # 원격 main 브랜치와 로컬 상태를 강제로 맞춤 (충돌 유발 파일 무시)
    git reset --hard origin/main || true
    git branch --set-upstream-to=origin/main main || true
fi

# 3) Java 애플리케이션 실행
# -Dserver.port 시스템 프로퍼티를 추가하여 Spring Boot 등에서 포트를 인식하도록 함
echo "[Server] Starting Java application on port ${SERVER_PORT}..."
exec java -Xms64m -Xmx200m -Dserver.port=${SERVER_PORT} -jar app.jar
