FROM eclipse-temurin:17-jdk-alpine AS builder

WORKDIR /build
COPY . .
RUN chmod +x ./gradlew && ./gradlew :notification-service:bootJar -x test

FROM eclipse-temurin:17-jre-alpine

WORKDIR /app
COPY --from=builder /build/notification-service/build/libs/*.jar app.jar

EXPOSE ${NOTIFICATION_SERVER_PORT}

ENTRYPOINT ["java", "-jar", "app.jar"]
