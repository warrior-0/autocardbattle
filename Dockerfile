# 1. 빌드
FROM maven:3.9.6-eclipse-temurin-17 AS build

WORKDIR /app

COPY pom.xml .
COPY src ./src

# index.html 안의 REPLACE_ME_... 문구를 Render 환경변수로 교체합니다.
RUN sed -i "s/FIREBASE_API_KEY_PLACEHOLDER/$FIREBASE_API_KEY_PLACEHOLDER/g" src/main/resources/static/index.html && \
    sed -i "s/FIREBASE_APP_ID_PLACEHOLDER/$FIREBASE_APP_ID_PLACEHOLDER/g" src/main/resources/static/index.html && \
    sed -i "s/FIREBASE_AUTH_DOMAIN_PLACEHOLDER/$FIREBASE_AUTH_DOMAIN_PLACEHOLDER/g" src/main/resources/static/index.html && \
    sed -i "s/FIREBASE_MEASUREMENT_ID_PLACEHOLDER/$FIREBASE_MEASUREMENT_ID_PLACEHOLDER/g" src/main/resources/static/index.html && \
    sed -i "s/FIREBASE_PROJECT_ID_PLACEHOLDER/$FIREBASE_PROJECT_ID_PLACEHOLDER/g" src/main/resources/static/index.html && \
    sed -i "s/FIREBASE_SENDER_ID_PLACEHOLDER/$FIREBASE_SENDER_ID_PLACEHOLDER/g" src/main/resources/static/index.html && \
    sed -i "s/FIREBASE_STORAGE_BUCKET_PLACEHOLDER/$FIREBASE_STORAGE_BUCKET_PLACEHOLDER/g" src/main/resources/static/index.html

RUN mvn clean package -DskipTests

# 2. 실행
FROM eclipse-temurin:17-jdk
WORKDIR /app

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
