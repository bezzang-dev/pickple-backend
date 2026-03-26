# Pickple Backend 코딩 컨벤션 리팩토링 완료 보고서

> 작성일: 2026-03-26
> 기반 문서: `docs/coding-convention-report.md`

---

## 목차

1. [리팩토링 요약](#1-리팩토링-요약)
2. [P1 - 즉시 수정 (완료)](#2-p1---즉시-수정-완료)
3. [P2 - 높은 우선순위 (완료)](#3-p2---높은-우선순위-완료)
4. [P3 - 점진적 개선 (완료)](#4-p3---점진적-개선-완료)
5. [미수행 항목 및 사유](#5-미수행-항목-및-사유)

---

## 1. 리팩토링 요약

| 우선순위 | 항목 수 | 완료 | 미수행 |
|---------|--------|------|--------|
| 🔴 P1   | 3      | 3    | 0      |
| 🟡 P2   | 4      | 4    | 0      |
| 🟢 P3   | 1      | 1    | 0      |

**변경된 파일 목록**

| 서비스 | 파일 | 변경 유형 |
|--------|------|----------|
| auth-service | `exception/CustomAuthException.java` | 삭제 |
| commerce-service | `exception/CommerceErrorCode.java` | 수정 |
| payment-service | `exception/PaymentErrorCode.java` | 수정 |
| delivery-service | `exception/DeliveryErrorCode.java` | 수정 |
| notification-service | `exception/NotificationErrorCode.java` | 수정 |
| notification-service | `exception/ChannelErrorCode.java` | 수정 |
| payment-service | `domain/model/PaymentStatusEnum.java` | 삭제 → `PaymentStatus.java`로 교체 |
| payment-service | `domain/model/PaymentStatus.java` | 신규 생성 |
| payment-service | `domain/model/Payment.java` | 참조 수정 |
| payment-service | `application/dto/PaymentRespDto.java` | 참조 수정 |
| payment-service | `messaging/events/PaymentCreateResponseEvent.java` | 불필요 import 제거 |
| payment-service | `test/.../PaymentServiceTest.java` | 참조 수정 |
| notification-service | `domain/model/NotificationCategoryEnum.java` | 삭제 → `NotificationCategory.java`로 교체 |
| notification-service | `domain/model/NotificationCategory.java` | 신규 생성 |
| notification-service | `domain/model/Notification.java` | 참조 수정 |
| delivery-service | `security/CustomAuthenticationFilter.java` | 삭제 → `CustomPreAuthFilter.java`로 교체 |
| delivery-service | `security/CustomPreAuthFilter.java` | 신규 생성 |
| delivery-service | `controller/DeliveryController.java` | Path Parameter 수정 |
| payment-service | `controller/PaymentController.java` | Path Parameter 수정 |
| commerce-service | `feign/PaymentClient.java` | Feign URL Path Parameter 수정 |
| notification-service | `exception/ChannelExceptionHandler.java` | 삭제 |

---

## 2. P1 - 즉시 수정 (완료)

### ✅ Dead Code 제거 - `CustomAuthException.java` 삭제

**변경 전**
```
auth-service/exception/
├── AuthErrorCode.java
├── AuthExceptionHandler.java
└── CustomAuthException.java  ← 미사용 Dead Code
```

**변경 후**
```
auth-service/exception/
├── AuthErrorCode.java
└── AuthExceptionHandler.java
```

**사유:** `CustomAuthException`은 정의만 되어 있고 서비스 코드 어디에서도 참조되지 않음.
실제 예외 처리는 `CustomException(AuthErrorCode.xxx)` 패턴으로 통일되어 있음.

---

### ✅ ErrorCode에 `@Getter` 통일 추가

**변경 전** - 서비스별 불일치

| 서비스 | `@Getter` 사용 | 수동 Override |
|--------|--------------|--------------|
| auth-service | ✅ | ❌ |
| user-service | ✅ | ❌ |
| commerce-service | ❌ | ✅ |
| payment-service | ❌ | ✅ |
| delivery-service | ❌ | ✅ |
| notification-service | ❌ | ✅ |

**변경 후** - 모든 서비스 `@Getter` 사용, 수동 Override 제거

대상 파일:
- `CommerceErrorCode.java` — `@Getter` 추가, 수동 `getMessage()`/`getStatus()` 제거
- `PaymentErrorCode.java` — `@Getter` 추가, 수동 override 제거
- `DeliveryErrorCode.java` — `@Getter` 추가, 수동 override 제거
- `NotificationErrorCode.java` — `@Getter` 추가, 수동 override 제거
- `ChannelErrorCode.java` — `@Getter` 추가, 수동 override 제거

**표준 템플릿**
```java
@Getter
@AllArgsConstructor
public enum {Service}ErrorCode implements ErrorCode {
    EXAMPLE_ERROR(HttpStatus.BAD_REQUEST, "오류 메시지입니다.");

    private final HttpStatus status;
    private final String message;
}
```

---

## 3. P2 - 높은 우선순위 (완료)

### ✅ Enum 네이밍 통일 - `PaymentStatusEnum` → `PaymentStatus`

**변경 파일:**
- `PaymentStatusEnum.java` 삭제
- `PaymentStatus.java` 신규 생성 (동일 내용 + `@Getter` 추가)

**변경된 참조:**
- `Payment.java` — `PaymentStatusEnum` → `PaymentStatus`
- `PaymentRespDto.java` — `PaymentStatusEnum` → `PaymentStatus`
- `PaymentServiceTest.java` — `PaymentStatusEnum` → `PaymentStatus`
- `PaymentCreateResponseEvent.java` — 불필요 import 제거

**변경 전**
```java
public enum PaymentStatusEnum {
    PENDING("결제 대기"),
    ...
    PaymentStatusEnum(String status) { ... }
}
```

**변경 후**
```java
@Getter
@AllArgsConstructor
public enum PaymentStatus {
    PENDING("결제 대기"),
    ...
    private final String status;
}
```

---

### ✅ Enum 네이밍 통일 - `NotificationCategoryEnum` → `NotificationCategory`

**변경 파일:**
- `NotificationCategoryEnum.java` 삭제
- `NotificationCategory.java` 신규 생성 (동일 내용 + `@Getter` 추가)

**변경된 참조:**
- `Notification.java` — `NotificationCategoryEnum` → `NotificationCategory`

---

### ✅ Security 필터명 통일 - `CustomAuthenticationFilter` → `CustomPreAuthFilter`

**변경 파일 (delivery-service):**
- `CustomAuthenticationFilter.java` 삭제
- `CustomPreAuthFilter.java` 신규 생성 (동일 로직)

**통일 후 전체 현황:**

| 서비스 | 필터 클래스명 |
|--------|-------------|
| common-module | `CommonPreAuthFilter` ✅ |
| user-service | `CustomPreAuthFilter` ✅ |
| payment-service | `CustomPreAuthFilter` ✅ |
| commerce-service | `CustomPreAuthFilter` ✅ |
| notification-service | `CustomPreAuthFilter` ✅ |
| delivery-service | `CustomPreAuthFilter` ✅ (변경됨) |

---

### ✅ Path Parameter 통일 - snake_case → camelCase

**변경 전 → 변경 후**

| 서비스 | 변경 전 | 변경 후 |
|--------|---------|--------|
| delivery-service | `/{delivery_id}` | `/{deliveryId}` |
| delivery-service | `@PathVariable("delivery_id") UUID deliveryId` | `@PathVariable UUID deliveryId` |
| payment-service | `/{payment_id}` | `/{paymentId}` |
| payment-service | `@PathVariable(name="payment_id") UUID paymentId` | `@PathVariable UUID paymentId` |
| payment-service (Feign) | `/getPaymentInfo/{order_id}` | `/getPaymentInfo/{orderId}` |
| commerce-service (Feign) | `@PathVariable("order_id") UUID orderId` | `@PathVariable UUID orderId` |

> 파라미터명과 path variable명이 동일한 경우 `@PathVariable` value 생략 (IDE hint 해소)

---

## 4. P3 - 점진적 개선 (완료)

### ✅ ExceptionHandler 병합 - `ChannelExceptionHandler` 삭제

notification-service에 `@RestControllerAdvice` 클래스가 2개 존재했음:
- `NotificationExceptionHandler` — notification-service 전용
- `ChannelExceptionHandler` — 채널 관련 전용 (빈 클래스, 확장 목적)

두 클래스 모두 `GlobalExceptionHandler`를 상속한 빈 클래스였으므로 `ChannelExceptionHandler` 삭제.
서비스당 ExceptionHandler 1개 원칙 준수.

**변경 후**
```
notification-service/exception/
├── ChannelErrorCode.java
├── NotificationErrorCode.java
└── NotificationExceptionHandler.java  (유일한 핸들러)
```

---

## 5. 미수행 항목 및 사유

아래 항목들은 영향 범위가 넓거나 런타임 동작 변경을 수반하여 별도 검토 후 적용을 권장합니다.

### 🔴 P1 - 루트 패키지명 통일

| 서비스 | 현재 | 제안 |
|--------|------|------|
| payment-service | `com.pickple.payment_service` | `com.pickple.payment` |
| commerce-service | `com.pickple.commerceservice` | `com.pickple.commerce` |
| notification-service | `com.pickple.notification_service` | `com.pickple.notification` |
| common-module | `com.pickple.common_module` | `com.pickple.common` |
| eureka-server | `com.pickple.eureka_server` | `com.pickple.eureka` |

**미수행 사유:** 루트 패키지명 변경은 모든 Java 파일의 `package` 선언 및 `import` 구문 일괄 수정이 필요하며, IDE의 Refactor > Rename Package 기능을 활용해야 안전하게 적용 가능. 수동 수정 시 누락 위험이 높아 별도 작업으로 분리.

### 🟡 P2 - DTO 네이밍 통일 (`RespDto` → `Response`, `ReqDto` → `Request`)

**미수행 사유:** DTO 클래스명 변경은 Controller, Service, Mapper, Test 등 다수 파일에 걸쳐 참조가 있어 영향 범위가 큼. 기능 변경 없이 네이밍만 바꾸는 작업이지만 누락 시 컴파일 오류가 발생하므로 서비스 단위로 순차 적용 권장.

### 🟡 P2 - `DeliveryApplicationService` → `DeliveryService` 리네임

**미수행 사유:** `DeliveryDetailApplicationService`도 함께 `DeliveryDetailService`로 변경해야 일관성이 유지됨. Controller, Consumer 등 참조 파일이 다수이므로 별도 작업으로 분리.

### 🟡 P2 - Config 패키지명 통일 (`configuration` → `config`)

**미수행 사유:** 패키지 이동을 수반하므로 모든 `import`를 일괄 수정해야 함. 루트 패키지명 변경 작업과 함께 묶어서 진행 권장.

### 🟢 P3 - 기타 구조적 개선

아래 항목들은 코드 동작에 영향 없는 구조적 개선으로, 팀 합의 후 점진적 적용 권장:

- `SecurityFilter` 위치 통일: user-service의 `application.security` → `infrastructure.security`
- Controller 위치 통일: notification의 `presentation/` → `presentation/controller/`
- Enum 위치 통일: 모두 `domain/enums/` 패키지로 이동
- Repository 메서드명 통일: `IsDeleteIsFalse` → `IsDeleteFalse`
- Custom Repository 구현체명 통일: `{Entity}RepositoryCustomImpl`
- Entity `@NoArgsConstructor(access = AccessLevel.PROTECTED)` 통일
- 소프트 삭제 메서드명 통일: `markAsDeleted(String deletedBy)`
- 모든 Controller에 한 줄 Javadoc 추가
- URL 리소스명 복수형 통일: `/notification` → `/notifications`

---

> 이 보고서는 `coding-convention-report.md`를 기반으로 실제 수행된 리팩토링 결과를 기록합니다.
