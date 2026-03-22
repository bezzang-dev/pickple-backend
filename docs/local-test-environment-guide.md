# Pickple Backend - 로컬 테스트 환경 구성 가이드

## 목차
1. [프로젝트 아키텍처 개요](#1-프로젝트-아키텍처-개요)
2. [사전 준비사항](#2-사전-준비사항)
3. [Docker Compose 실행 방법](#3-docker-compose-실행-방법)
4. [API 호출 테스트](#4-api-호출-테스트)
5. [모니터링 도구 URL](#5-모니터링-도구-url)
6. [개선사항 및 권장 수정 내용](#6-개선사항-및-권장-수정-내용)

---

## 1. 프로젝트 아키텍처 개요

### 서비스 구성 (8개 마이크로서비스)

| 서비스 | 포트 | 역할 | 데이터베이스 |
|--------|------|------|-------------|
| Eureka Server | 19090 | 서비스 레지스트리 | - |
| API Gateway | 19091 | 라우팅, 인증 | Redis |
| Auth Service | 19092 | JWT 토큰 관리 | Redis |
| User Service | 19093 | 사용자 관리 | PostgreSQL (user_db) |
| Commerce Service | 19094 | 상품/주문/재고 | PostgreSQL (commerce_db) + Elasticsearch |
| Payment Service | 19095 | 결제 처리 | PostgreSQL (payment_db) |
| Delivery Service | 19096 | 배송 관리 | MongoDB (delivery_db) |
| Notification Service | 19097 | 알림(이메일/Slack) | PostgreSQL (notification_db) |

### 인프라 구성

| 인프라 | 포트 | 역할 |
|--------|------|------|
| PostgreSQL (4개) | 5432, 5433, 5434, 5436 | 관계형 데이터 저장소 |
| MongoDB | 5435 (→27017) | 배송 데이터 저장소 |
| Zookeeper | 2181 | Kafka 코디네이터 |
| Kafka | 9092 (외부), 29092 (내부) | 메시지 브로커 |
| Kafka UI | 8080 | Kafka 모니터링 웹 UI |
| Redis Master | 6379 | 캐시/세션/분산 락 |
| Redis Replica 1/2 | 5000, 5001 | 읽기 분산 |
| Redis Sentinel 1/2/3 | 26379, 26380, 26381 | 자동 Failover |
| Prometheus | 9090 | 메트릭 수집 |
| Grafana | 3000 | 대시보드/알림 |
| Loki | 3100 | 로그 수집 |
| Zipkin | 9411 | 분산 트레이싱 |

---

## 2. 사전 준비사항

- **Docker Desktop** 설치 (Docker Compose V2 포함)
- **JDK 17** (로컬 빌드 시 필요)
- 최소 **8GB RAM** 권장 (컨테이너 20개 이상 실행)
- macOS의 경우 Docker Desktop 설정에서 Memory를 8GB 이상으로 설정

---

## 3. Docker Compose 실행 방법

### 3.1 방법 A: 인프라 + 서비스 전체 Docker 실행 (권장)

#### Step 1: 인프라 컨테이너 기동

```bash
cd /path/to/pickple-backend

# 인프라 (DB, Kafka, Redis, 모니터링) 기동
docker compose -f docker/docker-compose.dev.yml --env-file docker/dev.env up -d
```

이 명령으로 다음이 기동됩니다:
- PostgreSQL 4개 (user_db, commerce_db, payment_db, notification_db)
- MongoDB 1개 (delivery_db)
- Zookeeper + Kafka + Kafka UI
- Redis Master + 2 Replica + 3 Sentinel
- Prometheus + Grafana + Loki + Zipkin

#### Step 2: 인프라 정상 기동 확인

```bash
docker compose -f docker/docker-compose.dev.yml ps
```

모든 컨테이너가 `running` 상태인지 확인합니다.

#### Step 3: 마이크로서비스 기동

```bash
# 모든 서비스를 Docker로 빌드 & 실행
docker compose -f docker/docker-compose.service.yml --env-file docker/dev.env up -d --build
```

> **주의**: 서비스 Dockerfile은 멀티스테이지 빌드 방식이므로 최초 빌드 시 Gradle 의존성 다운로드로 인해 시간이 걸립니다.

### 3.2 방법 B: 인프라는 Docker, 서비스는 IDE에서 실행 (개발 시 권장)

```bash
# 인프라만 기동
docker compose -f docker/docker-compose.dev.yml --env-file docker/dev.env up -d
```

각 서비스를 IntelliJ/VSCode에서 `dev` 프로파일로 실행합니다:

```bash
# 예: commerce-service 실행 (프로젝트 루트에서)
./gradlew :eureka-server:bootRun
./gradlew :gateway:bootRun --args='--spring.profiles.active=dev'
./gradlew :auth-service:bootRun --args='--spring.profiles.active=dev'
./gradlew :user-service:bootRun --args='--spring.profiles.active=dev'
./gradlew :commerce-service:bootRun --args='--spring.profiles.active=dev'
./gradlew :payment-service:bootRun --args='--spring.profiles.active=dev'
./gradlew :delivery-service:bootRun --args='--spring.profiles.active=dev'
./gradlew :notification-service:bootRun --args='--spring.profiles.active=dev'
```

> **순서 중요**: Eureka Server → Gateway → 나머지 서비스 순서로 기동해야 합니다.

### 3.3 서비스 종료

```bash
# 서비스 종료
docker compose -f docker/docker-compose.service.yml --env-file docker/dev.env down

# 인프라 종료
docker compose -f docker/docker-compose.dev.yml --env-file docker/dev.env down

# 볼륨 포함 완전 제거
docker compose -f docker/docker-compose.dev.yml --env-file docker/dev.env down -v
```

---

## 4. API 호출 테스트

모든 API는 Gateway(`http://localhost:19091`)를 통해 호출합니다.

### 4.1 인증 (Auth Service)

```bash
# 회원가입
curl -X POST http://localhost:19091/api/v1/auth/sign-up \
  -H "Content-Type: application/json" \
  -d '{
    "username": "testuser",
    "password": "password123"
  }'

# 로그인 (JWT 토큰 발급)
curl -X POST http://localhost:19091/api/v1/auth/sign-in \
  -H "Content-Type: application/json" \
  -d '{
    "username": "testuser",
    "password": "password123"
  }'
```

> 로그인 응답에서 JWT 토큰을 획득하여 이후 요청에 사용합니다.

### 4.2 사용자 (User Service)

```bash
# 사용자 정보 조회
curl -X GET http://localhost:19091/api/v1/users/testuser \
  -H "Authorization: Bearer {JWT_TOKEN}"

# 사용자 검색 (페이징)
curl -X GET "http://localhost:19091/api/v1/users/search?page=0&size=10" \
  -H "Authorization: Bearer {JWT_TOKEN}"
```

### 4.3 상품 (Commerce Service)

```bash
# 상품 등록 (VENDOR_MANAGER/MASTER 권한 필요)
curl -X POST http://localhost:19091/api/v1/products \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer {JWT_TOKEN}" \
  -d '{
    "productName": "테스트 상품",
    "description": "테스트 상품 설명",
    "price": 10000
  }'

# 상품 목록 조회
curl -X GET "http://localhost:19091/api/v1/products?page=0&size=10"

# 상품 검색
curl -X GET "http://localhost:19091/api/v1/products/search?keyword=테스트&page=0&size=10"

# 재고 조회
curl -X GET http://localhost:19091/api/v1/stocks/products/{productId}
```

### 4.4 주문 (Commerce Service)

```bash
# 주문 생성 (USER/MASTER 권한 필요)
curl -X POST http://localhost:19091/api/v1/orders \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer {JWT_TOKEN}" \
  -d '{
    "productId": "{productId}",
    "quantity": 1
  }'

# 내 주문 목록 조회
curl -X GET http://localhost:19091/api/v1/orders/my \
  -H "Authorization: Bearer {JWT_TOKEN}"

# 주문 취소
curl -X DELETE http://localhost:19091/api/v1/orders/{orderId} \
  -H "Authorization: Bearer {JWT_TOKEN}"
```

### 4.5 결제 (Payment Service)

```bash
# 결제 상세 조회
curl -X GET http://localhost:19091/api/v1/payments/details/{paymentId} \
  -H "Authorization: Bearer {JWT_TOKEN}"

# 전체 결제 목록 (MASTER 권한)
curl -X GET "http://localhost:19091/api/v1/payments/all-payments?page=0&size=10" \
  -H "Authorization: Bearer {JWT_TOKEN}"
```

### 4.6 배송 (Delivery Service)

```bash
# 배송 정보 조회
curl -X GET http://localhost:19091/api/v1/deliveries/{deliveryId} \
  -H "Authorization: Bearer {JWT_TOKEN}"

# 배송 시작 (VENDOR_MANAGER/MASTER 권한)
curl -X POST http://localhost:19091/api/v1/deliveries/{deliveryId}/start \
  -H "Authorization: Bearer {JWT_TOKEN}"

# 배송 완료 (VENDOR_MANAGER/MASTER 권한)
curl -X POST http://localhost:19091/api/v1/deliveries/{deliveryId}/end \
  -H "Authorization: Bearer {JWT_TOKEN}"
```

### 4.7 알림 (Notification Service)

```bash
# 알림 이력 조회
curl -X GET http://localhost:19091/api/v1/notification/notification-history/{username} \
  -H "Authorization: Bearer {JWT_TOKEN}"
```

### 4.8 전체 테스트 시나리오 (E2E)

1. **회원가입** → `POST /api/v1/auth/sign-up`
2. **로그인** → `POST /api/v1/auth/sign-in` (JWT 토큰 획득)
3. **상품 등록** → `POST /api/v1/products` (VENDOR_MANAGER 권한)
4. **상품 조회** → `GET /api/v1/products`
5. **주문 생성** → `POST /api/v1/orders` (Kafka로 결제/배송 자동 생성)
6. **결제 확인** → `GET /api/v1/payments/details/{paymentId}`
7. **배송 시작** → `POST /api/v1/deliveries/{deliveryId}/start`
8. **배송 완료** → `POST /api/v1/deliveries/{deliveryId}/end`
9. **알림 확인** → `GET /api/v1/notification/notification-history/{username}`

---

## 5. 모니터링 도구 URL

| 도구 | URL | 계정 | 용도 |
|------|-----|------|------|
| **Eureka Dashboard** | http://localhost:19090 | - | 서비스 등록 상태 확인 |
| **Kafka UI** | http://localhost:8080 | - | Kafka 토픽/메시지 모니터링 |
| **Grafana** | http://localhost:3000 | admin / pickple | 대시보드, 알림 설정 |
| **Prometheus** | http://localhost:9090 | - | 메트릭 쿼리 |
| **Zipkin** | http://localhost:9411 | - | 분산 트레이싱 |
| **Loki** | http://localhost:3100 | - | 로그 수집 (Grafana에서 조회) |

### Grafana 사용법

1. http://localhost:3000 접속 → `admin` / `pickple` 로그인
2. **Datasource** 확인: Prometheus, Loki, Jaeger(Zipkin)가 프로비저닝됨
3. **주요 확인 항목**:
   - `Explore` → Loki 데이터소스 선택 → 서비스별 로그 조회
   - `Explore` → Prometheus 데이터소스 → PromQL 쿼리 실행
   - 예: `up{service="commerce"}` → Commerce 서비스 가동 상태

### Prometheus 주요 쿼리

```promql
# 서비스 가동 상태
up

# HTTP 요청 수
http_server_requests_seconds_count

# JVM 메모리 사용량
jvm_memory_used_bytes

# 서킷 브레이커 상태
resilience4j_circuitbreaker_state
```

### Zipkin 사용법

1. http://localhost:9411 접속
2. 서비스 선택 후 `Run Query`로 트레이스 검색
3. 서비스 간 호출 흐름과 지연 시간 확인 가능

---

## 6. 개선사항 및 권장 수정 내용

### 6.1 [Critical] Elasticsearch 컨테이너 누락

**현상**: `commerce-service`의 `application-dev.yml`에서 Elasticsearch(`localhost:9200`)를 참조하지만, `docker-compose.dev.yml`에 Elasticsearch 컨테이너가 정의되어 있지 않습니다.

**영향**: 상품 검색(`/api/v1/products/search`) 기능이 동작하지 않으며, Commerce 서비스 기동 시 연결 오류가 발생할 수 있습니다.

**해결 방법**: `docker-compose.dev.yml`에 Elasticsearch 컨테이너 추가:

```yaml
elasticsearch:
  image: docker.elastic.co/elasticsearch/elasticsearch:8.11.0
  container_name: elasticsearch
  environment:
    - discovery.type=single-node
    - xpack.security.enabled=true
    - ELASTIC_PASSWORD=changeme
    - "ES_JAVA_OPTS=-Xms512m -Xmx512m"
  ports:
    - "9200:9200"
  networks:
    - t4y
```

### 6.2 [Critical] Docker 네트워크 불일치

**현상**: `docker-compose.dev.yml`의 일부 서비스(DB, Kafka, Loki)는 `networks` 설정이 없고, 일부(Redis, Prometheus, Grafana, Zipkin)는 `t4y` 네트워크를 사용합니다. `docker-compose.service.yml`에는 네트워크 설정이 전혀 없습니다.

**영향**: 두 compose 파일을 별도로 실행하면 서비스가 인프라 컨테이너에 접근할 수 없습니다.

**해결 방법**:
1. `docker-compose.dev.yml`에서 모든 서비스에 `t4y` 네트워크를 추가합니다.
2. `docker-compose.service.yml`에서 `t4y`를 `external` 네트워크로 참조합니다:

```yaml
# docker-compose.service.yml에 추가
networks:
  t4y:
    external: true

# 각 서비스에 networks 추가
services:
  eureka-server:
    networks:
      - t4y
    # ... 기존 설정
```

### 6.3 [Critical] Dockerfile 멀티스테이지 빌드 비효율

**현상**: 각 서비스의 Dockerfile이 `COPY . .`으로 전체 프로젝트를 복사한 후 특정 모듈만 빌드합니다. `context`가 해당 서비스 디렉토리(`../eureka-server`)로 설정되어 있지만, Dockerfile 내에서 `./gradlew :eureka-server:bootJar`처럼 루트 프로젝트 Gradle 래퍼를 실행합니다.

**영향**: 서비스 디렉토리에는 루트의 `gradlew`, `settings.gradle`, 다른 모듈들이 없으므로 **빌드가 실패**합니다.

**해결 방법**: `docker-compose.service.yml`의 build context를 프로젝트 루트로 변경:

```yaml
services:
  eureka-server:
    build:
      context: ..          # 프로젝트 루트
      dockerfile: eureka-server/Dockerfile
```

또는 각 Dockerfile에 `.dockerignore`를 적절히 설정하여 불필요한 파일 복사를 줄입니다.

### 6.4 [High] dev.env 환경 변수와 dev 프로파일 설정 불일치

**현상**: `dev.env`의 DB URL이 Docker 내부 네트워크 호스트명(`user_db`, `payment_db` 등)을 사용하지만, `application-dev.yml`은 `localhost`를 기본값으로 사용합니다.

**설명**:
- `dev.env`: `USER_DB_URL=jdbc:postgresql://user_db:5432/user_db` (Docker 내부용)
- `application-dev.yml`: `url: ${COMMERCE_DB_URL:jdbc:postgresql://localhost:5433/commerce_db}` (로컬 IDE 실행용)

**현재 동작**: 방법 B (IDE 실행)에서는 `localhost`로 연결하므로 정상 동작하지만, 방법 A (전체 Docker)에서 `dev` 프로파일을 사용하면 혼동이 발생합니다. `docker-compose.service.yml`은 `prod` 프로파일을 사용하므로 현재는 문제 없지만, `dev.env`가 두 시나리오를 혼용하고 있어 혼란의 원인이 됩니다.

**권장**: `dev.env`를 `docker.env`와 `local.env`로 분리하거나, 주석으로 명확히 구분합니다.

### 6.5 [High] `depends_on`에 Health Check 미설정

**현상**: `docker-compose.service.yml`에서 서비스들이 `depends_on: eureka-server`만 설정하고, 실제 Eureka가 준비될 때까지 기다리지 않습니다.

**영향**: Eureka가 완전히 기동되기 전에 다른 서비스가 시작되면 등록 실패가 발생합니다.

**해결 방법**:

```yaml
services:
  eureka-server:
    healthcheck:
      test: ["CMD", "wget", "--spider", "-q", "http://localhost:19090/actuator/health"]
      interval: 10s
      timeout: 5s
      retries: 10

  gateway:
    depends_on:
      eureka-server:
        condition: service_healthy
```

### 6.6 [Medium] Redis Sentinel `master` 설정 오류

**현상**: `application-dev.yml`의 Redis Sentinel 설정에서 `master` 이름(`mymaster`)이 명시되어 있지 않습니다.

```yaml
# 현재 설정 (gateway/application-dev.yml)
spring:
  data:
    redis:
      sentinel:
        master:
          host: localhost
          port: 6379
```

**해결 방법**: Sentinel은 `master` 이름으로 마스터를 찾으므로, `master: mymaster`로 설정해야 합니다:

```yaml
spring:
  data:
    redis:
      sentinel:
        master: mymaster
        nodes:
          - localhost:26379
          - localhost:26380
          - localhost:26381
```

### 6.7 [Medium] Gradle 래퍼 실행 권한

**현상**: `gradlew` 파일이 수정(`M`) 상태이며, 실행 권한이 제거되었을 수 있습니다.

**해결 방법**:
```bash
chmod +x gradlew
```

### 6.8 [Medium] `commerce_db` init SQL 마운트 비활성화

**현상**: `docker-compose.dev.yml`에서 `commerce_db`의 init SQL 볼륨 마운트가 주석 처리되어 있습니다:

```yaml
commerce_db:
  # volumes:
    # - ../db-init/commerce/init-commerce.sql:/docker-entrypoint-initdb.d/init-commerce.sql
```

**영향**: commerce DB의 초기 스키마/권한 설정이 적용되지 않습니다. JPA `ddl-auto: update`로 테이블은 생성되지만, 권한 관련 설정은 누락됩니다.

**해결 방법**: 주석을 해제하거나, `init-commerce.sql`이 존재하는지 확인 후 마운트합니다.

### 6.9 [Low] 로컬 개발 편의를 위한 통합 실행 스크립트 추가

**권장**: 프로젝트 루트에 로컬 개발 환경을 원클릭으로 구성하는 스크립트를 추가합니다:

```bash
#!/bin/bash
# scripts/local-up.sh

echo "=== Starting infrastructure ==="
docker compose -f docker/docker-compose.dev.yml --env-file docker/dev.env up -d

echo "=== Waiting for infrastructure to be ready ==="
sleep 15

echo "=== Starting services ==="
docker compose -f docker/docker-compose.service.yml --env-file docker/dev.env up -d --build

echo ""
echo "=== Access URLs ==="
echo "Gateway:    http://localhost:19091"
echo "Eureka:     http://localhost:19090"
echo "Grafana:    http://localhost:3000 (admin/pickple)"
echo "Kafka UI:   http://localhost:8080"
echo "Zipkin:     http://localhost:9411"
echo "Prometheus: http://localhost:9090"
```

### 6.10 [Low] Kafka UI 포트 충돌 가능성

**현상**: Kafka UI가 `8080` 포트를 사용합니다. 로컬에서 다른 애플리케이션이 8080을 사용하는 경우 충돌합니다.

**해결 방법**: `docker-compose.dev.yml`에서 Kafka UI 포트를 변경합니다:
```yaml
kafka-ui:
  ports:
    - "9080:8080"  # 충돌 방지
```

---

## 부록: 포트 전체 맵

```
┌─────────────────────────────────────────────────────┐
│                   로컬 포트 맵                        │
├──────────────────┬──────────────────────────────────┤
│ 19090            │ Eureka Server                    │
│ 19091            │ API Gateway (모든 API 진입점)      │
│ 19092            │ Auth Service                     │
│ 19093            │ User Service                     │
│ 19094            │ Commerce Service                 │
│ 19095            │ Payment Service                  │
│ 19096            │ Delivery Service                 │
│ 19097            │ Notification Service             │
├──────────────────┼──────────────────────────────────┤
│ 5432             │ PostgreSQL (user_db)             │
│ 5433             │ PostgreSQL (commerce_db)         │
│ 5434             │ PostgreSQL (payment_db)          │
│ 5435             │ MongoDB (delivery_db)            │
│ 5436             │ PostgreSQL (notification_db)     │
├──────────────────┼──────────────────────────────────┤
│ 6379             │ Redis Master                     │
│ 5000, 5001       │ Redis Replicas                   │
│ 26379-26381      │ Redis Sentinels                  │
├──────────────────┼──────────────────────────────────┤
│ 9092             │ Kafka (외부)                      │
│ 2181             │ Zookeeper                        │
│ 8080             │ Kafka UI                         │
├──────────────────┼──────────────────────────────────┤
│ 9090             │ Prometheus                       │
│ 3000             │ Grafana                          │
│ 3100             │ Loki                             │
│ 9411             │ Zipkin                           │
└──────────────────┴──────────────────────────────────┘
```

---

*작성일: 2026-03-21*
