# Delivery 도메인 객체 설계 분석 보고서

> **분석 대상**: `delivery-service` 모듈
> **핵심 파일**: `domain/model/Delivery.java`, `application/service/DeliveryApplicationService.java`
> **분석 기준 브랜치**: `feature/order-cqrs`

---

## 발견된 문제 요약

| 심각도 | 건수 |
|--------|------|
| 심각   | 2    |
| 주요   | 6    |
| 보통   | 3    |
| 경미   | 3    |

---

## 1. 도메인 구조 개요

### 1.1 레이어드 아키텍처 패키지 맵

```
delivery-service
├── presentation/
│   └── controller/DeliveryController.java
├── application/
│   ├── service/
│   │   ├── DeliveryApplicationService.java
│   │   └── DeliveryDetailApplicationService.java
│   ├── dto/request/         # DeliveryCreateRequestDto 등 요청 DTO
│   ├── dto/response/        # DeliveryInfoResponseDto 등 응답 DTO
│   ├── mapper/              # DeliveryMapper, DeliveryDetailMapper
│   ├── events/              # DeliveryCreateResponseEvent, DeliveryEndEvent 등
│   └── port/OrderClient.java
├── domain/
│   ├── model/
│   │   ├── Delivery.java          ← 집계 루트 (분석 핵심)
│   │   ├── DeliveryDetail.java    ← 임베디드 도큐먼트
│   │   ├── BaseEntity.java        ← 감사 필드
│   │   ├── enums/
│   │   │   ├── DeliveryStatus.java    (PENDING, IN_TRANSIT, DELIVERED)
│   │   │   ├── DeliveryCarrier.java   (LOZEN, CJ_LOGISTICS, HAJIN, EPOST)
│   │   │   └── DeliveryType.java      (DIRECT, COURIER, EXPRESS)
│   │   └── deleted/
│   │       ├── DeliveryDeleted.java
│   │       └── DeliveryDetailDeleted.java
│   └── repository/
│       └── DeliveryRepository.java    ← 도메인 포트 인터페이스
└── infrastructure/
    ├── repository/DeliveryMongoRepository.java  ← 어댑터 구현체
    ├── messaging/
    │   ├── DeliveryMessageConsumerService.java
    │   └── DeliveryMessageProducerService.java
    ├── feign/OrderFeignClient.java
    └── config/RedisConfig.java
```

### 1.2 MongoDB 컬렉션 구조

```
p_deliveries (Delivery)
  └── delivery_details: [DeliveryDetail]  ← 임베디드 (별도 컬렉션 없음)

p_deliveries_deleted (DeliveryDeleted)
  └── delivery_details: [DeliveryDetailDeleted]
```

### 1.3 서비스 간 통신 흐름

```
[Order/Commerce Service]
    │
    ├─ Kafka: delivery-create-request ──────► [DeliveryMessageConsumerService]
    │                                                    │
    │                                              createDelivery()
    │                                                    │
    │◄─ Kafka: delivery-create-response ─────────────────┘
    │
    │◄─ Kafka: delivery-end-response ◄────── endDelivery() / deleteDelivery()
    │
[Admin/Vendor HTTP] ──► [DeliveryController]
                              │
                         startDelivery()
                         endDelivery()
                              │
                         ──► Feign (동기) ──► [Order Service] (알림용 username 조회)
                         ──► Kafka ──────────► [Notification Service] (이메일 알림)
```

---

## 2. 도메인 모델 상세 분석

### 2.1 Delivery 집계 루트 — 필드 정의

| Java 필드 | MongoDB 필드 | 타입 | 초기값 | 비고 |
|-----------|-------------|------|--------|------|
| `deliveryId` | `delivery_id` | `UUID` | `UUID.randomUUID()` | `@Id`, `Persistable` 구현 |
| `orderId` | `order_id` | `UUID` | `null` | Order Service 외래키 역할 |
| `carrierId` | `carrier_id` | `String` | `null` | `DeliveryCarrier.companyId`와 중복 ⚠️ |
| `carrierName` | `carrier_name` | `String` | `null` | `DeliveryCarrier.companyName`과 중복 ⚠️ |
| `deliveryType` | `delivery_type` | `DeliveryType` | `null` | enum: DIRECT/COURIER/EXPRESS |
| `deliveryStatus` | `delivery_status` | `DeliveryStatus` | `PENDING` | 상태 머신 현재 상태 |
| `deliveryRequirement` | `delivery_requirement` | `String` | `null` | 배송 요청사항 (자유 문자열) |
| `trackingNumber` | `tracking_number` | `String` | `null` | 배송 시작 시 할당 |
| `recipientName` | `recipient_name` | `String` | `null` | 개인정보 |
| `recipientAddress` | `recipient_address` | `String` | `null` | 개인정보 |
| `recipientContact` | `recipient_contact` | `String` | `null` | 개인정보, 포맷 검증 없음 ⚠️ |
| `deliveryDetails` | `delivery_details` | `List<DeliveryDetail>` | `new ArrayList<>()` | 임베디드 배송 경로 이력 |

### 2.2 DeliveryDetail 임베디드 도큐먼트

```java
public class DeliveryDetail {
    private String deliveryDetailStatus;       // ⚠️ String 타입 — Enum 미사용
    private String deliveryDetailDescription;
    private Date deliveryDetailTime;           // ⚠️ java.util.Date — 가변 타입
}
```

`deliveryDetailStatus`는 배송 경로의 상태를 나타내지만 자유 문자열로 저장되어
어떤 값이든 입력 가능합니다. `DeliveryStatus` enum처럼 전용 Enum이 없습니다.

### 2.3 BaseEntity 감사 필드

```java
public abstract class BaseEntity implements Serializable {
    @CreatedDate  private Date createdAt;   // ⚠️ java.util.Date
    @LastModifiedDate  private Date updatedAt;
    @CreatedBy  private String createdBy;
    @LastModifiedBy  private String updatedBy;
}
```

`DeliveryDeleted`는 `deletedAt`을 `Instant` 타입으로 선언하여
`BaseEntity`의 `Date` 타입과 불일치합니다.

### 2.4 배송 상태 머신

```
             [배송 생성]
                 │
            PENDING (배송준비중)
                 │
                 │ startDelivery()
                 │  [검증: status != PENDING → DELIVERY_ALREADY_START]
                 ▼
           IN_TRANSIT (배송중)
                 │
                 │ endDelivery()
                 │  [검증: status == PENDING → DELIVERY_NOT_STARTED]
                 │  [검증: status == DELIVERED → DELIVERY_ALREADY_DELIVERED]
                 ▼
           DELIVERED (배송완료)
```

**현재 문제**: 상태 전이 검증 로직이 모두 `DeliveryApplicationService`에 위치합니다.
도메인 메서드(`startDelivery()`, `endDelivery()`) 자체에는 검증이 없습니다.

### 2.5 Enum 설계

```java
// DeliveryCarrier — 외부 배송 추적 API와 연동되는 companyId 보유
public enum DeliveryCarrier {
    LOZEN("로젠택배", "kr.logen"),
    CJ_LOGISTICS("CJ대한통운", "kr.cjlogistics"),
    HAJIN("한진택배", "kr.hanjin"),
    EPOST("우체국택배", "kr.epost");

    private final String companyName;  // Delivery.carrierName과 중복
    private final String companyId;    // Delivery.carrierId와 중복
}
```

---

## 3. 서비스 레이어 분석

### 3.1 오퍼레이션 일람표

| 메서드 | 트랜잭션 | 캐시 | Kafka 발행 | Feign 호출 | 비고 |
|--------|---------|------|-----------|-----------|------|
| `createDelivery` | `@Transactional` | - | `delivery-create-response` | - | Kafka 커밋 전 발행 ⚠️ |
| `updateDelivery` | `@Transactional` | - | - | - | null 덮어쓰기 위험 ⚠️ |
| `startDelivery` | `@Transactional(readOnly=true)` | - | - | `OrderClient` | **버그**: 쓰기 수행 🔴 |
| `endDelivery` | `@Transactional` | `@CachePut` | `delivery-end-response`, `email-create-request` | `OrderClient` | - |
| `deleteDelivery` | `@Transactional` | - | `delivery-end-response` | - | `log.error` 오용 ⚠️ |
| `getDeliveryInfo` | `@Transactional(readOnly)` | 수동 Redis 조회 | - | - | `@Cacheable` 미사용 |
| `getDeliveryInfoByOrderId` | `@Transactional(readOnly)` | - | - | - | 반환 타입 불일치 ⚠️ |

### 3.2 소프트 삭제 패턴

삭제 시 `p_deliveries` → `p_deliveries_deleted` 이동하는 아카이브 패턴을 사용합니다.
`DeliveryDeleted.fromDelivery()` 정적 팩토리 메서드를 활용하는 설계는 긍정적입니다.

### 3.3 Redis 캐시 전략

- `endDelivery()`: `@CachePut`으로 완료 시점에 캐시 갱신
- `getDeliveryInfo()`: `redisTemplate.opsForValue().get()`으로 수동 조회
- `createDeliveryDetail()`: `@CachePut`으로 경로 추가 후 캐시 갱신

`@Cacheable`과 수동 `redisTemplate` 조회가 혼용되어 캐시 전략이 일관적이지 않습니다.

---

## 4. 발견된 문제 목록

---

### 🔴 [심각] 4.1 — `startDelivery()` @Transactional(readOnly=true) 버그

**위치**: [DeliveryApplicationService.java:106-125](../src/main/java/com/pickple/delivery/application/service/DeliveryApplicationService.java#L106)

**현상**

```java
@Transactional(readOnly = true)  // ← readOnly인데...
public DeliveryStartResponseDto startDelivery(DeliveryStartRequestDto dto) {
    Delivery delivery = deliveryRepository.findById(dto.getDeliveryId()).orElseThrow();
    delivery.startDelivery(carrierId, dto.getDeliveryType(), dto);  // 상태 변경
    return DeliveryMapper.convertEntityToStartResponseDto(
        deliveryRepository.save(delivery)  // ← 쓰기 작업 수행!
    );
}
```

**영향**

- MongoDB Replica Set 환경에서 `readOnly=true`는 읽기를 Secondary 노드로 라우팅합니다.
  Secondary에 `save()`를 시도하면 쓰기 실패가 발생합니다.
- 로컬 단일 노드에서는 동작하지만 프로덕션 환경에서 장애 위험이 있습니다.
- 코드의 의도와 실제 동작이 불일치하여 유지보수 시 혼란을 줍니다.

**권장 방향**

```java
@Transactional  // readOnly 제거
public DeliveryStartResponseDto startDelivery(DeliveryStartRequestDto dto) { ... }
```

---

### 🔴 [심각] 4.2 — 트랜잭션-Kafka 이중 커밋 문제 (2PC)

**위치**: [DeliveryApplicationService.java:72-90](../src/main/java/com/pickple/delivery/application/service/DeliveryApplicationService.java#L72)

**현상**

```java
@Transactional
public void createDelivery(@Valid DeliveryCreateRequestDto dto) {
    delivery = deliveryRepository.save(Delivery.createFrom(dto));  // DB 저장 (아직 커밋 전)

    // ↓ DB 트랜잭션이 커밋되기 전에 Kafka 메시지 발행
    deliveryMessageProducerService.sendMessage(deliveryCreateResponseTopic,
            EventSerializer.serialize(deliveryCreateResponseEvent));
}
```

`endDelivery()` (L127-153)에도 동일한 패턴이 존재합니다.

**영향**

DB 커밋과 Kafka 발행은 원자적이지 않습니다. 다음 시나리오가 발생할 수 있습니다:

1. Kafka 발행 성공 → DB 커밋 실패 → **Order Service는 이미 `deliveryId`를 수신했지만 실제 배송 데이터는 없음**
2. DB 커밋 성공 → Kafka 발행 실패 → **Order Service가 배송 생성 완료를 알지 못함** (주문 처리 중단)

**권장 방향**

```java
// 방법 1: @TransactionalEventListener 활용
@Transactional
public void createDelivery(@Valid DeliveryCreateRequestDto dto) {
    Delivery delivery = deliveryRepository.save(Delivery.createFrom(dto));
    applicationEventPublisher.publishEvent(
        new DeliveryCreatedEvent(delivery.getOrderId(), delivery.getDeliveryId())
    );
}

@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void onDeliveryCreated(DeliveryCreatedEvent event) {
    deliveryMessageProducerService.sendMessage(topic, ...);
}

// 방법 2: Transactional Outbox Pattern 도입
// DB에 outbox 테이블/컬렉션을 두고, 별도 폴러가 Kafka 발행 후 처리 완료 표시
```

---

### 🟠 [주요] 4.3 — 도메인 불변식이 Service 레이어에 위치

**위치**: [DeliveryApplicationService.java:97, 114, 135, 138](../src/main/java/com/pickple/delivery/application/service/DeliveryApplicationService.java#L97)

**현상**

```java
// Service에서 상태 전이 검증
if (delivery.getDeliveryStatus() != DeliveryStatus.PENDING) {
    throw new CustomException(DeliveryErrorCode.DELIVERY_ALREADY_START);
}
delivery.startDelivery(...);  // 도메인 메서드는 검증 없이 상태만 변경

// domain/model/Delivery.java - 검증 없음
public void startDelivery(String carrierId, DeliveryType deliveryType, DeliveryStartRequestDto dto) {
    this.deliveryStatus = DeliveryStatus.IN_TRANSIT;  // 그냥 변경
    // ...
}
```

**영향**

- `DeliveryMessageConsumerService`(Kafka Consumer)처럼 새로운 진입점이 추가될 때
  Service 검증 로직을 복사하지 않으면 불법적인 상태 전이가 발생합니다.
- 도메인 객체를 직접 사용하는 테스트 코드에서 도메인 불변식을 테스트할 수 없습니다.

**권장 방향**

```java
// domain/model/Delivery.java
public void startDelivery(String carrierId, DeliveryType deliveryType, ...) {
    if (this.deliveryStatus != DeliveryStatus.PENDING) {
        throw new DeliveryDomainException(DeliveryErrorCode.DELIVERY_ALREADY_START);
    }
    this.deliveryStatus = DeliveryStatus.IN_TRANSIT;
    // ...
}

public void endDelivery() {
    if (this.deliveryStatus == DeliveryStatus.PENDING) {
        throw new DeliveryDomainException(DeliveryErrorCode.DELIVERY_NOT_STARTED);
    }
    if (this.deliveryStatus == DeliveryStatus.DELIVERED) {
        throw new DeliveryDomainException(DeliveryErrorCode.DELIVERY_ALREADY_DELIVERED);
    }
    this.deliveryStatus = DeliveryStatus.DELIVERED;
}
```

---

### 🟠 [주요] 4.4 — `updateDelivery()` null 덮어쓰기

**위치**: [Delivery.java:95-103](../src/main/java/com/pickple/delivery/domain/model/Delivery.java#L95)

**현상**

```java
public void updateDelivery(DeliveryUpdateRequestDto dto) {
    this.deliveryStatus = dto.getDeliveryStatus();         // null 가능
    this.deliveryType = dto.getDeliveryType();             // null 가능
    this.deliveryRequirement = dto.getDeliveryRequirement(); // null 가능
    this.trackingNumber = dto.getTrackingNumber();         // null 가능
    this.recipientName = dto.getRecipientName();           // null 가능
    this.recipientAddress = dto.getRecipientAddress();     // null 가능
    this.recipientContact = dto.getRecipientContact();     // null 가능
}
```

**영향**

- 클라이언트가 `recipientName`만 변경하려 해도 나머지 모든 필드를 요청에 포함시켜야 합니다.
- 클라이언트가 일부 필드를 생략하면 기존 값이 `null`로 교체되어 데이터 유실이 발생합니다.

**권장 방향**

```java
public void updateDelivery(DeliveryUpdateRequestDto dto) {
    Optional.ofNullable(dto.getDeliveryStatus()).ifPresent(v -> this.deliveryStatus = v);
    Optional.ofNullable(dto.getDeliveryType()).ifPresent(v -> this.deliveryType = v);
    Optional.ofNullable(dto.getDeliveryRequirement()).ifPresent(v -> this.deliveryRequirement = v);
    Optional.ofNullable(dto.getTrackingNumber()).ifPresent(v -> this.trackingNumber = v);
    Optional.ofNullable(dto.getRecipientName()).ifPresent(v -> this.recipientName = v);
    Optional.ofNullable(dto.getRecipientAddress()).ifPresent(v -> this.recipientAddress = v);
    Optional.ofNullable(dto.getRecipientContact()).ifPresent(v -> this.recipientContact = v);
}
```

---

### 🟠 [주요] 4.5 — `carrierId` / `carrierName` 중복 저장

**위치**: [Delivery.java:37-41](../src/main/java/com/pickple/delivery/domain/model/Delivery.java#L37), [startDelivery():83-84](../src/main/java/com/pickple/delivery/domain/model/Delivery.java#L83)

**현상**

```java
// Delivery 엔티티에 별도 String 필드로 저장
private String carrierId;    // "kr.logen"
private String carrierName;  // "로젠택배"

// startDelivery에서 enum으로부터 분리하여 저장
this.carrierId = carrierId;                               // DeliveryCarrier.companyId
this.carrierName = dto.getDeliveryCarrier().getCompanyName(); // DeliveryCarrier.companyName

// DeliveryCarrier enum은 이미 두 값을 모두 보유
public enum DeliveryCarrier {
    LOZEN("로젠택배", "kr.logen");  // companyName + companyId
}
```

**영향**

- 동일한 정보가 두 곳에 저장됩니다.
- Enum 값이 변경될 경우 DB의 String 필드와 불일치가 발생합니다.

**권장 방향**

```java
// Delivery 엔티티
@Field("delivery_carrier")
private DeliveryCarrier deliveryCarrier;  // enum 하나로 통합

// carrierId, companyName은 enum에서 파생
public String getCarrierId() { return deliveryCarrier.getCompanyId(); }
public String getCarrierName() { return deliveryCarrier.getCompanyName(); }
```

---

### 🟠 [주요] 4.6 — `DeliveryDetail.deliveryDetailStatus` String 타입

**위치**: [DeliveryDetail.java:20](../src/main/java/com/pickple/delivery/domain/model/DeliveryDetail.java#L20)

**현상**

```java
public class DeliveryDetail {
    @Field("delivery_detail_status")
    private String deliveryDetailStatus;  // 자유 문자열
}
```

**영향**

- `"배송중"`, `"배송 중"`, `"배달중"` 등 동일한 의미의 서로 다른 문자열이 저장될 수 있습니다.
- 허용 값의 범위를 코드 수준에서 강제할 수 없습니다.

**권장 방향**

```java
public enum DeliveryDetailStatus {
    PICKED_UP("집하"),
    IN_TRANSIT("이동중"),
    OUT_FOR_DELIVERY("배송출발"),
    DELIVERED("배달완료"),
    FAILED("배달실패");
}

public class DeliveryDetail {
    private DeliveryDetailStatus deliveryDetailStatus;
}
```

---

### 🟠 [주요] 4.7 — `deliveryDetails` 리스트 불변성 문제

**위치**: [Delivery.java:65-67](../src/main/java/com/pickple/delivery/domain/model/Delivery.java#L65)

**현상**

```java
@Builder.Default
private List<DeliveryDetail> deliveryDetails = new ArrayList<>();

// @Getter가 생성하는 접근자
public List<DeliveryDetail> getDeliveryDetails() {
    return deliveryDetails;  // 원본 ArrayList 직접 반환
}

// 외부에서 도메인 캡슐화를 우회하여 직접 수정 가능
delivery.getDeliveryDetails().add(unauthorizedDetail);
delivery.getDeliveryDetails().clear();
```

**영향**

- `addDeliveryDetail()` 메서드를 통한 제어 우회 가능
- 배송 경로 데이터의 무결성 보장 불가

**권장 방향**

```java
// Delivery.java에 명시적 getter 추가 (Lombok @Getter 대신)
public List<DeliveryDetail> getDeliveryDetails() {
    return Collections.unmodifiableList(deliveryDetails);
}
```

---

### 🟠 [주요] 4.8 — 도메인 모델이 Application DTO에 직접 의존

**위치**: [Delivery.java:3-5](../src/main/java/com/pickple/delivery/domain/model/Delivery.java#L3)

**현상**

```java
// domain/model/Delivery.java
package com.pickple.delivery.domain.model;

import com.pickple.delivery.application.dto.request.DeliveryCreateRequestDto;  // application 레이어 의존!
import com.pickple.delivery.application.dto.request.DeliveryStartRequestDto;
import com.pickple.delivery.application.dto.request.DeliveryUpdateRequestDto;

public static Delivery createFrom(DeliveryCreateRequestDto dto) { ... }
public void startDelivery(..., DeliveryStartRequestDto dto) { ... }
public void updateDelivery(DeliveryUpdateRequestDto dto) { ... }
```

**영향**

- Clean Architecture에서 의존성 방향은 외부 → 내부여야 합니다.
  도메인이 application 레이어에 의존하면 역전됩니다.
- 도메인 객체를 단독으로 테스트하기 위해 DTO 클래스를 생성해야 합니다.
- 도메인 로직을 다른 컨텍스트에서 재사용할 때 application DTO가 따라붙습니다.

**권장 방향**

```java
// 도메인 Command 객체 도입
public record DeliveryCreateCommand(
    UUID orderId,
    String deliveryRequirement,
    String recipientName,
    String recipientAddress,
    String recipientContact
) {}

// 도메인 메서드는 Command에 의존 (domain 패키지 내부)
public static Delivery createFrom(DeliveryCreateCommand command) { ... }

// Service에서 DTO → Command 변환
public void createDelivery(DeliveryCreateRequestDto dto) {
    DeliveryCreateCommand command = mapper.toCommand(dto);
    Delivery delivery = Delivery.createFrom(command);
    // ...
}
```

---

### 🟡 [보통] 4.9 — `java.util.Date` 타입 사용

**위치**: [BaseEntity.java:14,18](../src/main/java/com/pickple/delivery/domain/model/BaseEntity.java#L14), [DeliveryDetail.java:26](../src/main/java/com/pickple/delivery/domain/model/DeliveryDetail.java#L26)

**현상**

```java
// BaseEntity
private Date createdAt;   // java.util.Date — 가변, 스레드 안전하지 않음
private Date updatedAt;

// DeliveryDetail
private Date deliveryDetailTime;  // 동일 문제

// DeliveryDeleted — 타입 불일치
protected Instant deletedAt;  // Instant 사용 (BaseEntity와 다름)
```

**영향**

- `Date`는 가변 객체로, 외부에서 getter로 받은 `Date` 객체를 수정하면 내부 상태가 변경됩니다.
- `BaseEntity`(Date)와 `DeliveryDeleted`(Instant) 간 타입 불일치로 일관성이 부족합니다.

**권장 방향**

```java
// BaseEntity
@Field("created_at")
@CreatedDate
private Instant createdAt;

@Field("updated_at")
@LastModifiedDate
private Instant updatedAt;
```

---

### 🟡 [보통] 4.10 — `sendNotification()` NPE/NoSuchElementException 위험

**위치**: [DeliveryApplicationService.java:257-272](../src/main/java/com/pickple/delivery/application/service/DeliveryApplicationService.java#L257)

**현상**

```java
private void sendNotification(Delivery delivery, String subject, String content) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    String sender = (String) authentication.getPrincipal();
    String role = authentication.getAuthorities().stream()
        .findFirst()
        .get()            // ← 권한이 없으면 NoSuchElementException 발생!
        .getAuthority();
    String username = orderClient.getUsernameByDeliveryId(  // ← 동기 Feign 호출
            delivery.getDeliveryId(), role, sender);
    // ...
}
```

**영향**

1. `.get()` 호출: 권한이 없는 Authentication 객체가 들어오면 예외 발생
2. Feign 동기 호출: Order Service가 다운되면 `sendNotification()` 실패 → `endDelivery()` 전체 실패
   배송 완료 처리가 알림 서비스 장애로 인해 실패하는 강결합 구조입니다.

**권장 방향**

```java
// .get() → .orElseThrow()로 명시적 예외 처리
String role = authentication.getAuthorities().stream()
    .findFirst()
    .orElseThrow(() -> new CustomException(CommonErrorCode.UNAUTHORIZED))
    .getAuthority();

// 알림은 비동기로 처리하여 배송 완료 트랜잭션과 분리
@Async
private void sendNotificationAsync(Delivery delivery, String subject, String content) { ... }
```

---

### 🟡 [보통] 4.11 — 개인정보 필드 포맷 검증 없음

**위치**: `DeliveryCreateRequestDto`, `Delivery.java:62-63`

**현상**

`recipientContact`(수신자 연락처)에 전화번호 포맷 검증이 없습니다.
임의의 문자열(`"abc"`, `"123"`)이 그대로 저장됩니다.

**영향**

- 잘못된 형식의 전화번호로 알림 발송 시도 시 실패
- 입력 검증이 클라이언트에만 의존하여 서버에서 방어 불가

**권장 방향**

```java
// DeliveryCreateRequestDto
@Pattern(regexp = "^\\d{2,3}-\\d{3,4}-\\d{4}$", message = "올바른 전화번호 형식이 아닙니다.")
private String recipientContact;
```

---

### 🔵 [경미] 4.12 — `deleteDelivery()` 정상 흐름에서 `log.error()` 오용

**위치**: [DeliveryApplicationService.java:230](../src/main/java/com/pickple/delivery/application/service/DeliveryApplicationService.java#L230)

**현상**

```java
@Transactional
public DeliveryDeleteResponseDto deleteDelivery(UUID deliveryId, String deleter) {
    log.error("배송을 삭제합니다. 배송 ID: {}, 배송 삭제 요청자: {}", deliveryId, deleter);
    // ↑ 정상 비즈니스 흐름에서 error 레벨 로그
```

**영향**

- 모니터링 시스템에서 정상 삭제가 에러 알람으로 집계됩니다.
- 실제 에러와 구분이 어려워 인시던트 대응 시 혼란을 줍니다.

**권장 방향**: `log.info()`로 변경

---

### 🔵 [경미] 4.13 — `getDeliveryInfoByOrderId()` 반환 타입 의미 불일치

**위치**: [DeliveryApplicationService.java:156](../src/main/java/com/pickple/delivery/application/service/DeliveryApplicationService.java#L156)

**현상**

```java
// 배송 정보를 조회하는 메서드인데 "Start"ResponseDto를 반환
public DeliveryStartResponseDto getDeliveryInfoByOrderId(UUID orderId) {
    return DeliveryMapper.convertEntityToStartResponseDto(delivery);
}
```

**권장 방향**: `DeliveryInfoResponseDto`를 반환하도록 변경하거나, 반환 DTO를 명확히 분리

---

### 🔵 [경미] 4.14 — 에러 코드 일관성 없음

**위치**: [DeliveryApplicationService.java:80](../src/main/java/com/pickple/delivery/application/service/DeliveryApplicationService.java#L80)

**현상**

```java
// createDelivery — CommonErrorCode 사용
} catch (Exception e) {
    throw new CustomException(CommonErrorCode.DATABASE_ERROR);
}

// 나머지 메서드 — DeliveryErrorCode 사용
throw new CustomException(DeliveryErrorCode.DELIVERY_NOT_FOUND);
```

`DeliveryErrorCode`에 `DELIVERY_CREATE_FAILURE`가 이미 정의되어 있습니다.

**권장 방향**: `CommonErrorCode.DATABASE_ERROR` → `DeliveryErrorCode.DELIVERY_CREATE_FAILURE` 통일

---

## 5. 긍정적 설계 요소

### 5.1 소프트 삭제 아카이브 패턴

```java
// 정적 팩토리 메서드로 삭제 레코드 생성 — 명확한 책임 분리
DeliveryDeleted deletedDelivery = DeliveryDeleted.fromDelivery(delivery, deliveryDetailDeleted);
deletedDelivery.delete(deleter);
deliveryDeletedRepository.save(deletedDelivery);
deliveryRepository.deleteById(deliveryId);
```

원본 데이터를 별도 컬렉션(`p_deliveries_deleted`)으로 이동하여 감사(audit) 추적을 지원합니다.

### 5.2 Repository 포트-어댑터 분리 (DIP 준수)

```
domain/repository/DeliveryRepository.java     ← 인터페이스 (포트)
infrastructure/repository/DeliveryMongoRepository.java  ← 구현체 (어댑터)
```

도메인이 인프라 구현에 의존하지 않아 데이터 저장소 교체 시 도메인 코드 변경이 불필요합니다.

### 5.3 Enum의 도메인 로직 내장

```java
/**
 * companyId는 배송 추적 API의 carrierId.
 * @see <a href="https://tracker.delivery/docs/tracking-api#carriers-search">Carrier API</a>
 */
public enum DeliveryCarrier {
    LOZEN("로젠택배", "kr.logen"),
    CJ_LOGISTICS("CJ대한통운", "kr.cjlogistics"),
    ...

    public static DeliveryCarrier getFromCarrierName(String name) { ... }
}
```

외부 API 연동 정보(companyId)를 Enum에 캡슐화하고 문서 링크를 포함한 좋은 예입니다.

### 5.4 Persistable 구현을 통한 INSERT/UPSERT 명시적 제어

```java
@Override
public boolean isNew() {
    return getCreatedAt() == null;  // Spring Data @CreatedDate 활용
}
```

MongoDB `save()` 호출 시 신규 도큐먼트와 기존 도큐먼트를 `createdAt` 기준으로 명시적으로 구분합니다.

### 5.5 OrderClient 포트 인터페이스

```java
// application/port/OrderClient.java — 인터페이스 (포트)
public interface OrderClient {
    String getUsernameByDeliveryId(UUID deliveryId, String role, String username);
}

// infrastructure/feign/OrderFeignClient.java — Feign 구현체 (어댑터)
@FeignClient(name = "commerce-service")
public interface OrderFeignClient extends OrderClient { ... }
```

Feign 구현체와 포트를 분리하여 테스트 시 Mock으로 쉽게 대체 가능합니다.

### 5.6 Redis Sentinel 고가용성 구성

REPLICA_PREFERRED 읽기 전략으로 읽기 요청을 Replica 노드에 분산하는 프로덕션 수준의 구성입니다.

---

## 6. 개선 로드맵

### Phase 1 — 즉시 수정 (30분 이내, 버그/위험)

| 항목 | 작업 | 수정 파일 |
|------|------|----------|
| 4.1 | `startDelivery` `@Transactional(readOnly=true)` → `@Transactional` | `DeliveryApplicationService.java:106` |
| 4.12 | `log.error` → `log.info` | `DeliveryApplicationService.java:230` |
| 4.10 | `.get()` → `.orElseThrow()` | `DeliveryApplicationService.java:259` |
| 4.14 | 에러 코드 일관성 통일 | `DeliveryApplicationService.java:80` |

### Phase 2 — 단기 개선 (1~2일, 설계 결함)

| 항목 | 작업 | 수정 파일 |
|------|------|----------|
| 4.4 | `updateDelivery` null-safe 처리 | `Delivery.java:95-103` |
| 4.7 | `deliveryDetails` getter 불변 리스트 반환 | `Delivery.java:65-67` |
| 4.6 | `deliveryDetailStatus` 전용 Enum 정의 및 교체 | `DeliveryDetail.java`, 신규 Enum |
| 4.9 | `Date` → `Instant` 타입 마이그레이션 | `BaseEntity.java`, `DeliveryDetail.java` |
| 4.13 | 반환 타입 의미 불일치 수정 | `DeliveryApplicationService.java:156` |
| 4.11 | `@Pattern` 검증 어노테이션 추가 | Request DTO |

### Phase 3 — 중장기 리팩토링 (1주일+, 아키텍처)

| 항목 | 작업 |
|------|------|
| 4.2 | Transactional Outbox Pattern 또는 `@TransactionalEventListener(AFTER_COMMIT)` 도입 |
| 4.3 | 상태 전이 불변식을 도메인 메서드 내부로 이동 |
| 4.5 | `carrierId`/`carrierName` String 필드 제거, `DeliveryCarrier` enum 단일 참조 |
| 4.8 | 도메인 → Application DTO 의존 제거 (도메인 Command 객체 도입) |

---

## 7. 부록

### 7.1 Kafka 토픽 목록

| 토픽 이름 | 방향 | 발행/소비 | 관련 메서드 |
|----------|------|---------|-----------|
| `delivery-create-request` | Order → Delivery | 소비 | `consumeDeliveryCreation` |
| `delivery-create-response` | Delivery → Order | 발행 | `createDelivery` |
| `delivery-end-response` | Delivery → Order | 발행 | `endDelivery`, `deleteDelivery` |
| `delivery-delete-request` | Order → Delivery | 소비 | `consumeDeliveryDeletion` |
| `email-create-request` | Delivery → Notification | 발행 | `sendNotification` |

### 7.2 REST API 엔드포인트 목록

| Method | Path | 권한 | 서비스 메서드 |
|--------|------|------|-------------|
| `POST` | `/api/v1/deliveries/{id}/start` | VENDOR_MANAGER, MASTER | `startDelivery` |
| `POST` | `/api/v1/deliveries/{id}/end` | VENDOR_MANAGER, MASTER | `endDelivery` |
| `GET` | `/api/v1/deliveries/{id}` | USER, VENDOR_MANAGER, MASTER | `getDeliveryInfo` |
| `GET` | `/api/v1/deliveries/orders/{orderId}` | USER, VENDOR_MANAGER, MASTER | `getDeliveryInfoByOrderId` |
| `GET` | `/api/v1/deliveries` | MASTER | `getAllDeliveryInfo` |
| `PUT` | `/api/v1/deliveries/{id}` | VENDOR_MANAGER, MASTER | `updateDelivery` |
| `DELETE` | `/api/v1/deliveries/{id}` | MASTER | `deleteDelivery` |
| `GET` | `/api/v1/deliveries/{id}/status` | MASTER | `getDeliveryStatus` |
| `GET` | `/api/v1/deliveries/carrier` | MASTER | `getDeliveriesByCarrier` |
| `GET` | `/api/v1/deliveries/status` | MASTER | `getDeliveriesByStatus` |
| `GET` | `/api/v1/deliveries/type` | MASTER | `getDeliveriesByDeliveryType` |
| `GET` | `/api/v1/deliveries/tracking-number` | MASTER | `getDeliveriesByTrackingNumber` |
| `POST` | `/api/v1/deliveries/{id}/details` | VENDOR_MANAGER, MASTER | `createDeliveryDetail` |

### 7.3 참고 자료

- [Transactional Outbox Pattern](https://microservices.io/patterns/data/transactional-outbox.html)
- [Spring @TransactionalEventListener](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/transaction/event/TransactionalEventListener.html)
- [Tracker Delivery Carrier API](https://tracker.delivery/docs/tracking-api#carriers-search)
- [Spring Data MongoDB - Persistable](https://docs.spring.io/spring-data/mongodb/docs/current/api/org/springframework/data/domain/Persistable.html)
