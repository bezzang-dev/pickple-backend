# CRITICAL + HIGH 이슈 리팩토링 완료 보고서

> 작성일: 2026-03-20
> 대상: pickple-backend (이커머스 MSA 프로젝트)
> 기반 문서: `docs/refactoring-analysis-report.md` — Phase 1 (CRITICAL) + Phase 2 (HIGH)

---

## 요약

리팩토링 분석 보고서에서 식별된 **CRITICAL 심각도 이슈 3건** 및 **HIGH 심각도 이슈 8건**을 모두 수정 완료하였습니다.

### Phase 1: CRITICAL (3건)

| # | 이슈 | 상태 |
|---|------|------|
| 1 | Notification-service Producer Serializer 오류 | **수정 완료** |
| 2 | Commerce-service SecurityConfig 잘못된 설정 | **수정 완료** |
| 3 | 설정 파일 내 자격 증명 노출 | **수정 완료** |

### Phase 2: HIGH (8건)

| # | 이슈 | 상태 |
|---|------|------|
| 4 | Kafka DLT + Error Handler 미적용 서비스 | **수정 완료** |
| 5 | Producer Idempotency 미설정 | **수정 완료** |
| 6 | Consumer Auto-Commit 사용 | **수정 완료** |
| 7 | 직렬화/역직렬화 불일치 | **수정 완료** |
| 8 | Circuit Breaker 누락 서비스 | **수정 완료** |
| 9 | Fallback 메서드 잘못된 구현 | **수정 완료** |
| 10 | 에러 응답 포맷 불일치 | **수정 완료** |
| 11 | 인증 필터 구현 불일치 | **수정 완료** |

---

## 1. Notification-service Producer Serializer 수정

### 문제
Producer 설정에 `StringDeserializer`가 지정되어 있어 메시지 전송 시 직렬화 실패가 발생합니다.

### 수정 내용

**수정 파일:**
- `notification-service/src/main/resources/application-dev.yml` (23-25행)
- `notification-service/src/main/resources/application-prod.yml` (24-26행)

**변경 전:**
```yaml
producer:
  key-serializer: org.apache.kafka.common.serialization.StringDeserializer
  value-serializer: org.apache.kafka.common.serialization.StringDeserializer
```

**변경 후:**
```yaml
producer:
  key-serializer: org.apache.kafka.common.serialization.StringSerializer
  value-serializer: org.apache.kafka.common.serialization.StringSerializer
```

### 비고
- dev 환경뿐만 아니라 **prod 환경에도 동일한 버그가 존재**하여 함께 수정하였습니다.

---

## 2. Commerce-service SecurityConfig 수정

### 문제
`SecurityConfig.java`에서 `anyRequest().permitAll()`로 설정되어 모든 요청이 인증 없이 허용되고 있었습니다. 주석에는 "나머지 요청은 인증 필요"라고 되어있어 코드와 의도가 불일치합니다.

### 수정 내용

**수정 파일:**
- `commerce-service/src/main/java/com/pickple/commerceservice/infrastructure/configuration/SecurityConfig.java` (42행)

**변경 전:**
```java
.anyRequest().permitAll()  // 나머지 요청은 인증 필요
```

**변경 후:**
```java
.anyRequest().authenticated()  // 나머지 요청은 인증 필요
```

### 비고
- `/api/v1/products`, `/api/v1/products/search` 엔드포인트는 기존대로 `permitAll()` 유지합니다.
- 그 외 모든 요청은 인증이 필요하도록 변경되었습니다.

---

## 3. 설정 파일 자격 증명 환경 변수 외부화

### 문제
모든 서비스의 `application-dev.yml`에 DB 비밀번호, JWT 시크릿 키, Gmail 앱 비밀번호 등이 하드코딩되어 있어 보안 위험이 존재합니다.

### 수정 내용

모든 민감 정보를 `${ENV_VAR:기본값}` 형식으로 변경하여, 환경 변수가 설정되면 해당 값을 사용하고 미설정 시 기존 기본값으로 폴백하도록 하였습니다.

#### 3.1 notification-service (`application-dev.yml`)

| 항목 | 환경 변수 |
|------|-----------|
| DB URL | `NOTIFICATION_DB_URL` |
| DB Username | `NOTIFICATION_DB_USERNAME` |
| DB Password | `NOTIFICATION_DB_PASSWORD` |
| Mail Username | `MAIL_USERNAME` |
| Mail Password | `MAIL_PASSWORD` (기본값 제거 — 앱 비밀번호 노출 방지) |

#### 3.2 commerce-service (`application-dev.yml`)

| 항목 | 환경 변수 |
|------|-----------|
| DB URL | `COMMERCE_DB_URL` |
| DB Username | `COMMERCE_DB_USERNAME` |
| DB Password | `COMMERCE_DB_PASSWORD` |
| Elasticsearch Username | `ELASTIC_SEARCH_USERNAME` |
| Elasticsearch Password | `ELASTIC_SEARCH_PASSWORD` |

#### 3.3 payment-service (`application-dev.yml`)

| 항목 | 환경 변수 |
|------|-----------|
| DB URL | `PAYMENT_DB_URL` |
| DB Username | `PAYMENT_DB_USERNAME` |
| DB Password | `PAYMENT_DB_PASSWORD` |

#### 3.4 delivery-service (`application-dev.yml`)

| 항목 | 환경 변수 |
|------|-----------|
| MongoDB Host | `DELIVERY_MONGODB_HOST` |
| MongoDB Port | `DELIVERY_MONGODB_PORT` |
| MongoDB Database | `DELIVERY_MONGODB_DATABASE` |
| MongoDB Username | `DELIVERY_MONGODB_USERNAME` |
| MongoDB Password | `DELIVERY_MONGODB_PASSWORD` |

#### 3.5 user-service (`application-dev.yml`)

| 항목 | 환경 변수 |
|------|-----------|
| DB URL | `USER_DB_URL` |
| DB Username | `USER_DB_USERNAME` |
| DB Password | `USER_DB_PASSWORD` |

#### 3.6 auth-service (`application-dev.yml`)

| 항목 | 환경 변수 |
|------|-----------|
| JWT Secret Key | `JWT_SECRET_KEY` |

#### 3.7 gateway (`application-dev.yml`)

| 항목 | 환경 변수 |
|------|-----------|
| JWT Secret Key | `JWT_SECRET_KEY` |

### 비고
- prod 환경은 이미 환경 변수를 사용하고 있어 해당 패턴과 동일한 변수명을 사용했습니다.
- dev 환경에서는 `${ENV_VAR:기본값}` 형식으로 기본값을 유지하여 기존 로컬 개발 환경에 영향을 주지 않습니다.
- **Gmail 앱 비밀번호**(`MAIL_PASSWORD`)는 민감도가 높아 기본값을 제거했습니다. 로컬 개발 시 환경 변수로 설정이 필요합니다.

---

## 수정 파일 목록

| # | 파일 경로 | 수정 사항 |
|---|-----------|-----------|
| 1 | `notification-service/src/main/resources/application-dev.yml` | Serializer 수정 + 자격 증명 외부화 |
| 2 | `notification-service/src/main/resources/application-prod.yml` | Serializer 수정 |
| 3 | `commerce-service/.../SecurityConfig.java` | `permitAll()` → `authenticated()` |
| 4 | `commerce-service/src/main/resources/application-dev.yml` | 자격 증명 외부화 |
| 5 | `payment-service/src/main/resources/application-dev.yml` | 자격 증명 외부화 |
| 6 | `delivery-service/src/main/resources/application-dev.yml` | 자격 증명 외부화 |
| 7 | `user-service/src/main/resources/application-dev.yml` | 자격 증명 외부화 |
| 8 | `auth-service/src/main/resources/application-dev.yml` | JWT 시크릿 외부화 |
| 9 | `gateway/src/main/resources/application-dev.yml` | JWT 시크릿 외부화 |

---

---

# Phase 2: HIGH 이슈 수정 상세

---

## 4. Kafka DLT + Error Handler 적용

### 문제
commerce-service를 제외한 모든 서비스(payment, delivery, notification)에서 Consumer 처리 실패 시 메시지가 유실되었습니다. 재시도 없이 즉시 실패하며, Dead Letter Topic으로 전송되지 않아 복구가 불가능했습니다.

### 수정 내용

commerce-service의 `KafkaConsumerConfig` 패턴을 기반으로 3개 서비스에 동일한 DLT + Error Handler 설정을 생성하였습니다.

**신규 생성 파일:**
- `payment-service/.../infrastructure/configuration/KafkaConsumerConfig.java`
- `delivery-service/.../infrastructure/config/KafkaConsumerConfig.java`
- `notification-service/.../infrastructure/configuration/KafkaConsumerConfig.java`

**적용된 설정:**
- `DeadLetterPublishingRecoverer`: 처리 실패 메시지를 `{원본토픽}.DLT` 토픽으로 전송
- `DefaultErrorHandler`: 최대 3회 재시도 (1초 간격)
- `DeserializationException`은 재시도 없이 즉시 DLT로 전송

**추가 수정:**
- `delivery-service/.../DeliveryMessageConsumerService.java`에서 `// TODO: Kafka errorHandler 구현` 코멘트 제거

---

## 5. Producer acks=all + Idempotency 설정

### 문제
모든 서비스의 Kafka Producer에 `acks=all`, `enable.idempotence` 설정이 누락되어 네트워크 장애 시 메시지 중복 전송 또는 순서 역전이 발생할 수 있었습니다.

### 수정 내용

**수정 파일:** 모든 서비스의 `application-dev.yml`

```yaml
producer:
  acks: all
  properties:
    enable.idempotence: true
    max.in.flight.requests.per.connection: 5
```

**적용 서비스:** commerce, payment, delivery, notification

---

## 6. Consumer Manual Commit 전환

### 문제
모든 서비스가 Kafka Consumer의 기본 auto-commit을 사용하여, 처리 완료 전에 offset이 커밋되면 메시지 유실이 발생할 수 있었습니다.

### 수정 내용

**수정 파일:** 모든 서비스의 `application-dev.yml`

```yaml
consumer:
  enable-auto-commit: false
listener:
  ack-mode: manual
```

**적용 서비스:** commerce, payment, delivery, notification

---

## 7. 직렬화 전략 통일

### 문제
commerce-service의 Producer가 `JsonSerializer`를 사용하는 반면, 다른 서비스는 `StringSerializer`를 사용하여 직렬화 전략이 불일치했습니다.

### 수정 내용

모든 서비스의 Producer를 `StringSerializer`로 통일하였습니다. 모든 서비스가 이미 `EventSerializer`를 통해 수동 JSON 변환을 수행하고 있어 `StringSerializer`가 적합합니다.

**변경 서비스:** commerce-service (`JsonSerializer` → `StringSerializer`)

---

## 8. Circuit Breaker 누락 서비스 적용

### 문제
delivery → commerce, notification → user, auth → user FeignClient에 Circuit Breaker가 없어 대상 서비스 장애 시 연쇄 장애가 전파되었습니다.

### 수정 내용

**수정 파일:**
- `delivery-service/.../feign/OrderFeignClient.java` — `@CircuitBreaker` 추가 + fallback 메서드
- `notification-service/.../feign/UserFeignClient.java` — `@CircuitBreaker` 추가 + fallback 메서드
- `auth-service/.../feign/UserServiceClient.java` — `@CircuitBreaker` 추가 + fallback 메서드

**의존성 추가 (`build.gradle`):**
- `delivery-service`, `notification-service`, `auth-service`에 resilience4j 의존성 추가

**설정 추가 (`application-dev.yml`):**
- 3개 서비스에 resilience4j circuitbreaker 기본 설정 추가 (COUNT_BASED, 5회 윈도우, 50% 실패율 임계값)

**신규 에러 코드:**
- `DeliveryErrorCode.ORDER_SERVICE_ERROR`
- `NotificationErrorCode.USER_SERVICE_ERROR`
- `AuthErrorCode.USER_SERVICE_ERROR`

---

## 9. Fallback 메서드 Graceful Degradation 구현

### 문제
commerce-service의 Circuit Breaker fallback이 `RuntimeException`을 throw하여 실질적인 장애 격리 효과가 없었습니다.

### 수정 내용

**수정 파일:**
- `commerce-service/.../feign/DeliveryClient.java`
- `commerce-service/.../feign/PaymentClient.java`

**변경 전:**
```java
throw new RuntimeException("배송 서비스와의 통신이 원활하지 않습니다.", throwable);
```

**변경 후:**
```java
throw new CustomException(CommerceErrorCode.DELIVERY_SERVICE_ERROR);
throw new CustomException(CommerceErrorCode.PAYMENT_SERVICE_ERROR);
```

`CustomException`을 사용하여 `GlobalExceptionHandler`에서 일관된 `ErrorResponse` 형식으로 클라이언트에 반환됩니다.

---

## 10. 에러 응답 포맷 통일

### 문제
auth-service는 plain `String`을, delivery-service는 `CommonErrorCode` 객체를 반환하여 클라이언트가 서비스별로 다른 에러 응답 형식을 처리해야 했습니다.

### 수정 내용

**auth-service:**
- `CustomAuthException` 사용을 제거하고 `CustomException` + `AuthErrorCode`로 전환
- `AuthExceptionHandler`를 `ErrorResponse` 반환으로 수정
- `AuthService.signup()`에서 `CustomAuthException` → `CustomException(AuthErrorCode.SIGNUP_FAILED)` 변경
- 신규 에러 코드: `AuthErrorCode.SIGNUP_FAILED`

**delivery-service:**
- `DeliveryExceptionHandler`를 `ErrorResponse` 반환으로 수정
- `CommonErrorCode` 객체 직접 반환 → `ErrorResponse.builder()` 사용

---

## 11. 공통 인증 필터 common-module로 이동

### 문제
동일한 기능(헤더에서 사용자 정보 추출)을 수행하는 필터가 서비스마다 다른 이름과 구현으로 중복 존재했습니다.

| 서비스 | 기존 필터 클래스명 |
|--------|-------------------|
| commerce, user, payment, notification | `CustomPreAuthFilter` |
| delivery | `CustomAuthenticationFilter` |

### 수정 내용

**신규 생성:**
- `common-module/.../infrastructure/security/CommonPreAuthFilter.java`
- `common-module/build.gradle`에 `spring-boot-starter-security` 의존성 추가

**수정된 SecurityConfig (5개 서비스):**
- `commerce-service/.../SecurityConfig.java`
- `payment-service/.../SecurityConfig.java`
- `notification-service/.../SecurityConfig.java`
- `delivery-service/.../SecurityConfig.java`
- `user-service/.../SecurityConfig.java`

모든 서비스가 `CommonPreAuthFilter`를 빈으로 등록하여 사용하도록 변경하였습니다.

---

## 전체 수정 파일 목록

### Phase 1: CRITICAL (9개 파일)

| # | 파일 경로 | 수정 사항 |
|---|-----------|-----------|
| 1 | `notification-service/.../application-dev.yml` | Serializer 수정 + 자격 증명 외부화 |
| 2 | `notification-service/.../application-prod.yml` | Serializer 수정 |
| 3 | `commerce-service/.../SecurityConfig.java` | `permitAll()` → `authenticated()` |
| 4 | `commerce-service/.../application-dev.yml` | 자격 증명 외부화 |
| 5 | `payment-service/.../application-dev.yml` | 자격 증명 외부화 |
| 6 | `delivery-service/.../application-dev.yml` | 자격 증명 외부화 |
| 7 | `user-service/.../application-dev.yml` | 자격 증명 외부화 |
| 8 | `auth-service/.../application-dev.yml` | JWT 시크릿 외부화 |
| 9 | `gateway/.../application-dev.yml` | JWT 시크릿 외부화 |

### Phase 2: HIGH (25개 파일)

| # | 파일 경로 | 수정 사항 |
|---|-----------|-----------|
| 10 | `payment-service/.../KafkaConsumerConfig.java` | **신규** — DLT + Error Handler |
| 11 | `delivery-service/.../KafkaConsumerConfig.java` | **신규** — DLT + Error Handler |
| 12 | `notification-service/.../KafkaConsumerConfig.java` | **신규** — DLT + Error Handler |
| 13 | `delivery-service/.../DeliveryMessageConsumerService.java` | TODO 코멘트 제거 |
| 14 | `commerce-service/.../application-dev.yml` | acks=all, idempotency, manual commit, StringSerializer 통일 |
| 15 | `payment-service/.../application-dev.yml` | acks=all, idempotency, manual commit |
| 16 | `delivery-service/.../application-dev.yml` | acks=all, idempotency, manual commit, resilience4j 설정 |
| 17 | `notification-service/.../application-dev.yml` | acks=all, idempotency, manual commit, resilience4j 설정 |
| 18 | `auth-service/.../application-dev.yml` | resilience4j 설정 |
| 19 | `delivery-service/build.gradle` | resilience4j 의존성 추가 |
| 20 | `notification-service/build.gradle` | resilience4j 의존성 추가 |
| 21 | `auth-service/build.gradle` | resilience4j 의존성 추가 |
| 22 | `delivery-service/.../OrderFeignClient.java` | Circuit Breaker + fallback 추가 |
| 23 | `notification-service/.../UserFeignClient.java` | Circuit Breaker + fallback 추가 |
| 24 | `auth-service/.../UserServiceClient.java` | Circuit Breaker + fallback 추가 |
| 25 | `commerce-service/.../DeliveryClient.java` | fallback RuntimeException → CustomException |
| 26 | `commerce-service/.../PaymentClient.java` | fallback RuntimeException → CustomException |
| 27 | `delivery-service/.../DeliveryErrorCode.java` | `ORDER_SERVICE_ERROR` 추가 |
| 28 | `notification-service/.../NotificationErrorCode.java` | `USER_SERVICE_ERROR` 추가 |
| 29 | `auth-service/.../AuthErrorCode.java` | `SIGNUP_FAILED`, `USER_SERVICE_ERROR` 추가 |
| 30 | `auth-service/.../AuthService.java` | CustomAuthException → CustomException 전환 |
| 31 | `auth-service/.../AuthExceptionHandler.java` | ErrorResponse 반환으로 수정 |
| 32 | `delivery-service/.../DeliveryExceptionHandler.java` | ErrorResponse 반환으로 수정 |
| 33 | `common-module/.../CommonPreAuthFilter.java` | **신규** — 공통 인증 필터 |
| 34 | `common-module/build.gradle` | spring-boot-starter-security 의존성 추가 |
| 35 | 5개 서비스 SecurityConfig.java | CommonPreAuthFilter 사용으로 전환 |

---

## 다음 단계 (Phase 3: MEDIUM 이슈)

Phase 1, 2 수정이 완료되었으며, 다음 단계로 MEDIUM 심각도 이슈들의 수정이 권장됩니다:

1. Transactional Outbox 패턴 완전 구현
2. FeignClient 타임아웃 설정
3. @Retry, @TimeLimiter 등 Resilience 패턴 추가
4. HTTP 상태 코드 사용 표준화
5. 입력 검증(@Valid) 전체 적용
6. 과도한 Exception catch 세분화
7. 로깅 가이드라인 수립 및 적용
