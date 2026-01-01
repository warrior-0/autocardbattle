# 1. 빌드 단계
FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /app

# [변경 포인트 1] pom.xml만 먼저 복사합니다.
COPY pom.xml .

# [변경 포인트 2] 의존성 라이브러리만 미리 다운로드합니다.
# 이 단계가 실행되면 라이브러리들이 Docker 레이어에 저장(캐싱)됩니다.
# pom.xml이 수정되지 않는 한, 다음 배포부터 이 과정은 순식간에 지나갑니다.
RUN mvn dependency:go-offline -B

# [변경 포인트 3] 그 다음 소스 코드를 복사하고 빌드합니다.
COPY src ./src
RUN mvn clean package -DskipTests

# 2. 실행 단계 (이전과 동일)
FROM eclipse-temurin:17-jdk
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
