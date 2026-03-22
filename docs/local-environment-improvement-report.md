# Pickple Backend - 로컬 테스트 환경 개선 보고서

## 개요

로컬 테스트 환경 구성 가이드 분석에서 발견된 개선사항들을 실제로 반영한 내역입니다.
총 **7건의 개선사항**을 적용했으며, Critical 3건, High 2건, Medium 1건, Low 1건입니다.

---

## 1. [Critical] Elasticsearch 컨테이너 추가

### 문제
`commerce-service`가 Elasticsearch(localhost:9200)를 사용하여 상품 검색(CQRS)을 처리하지만, `docker-compose.dev.yml`에 Elasticsearch 컨테이너가 정의되어 있지 않아 상품 검색 기능이 동작 불가능했습니다.

### 변경 파일
- `docker/docker-compose.dev.yml`

### 변경 내용
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
  healthcheck:
    test: ["CMD-SHELL", "curl -s -u elastic:changeme http://localhost:9200/_cluster/health | grep -q '\"status\"'"]
    interval: 15s
    timeout: 10s
    retries: 10
  networks:
    - t4y
```

### 설정 상세
- **싱글 노드 모드**: 로컬 개발 환경에 적합한 단일 노드 구성
- **보안 활성화**: 기존 `dev.env`의 `ELASTIC_SEARCH_USERNAME=elastic`, `ELASTIC_SEARCH_PASSWORD=changeme` 설정과 일치
- **메모리 제한**: `-Xms512m -Xmx512m`으로 메모리 사용량 제한 (로컬 리소스 보호)
- **Healthcheck**: 클러스터 상태 API로 정상 기동 확인
- **t4y 네트워크**: 다른 서비스들과 동일 네트워크에 연결

### `dev.env` 변경
```properties
# 변경 전
ELASTIC_SEARCH_HOST=localhost

# 변경 후 (Docker 컨테이너 간 통신에 맞게)
ELASTIC_SEARCH_HOST=elasticsearch
```

---

## 2. [Critical] Docker 네트워크 통합

### 문제
`docker-compose.dev.yml`에서 DB(PostgreSQL 4개, MongoDB), Zookeeper, Kafka, Kafka UI, Loki는 네트워크 설정이 없었고(기본 네트워크 사용), Redis/Prometheus/Grafana/Zipkin만 `t4y` 네트워크를 사용했습니다. `docker-compose.service.yml`에는 네트워크 설정이 전혀 없었습니다.

두 compose 파일을 별도로 실행하면 각각 다른 Docker 네트워크가 생성되어, 서비스 컨테이너가 인프라 컨테이너에 컨테이너명으로 접근할 수 없습니다.

### 변경 파일
- `docker/docker-compose.dev.yml`
- `docker/docker-compose.service.yml`

### 변경 내용

**docker-compose.dev.yml** - 모든 서비스에 `t4y` 네트워크 추가:
```yaml
# 추가된 서비스들: delivery_db, commerce_db, payment_db, user_db,
# notification_db, zookeeper, kafka, kafka-ui, loki
# 모두에 아래 설정 추가:
    networks:
      - t4y
```

**docker-compose.service.yml** - `t4y`를 외부 네트워크로 참조:
```yaml
# 파일 최하단에 추가
networks:
  t4y:
    external: true

# 모든 서비스에 networks: - t4y 추가
```

### 동작 방식
1. `docker-compose.dev.yml` 실행 시 `t4y` 브릿지 네트워크가 생성됩니다
2. `docker-compose.service.yml` 실행 시 `external: true`로 기존 `t4y` 네트워크에 참여합니다
3. 모든 컨테이너가 동일 네트워크에서 컨테이너명으로 서로 통신 가능합니다

---

## 3. [Critical] Dockerfile 빌드 컨텍스트 수정

### 문제
각 서비스의 Dockerfile은 프로젝트 루트의 `gradlew`, `settings.gradle`, `common-module` 등을 필요로 하지만, `docker-compose.service.yml`의 `build.context`가 각 서비스 디렉토리(`../eureka-server` 등)로 설정되어 있어 빌드가 실패합니다.

### 변경 파일
- `docker/docker-compose.service.yml`

### 변경 내용 (전체 8개 서비스)
```yaml
# 변경 전
eureka-server:
  build:
    context: ../eureka-server
    dockerfile: Dockerfile

# 변경 후
eureka-server:
  build:
    context: ..                          # 프로젝트 루트
    dockerfile: eureka-server/Dockerfile  # 서비스별 Dockerfile 경로
```

### 적용 서비스
| 서비스 | context (변경 후) | dockerfile (변경 후) |
|--------|---------|------------|
| eureka-server | `..` | `eureka-server/Dockerfile` |
| gateway | `..` | `gateway/Dockerfile` |
| auth-service | `..` | `auth-service/Dockerfile` |
| user-service | `..` | `user-service/Dockerfile` |
| commerce-service | `..` | `commerce-service/Dockerfile` |
| payment-service | `..` | `payment-service/Dockerfile` |
| delivery-service | `..` | `delivery-service/Dockerfile` |
| notification-service | `..` | `notification-service/Dockerfile` |

---

## 4. [High] Eureka Health Check 기반 서비스 기동 순서 보장

### 문제
`docker-compose.service.yml`에서 각 서비스가 `depends_on: - eureka-server`만 설정하여, Eureka가 HTTP 요청을 받을 준비가 되기 전에 다른 서비스들이 시작되어 등록 실패가 발생할 수 있었습니다.

### 변경 파일
- `docker/docker-compose.service.yml`

### 변경 내용

**Eureka Server에 healthcheck 추가:**
```yaml
eureka-server:
  healthcheck:
    test: ["CMD", "wget", "--spider", "-q", "http://localhost:${EUREKA_SERVER_PORT}/actuator/health"]
    interval: 10s
    timeout: 5s
    retries: 10
    start_period: 30s
```

**모든 하위 서비스의 depends_on을 조건부로 변경:**
```yaml
# 변경 전
depends_on:
  - eureka-server

# 변경 후
depends_on:
  eureka-server:
    condition: service_healthy
```

### 동작 방식
1. Eureka Server가 기동 후 `start_period` 30초 동안 대기합니다
2. 이후 10초마다 `/actuator/health` 엔드포인트를 호출합니다
3. 정상 응답을 받으면 `healthy` 상태가 되고, 그제서야 나머지 서비스들이 기동됩니다

---

## 5. [High] dev.env 환경 변수 정합성 수정

### 문제
`dev.env`가 Docker 내부 통신용과 로컬 호스트 통신용 설정이 혼재되어 있었습니다.

### 변경 파일
- `docker/dev.env`

### 주요 변경 내용

| 변수 | 변경 전 | 변경 후 | 사유 |
|------|---------|---------|------|
| `REDIS_HOST` | `redis_master` | `redis-master` | hostname과 일치시킴 |
| `EUREKA_GATEWAY_SERVER_URL` | `http://localhost:19090/eureka/` | `http://eureka:19090/eureka/` | Docker 내부 통신용 |
| `EUREKA_HOSTNAME` | `localhost` | `eureka` | 컨테이너명과 일치 |
| `PAYMENT_SERVICE_URL` | `http://localhost:19095` | `http://payment-service:19095` | Docker 내부 통신용 |
| `DELIVERY_SERVICE_URL` | `http://localhost:19096` | `http://delivery-service:19096` | Docker 내부 통신용 |
| `ELASTIC_SEARCH_HOST` | `localhost` | `elasticsearch` | Docker 컨테이너명 |

### 설계 원칙
- `dev.env`: Docker Compose 환경 전용 (컨테이너명 기반 호스트)
- `application-dev.yml`: IDE 로컬 실행 전용 (`localhost` 기반, 기존 유지)

---

## 6. [High] Gateway Eureka URL 하드코딩 제거

### 문제
`gateway/src/main/resources/application-prod.yml`에서 Eureka URL이 `http://eureka-server:19090/eureka/`로 하드코딩되어 있었습니다. 실제 컨테이너명은 `eureka`이므로 이름이 불일치하며, 다른 서비스들은 이미 `${EUREKA_SERVER_URL}` 환경 변수를 사용하고 있었습니다.

### 변경 파일
- `gateway/src/main/resources/application-prod.yml`

### 변경 내용
```yaml
# 변경 전
eureka:
  client:
    service-url:
      defaultZone: http://eureka-server:19090/eureka/

# 변경 후
eureka:
  client:
    service-url:
      defaultZone: ${EUREKA_GATEWAY_SERVER_URL}
```

`docker-compose.service.yml`에서 이미 `EUREKA_GATEWAY_SERVER_URL` 환경 변수를 주입하고 있었으므로, 해당 값을 참조하도록 변경했습니다.

---

## 7. [Medium] commerce_db init SQL 마운트 활성화

### 문제
`docker-compose.dev.yml`에서 `commerce_db`의 init SQL 볼륨 마운트가 주석 처리되어 있었습니다. `docker/db-init/commerce/init-commerce.sql`에 벤더, 상품, 주문 등의 더미 데이터가 정의되어 있지만 적용되지 않았습니다.

### 변경 파일
- `docker/docker-compose.dev.yml`

### 변경 내용
```yaml
# 변경 전
commerce_db:
  # volumes:
    # - ../db-init/commerce/init-commerce.sql:/docker-entrypoint-initdb.d/init-commerce.sql

# 변경 후
commerce_db:
  volumes:
    - ./db-init/commerce/init-commerce.sql:/docker-entrypoint-initdb.d/init-commerce.sql
```

> **참고**: init SQL은 PostgreSQL 컨테이너가 최초 생성될 때만 실행됩니다. 이미 볼륨이 존재하는 경우 재실행하려면 `docker compose down -v`로 볼륨을 삭제 후 재기동해야 합니다.

---

## 8. [Low] 로컬 개발 편의 스크립트 추가

### 추가 파일
- `scripts/local-up.sh` — 인프라 + 서비스 원클릭 기동
- `scripts/local-down.sh` — 전체 종료

### `local-up.sh` 주요 기능
1. 인프라 컨테이너 기동 (`docker-compose.dev.yml`)
2. 인프라 준비 상태 확인 (PostgreSQL `pg_isready`, Kafka 토픽 리스트, Redis `PING`)
3. 마이크로서비스 빌드 & 기동 (`docker-compose.service.yml`)
4. 접속 URL 안내 출력

### `local-down.sh` 주요 기능
1. 서비스 컨테이너 종료
2. 인프라 컨테이너 종료
3. 볼륨 삭제 안내 메시지 출력

### 사용법
```bash
# 전체 기동
./scripts/local-up.sh

# 전체 종료
./scripts/local-down.sh
```

---

## 9. [Low] gradlew 실행 권한 복원

### 문제
`gradlew` 파일의 실행 권한이 제거된 상태(`git status`에서 Modified)였습니다.

### 조치
```bash
chmod +x gradlew
```

---

## 변경 파일 요약

| 파일 | 변경 유형 | 관련 개선 항목 |
|------|-----------|---------------|
| `docker/docker-compose.dev.yml` | 수정 | #1 Elasticsearch 추가, #2 네트워크 통합, #7 init SQL |
| `docker/docker-compose.service.yml` | 수정 | #2 네트워크 통합, #3 빌드 컨텍스트, #4 Healthcheck, #5 ES 환경변수 |
| `docker/dev.env` | 수정 | #5 환경 변수 정합성 |
| `gateway/src/main/resources/application-prod.yml` | 수정 | #6 Eureka URL |
| `scripts/local-up.sh` | 신규 | #8 편의 스크립트 |
| `scripts/local-down.sh` | 신규 | #8 편의 스크립트 |
| `gradlew` | 권한 변경 | #9 실행 권한 복원 |

---

## 적용 후 기동 방법

### 방법 A: 전체 Docker 실행 (스크립트 사용)

```bash
./scripts/local-up.sh
```

### 방법 B: 전체 Docker 실행 (수동)

```bash
# 1. 인프라 기동
docker compose -f docker/docker-compose.dev.yml --env-file docker/dev.env up -d

# 2. 인프라 준비 대기 (약 20~30초)

# 3. 서비스 빌드 & 기동
docker compose -f docker/docker-compose.service.yml --env-file docker/dev.env up -d --build
```

### 방법 C: 인프라 Docker + 서비스 IDE (개발 시 권장)

```bash
# 인프라만 기동
docker compose -f docker/docker-compose.dev.yml --env-file docker/dev.env up -d

# IDE에서 각 서비스를 dev 프로파일로 실행
# (application-dev.yml의 localhost 기반 설정이 적용됨)
```

---

## 남아 있는 고려사항

| 항목 | 설명 | 우선순위 |
|------|------|----------|
| Kafka UI 포트 충돌 | 8080 포트가 다른 앱과 충돌 가능 (변경 시 다른 compose 파일도 수정 필요) | Low |
| Redis Sentinel 미사용 | 현재 `RedisConfig`가 `RedisStaticMasterReplicaConfiguration`을 사용하여 실제 Sentinel 자동 failover가 동작하지 않음. `RedisSentinelConfiguration` 전환 검토 필요 | Low |
| `docker-compose.eureka-gateway.yml` 등 개별 배포용 파일 | 본 개선은 `dev.yml`/`service.yml`에만 적용됨. 프로덕션 배포용 compose 파일은 별도 검토 필요 | Low |
| `.dockerignore` 미설정 | 빌드 컨텍스트가 프로젝트 루트로 변경되어 `.git`, `.idea`, `docs` 등 불필요한 파일이 빌드에 포함됨. `.dockerignore` 추가 권장 | Medium |

---

*작성일: 2026-03-21*
