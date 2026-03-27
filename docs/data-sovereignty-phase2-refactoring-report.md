# Phase 2 리팩토링 수행 보고서

> 참조 문서: [data-sovereignty-analysis.md](data-sovereignty-analysis.md) §5 Phase 2  
> 설계 문서: [data-sovereignty-phase2-improvement-plan.md](data-sovereignty-phase2-improvement-plan.md)

---

## 1. 개요

Phase 2의 목표였던 **Commerce Service 읽기 경로의 동기 Feign 제거**를 수행했다.
이번 작업의 핵심은 결제/배송 응답 이벤트를 보강하고, `Order` Aggregate 안에 **결제/배송 스냅샷**을 저장하도록 바꿔서 주문 상세 조회가 외부 서비스 호출 없이 동작하게 만드는 것이다.

이번 작업 후 주문 상세 조회는 다음 흐름으로 변경되었다.

```text
Before
OrderService.getOrderById()
  ├─ Order DB 조회
  ├─ Feign → payment-service
  └─ Feign → delivery-service

After
OrderService.getOrderById()
  └─ Order DB 조회 + 저장된 payment/delivery snapshot으로 응답 구성
```

추가로 `cancelOrder()`도 `Order.deliveryId`를 직접 사용하도록 변경하여, `Commerce -> Delivery` 조회 Feign 역시 제거했다.

---

## 2. 변경 파일 목록

### 2.1 commerce-service

| 파일 | 변경 유형 | 변경 내용 |
|------|-----------|-----------|
| `domain/model/Order.java` | 수정 | 결제/배송 스냅샷 필드 추가 (`paymentAmount`, `paymentMethod`, `paymentStatus`, `deliveryStatus`, `deliveryType`, `carrierName`, `trackingNumber`, `deliveryRequirement`, `recipientName`, `recipientAddress`, `recipientContact`) |
| `domain/model/Order.java` | 수정 | `assignPaymentSnapshot()`, `assignDeliverySnapshot()`, `updateDeliveryStatus()`, `clearPaymentSnapshot()` 메서드 추가 |
| `application/dto/OrderResponseDto.java` | 수정 | Feign DTO 기반 응답에서 로컬 스냅샷 DTO 기반 응답으로 전환 |
| `application/dto/OrderPaymentSnapshotDto.java` | 신규 | 주문 응답용 결제 스냅샷 DTO 추가 |
| `application/dto/OrderDeliverySnapshotDto.java` | 신규 | 주문 응답용 배송 스냅샷 DTO 추가 |
| `application/service/OrderService.java` | 수정 | `getOrderById()`에서 Payment/Delivery Feign 제거, `cancelOrder()`에서 `Order.deliveryId` 직접 사용 |
| `application/service/OrderEventService.java` | 수정 | 결제/배송 이벤트 수신 시 `Order` 스냅샷 저장 및 배송 상태 갱신 로직 추가 |
| `infrastructure/messaging/OrderMessagingConsumerService.java` | 수정 | 보강된 `payment-create-response`, `delivery-create-response` 이벤트 필드 소비 |
| `infrastructure/messaging/events/PaymentCreateResponseEvent.java` | 수정 | `amount` 필드 추가 |
| `infrastructure/messaging/events/DeliveryCreateResponseEvent.java` | 수정 | 배송 상세 스냅샷 필드 추가 |
| `infrastructure/feign/PaymentClient.java` | 삭제 | 주문 조회용 payment-service Feign 제거 |
| `infrastructure/feign/DeliveryClient.java` | 삭제 | 주문 조회/취소용 delivery-service Feign 제거 |
| `infrastructure/feign/dto/PaymentClientDto.java` | 삭제 | Feign 전용 DTO 제거 |
| `infrastructure/feign/dto/DeliveryClientDto.java` | 삭제 | Feign 전용 DTO 제거 |
| `src/test/.../OrderServiceExtendedTest.java` | 수정 | 스냅샷 기반 조회 테스트 추가, 트랜잭션 동기화 테스트 보강 |
| `src/test/.../OrderEventServiceTest.java` | 신규 | 결제/배송 스냅샷 저장 테스트 추가 |

### 2.2 payment-service

| 파일 | 변경 유형 | 변경 내용 |
|------|-----------|-----------|
| `application/service/PaymentService.java` | 수정 | `PaymentCreateResponseEvent` 발행 시 `amount`, `method`, `status`를 명시적으로 포함 |
| `infrastructure/messaging/events/PaymentCreateResponseEvent.java` | 수정 | `amount` 필드 추가, 전체 스냅샷 전달용 구조로 정리 |

### 2.3 delivery-service

| 파일 | 변경 유형 | 변경 내용 |
|------|-----------|-----------|
| `application/service/DeliveryApplicationService.java` | 수정 | `DeliveryCreateResponseEvent` 발행 시 배송 초기 스냅샷 포함 |
| `application/events/DeliveryCreateResponseEvent.java` | 수정 | `deliveryStatus`, `deliveryType`, `carrierName`, `trackingNumber`, `deliveryRequirement`, `recipientName`, `recipientAddress`, `recipientContact` 추가 |
| `infrastructure/messaging/DeliveryMessageConsumerService.java` | 수정 | 존재하지 않는 `EventSerializer.objectMapper` 정적 참조 제거, 공용 `EventSerializer.deserialize()` 사용으로 컴파일 오류 수정 |

---

## 3. 핵심 변경 사항

### 3.1 주문 조회 Feign 제거

**Before**

```java
PaymentClientDto paymentInfo = paymentClient.getPaymentInfo(role, username, orderId);
DeliveryClientDto deliveryInfo = deliveryClient.getDeliveryInfo(role, username, orderId).getData();
return OrderResponseDto.fromEntity(order, paymentInfo, deliveryInfo);
```

**After**

```java
return OrderResponseDto.fromEntity(order);
```

의미:

- 주문 상세 조회는 이제 `Commerce DB`에 저장된 `Order`만 조회한다.
- 결제/배송 정보는 이벤트로 저장된 스냅샷을 사용한다.
- 조회 API가 payment-service, delivery-service의 가용성에 영향을 받지 않는다.

### 3.2 결제 응답 이벤트 보강

`payment-service`는 결제 완료 후 아래 필드를 이벤트로 발행하도록 바뀌었다.

- `orderId`
- `paymentId`
- `amount`
- `method`
- `status`

`commerce-service`는 이 값을 받아 `Order.assignPaymentSnapshot(...)`으로 저장한다.

### 3.3 배송 응답 이벤트 보강

`delivery-service`는 배송 생성 후 아래 필드를 이벤트로 발행하도록 바뀌었다.

- `orderId`
- `deliveryId`
- `deliveryStatus`
- `deliveryType`
- `carrierName`
- `trackingNumber`
- `deliveryRequirement`
- `recipientName`
- `recipientAddress`
- `recipientContact`

배송 생성 시점에 아직 비어 있는 필드는 nullable 상태로 유지되고, 이후 `delivery-end-response`로 상태가 갱신된다.

### 3.4 `cancelOrder()`의 로컬 데이터 사용

기존에는 배송 삭제 이벤트를 보내기 전에 `delivery-service`를 조회했다.
이제는 `Order`가 이미 `deliveryId`를 보유하므로, 외부 조회 없이 바로 처리한다.

```java
UUID deliveryId = order.getDeliveryId();
if (deliveryId != null) {
    messagingProducerService.sendDeliveryDeleteRequest(deliveryId, orderId, username);
}
```

이 변경으로 `Commerce -> Delivery` Feign도 완전히 제거됐다.

---

## 4. 제거된 Feign 호출

| 제거된 호출 | 호출 서비스 → 대상 | 대체 방법 |
|-------------|-----------------|-----------|
| `PaymentClient.getPaymentInfo()` | Commerce → Payment | `Order.payment*` 스냅샷 필드 직접 참조 |
| `DeliveryClient.getDeliveryInfo()` | Commerce → Delivery | `Order.delivery*` 스냅샷 필드 직접 참조 |

> 결과적으로 `Commerce Service` 내부에는 Payment/Delivery 조회용 Feign 의존성이 남지 않는다.

---

## 5. 테스트 및 검증

### 5.1 성공한 검증

실행 명령:

```bash
./gradlew :commerce-service:test --tests "com.pickple.commerceservice.application.service.OrderServiceExtendedTest" --tests "com.pickple.commerceservice.application.service.OrderEventServiceTest"
./gradlew :payment-service:compileJava
./gradlew :delivery-service:compileJava
./gradlew :payment-service:compileJava :delivery-service:compileJava :commerce-service:test --tests "com.pickple.commerceservice.application.service.OrderServiceExtendedTest" --tests "com.pickple.commerceservice.application.service.OrderEventServiceTest"
```

결과:

- `commerce-service` 대상 테스트 통과
- `payment-service` 컴파일 성공
- `delivery-service` 컴파일 성공
- 최종 교차 검증 명령 성공

검증된 주요 시나리오:

- `OrderService.getOrderById()`가 스냅샷만으로 응답을 구성하는지
- `OrderEventService.handlePaymentComplete()`가 결제 스냅샷을 저장하는지
- `OrderEventService.handleDeliveryComplete()`가 배송 스냅샷을 저장하는지
- `OrderEventService.handleDeliveryEnd()`가 주문 상태와 배송 상태를 함께 갱신하는지

### 5.2 추가 정리 사항

초기 검증 단계에서는 `delivery-service`에서 다수의 연쇄 컴파일 오류가 관찰됐다.
추적 결과 실제 원인은 [DeliveryMessageConsumerService.java](/Users/gimjinmyeong/Desktop/workspace/Github/pickple-backend/delivery-service/src/main/java/com/pickple/delivery/infrastructure/messaging/DeliveryMessageConsumerService.java)의 잘못된 정적 import였다.

문제 코드:

```java
import static com.pickple.common_module.infrastructure.messaging.EventSerializer.objectMapper;
```

`EventSerializer`는 `objectMapper`를 외부에 노출하지 않으므로, 이 코드는 컴파일 대상이 될 수 없다.
해당 부분을 `EventSerializer.deserialize()` 호출로 교체한 후 `delivery-service` 컴파일은 정상화됐다.

---

## 6. 효과

- **주문 상세 조회의 외부 서비스 장애 전파 제거**
- **조회 응답 시간 단축**: 원격 HTTP 호출 제거
- **Commerce Service 조회 자립성 확보**
- **결제/배송 상세를 이벤트 기반 로컬 스냅샷으로 관리**
- **Phase 3 CQRS 읽기 모델 도입 전 단계로 자연스럽게 연결 가능**

---

## 7. 잔여 고려사항

### 7.1 스냅샷은 조회용 복제본

- Payment 정본은 `payment-service`
- Delivery 정본은 `delivery-service`
- Commerce의 스냅샷은 **주문 조회 최적화용 로컬 데이터**다

### 7.2 배송 취소/실패 상태 표현

이번 변경에서는 `deliveryId`를 지우지 않고 `deliveryStatus`를 갱신하는 방향으로 정리했다.
이 방식은 “배송 미생성”과 “생성 후 취소/실패”를 구분할 수 있어 이력 보존 측면에서 더 적절하다.

### 7.3 다음 단계

Phase 3에서 아래로 확장 가능하다.

- `OrderReadModel` 별도 도입
- 이벤트 재처리/재동기화 배치 추가
- 주문 상세 외 목록/검색 조회까지 비정규화 모델 확장
