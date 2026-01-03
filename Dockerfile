# 1. 빌드 단계
FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /app

# pom.xml만 먼저 복사 (캐시 활용)
COPY pom.xml .

# 의존성 미리 다운로드
RUN mvn dependency:go-offline -B

# 소스 코드 복사
COPY src ./src

# [수정] -o 옵션을 제거합니다. 
# 라이브러리가 부족할 경우 인터넷에서 자동으로 받아오게 하여 빌드 성공률을 높입니다.
RUN mvn clean package -DskipTests

# 2. 실행 단계
FROM eclipse-temurin:17-jdk
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
