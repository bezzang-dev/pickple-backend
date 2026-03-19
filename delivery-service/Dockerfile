FROM eclipse-temurin:17-jdk-alpine AS builder

WORKDIR /build
COPY . .
RUN chmod +x ./gradlew && ./gradlew :delivery-service:bootJar -x test

FROM eclipse-temurin:17-jre-alpine

WORKDIR /app
COPY --from=builder /build/delivery-service/build/libs/*.jar app.jar

EXPOSE ${DELIVERY_SERVICE_PORT}

ENTRYPOINT ["java", "-jar", "app.jar"]
