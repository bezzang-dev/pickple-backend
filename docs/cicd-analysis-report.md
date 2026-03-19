# CI/CD 구성 분석 보고서

> 작성일: 2026-03-20

---

## 1. 전체 파이프라인 구조

```
PR/Push to develop ──→ build-ci.yml              (Gradle 빌드 검증 + 테스트)
                  └──→ docker-integrate-ci.yml   (통합 환경 검증)

Push to main ──→ 경로별 CD 워크플로우
                  ├── eureka-gateway-cd.yml           (gateway/**, eureka-server/**)
                  ├── commerce-payment-cd.yml          (commerce-service/**, payment-service/**)
                  ├── user-auth-cd.yml                 (auth-service/**, user-service/**)
                  └── delivery-notification-cd.yml     (delivery-service/**, notification-service/**)
```

---

## 2. CI 워크플로우 분석

### build-ci.yml

| 항목 | 내용 |
|------|------|
| 트리거 | push/PR → develop, main |
| 목적 | 기본 Gradle 빌드 검증 + 단위 테스트 |
| 빌드 명령 | `./gradlew build -x test` |
| 테스트 명령 | `./gradlew test --parallel` |
| JDK | Eclipse Temurin 17 |

build job과 test job이 병렬로 실행되며, 테스트 결과는 Artifact로 업로드됩니다.

### docker-integrate-ci.yml

| 항목 | 내용 |
|------|------|
| 트리거 | push/PR → develop, main |
| 목적 | 전체 통합 환경에서 빌드 검증 |
| 외부 의존 | `pickple-ecommerce/docker-elk` 레포 체크아웃 |

흐름:
1. ELK 스택 `docker compose up`
2. `docker-compose.dev.yml` (DB, Kafka, Redis, 모니터링) 기동
3. Kafka / PostgreSQL / Redis healthcheck 대기 (until 루프, 3초 간격)
4. Eureka 서버 JAR 빌드 및 기동
5. Eureka `/actuator/health` UP 확인 후 전체 Gradle 빌드

---

## 3. CD 워크플로우 분석 (공통 패턴)

4개의 CD 워크플로우가 동일한 2-job 구조를 공유합니다.

```
[Build Job]
1. Checkout 코드
2. JDK 17 셋업
3. ./gradlew bootJar (해당 서비스들)
4. AWS ECR 로그인
5. 외부 비공개 레포(pickple-ecommerce/config-secrets)에서 .env 파일 취득
6. docker compose build → docker tag → docker push (ECR)

[Deploy Job]  (Build 완료 후 실행)
7. EC2에 docker-compose 파일과 .env SCP 전송
8. EC2 SSH 접속 → docker compose pull → docker compose up -d
```

**서비스 그룹별 매핑:**

| 워크플로우 | 서비스 | Compose 파일 |
|-----------|-------|-------------|
| `eureka-gateway-cd.yml` | eureka-server, gateway | `docker-compose.eureka-gateway.yml` |
| `commerce-payment-cd.yml` | commerce, payment | `docker-compose.commerce-payment.yml` |
| `user-auth-cd.yml` | auth, user | `docker-compose.user-auth.yml` |
| `delivery-notification-cd.yml` | delivery, notification | `docker-compose.delivery-notification.yml` |

---

## 4. Dockerfile 분석

### 개선 전 (단일 스테이지)

모든 서비스가 동일한 단일 스테이지 패턴을 사용했습니다.
JAR를 GitHub Actions에서 먼저 빌드한 뒤 이미지에 복사만 하는 구조이며,
런타임에 불필요한 JDK 전체가 포함되는 문제가 있었습니다.

```dockerfile
# 개선 전 (예: delivery-service)
FROM eclipse-temurin:17-jdk-alpine
ARG JAR_FILE=build/libs/*.jar
WORKDIR /app
COPY ${JAR_FILE} app.jar
EXPOSE ${DELIVERY_SERVICE_PORT}
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### 개선 후 (멀티스테이지 빌드)

builder 스테이지에서 Gradle 빌드를 수행하고,
런타임 스테이지는 JRE 경량 이미지만 사용합니다.
JDK 대비 JRE 이미지는 약 **200MB 이상** 절감됩니다.

```dockerfile
# 개선 후 (예: delivery-service)
FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /build
COPY . .
RUN chmod +x ./gradlew && ./gradlew :delivery-service:bootJar -x test

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=builder /build/delivery-service/build/libs/*.jar app.jar
EXPOSE ${DELIVERY_SERVICE_PORT}
ENTRYPOINT ["java", "-jar", "app.jar"]
```

**개선 적용 서비스 목록:**

| 서비스 | Dockerfile 경로 | 비고 |
|--------|----------------|------|
| eureka-server | `eureka-server/Dockerfile` | JDK 풀 이미지 → JRE alpine으로 통일 |
| gateway | `gateway/Dockerfile` | |
| auth-service | `auth-service/Dockerfile` | 하드코딩된 `ENV SPRING_PROFILES_ACTIVE=prod` 제거 |
| user-service | `user-service/Dockerfile` | |
| commerce-service | `commerce-service/Dockerfile` | 하드코딩된 `ENV SPRING_PROFILES_ACTIVE=prod` 제거 |
| payment-service | `payment-service/Dockerfile` | |
| delivery-service | `delivery-service/Dockerfile` | |
| notification-service | `notification-service/Dockerfile` | |

> `auth-service`와 `commerce-service`에만 존재하던 `ENV SPRING_PROFILES_ACTIVE=prod` 하드코딩도
> 멀티스테이지 전환과 함께 제거하여, 프로파일은 docker-compose의 `environment` 섹션에서 주입하도록 일관성을 맞췄습니다.

---

## 5. 인프라 구성 (docker-compose 계층)

| 파일 | 환경 | 포함 스택 |
|------|------|----------|
| `docker-compose.dev.yml` | 개발/CI 통합 | MongoDB, PostgreSQL(다수), Kafka+ZK, Redis HA(1M+2R+3Sentinel), Prometheus, Grafana, Loki, Zipkin |
| `docker-compose.eureka-gateway.yml` | 프로덕션 | eureka-server, gateway |
| `docker-compose.commerce-payment.yml` | 프로덕션 | commerce, payment |
| `docker-compose.user-auth.yml` | 프로덕션 | auth, user |
| `docker-compose.delivery-notification.yml` | 프로덕션 | delivery, notification |
| `docker-compose.service.yml` | 로컬 풀스택 | 전체 8개 서비스 + 전체 인프라 |
| `docker-compose.kafka.yml` | 독립 실행 | Kafka, Zookeeper, Kafka UI |
| `docker-compose.redis.yml` | 독립 실행 | Redis HA 클러스터 |
| `docker-compose.monitor.yml` | 독립 실행 | Prometheus, Grafana, Loki, Zipkin |

프로덕션 compose 파일들은 모두 `network_mode: host`를 사용하여 EC2 호스트 네트워크를 공유합니다.

---

## 6. 개선 사항 정리

### 높은 우선순위 (이번 PR에서 반영)

#### ① Dockerfile 멀티스테이지 빌드 적용 ✅

**문제:** 단일 스테이지로 JDK 전체가 런타임 이미지에 포함되어 이미지 용량이 불필요하게 컸습니다.
**개선:** builder(JDK) + runtime(JRE) 2스테이지 분리. 이미지 크기 ~200MB 절감 효과.
**추가:** eureka-server의 JDK 풀 이미지를 나머지와 동일하게 alpine으로 통일하고,
`auth-service`/`commerce-service`에 하드코딩된 `SPRING_PROFILES_ACTIVE` 제거.

#### ② CI에서 테스트 실행 추가 ✅

**문제:** `build-ci.yml`이 `-x test`로 테스트를 완전히 건너뛰어 실제 로직 검증이 없었습니다.
**개선:** `test` job을 별도로 추가하여 `build` job과 병렬 실행. 테스트 리포트는 Artifact로 업로드.

#### ③ `sleep 10` → healthcheck 기반 대기로 교체 ✅

**문제:** `jakejarvis/wait-action@master`로 무조건 10초 대기하여 Race Condition 위험이 있었고,
고정된 외부 액션에 대한 버전 고정도 되어 있지 않았습니다.
**개선:** Kafka, PostgreSQL(user_db), Redis 각각에 대해 `until` 루프로 실제 준비 여부를 확인.
Eureka 서버도 `/actuator/health` 엔드포인트가 `UP`을 반환할 때까지 대기.

---

### 중간 우선순위 (향후 개선 권장)

#### ④ CD 워크플로우 코드 중복 제거

4개의 CD 워크플로우가 거의 동일한 구조를 반복합니다.
[Reusable Workflow](https://docs.github.com/en/actions/sharing-automations/reusing-workflows)로 공통 로직을 추출하면 유지보수성이 향상됩니다.

```yaml
# .github/workflows/reusable-deploy.yml (예시)
on:
  workflow_call:
    inputs:
      services:
        required: true
        type: string
      compose-file:
        required: true
        type: string
    secrets: inherit
```

#### ⑤ 시크릿 관리 방식 개선

CD 워크플로우마다 외부 비공개 레포(`config-secrets`)를 체크아웃하여 `.env` 파일을 취득합니다.
GitHub Actions Secrets를 직접 사용하거나 AWS Secrets Manager를 연동하면 보안성과 감사 추적성이 향상됩니다.

---

### 낮은 우선순위 (선택적 개선)

#### ⑥ Docker 이미지 태그 전략

현재 `latest` 태그만 사용하여 이전 버전으로 롤백이 불가능합니다.

```yaml
# Git SHA 기반 태그를 latest와 함께 push
docker tag $IMAGE:latest $IMAGE:${{ github.sha }}
docker push $IMAGE:${{ github.sha }}
```

#### ⑦ Gradle 병렬 빌드 활용

CD에서 같은 그룹의 두 서비스를 순차 빌드합니다.

```yaml
run: ./gradlew :gateway:bootJar :eureka-server:bootJar --parallel
```

---

## 7. 현황 요약

| 구분 | 개선 전 | 개선 후 | 상태 |
|------|---------|---------|------|
| 빌드 자동화 | Gradle + GitHub Actions | 동일 | ✅ |
| 경로별 CD 트리거 | 서비스 그룹별 분리 | 동일 | ✅ |
| 테스트 자동화 | CI에서 테스트 미실행 | 병렬 test job 추가 | ✅ 개선 완료 |
| CI 서비스 대기 | 고정 sleep 10s | healthcheck 기반 대기 | ✅ 개선 완료 |
| Dockerfile | 단일 스테이지 JDK | 멀티스테이지 JRE 경량화 | ✅ 개선 완료 |
| 이미지 일관성 | eureka만 JDK 풀, 일부 프로파일 하드코딩 | 전체 통일 | ✅ 개선 완료 |
| 이미지 태그 | latest 태그만 사용 | SHA 태그 추가 권장 | ⚠️ 향후 개선 |
| CD 워크플로우 중복 | 4개 CD 동일 구조 반복 | Reusable Workflow 권장 | ⚠️ 향후 개선 |
| 시크릿 관리 | 외부 레포 체크아웃 방식 | Secrets Manager 연동 권장 | ⚠️ 향후 개선 |
| 인프라 코드 | 환경별 compose 파일 분리 | 동일 | ✅ |
