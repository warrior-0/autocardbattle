# 1. 빌드 단계
FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /app

# [개선] pom.xml만 먼저 복사하여 라이브러리를 미리 다운로드합니다.
# 이 레이어는 pom.xml이 수정되지 않는 한 Render에서 캐싱되어 재사용됩니다.
COPY pom.xml .
RUN mvn dependency:go-offline -B

# 소스 코드를 복사하고 빌드합니다.
# 이제 라이브러리는 이미 받아져 있으므로 코드 변경 시 빌드 시간이 획기적으로 줄어듭니다.
COPY src ./src
RUN mvn clean package -DskipTests

# 2. 실행 단계
FROM eclipse-temurin:17-jdk
WORKDIR /app

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
