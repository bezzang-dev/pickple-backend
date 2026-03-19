FROM eclipse-temurin:17-jdk-alpine AS builder

WORKDIR /build
COPY . .
RUN chmod +x ./gradlew && ./gradlew :auth-service:bootJar -x test

FROM eclipse-temurin:17-jre-alpine

WORKDIR /app
COPY --from=builder /build/auth-service/build/libs/*.jar app.jar

EXPOSE ${AUTH_SERVER_PORT}

ENTRYPOINT ["java", "-jar", "app.jar"]
