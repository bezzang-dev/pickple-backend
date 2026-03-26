# Pickple Backend 코딩 컨벤션 분석 및 제안 보고서

> 프로젝트 전체 코드를 분석하여 서비스 간 불일치를 식별하고, 통일된 컨벤션을 제안합니다.

---

## 목차

1. [현황 요약](#1-현황-요약)
2. [패키지 구조 컨벤션](#2-패키지-구조-컨벤션)
3. [네이밍 컨벤션](#3-네이밍-컨벤션)
4. [예외 처리 컨벤션](#4-예외-처리-컨벤션)
5. [API 설계 컨벤션](#5-api-설계-컨벤션)
6. [엔티티 & 리포지토리 컨벤션](#6-엔티티--리포지토리-컨벤션)
7. [로깅 컨벤션](#7-로깅-컨벤션)
8. [주석 컨벤션](#8-주석-컨벤션)
9. [우선순위별 리팩토링 체크리스트](#9-우선순위별-리팩토링-체크리스트)

---

## 1. 현황 요약

### 1.1 프로젝트 구성

| 서비스 | 역할 | DB |
|--------|------|-----|
| user-service | 사용자 관리 | PostgreSQL |
| auth-service | 인증/JWT | Redis |
| payment-service | 결제 처리 | PostgreSQL |
| commerce-service | 상품/주문/재고 | PostgreSQL, Redis, ElasticSearch |
| delivery-service | 배송 관리 | MongoDB |
| notification-service | 알림/이메일 | PostgreSQL |
| gateway | API 라우팅 | - |
| eureka-server | 서비스 디스커버리 | - |
| common-module | 공통 라이브러리 | - |

### 1.2 불일치 심각도 요약

| 영역 | 심각도 | 설명 |
|------|--------|------|
| 루트 패키지명 | 🔴 높음 | snake_case / camelCase / 단일단어 혼재 |
| Config 패키지명 | 🟡 중간 | `config` vs `configuration` |
| DTO 네이밍 | 🟡 중간 | `ResponseDto` vs `RespDto` vs `Response` |
| Security 필터명 | 🟡 중간 | `CustomPreAuthFilter` vs `CustomAuthenticationFilter` |
| Path Parameter | 🟡 중간 | `{delivery_id}` vs `{deliveryId}` |
| ErrorCode 중복 | 🔴 높음 | 동일 에러코드가 여러 서비스에 중복 정의 |
| 소프트 삭제 메서드명 | 🟡 중간 | `markAsDeleted()` vs `softDelete()` vs `delete()` |
| 로깅 상세도 | 🟢 낮음 | 서비스별 로깅 수준 차이 |

---

## 2. 패키지 구조 컨벤션

### 2.1 루트 패키지명

**현황 (불일치)**

| 서비스 | 현재 패키지명 | 문제 |
|--------|-------------|------|
| auth-service | `com.pickple.auth` | ✅ |
| user-service | `com.pickple.user` | ✅ |
| delivery-service | `com.pickple.delivery` | ✅ |
| gateway | `com.pickple.gateway` | ✅ |
| payment-service | `com.pickple.payment_service` | ❌ 언더스코어 |
| commerce-service | `com.pickple.commerceservice` | ❌ 두 단어 결합 |
| notification-service | `com.pickple.notification_service` | ❌ 언더스코어 |
| common-module | `com.pickple.common_module` | ❌ 언더스코어 |
| eureka-server | `com.pickple.eureka_server` | ❌ 언더스코어 |

**제안 규칙**

```
com.pickple.{단일단어 서비스명}
```

| 서비스 | 제안 패키지명 |
|--------|-------------|
| payment-service | `com.pickple.payment` |
| commerce-service | `com.pickple.commerce` |
| notification-service | `com.pickple.notification` |
| common-module | `com.pickple.common` |
| eureka-server | `com.pickple.eureka` |

> Java 패키지 명명 규칙상 언더스코어는 사용하지 않으며, 모두 소문자 단일 단어를 사용합니다.

---

### 2.2 계층별 패키지 구조

**제안 표준 구조**

```
com.pickple.{service}/
├── {ServiceName}Application.java
├── presentation/
│   ├── controller/          # REST Controller
│   └── dto/
│       ├── request/         # 요청 DTO
│       └── response/        # 응답 DTO
├── application/
│   ├── service/             # 비즈니스 로직
│   ├── dto/                 # 내부 DTO (서비스 간 전달용)
│   ├── mapper/              # DTO ↔ Entity 변환
│   └── events/              # 도메인 이벤트
├── domain/
│   ├── model/               # Entity, VO
│   ├── enums/               # 도메인 열거형
│   └── repository/          # Repository 인터페이스
├── infrastructure/
│   ├── config/              # 설정 클래스 (⚠️ configuration 아님)
│   ├── security/            # 보안 필터
│   ├── messaging/           # Kafka Producer/Consumer
│   ├── feign/               # Feign Client
│   └── repository/          # Repository 구현체
└── exception/
    ├── {Service}ErrorCode.java
    └── {Service}ExceptionHandler.java
```

**현재 불일치 및 수정 사항**

| 항목 | 현재 | 제안 |
|------|------|------|
| Config 패키지 | payment, commerce, notification: `configuration` | 모두 `config`로 통일 |
| Security 필터 위치 | user-service: `application.security` | `infrastructure.security`로 이동 |
| Controller 위치 | notification: `presentation/` 직하위 | `presentation.controller/`로 이동 |
| Enum 위치 | delivery: `domain.model.enums/`, 기타: `domain.model/` 직하위 | 모두 `domain.enums/`로 분리 |

---

## 3. 네이밍 컨벤션

### 3.1 DTO 클래스

**현황 (불일치)**

| 유형 | 사용 중인 패턴 | 서비스 |
|------|--------------|--------|
| 응답 DTO | `{Entity}ResponseDto` | auth, user, commerce, delivery |
| 응답 DTO | `{Entity}RespDto` | payment, notification |
| 요청 DTO | `{Action}RequestDto` | auth, user, commerce, delivery |
| 요청 DTO | `{Action}ReqDto` | commerce(일부), notification |
| 요청 DTO | `{Action}Request` | delivery(일부) |

**제안 규칙**

```
요청 DTO:  {Entity}{Action}Request      (예: ProductCreateRequest)
응답 DTO:  {Entity}Response              (예: ProductResponse)
내부 DTO:  {Entity}Dto                   (예: UserDto)
```

- `Dto` 접미사는 presentation 계층의 request/response에서 제거 (불필요한 중복)
- `Resp`, `Req` 약어 사용 금지 → 완전한 단어 사용
- application 계층의 내부 DTO에만 `Dto` 접미사 사용

### 3.2 Service 클래스

**현황 (불일치)**

| 서비스 | 클래스명 | 문제 |
|--------|---------|------|
| delivery-service | `DeliveryApplicationService` | ❌ 불필요한 Application 접미사 |
| commerce-service | `ProductCommandService`, `ProductQueryService` | CQRS 패턴 (여기만 적용) |
| 기타 | `{Entity}Service` | ✅ |

**제안 규칙**

```
기본:     {Entity}Service
CQRS 적용 시: {Entity}CommandService, {Entity}QueryService
이벤트:   {Entity}EventService
```

- `ApplicationService` 접미사 사용 금지 → `{Entity}Service`로 통일
- CQRS는 필요한 서비스에만 선택적 적용 (강제하지 않음)

### 3.3 Security 필터 클래스

**현황 (불일치)**

| 서비스 | 클래스명 |
|--------|---------|
| user, payment, commerce, notification | `CustomPreAuthFilter` |
| delivery | `CustomAuthenticationFilter` |
| common-module | `CommonPreAuthFilter` |

**제안 규칙**

```
공통 모듈:   CommonPreAuthFilter (유지)
각 서비스:   CustomPreAuthFilter (통일)
```

- delivery-service의 `CustomAuthenticationFilter` → `CustomPreAuthFilter`로 변경

### 3.4 Enum 클래스

**현황 (불일치)**

| 서비스 | 클래스명 | 문제 |
|--------|---------|------|
| payment | `PaymentStatusEnum` | ❌ Enum 접미사 |
| notification | `NotificationCategoryEnum` | ❌ Enum 접미사 |
| commerce | `OrderStatus` | ✅ |
| delivery | `DeliveryStatus`, `DeliveryType` | ✅ |
| user | `UserRole` | ✅ |

**제안 규칙**

```
{Entity}{개념}   (예: OrderStatus, DeliveryType, PaymentStatus)
```

- `Enum` 접미사 사용 금지 (이미 enum 키워드가 타입을 명시)
- `PaymentStatusEnum` → `PaymentStatus`
- `NotificationCategoryEnum` → `NotificationCategory`

---

## 4. 예외 처리 컨벤션

### 4.1 ErrorCode Enum

**현황 (불일치)**

| 항목 | Auth, User | Commerce, Payment, Delivery, Notification |
|------|-----------|------------------------------------------|
| `@Getter` | ✅ 사용 | ❌ 미사용 |
| `@AllArgsConstructor` | ✅ 사용 | ✅ 사용 |

**제안 표준 ErrorCode 템플릿**

```java
@Getter
@AllArgsConstructor
public enum {Service}ErrorCode implements ErrorCode {

    // 400 Bad Request
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "잘못된 입력값입니다."),

    // 404 Not Found
    {ENTITY}_NOT_FOUND(HttpStatus.NOT_FOUND, "{엔티티}을(를) 찾을 수 없습니다."),

    // 409 Conflict
    {ENTITY}_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 존재하는 {엔티티}입니다."),

    // 500 Internal Server Error
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String message;
}
```

규칙:
- 모든 ErrorCode에 `@Getter` 추가
- 에러 메시지는 한글, 마침표(`.`)로 종료
- 에러코드 네이밍: `UPPER_SNAKE_CASE`
- 주석으로 HTTP 상태 코드 그룹별 구분

### 4.2 ErrorCode 중복 제거

**현재 중복 사례**

```
AuthErrorCode.USER_NOT_FOUND     ↔  UserErrorCode.USER_NOT_FOUND
AuthErrorCode.ALREADY_SAME_ROLE  ↔  UserErrorCode.ALREADY_SAME_ROLE
CommerceErrorCode.PAYMENT_CREATE_FAILED (BAD_GATEWAY)
PaymentErrorCode.PAYMENT_CREATE_FAILED  (INTERNAL_SERVER_ERROR)  ← HttpStatus도 다름!
```

**제안 규칙**

- 각 서비스는 자신의 도메인 에러코드만 정의
- 타 서비스 호출 실패는 `{SERVICE}_CALL_FAILED` 패턴 사용
- 동일 에러코드가 여러 서비스에 정의되지 않도록 관리

```java
// ✅ commerce-service에서 payment 호출 실패
PAYMENT_SERVICE_CALL_FAILED(HttpStatus.BAD_GATEWAY, "결제 서비스 호출에 실패했습니다.")

// ❌ payment-service의 에러코드를 commerce에서 중복 정의하지 않음
```

### 4.3 ExceptionHandler

**현황 (불일치)**

| 서비스 | 추가 핸들러 | 개수 |
|--------|-----------|------|
| auth | `BadCredentialsException` 처리 | 1개 |
| delivery | `HttpMessageNotReadableException` 처리 | 1개 |
| notification | `NotificationExceptionHandler` + `ChannelExceptionHandler` | ❌ 2개 |
| 기타 | 빈 클래스 (상속만) | 1개 |

**제안 규칙**

```java
@RestControllerAdvice
public class {Service}ExceptionHandler extends GlobalExceptionHandler {
    // 서비스 특화 예외만 추가 (필요한 경우)
}
```

- 서비스당 ExceptionHandler는 **1개**만 유지
- notification의 `ChannelExceptionHandler` → `NotificationExceptionHandler`로 병합
- 불필요한 `CustomAuthException` (사용되지 않는 Dead Code) 삭제

---

## 5. API 설계 컨벤션

### 5.1 엔드포인트 URL 패턴

**제안 규칙**

```
Base URL:     /api/v1/{resources}          (복수형)
단건 조회:     /api/v1/{resources}/{id}
목록 조회:     /api/v1/{resources}
검색:          /api/v1/{resources}/search?keyword=xxx
하위 리소스:   /api/v1/{resources}/{id}/{sub-resources}
```

**현재 불일치 사례**

| 현재 | 제안 |
|------|------|
| `/api/v1/notification` (단수) | `/api/v1/notifications` (복수) |
| `/deliveries/carrier?value=xxx` | `/deliveries?carrier=xxx` |
| `/deliveries/status?value=xxx` | `/deliveries?status=xxx` |
| `/orders/{vendorId}/vendor` | `/vendors/{vendorId}/orders` |

### 5.2 Path Parameter 네이밍

**현황 (불일치)**

| 서비스 | 패턴 | 예시 |
|--------|------|------|
| delivery, payment | snake_case | `{delivery_id}`, `{payment_id}` |
| commerce | camelCase | `{productId}`, `{orderId}` |
| user | 필드명 | `{username}` |

**제안 규칙**

```
camelCase 통일: {deliveryId}, {paymentId}, {orderId}
```

### 5.3 API 응답 형식

**현재 상태: ✅ 잘 통일되어 있음**

모든 서비스가 `ApiResponse<T>` 래퍼를 사용하고 있어 일관성이 높습니다.

**제안 표준 패턴**

```java
// 조회 (200 OK)
return ResponseEntity.ok(
    ApiResponse.success(HttpStatus.OK, "조회 성공 메시지", data)
);

// 생성 (201 CREATED)
return ResponseEntity.status(HttpStatus.CREATED)
    .body(ApiResponse.success(HttpStatus.CREATED, "생성 성공 메시지", data));

// 삭제 (204 NO CONTENT)
return ResponseEntity.noContent().build();
```

### 5.4 응답 메시지 형식

**제안 규칙**

```
조회: "{엔티티} 조회에 성공하였습니다."
생성: "{엔티티}이(가) 생성되었습니다."
수정: "{엔티티}이(가) 수정되었습니다."
삭제: (204 No Content, 메시지 없음)
```

- 한글 사용, 높임말 통일 (`~하였습니다.`)
- 마침표(`.`)로 종료

---

## 6. 엔티티 & 리포지토리 컨벤션

### 6.1 Entity 클래스 표준 구조

**제안 템플릿 (JPA)**

```java
@Entity
@Table(name = "p_{entities}")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
@SQLRestriction("is_delete = false")
public class {Entity} extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID {entity}Id;

    // 필드 정의 ...

    // 비즈니스 메서드
    public void update({UpdateRequest} request) { ... }

    public void markAsDeleted(String deletedBy) {
        this.isDelete = true;
        this.deletedBy = deletedBy;
        this.deletedAt = LocalDateTime.now();
    }
}
```

**현재 불일치 및 수정 사항**

| 항목 | 현재 | 제안 |
|------|------|------|
| `@NoArgsConstructor` | 일부 `PROTECTED`, 일부 기본값 | 모두 `AccessLevel.PROTECTED` |
| `@AllArgsConstructor` | 일부 `PROTECTED`, 일부 기본값 | 모두 `AccessLevel.PRIVATE` |
| 소프트 삭제 메서드 | `markAsDeleted()` / `softDelete()` / `delete()` | `markAsDeleted(String deletedBy)`로 통일 |

### 6.2 Repository 컨벤션

**소프트 삭제 필터링 메서드명**

```
현재 혼재:
  findByUsernameAndIsDeleteFalse(...)         ← User
  findByPaymentIdAndIsDeleteIsFalse(...)      ← Payment

제안:
  모두 AndIsDeleteFalse 패턴 사용 (간결)
  findByPaymentIdAndIsDeleteFalse(...)
```

**Custom Repository 네이밍**

```
인터페이스:   {Entity}RepositoryCustom
구현체:       {Entity}RepositoryCustomImpl    (⚠️ Impl 접미사 필수 - Spring Data 규칙)
```

현재 불일치:
- `VendorRepositoryCustomImpl` ✅
- `ProductRepositoryImpl` ❌ → `ProductRepositoryCustomImpl`
- `UserRepositoryImpl` ❌ → `UserRepositoryCustomImpl`

**@Repository 어노테이션**

```
Custom Repository 구현체:  @Repository 불필요 (Spring Data가 자동 감지)
                           단, 일관성을 위해 모두 제거하거나 모두 추가
제안: 모두 제거 (불필요한 어노테이션)
```

### 6.3 Enum 표준 구조

**제안 템플릿**

```java
@Getter
@AllArgsConstructor
public enum {Entity}{Concept} {

    VALUE_ONE("설명"),
    VALUE_TWO("설명");

    private final String description;
}
```

- `Enum` 접미사 사용 금지
- `@Getter` 필수
- 위치: `domain.enums/` 패키지

---

## 7. 로깅 컨벤션

### 7.1 로거 선언

**현재 상태: ✅ 모든 서비스에서 `@Slf4j` 사용 (일관성 높음)**

### 7.2 로깅 레벨 기준

**제안 규칙**

| 레벨 | 용도 | 예시 |
|------|------|------|
| `log.info` | 비즈니스 흐름 추적 (시작, 완료) | `"주문이 생성되었습니다. orderId: {}"` |
| `log.warn` | 예상 가능한 예외, 폴백 동작 | `"재고 부족으로 주문 실패. productId: {}"` |
| `log.error` | 예상치 못한 오류, 시스템 장애 | `"결제 서비스 호출 실패"`, 예외 객체 포함 |
| `log.debug` | 개발/디버깅용 상세 정보 | `"요청 파라미터: {}"` |

### 7.3 로그 메시지 형식

**제안 표준 포맷**

```java
// 작업 시작
log.info("{작업}을 수행합니다. {식별자명}: {}", id);

// 작업 완료
log.info("{작업}이 완료되었습니다. {식별자명}: {}", id);

// 에러 (예외 객체 반드시 포함)
log.error("{작업}에 실패했습니다. {식별자명}: {}", id, exception);
```

규칙:
- 한글 사용, 높임말 통일
- 식별자(ID)는 반드시 로그에 포함
- `log.error`에는 예외 객체를 마지막 인자로 반드시 전달
- 민감 정보(비밀번호, 토큰 등) 로깅 금지

### 7.4 로깅 적용 범위

**제안: 모든 Service 클래스에서 최소한 다음 로깅 필수**

```java
@Transactional
public EntityResponse createEntity(CreateRequest request) {
    log.info("{엔티티} 생성 요청. 요청자: {}", username);
    // ... 비즈니스 로직
    log.info("{엔티티} 생성 완료. {엔티티}Id: {}", entity.getId());
    return response;
}
```

---

## 8. 주석 컨벤션

### 8.1 Javadoc

**현황 (불일치)**

| 서비스 | Controller Javadoc | Service Javadoc |
|--------|-------------------|-----------------|
| commerce | ✅ 모든 메서드 | ✅ 있음 |
| user | ✅ 있음 | ✅ 있음 |
| delivery | ❌ 없음 | ❌ 없음 |
| payment | ❌ 없음 | 일부 라인 주석 |
| auth | ❌ 없음 | ❌ 없음 |
| notification | ❌ 없음 | 일부 라인 주석 |

**제안 규칙**

```java
// Controller: 간단한 한 줄 Javadoc
/** 상품 생성 */
@PostMapping
public ResponseEntity<ApiResponse<ProductResponse>> createProduct(...)

// Service: 복잡한 비즈니스 로직에만 Javadoc
/**
 * 주문 생성 및 결제 요청
 * - 재고 차감 (Redisson 분산 락)
 * - Kafka로 결제 이벤트 발행
 */
@Transactional
public OrderResponse createOrder(...)
```

규칙:
- Controller 메서드: **한 줄 Javadoc 필수** (`/** 동작 설명 */`)
- Service 메서드: 단순 CRUD는 생략 가능, 복잡한 로직은 Javadoc 작성
- 언어: **한글**
- 라인 주석(`//`)은 코드 내 복잡한 로직 설명에만 사용

### 8.2 TODO/FIXME

```java
// TODO: 설명 - 담당자 (yyyy-MM-dd)
// FIXME: 설명 - 담당자 (yyyy-MM-dd)
```

---

## 9. 우선순위별 리팩토링 체크리스트

### 🔴 P1 - 즉시 수정 (코드 품질/일관성에 직접적 영향)

- [ ] 루트 패키지명 통일 (`com.pickple.{단일단어}`)
  - `payment_service` → `payment`
  - `commerceservice` → `commerce`
  - `notification_service` → `notification`
  - `common_module` → `common`
  - `eureka_server` → `eureka`
- [ ] ErrorCode 중복 제거 및 HttpStatus 불일치 수정
  - `CommerceErrorCode.PAYMENT_CREATE_FAILED` (BAD_GATEWAY) vs `PaymentErrorCode.PAYMENT_CREATE_FAILED` (INTERNAL_SERVER_ERROR)
- [ ] Dead Code 제거
  - `CustomAuthException.java` (auth-service, 미사용)

### 🟡 P2 - 높은 우선순위 (네이밍 일관성)

- [ ] Config 패키지명 통일: `configuration` → `config`
  - payment-service, commerce-service, notification-service
- [ ] DTO 네이밍 통일
  - `RespDto` → `Response`, `ReqDto` → `Request`
  - `Dto` 접미사 정리 (presentation 계층에서 제거)
- [ ] Path Parameter 통일: snake_case → camelCase
  - `{delivery_id}` → `{deliveryId}`
  - `{payment_id}` → `{paymentId}`
- [ ] Security 필터명 통일
  - delivery의 `CustomAuthenticationFilter` → `CustomPreAuthFilter`
- [ ] Enum 네이밍 통일
  - `PaymentStatusEnum` → `PaymentStatus`
  - `NotificationCategoryEnum` → `NotificationCategory`
- [ ] Service 클래스명 통일
  - `DeliveryApplicationService` → `DeliveryService`

### 🟢 P3 - 점진적 개선 (구조/품질)

- [ ] Security 필터 위치 통일: user-service의 `application.security` → `infrastructure.security`
- [ ] Controller 위치 통일: notification의 `presentation/` → `presentation.controller/`
- [ ] Enum 위치 통일: 모두 `domain.enums/` 패키지로 이동
- [ ] ExceptionHandler 통일: notification의 2개 → 1개로 병합
- [ ] Repository 메서드명 통일: `IsDeleteIsFalse` → `IsDeleteFalse`
- [ ] Custom Repository 구현체명 통일: `{Entity}RepositoryCustomImpl`
- [ ] Entity `@NoArgsConstructor(access = AccessLevel.PROTECTED)` 통일
- [ ] 소프트 삭제 메서드명 통일: `markAsDeleted(String deletedBy)`
- [ ] 모든 Controller에 한 줄 Javadoc 추가
- [ ] Service 로깅 수준 표준화 (특히 payment-service 보강)
- [ ] URL 리소스명 복수형 통일: `/notification` → `/notifications`
- [ ] ErrorCode에 `@Getter` 통일 추가

---

> 이 보고서의 제안 사항은 기존 코드의 다수결 패턴과 Java/Spring 생태계의 일반적 관례를 기반으로 작성되었습니다. 팀 내 합의를 거쳐 프로젝트에 맞게 조정하여 적용하시기 바랍니다.
