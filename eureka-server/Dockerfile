FROM eclipse-temurin:17-jdk-alpine AS builder

WORKDIR /build
COPY . .
RUN chmod +x ./gradlew && ./gradlew :eureka-server:bootJar -x test

FROM eclipse-temurin:17-jre-alpine

WORKDIR /app
COPY --from=builder /build/eureka-server/build/libs/*.jar app.jar

EXPOSE ${EUREKA_SERVER_PORT}

ENTRYPOINT ["java", "-jar", "app.jar"]
