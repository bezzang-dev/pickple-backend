# Pickple Backend 리팩토링 분석 보고서

> 작성일: 2026-03-20
> 대상: pickple-backend (이커머스 MSA 프로젝트)
> 모듈: commerce-service, payment-service, delivery-service, notification-service, user-service, auth-service, gateway, eureka-server, common-module

---

## 목차

1. [요약](#1-요약)
2. [Kafka 메시지 유실 위험](#2-kafka-메시지-유실-위험)
3. [예외 처리 비일관성](#3-예외-처리-비일관성)
4. [장애 전파 방지 미흡](#4-장애-전파-방지-미흡)
5. [보안 취약점](#5-보안-취약점)
6. [코드 일관성 문제](#6-코드-일관성-문제)
7. [테스트 커버리지 부족](#7-테스트-커버리지-부족)
8. [우선순위별 리팩토링 로드맵](#8-우선순위별-리팩토링-로드맵)

---

## 1. 요약

본 프로젝트는 9개 모듈로 구성된 MSA 이커머스 시스템으로, 여러 개발자가 각각 모듈을 담당하여 개발한 결과 **일관성 부재, 예외 처리 미흡, Kafka 메시지 유실 가능성, 장애 전파 위험** 등의 문제가 발견되었습니다.

| 심각도 | 이슈 수 | 주요 영역 |
|--------|---------|-----------|
| **CRITICAL** | 5 | Kafka 설정 오류, 보안 설정 미흡, 자격 증명 노출 |
| **HIGH** | 8 | 에러 핸들러 미적용, Circuit Breaker 누락, 직렬화 불일치 |
| **MEDIUM** | 7 | 응답 포맷 불일치, 검증 부족, 로깅 불일치 |
| **LOW** | 4 | 테스트 부족, 비활성화된 테스트, 설정 하드코딩 |

---

## 2. Kafka 메시지 유실 위험

### 2.1 [CRITICAL] Notification-Service Producer Serializer 오류

**파일:** `notification-service/src/main/resources/application-dev.yml` (24-25행)

```yaml
producer:
  key-serializer: org.apache.kafka.common.serialization.StringDeserializer   # ← 잘못됨
  value-serializer: org.apache.kafka.common.serialization.StringDeserializer # ← 잘못됨
```

**문제:** Producer에 `StringDeserializer`가 설정되어 있어 메시지 전송 시 직렬화 실패가 발생합니다. `StringSerializer`로 수정해야 합니다.

---

### 2.2 [HIGH] Error Handler(DLT) 미적용 서비스

| 서비스 | DLT 설정 | Error Handler |
|--------|----------|---------------|
| commerce-service | O (`.DLT` 토픽) | O (3회 재시도, 1초 간격) |
| payment-service | **X** | **X** |
| delivery-service | **X** | **X** (TODO 코멘트만 존재) |
| notification-service | **X** | **X** |

**위험:** commerce-service를 제외한 모든 서비스에서 Consumer 처리 실패 시 메시지가 유실됩니다. 재시도 없이 즉시 실패하며, Dead Letter Topic으로 전송되지 않아 복구가 불가능합니다.

**delivery-service 코드 내 TODO:**
```java
// TODO: Kafka errorHandler 구현
```

**권장사항:** common-module에 공통 `KafkaConsumerConfig`를 생성하여 모든 서비스에 DLT + 재시도 전략을 일괄 적용

---

### 2.3 [HIGH] Producer Idempotency 미설정

모든 서비스의 Kafka Producer에 다음 설정이 누락되어 있습니다:

```yaml
# 누락된 설정
spring.kafka.producer:
  acks: all                    # 현재: 기본값(1) → 리더만 확인
  properties:
    enable.idempotence: true   # 중복 전송 방지
    max.in.flight.requests.per.connection: 5
```

**위험:** 네트워크 장애 시 메시지 중복 전송 또는 순서 역전이 발생할 수 있습니다.

---

### 2.4 [HIGH] Consumer Auto-Commit 사용

모든 서비스가 Kafka Consumer의 기본 auto-commit을 사용 중입니다.

**위험:** 메시지를 수신한 후 처리 완료 전에 offset이 커밋되면, 서비스 장애 시 해당 메시지는 재처리되지 않아 유실됩니다.

**권장사항:** Manual commit (MANUAL_ACK) 모드로 전환하여 처리 완료 후 명시적으로 커밋

```yaml
spring.kafka.consumer:
  enable-auto-commit: false
spring.kafka.listener:
  ack-mode: MANUAL_ACK
```

---

### 2.5 [HIGH] 직렬화/역직렬화 불일치

| 서비스 | Producer Serializer | Consumer Deserializer |
|--------|--------------------|-----------------------|
| commerce-service | `JsonSerializer` | `StringDeserializer` |
| payment-service | `StringSerializer` | `StringDeserializer` |
| delivery-service | `StringSerializer` | `StringDeserializer` |
| notification-service | `StringDeserializer`(오류) | `StringDeserializer` |

**문제:** Commerce-service의 Producer가 `JsonSerializer`를 사용하는 반면, 수신 측은 `StringDeserializer`를 사용합니다. 현재는 `EventSerializer`로 수동 변환하여 우회하고 있으나, 직렬화 전략이 서비스마다 다릅니다.

**권장사항:** 모든 서비스의 직렬화 전략을 통일 (String + EventSerializer 또는 Json 일괄 적용)

---

### 2.6 [MEDIUM] Outbox 패턴 불완전 구현

현재 `@TransactionalEventListener(phase = AFTER_COMMIT)` 패턴을 사용하여 DB 커밋 후 Kafka 전송을 보장하지만, **Kafka 전송 실패 시 복구 메커니즘이 없습니다.**

```
DB 커밋 성공 → Kafka 전송 실패 → 메시지 유실 (복구 불가)
```

**권장사항:** Outbox 테이블을 도입하여 메시지를 DB에 저장하고, 별도 스케줄러가 미전송 메시지를 재시도하는 완전한 Transactional Outbox 패턴 적용

---

## 3. 예외 처리 비일관성

### 3.1 [HIGH] 에러 응답 포맷 불일치

| 서비스 | Exception Handler | 응답 포맷 |
|--------|-------------------|-----------|
| common-module | `GlobalExceptionHandler` | `ErrorResponse` (구조화) |
| auth-service | `AuthExceptionHandler` | **plain String** (비일관) |
| delivery-service | `DeliveryExceptionHandler` | **CommonErrorCode 객체** (비일관) |
| 기타 서비스 | GlobalExceptionHandler 상속 | `ErrorResponse` |

**문제:** 클라이언트가 서비스별로 다른 에러 응답 형식을 처리해야 합니다.

**Auth-service 예시:**
```java
// 단순 String 반환 — ErrorResponse로 래핑되지 않음
@ExceptionHandler(CustomAuthException.class)
public ResponseEntity<String> handleCustomAuthException(CustomAuthException e) {
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(e.getMessage());
}
```

**권장사항:** 모든 서비스에서 common-module의 `GlobalExceptionHandler`와 `ErrorResponse`를 통일적으로 사용

---

### 3.2 [MEDIUM] 과도하게 넓은 Exception Catch

다음 서비스에서 `catch (Exception e)` 패턴으로 모든 예외를 포괄적으로 잡아 근본 원인이 은폐됩니다:

| 파일 | 위치 |
|------|------|
| `PaymentService.deletePayment()` | `DATABASE_ERROR`로 일괄 변환 |
| `NotificationService.deleteChannel()` | `DATABASE_ERROR`로 일괄 변환 |
| `DeliveryApplicationService.createDelivery()` | `DELIVERY_CREATE_FAILURE`로 일괄 변환 |

**권장사항:** 구체적인 예외 타입(DataAccessException, ConstraintViolationException 등)으로 세분화

---

### 3.3 [MEDIUM] Auth-service 자체 예외 체계

Auth-service만 `CustomAuthException`이라는 독자적 예외 클래스를 사용하며, common-module의 `CustomException`을 사용하지 않습니다.

**권장사항:** common-module의 `CustomException` + `ErrorCode` 인터페이스로 통일

---

## 4. 장애 전파 방지 미흡

### 4.1 [HIGH] Circuit Breaker 누락 서비스

| FeignClient | Circuit Breaker | Fallback |
|-------------|----------------|----------|
| commerce → delivery | O (`deliveryService`) | RuntimeException throw |
| commerce → payment | O (`paymentService`) | RuntimeException throw |
| delivery → commerce (`OrderFeignClient`) | **X** | **없음** |
| notification → user (`UserFeignClient`) | **X** | **없음** |
| auth → user (`UserServiceClient`) | **X** | **없음** |

**위험:** Circuit Breaker가 없는 FeignClient에서 대상 서비스 장애 시 호출 서비스까지 연쇄 장애가 전파됩니다.

---

### 4.2 [HIGH] Fallback 메서드의 잘못된 구현

Commerce-service의 Circuit Breaker fallback이 `RuntimeException`을 throw하여 실질적인 장애 격리 효과가 없습니다:

```java
// 현재 구현 — fallback에서 다시 예외를 던짐
private DeliveryDetailResponseDto getDeliveryInfoFallback(UUID deliveryId, Throwable t) {
    throw new RuntimeException("Delivery 서비스 호출 실패: " + t.getMessage());
}
```

**권장사항:** fallback에서는 캐시된 데이터 반환, 기본값 반환, 또는 CustomException으로 변환하여 graceful degradation 구현

---

### 4.3 [MEDIUM] Resilience 패턴 부재

| 패턴 | 적용 여부 |
|------|-----------|
| Circuit Breaker | 부분 적용 (commerce만) |
| Retry (`@Retry`) | **미적용** |
| Rate Limiter (`@RateLimiter`) | **미적용** |
| Bulkhead (`@Bulkhead`) | **미적용** |
| Timeout (`@TimeLimiter`) | **미적용** |

**권장사항:** FeignClient 호출에 `@Retry` + `@CircuitBreaker` 조합 적용, 외부 API 호출에 `@TimeLimiter` 적용

---

### 4.4 [MEDIUM] FeignClient 타임아웃 미설정

모든 FeignClient에 명시적 타임아웃이 설정되어 있지 않아, 대상 서비스 응답 지연 시 호출 스레드가 무한 대기할 수 있습니다.

**권장사항:**
```yaml
spring.cloud.openfeign.client.config:
  default:
    connectTimeout: 3000
    readTimeout: 5000
```

---

## 5. 보안 취약점

### 5.1 [CRITICAL] Commerce-service SecurityConfig 잘못된 설정

**파일:** `commerce-service/src/main/java/.../SecurityConfig.java` (42행)

```java
.anyRequest().permitAll()  // 나머지 요청은 인증 필요  ← 주석과 코드가 불일치!
```

**문제:** 주석은 "인증 필요"라고 되어있지만, 실제 코드는 `permitAll()`로 모든 요청을 허용합니다. `.authenticated()`로 변경해야 합니다.

---

### 5.2 [CRITICAL] 설정 파일 내 자격 증명 노출

| 서비스 | 노출 항목 | 파일 |
|--------|-----------|------|
| notification-service | Gmail 앱 비밀번호 | `application-dev.yml:31` |
| auth-service, gateway | JWT Secret Key | `application-dev.yml` |
| 모든 서비스 | DB 비밀번호 | `application-dev.yml` |

**권장사항:** 환경 변수 또는 Spring Cloud Config / Vault로 민감 정보 외부화

---

### 5.3 [HIGH] 인증 필터 구현 불일치

| 서비스 | 필터 클래스명 | 위치 |
|--------|-------------|------|
| commerce, user, payment, notification | `CustomPreAuthFilter` | 각 서비스 내 |
| delivery | `CustomAuthenticationFilter` | delivery 서비스 내 |

**문제:** 동일한 기능(헤더에서 사용자 정보 추출)을 수행하는 필터가 서비스마다 다른 이름과 구현으로 중복 존재합니다.

**권장사항:** common-module에 공통 인증 필터를 구현하고 모든 서비스에서 재사용

---

## 6. 코드 일관성 문제

### 6.1 [MEDIUM] HTTP 상태 코드 사용 불일치

- `OrderController.createOrder()` → `HttpStatus.CREATED` (201)
- 다른 생성 API → `HttpStatus.OK` (200)
- `PaymentController.deletePayment()` → `204 No Content` (ApiResponse 미사용)
- 다른 삭제 API → `200 OK` with ApiResponse body

**권장사항:** REST 규약에 따라 생성(201), 수정(200), 삭제(204) 등 통일

---

### 6.2 [MEDIUM] 입력 검증(Validation) 부족

| 서비스 | @Valid 사용 | DTO 검증 어노테이션 |
|--------|------------|-------------------|
| commerce-service | O | @NotNull, @NotBlank, @DecimalMin |
| delivery-service | O | @NotNull, @NotBlank |
| payment-service | **X** | **없음** |
| user-service | **X** | **없음** |
| auth-service | **X** | **없음** |

**권장사항:** 모든 API 입력 DTO에 JSR-380 검증 어노테이션 적용

---

### 6.3 [MEDIUM] 로깅 수준 불일치

- **Auth-service:** 로그인 시 username 로깅 (개인정보 노출 가능)
- **Commerce/User-service:** 서비스 레이어 로그 거의 없음 (디버깅 어려움)
- **Delivery-service:** 요청자 이름, 권한 정보 로깅

**권장사항:** 공통 로깅 가이드라인 수립 (민감 정보 마스킹, 주요 비즈니스 이벤트 로깅)

---

## 7. 테스트 커버리지 부족

### 7.1 [LOW] 전체 테스트 현황

| 서비스 | 테스트 파일 수 | 실질적 테스트 |
|--------|--------------|-------------|
| commerce-service | 3 | OrderServiceTest(대부분 @Disabled), StockServiceTest |
| delivery-service | 2 | DeliveryMongoRepositoryTest |
| 기타 서비스 | 각 1 | ApplicationTests (컨텍스트 로드만 확인) |

**주요 부재:**
- Controller 테스트 없음
- Kafka 메시징 통합 테스트 없음
- 분산 트랜잭션(Saga) 테스트 없음
- Exception Handler 테스트 없음
- 서비스 간 통합 테스트 없음

---

## 8. 우선순위별 리팩토링 로드맵

### Phase 1: 긴급 수정 (CRITICAL)
| # | 항목 | 대상 |
|---|------|------|
| 1 | Notification-service Producer Serializer 수정 | `application-dev.yml` |
| 2 | Commerce-service SecurityConfig `permitAll()` → `authenticated()` | `SecurityConfig.java` |
| 3 | 설정 파일 자격 증명 환경 변수 외부화 | 모든 `application-*.yml` |

### Phase 2: 안정성 강화 (HIGH)
| # | 항목 | 대상 |
|---|------|------|
| 4 | 모든 서비스에 Kafka DLT + Error Handler 적용 | payment, delivery, notification |
| 5 | Producer `acks=all` + idempotency 설정 | 모든 서비스 |
| 6 | Consumer Manual Commit 전환 | 모든 서비스 |
| 7 | 직렬화 전략 통일 | 모든 서비스 |
| 8 | 누락된 FeignClient에 Circuit Breaker 적용 | delivery, notification, auth |
| 9 | Fallback 메서드 graceful degradation 구현 | commerce-service |
| 10 | 에러 응답 포맷 통일 (GlobalExceptionHandler) | auth, delivery |
| 11 | 공통 인증 필터 common-module로 이동 | 모든 서비스 |

### Phase 3: 품질 개선 (MEDIUM)
| # | 항목 | 대상 |
|---|------|------|
| 12 | Transactional Outbox 패턴 완전 구현 | 모든 Kafka 발행 서비스 |
| 13 | FeignClient 타임아웃 설정 | 모든 서비스 |
| 14 | @Retry, @TimeLimiter 등 Resilience 패턴 추가 | FeignClient 호출부 |
| 15 | HTTP 상태 코드 사용 표준화 | 모든 Controller |
| 16 | 입력 검증(@Valid) 전체 적용 | payment, user, auth |
| 17 | 과도한 Exception catch 세분화 | payment, notification, delivery |
| 18 | 로깅 가이드라인 수립 및 적용 | 모든 서비스 |

### Phase 4: 테스트 보강 (LOW)
| # | 항목 | 대상 |
|---|------|------|
| 19 | Kafka 메시징 통합 테스트 작성 | 모든 Kafka 사용 서비스 |
| 20 | Controller 단위 테스트 작성 | 모든 서비스 |
| 21 | Saga 플로우 E2E 테스트 작성 | commerce ↔ payment ↔ delivery |
| 22 | 비활성화된 테스트 복원 또는 삭제 | commerce-service |

---

> 본 보고서는 코드 정적 분석을 기반으로 작성되었으며, 런타임 환경에서의 추가 검증이 필요할 수 있습니다.
