# 1. 빌드 단계: Maven으로 Java 패키징
FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /app

# pom.xml 먼저 복사 → 의존성 캐시 활용
COPY pom.xml .

# 의존성 미리 다운로드
RUN mvn dependency:go-offline -B

# 소스 복사 (필요한 경우 .git 폴더 포함을 위해 전체 복사 후 처리 가능)
COPY . .

# 프로젝트 빌드 (테스트 스킵)
RUN mvn clean package -DskipTests

# 2. 실행 단계: Java + Python 환경
FROM eclipse-temurin:17-jdk
WORKDIR /app

# 시스템 패키지 업데이트 및 필수 빌드 도구 설치
# 사용자께서 제시해주신 "성공했던 Dockerfile"의 설치 구성을 그대로 사용합니다.
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
    python3 \
    python3-pip \
    python3-dev \
    python3-venv \
    git \
    build-essential \
    gfortran \
    pkg-config \
    libopenblas-dev && \
    rm -rf /var/lib/apt/lists/*

# python3 -> python 심볼릭 링크 생성
RUN ln -sf /usr/bin/python3 /usr/bin/python

# Python 가상환경 생성 및 경로 설정
RUN python3 -m venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH"

# pip 업그레이드 및 필수 패키지 설치
# NumPy 1.24.3은 현재 Python 버전에서 바이너리(Wheel)를 찾을 수 없어 1.26.4로 업데이트합니다.
# 512MB RAM 환경에서 OOM을 방지하기 위해 --only-binary=:all: 옵션을 유지합니다.
RUN pip install --no-cache-dir --upgrade pip setuptools wheel && \
    pip install --no-cache-dir --only-binary=:all: numpy==1.26.4 numba==0.60.0 llvmlite==0.43.0

# 빌드된 jar 복사
COPY --from=build /app/target/*.jar app.jar

# Entrypoint 스크립트 복사 및 실행 권한 부여
COPY entrypoint.sh .
RUN chmod +x entrypoint.sh

# Python 스크립트 및 리소스 복사
COPY src/main/resources/python ./src/main/resources/python

# 포트 설정
# Render default port
EXPOSE 10000

# 환경 변수 설정
ENV PYTHONUNBUFFERED=1
ENV PYTHONPATH="/app/src/main/resources/python"
ENV SERVER_PORT=10000

# [수정] entrypoint.sh를 통해 Git 환경을 동적으로 초기화합니다.
ENTRYPOINT ["./entrypoint.sh"]
