FROM eclipse-temurin:17-jdk-alpine AS builder

WORKDIR /build
COPY . .
RUN chmod +x ./gradlew && ./gradlew :commerce-service:bootJar -x test

FROM eclipse-temurin:17-jre-alpine

WORKDIR /app
COPY --from=builder /build/commerce-service/build/libs/*.jar app.jar

EXPOSE ${COMMERCE_SERVICE_PORT}

ENTRYPOINT ["java", "-jar", "app.jar"]
