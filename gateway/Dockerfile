FROM eclipse-temurin:17-jdk-alpine AS builder

WORKDIR /build
COPY . .
RUN chmod +x ./gradlew && ./gradlew :gateway:bootJar -x test

FROM eclipse-temurin:17-jre-alpine

WORKDIR /app
COPY --from=builder /build/gateway/build/libs/*.jar app.jar

EXPOSE ${GATEWAY_SERVER_PORT}

ENTRYPOINT ["java", "-jar", "app.jar"]
