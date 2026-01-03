# 1. 빌드 단계
FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /app

# pom.xml만 먼저 복사 (캐시 활용)
COPY pom.xml .

# [수정] 의존성 다운로드 강화
# go-offline만으로는 부족할 때가 있어, verify 등을 추가하거나 그대로 둡니다.
# 가장 확실한 건 아래 빌드 단계에서 오프라인 모드를 켜는 것입니다.
RUN mvn dependency:go-offline -B

# 소스 코드 복사
COPY src ./src

# [수정] 빌드 실행 시 '-o' (offline) 옵션 추가
# -o: 네트워크 접속 없이, 아까 다운로드해둔 로컬 저장소(.m2)만 사용해서 빌드하라
RUN mvn clean package -DskipTests -o

# 2. 실행 단계 (이전과 동일)
FROM eclipse-temurin:17-jdk
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
