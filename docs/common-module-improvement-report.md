# Common-Module 개선 보고서

> 작성일: 2026-03-19
> 대상 브랜치: develop

---

## 개요

이 보고서는 `common-module`에 대한 코드 분석 결과를 바탕으로, 두 가지 핵심 개선 사항을 구현한 내용을 기술합니다.

1. **EventSerializer 중복 제거 및 안정성 개선**
2. **GlobalExceptionHandler 공통화**

---

## 1. EventSerializer 중복 제거 및 안정성 개선

### 문제

`commerce-service`에 별도의 `EventSerializer`가 중복 구현되어 있었습니다.

| 항목 | common-module | commerce-service (삭제됨) |
|------|--------------|--------------------------|
| 위치 | `common_module/infrastructure/messaging/EventSerializer.java` | `commerceservice/infrastructure/configuration/EventSerializer.java` |
| `objectMapper` 접근 제어자 | `public static final` (외부 변경 가능) | `private static final` |
| 에러 메시지 언어 | 영어 (`"Serialization error"`) | 한국어 (`"직렬화 중 오류 발생"`) |
| 로깅 | 없음 | 없음 |
| JavaTimeModule | 미등록 | 미등록 |

두 구현이 존재함으로써 다음 문제가 발생했습니다.
- `LocalDateTime` 등 Java 8 날짜 타입 직렬화 시 예외 발생 가능 (JavaTimeModule 미등록)
- 에러 메시지 불일치로 로그 분석 혼란
- 동일 기능의 중복 유지보수

### 변경 내용

#### `common-module/infrastructure/messaging/EventSerializer.java` 개선

```java
// Before
public class EventSerializer {
    public static final ObjectMapper objectMapper = new ObjectMapper(); // public → 외부 변경 가능

    public static <T> String serialize(T object) {
        try {
            return objectMapper.writeValueAsString(object);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Serialization error", e); // 로깅 없음, 영어 메시지
        }
    }
    // ...
}

// After
public class EventSerializer {
    private static final Logger log = LoggerFactory.getLogger(EventSerializer.class);

    private static final ObjectMapper objectMapper = new ObjectMapper() // private으로 변경
            .registerModule(new JavaTimeModule())                       // LocalDateTime 지원
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);  // ISO 8601 포맷

    private EventSerializer() {} // 인스턴스화 방지

    public static <T> String serialize(T object) {
        try {
            return objectMapper.writeValueAsString(object);
        } catch (JsonProcessingException e) {
            log.error("직렬화 중 오류 발생: type={}", object.getClass().getName(), e); // 로깅 추가
            throw new RuntimeException("직렬화 중 오류 발생: " + e.getMessage(), e);
        }
    }
    // ...
}
```

#### commerce-service 중복 파일 삭제 및 import 교체

| 파일 | 변경 전 import | 변경 후 import |
|------|--------------|--------------|
| `OrderMessagingConsumerService.java` | `commerceservice.infrastructure.configuration.EventSerializer` | `common_module.infrastructure.messaging.EventSerializer` |
| `TemporaryStorageService.java` | `commerceservice.infrastructure.configuration.EventSerializer` | `common_module.infrastructure.messaging.EventSerializer` |

삭제된 파일:
- `commerce-service/src/main/java/com/pickple/commerceservice/infrastructure/configuration/EventSerializer.java`

### 개선 효과

- `ObjectMapper`가 `private`으로 변경되어 외부에서 설정을 임의 변경할 수 없음
- `JavaTimeModule` 등록으로 `LocalDateTime`, `LocalDate` 등 날짜 타입 직렬화 안정성 확보
- 직렬화 실패 시 로그에 대상 타입명이 기록되어 디버깅 용이
- 6개 서비스 전체가 동일한 직렬화 동작을 보장

---

## 2. GlobalExceptionHandler 공통화

### 문제

`CustomException`과 `MethodArgumentNotValidException` 처리 코드가 5개 서비스에 거의 동일하게 반복되어 있었습니다.

```
auth-service/     → AuthExceptionHandler.java
user-service/     → UserExceptionHandler.java         ← 동일 코드
payment-service/  → PaymentExceptionHandler.java      ← 동일 코드
delivery-service/ → DeliveryExceptionHandler.java     ← 부분 중복
commerce-service/ → CommerceExceptionHandler.java     ← 동일 코드
notification-service/ → NotificationExceptionHandler.java ← 동일 코드
notification-service/ → ChannelExceptionHandler.java  ← 동일 코드
```

문제점:
- 예외 응답 형식을 수정할 경우 7개 파일을 모두 수정해야 함
- `MethodArgumentNotValidException` 처리에서 에러 메시지 포맷이 서비스마다 미묘하게 다름 (`e.getMessage()` vs `CommonErrorCode.INVALID_INPUT_VALUE.getMessage()`)
- `Throwable` fallback 핸들러가 commerce-service에만 존재하여 다른 서비스는 미처리 예외가 500 응답 없이 전파될 수 있음

### 변경 내용

#### 신규: `common-module/presentation/advice/GlobalExceptionHandler.java`

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // CustomException → ErrorResponse 반환
    @ExceptionHandler(CustomException.class)
    public ResponseEntity<ErrorResponse> handleCustomException(CustomException e) {
        log.warn("CustomException 발생: code={}, message={}", ...);
        return ResponseEntity
                .status(e.getErrorCode().getStatus())
                .body(ErrorResponse.error(e.getErrorCode()));
    }

    // @Valid 검증 실패 → 필드별 에러 반환
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        ErrorResponse errorResponse = ErrorResponse.builder()
                .status(HttpStatus.BAD_REQUEST)
                .message(CommonErrorCode.INVALID_INPUT_VALUE.getMessage())
                .build();
        e.getBindingResult().getFieldErrors().forEach(error ->
                errorResponse.addValidation(error.getField(), error.getDefaultMessage())
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }

    // 처리되지 않은 모든 예외 → 500 응답 (fallback)
    @ExceptionHandler(Throwable.class)
    public ResponseEntity<ErrorResponse> handleThrowable(Throwable e) {
        log.error("처리되지 않은 예외 발생", e);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.error(CommonErrorCode.INTERNAL_SERVER_ERROR));
    }
}
```

#### 각 서비스 ExceptionHandler 변경 현황

| 서비스 | 변경 전 | 변경 후 | 사유 |
|--------|--------|--------|------|
| `user-service` | `CustomException` + `MethodArgumentNotValidException` | **파일 삭제** | GlobalExceptionHandler로 완전 대체 |
| `payment-service` | `CustomException` | **파일 삭제** | GlobalExceptionHandler로 완전 대체 |
| `notification-service` | `NotificationExceptionHandler` + `ChannelExceptionHandler` | **두 파일 모두 삭제** | GlobalExceptionHandler로 완전 대체 |
| `commerce-service` | `CustomException` + `MethodArgumentNotValidException` + `Throwable` | **파일 삭제** | GlobalExceptionHandler로 완전 대체 |
| `auth-service` | `CustomAuthException` + `MethodArgumentNotValidException` | `CustomAuthException`만 유지 | `CustomAuthException`은 서비스 고유 예외 |
| `delivery-service` | `CustomException` + `MethodArgumentNotValidException` + `HttpMessageNotReadableException` | `HttpMessageNotReadableException`만 유지 | 필드명 추출 로직은 서비스 고유 처리 |

### 개선 효과

- 예외 응답 형식 변경 시 `GlobalExceptionHandler.java` 한 곳만 수정
- 모든 서비스의 `MethodArgumentNotValidException` 응답 메시지가 `CommonErrorCode.INVALID_INPUT_VALUE`로 통일
- 처리되지 않은 예외에 대한 `Throwable` fallback이 모든 서비스에 적용
- 삭제된 파일 수: **7개** → 코드베이스 200줄 이상 감소

---

## 변경 파일 요약

### 신규 생성
- `common-module/src/main/java/com/pickple/common_module/presentation/advice/GlobalExceptionHandler.java`

### 수정
- `common-module/src/main/java/com/pickple/common_module/infrastructure/messaging/EventSerializer.java`
- `auth-service/src/main/java/com/pickple/auth/exception/AuthExceptionHandler.java`
- `delivery-service/src/main/java/com/pickple/delivery/exception/DeliveryExceptionHandler.java`
- `commerce-service/src/main/java/com/pickple/commerceservice/infrastructure/messaging/OrderMessagingConsumerService.java`
- `commerce-service/src/main/java/com/pickple/commerceservice/infrastructure/redis/TemporaryStorageService.java`

### 삭제
- `commerce-service/src/main/java/com/pickple/commerceservice/infrastructure/configuration/EventSerializer.java`
- `user-service/src/main/java/com/pickple/user/exception/UserExceptionHandler.java`
- `payment-service/src/main/java/com/pickple/payment_service/exception/PaymentExceptionHandler.java`
- `notification-service/src/main/java/com/pickple/notification_service/exception/NotificationExceptionHandler.java`
- `notification-service/src/main/java/com/pickple/notification_service/exception/ChannelExceptionHandler.java`
- `commerce-service/src/main/java/com/pickple/commerceservice/exception/CommerceExceptionHandler.java`

---

## 추가 개선 권고사항 (미적용)

다음 항목들은 이번 개선 범위에 포함되지 않았으나, 추후 검토를 권장합니다.

| 항목 | 내용 | 우선순위 |
|------|------|--------|
| `PaginatedApiResponse<T>` 추가 | 현재 `ApiResponse<T>`는 단건만 지원. 페이지 응답 래퍼를 각 서비스에서 별도 구현 중 | 중 |
| `BaseEntity` 소프트 삭제 메서드 | `softDelete(String deletedBy)` 편의 메서드 추가로 서비스 코드 간소화 | 중 |
| 단위 테스트 추가 | `EventSerializer`, `ErrorResponse`, `GlobalExceptionHandler` 테스트 코드 부재 | 중 |
| `DomainEvent` 인터페이스 | 이벤트 타입 안전성을 위한 기반 인터페이스 정의 | 낮음 |
