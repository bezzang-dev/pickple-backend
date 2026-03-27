# Phase 2 개선안 — 읽기 경로 Feign 제거 및 Order 스냅샷 도입

> 참조 문서: [data-sovereignty-analysis.md](data-sovereignty-analysis.md) §5 Phase 2  
> 선행 작업: [data-sovereignty-phase1-refactoring-report.md](data-sovereignty-phase1-refactoring-report.md)

---

## 1. 목적

Phase 2의 목표는 **Commerce Service의 주문 조회 읽기 경로에서 남아 있는 동기 Feign 호출을 제거**하는 것이다.
현재 `OrderService.getOrderById()`는 주문 자체 데이터 외에 결제/배송 정보를 조회하기 위해 아래 호출에 의존한다.

- `PaymentClient.getPaymentInfo()`
- `DeliveryClient.getDeliveryInfo()`

이 구조는 Phase 1에서 제거한 Critical 런타임 결합보다 우선순위는 낮지만, 여전히 다음 문제를 남긴다.

- 주문 상세 조회가 `Commerce + Payment + Delivery` 3개 서비스의 가용성에 의존함
- 조회 응답 시간이 외부 서비스 네트워크 지연에 영향받음
- 조회 실패가 곧바로 사용자 API 실패로 전파됨
- `Commerce Service`가 자신의 조회 API를 스스로 완결하지 못함

Phase 2에서는 **CQRS 읽기 모델까지 확장하지 않고**, 현재 `Order` Aggregate 안에 결제/배송 스냅샷 필드를 추가하는 방식으로 문제를 먼저 줄인다.

---

## 2. 권장 방향

### 2.1 선택한 접근

**권장안: 응답 이벤트 보강 + Order 스냅샷 필드 저장**

구조는 다음과 같다.

1. `payment-service`가 결제 완료 이벤트에 조회용 상세 필드를 함께 발행한다.
2. `delivery-service`가 배송 생성 이벤트에 조회용 상세 필드를 함께 발행한다.
3. `commerce-service`가 해당 이벤트를 수신해 `Order` 엔티티에 스냅샷 형태로 저장한다.
4. 주문 상세 조회는 `Order` 단건 조회만으로 응답을 구성한다.

### 2.2 이번 단계에서 CQRS를 바로 도입하지 않는 이유

`OrderReadModel`을 별도로 두는 CQRS 방식은 최종 방향으로 타당하지만, 현재 코드베이스 기준으로는 다음 비용이 추가된다.

- 별도 읽기 모델 테이블/엔티티/리포지토리 관리 필요
- 이벤트 재처리 및 재구축 전략 필요
- 조회 모델과 쓰기 모델 간 동기화 실패 대응까지 설계해야 함

Phase 2의 목적은 **중간 비용으로 읽기 경로 의존성을 제거하는 것**이므로, 먼저 `Order` 자체에 스냅샷을 저장하는 편이 더 현실적이다.

---

## 3. 현재 구조와 문제 지점

### 3.1 현재 조회 흐름

```text
OrderService.getOrderById(orderId)
  1. Commerce DB에서 Order 조회
  2. paymentId 존재 시 Payment Service Feign 호출
  3. deliveryId 존재 시 Delivery Service Feign 호출
  4. OrderResponseDto 조합
```

### 3.2 현재 코드 기준 문제 포인트

- [OrderService.java](/Users/gimjinmyeong/Desktop/workspace/Github/pickple-backend/commerce-service/src/main/java/com/pickple/commerceservice/application/service/OrderService.java)
  - `getOrderById()`가 조회 시점마다 외부 서비스 호출 수행
  - `cancelOrder()`도 배송 삭제 요청을 위해 조회 Feign 결과에 의존
- [Order.java](/Users/gimjinmyeong/Desktop/workspace/Github/pickple-backend/commerce-service/src/main/java/com/pickple/commerceservice/domain/model/Order.java)
  - `paymentId`, `deliveryId`만 저장하고 상세 스냅샷은 없음
- [PaymentCreateResponseEvent.java](/Users/gimjinmyeong/Desktop/workspace/Github/pickple-backend/payment-service/src/main/java/com/pickple/payment_service/infrastructure/messaging/events/PaymentCreateResponseEvent.java)
  - `amount`, `method`, `status` 필드 정의는 있으나 생성 시 실제 값이 온전히 채워진다고 보장되지 않음
- [DeliveryCreateResponseEvent.java](/Users/gimjinmyeong/Desktop/workspace/Github/pickple-backend/delivery-service/src/main/java/com/pickple/delivery/application/events/DeliveryCreateResponseEvent.java)
  - `orderId`, `deliveryId`만 포함하고 있어 Commerce가 배송 상세를 복원할 수 없음

---

## 4. 상세 개선안

### 4.1 Commerce Service: Order 스냅샷 필드 추가

`Order` 엔티티에 아래 필드를 추가한다.

#### 결제 스냅샷

- `paymentAmount`
- `paymentMethod`
- `paymentStatus`

#### 배송 스냅샷

- `deliveryStatus`
- `deliveryType`
- `carrierName`
- `trackingNumber`
- `deliveryRequirement`
- `recipientName`
- `recipientAddress`
- `recipientContact`

권장 이유:

- 주문 상세 API가 실제로 필요한 조회 데이터를 `Order` 하나에서 바로 응답할 수 있음
- 별도 조인/원격 호출 없이 DTO 조립 가능
- Phase 3에서 CQRS로 확장하더라도 필드 의미가 명확해 재사용 가능

추가 메서드도 같이 두는 편이 좋다.

- `assignPaymentSnapshot(...)`
- `assignDeliverySnapshot(...)`
- `clearDeliverySnapshot()`
- `clearPaymentSnapshot()`
- `updateDeliveryStatus(...)`

엔티티 setter를 외부에 열기보다, 위 메서드로 상태 변경 책임을 `Order` 내부에 유지하는 편이 맞다.

### 4.2 Payment Service: 결제 응답 이벤트 명시적 보강

현재 이벤트 클래스에는 `method`, `status` 필드가 있으나, 생성 경로가 `new PaymentCreateResponseEvent(orderId, paymentId)`라서 생산자 의도가 명확하지 않다.

Phase 2에서는 아래처럼 **실제 결제 엔티티 값을 명시적으로 실어 보내도록 변경**한다.

```java
new PaymentCreateResponseEvent(
    payment.getOrderId(),
    payment.getPaymentId(),
    payment.getAmount(),
    payment.getMethod(),
    payment.getStatus().name()
)
```

권장 변경:

- 이벤트 필드 타입은 소비 안정성을 위해 문자열/원시 타입 위주 유지
- `status`는 enum 자체보다 문자열로 발행하는 편이 서비스 간 결합이 낮음
- 이벤트 생성은 생성자 오버로드에 의존하지 말고 전체 필드 생성자로 통일

### 4.3 Delivery Service: 배송 생성 응답 이벤트 보강

배송 생성 직후 Commerce가 저장해야 할 스냅샷 필드를 이벤트에 포함한다.

권장 필드:

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

주의점:

- 배송 생성 직후 `carrierName`, `trackingNumber`, `deliveryType`가 아직 비어 있을 수 있다.
- 따라서 생성 이벤트에는 “현재 시점의 배송 스냅샷”을 담고, 이후 상태 변화는 별도 이벤트로 반영하는 것이 맞다.

즉, `delivery-create-response`는 초기 스냅샷 저장용이고, `delivery-end-response`는 상태 갱신용으로 유지한다.

### 4.4 Commerce Service: 이벤트 소비 로직 확장

현재 [OrderEventService.java](/Users/gimjinmyeong/Desktop/workspace/Github/pickple-backend/commerce-service/src/main/java/com/pickple/commerceservice/application/service/OrderEventService.java)는 아래 수준까지만 처리한다.

- 결제 완료 시 `paymentId` 연결
- 배송 생성 시 `deliveryId` 연결
- 배송 완료 시 주문 상태만 `COMPLETED` 변경

Phase 2에서는 이를 아래처럼 확장한다.

- `handlePaymentComplete(...)`
  - `paymentId` 연결
  - `paymentAmount`, `paymentMethod`, `paymentStatus` 저장
- `handleDeliveryComplete(...)`
  - `deliveryId` 연결
  - 초기 배송 스냅샷 저장
- `handleDeliveryEnd(...)`
  - 주문 상태 `COMPLETED`
  - `deliveryStatus = DELIVERED` 반영
- `handleDeliveryCancel(...)`
  - `deliveryId` 제거 여부 재검토
  - 최소한 `deliveryStatus`는 취소/실패 상태로 남기기

여기서 중요한 점은 **배송 실패/취소 시 스냅샷을 무조건 지우지 않는 것**이다.
현재처럼 `deliveryId`를 null 처리하면, 주문 이력 관점에서 배송 처리 흔적이 사라질 수 있다.

권장 방침:

- “미생성”과 “생성 후 취소”는 다른 상태로 보존한다.
- 가능하면 `deliveryId`와 마지막 `deliveryStatus`는 남긴다.

### 4.5 Commerce Service: 조회 DTO를 스냅샷 기반으로 전환

현재 [OrderResponseDto.java](/Users/gimjinmyeong/Desktop/workspace/Github/pickple-backend/commerce-service/src/main/java/com/pickple/commerceservice/application/dto/OrderResponseDto.java)는 `PaymentClientDto`, `DeliveryClientDto`를 그대로 포함한다.

Phase 2 이후에는 두 가지 선택지가 있다.

#### 선택지 A. 기존 DTO 재사용

- `PaymentClientDto`, `DeliveryClientDto`를 Feign DTO가 아니라 조회 공용 DTO처럼 사용
- `Order`의 스냅샷 필드에서 해당 DTO를 조립

장점:

- API 응답 구조를 거의 바꾸지 않음
- 프론트엔드/클라이언트 영향이 작음

단점:

- 이름이 여전히 `ClientDto`라 의미가 부정확함

#### 선택지 B. Snapshot DTO로 분리

- `OrderPaymentSnapshotDto`
- `OrderDeliverySnapshotDto`

장점:

- 현재 구조가 이벤트 기반 로컬 스냅샷임을 이름으로 표현 가능
- Feign DTO와 조회 DTO의 책임 분리 가능

권장안은 **B**다. 이번 Phase 2 문서화 목적에도 더 정확하다.

### 4.6 Commerce Service: 조회 Feign 제거

최종적으로 아래 의존성을 제거한다.

- [PaymentClient.java](/Users/gimjinmyeong/Desktop/workspace/Github/pickple-backend/commerce-service/src/main/java/com/pickple/commerceservice/infrastructure/feign/PaymentClient.java)
- [DeliveryClient.java](/Users/gimjinmyeong/Desktop/workspace/Github/pickple-backend/commerce-service/src/main/java/com/pickple/commerceservice/infrastructure/feign/DeliveryClient.java)

단, **즉시 삭제는 권장하지 않는다.**
먼저 `getOrderById()`를 스냅샷 기반으로 전환하고, `cancelOrder()`에서 필요한 데이터까지 이벤트/로컬 저장 데이터로 대체 가능한지 확인한 뒤 제거하는 순서가 안전하다.

---

## 5. 권장 구현 순서

### Step 1. 이벤트 계약 확장

수정 대상:

- `payment-service`의 `PaymentCreateResponseEvent`
- `delivery-service`의 `DeliveryCreateResponseEvent`
- `commerce-service`의 동일 이벤트 수신 DTO

핵심 작업:

- 생산자/소비자 양쪽 이벤트 필드 동기화
- nullable 가능한 배송 필드 범위 명확화
- JSON 역직렬화 호환성 점검

### Step 2. Order 엔티티 확장

수정 대상:

- `commerce-service/domain/model/Order.java`

핵심 작업:

- 결제/배송 스냅샷 필드 추가
- 스냅샷 갱신 메서드 추가
- 상태 전이 메서드 정리

### Step 3. 이벤트 소비 로직 확장

수정 대상:

- `commerce-service/application/service/OrderEventService.java`
- `commerce-service/infrastructure/messaging/OrderMessagingConsumerService.java`

핵심 작업:

- 이벤트 수신 시 snapshot 저장
- 배송 완료/취소 시 snapshot 상태 갱신
- 보상 트랜잭션과 snapshot 정합성 같이 검토

### Step 4. 주문 조회 API 전환

수정 대상:

- `commerce-service/application/service/OrderService.java`
- `commerce-service/application/dto/OrderResponseDto.java`
- 필요 시 신규 snapshot DTO

핵심 작업:

- `getOrderById()`에서 Feign 호출 제거
- 로컬 `Order` 데이터만으로 응답 조립
- 조회 실패 원인을 외부 서비스 장애가 아닌 로컬 데이터 부재로 한정

### Step 5. 잔여 Feign 사용처 정리

수정 대상:

- `cancelOrder()`의 배송 조회 의존성 재검토
- `PaymentClient`, `DeliveryClient` 실제 사용처 전수 확인

핵심 작업:

- 삭제 가능 시 Feign 인터페이스 및 fallback 제거
- 삭제가 어렵다면 “조회 API 전용”으로만 범위를 축소

---

## 6. 파일 단위 변경 예상 목록

### 6.1 commerce-service

추가/수정 예상 파일:

- `domain/model/Order.java`
- `application/service/OrderService.java`
- `application/service/OrderEventService.java`
- `infrastructure/messaging/OrderMessagingConsumerService.java`
- `application/dto/OrderResponseDto.java`
- `infrastructure/messaging/events/PaymentCreateResponseEvent.java`
- `infrastructure/messaging/events/DeliveryCreateResponseEvent.java`
- `application/dto/OrderPaymentSnapshotDto.java` 신규 권장
- `application/dto/OrderDeliverySnapshotDto.java` 신규 권장
- `infrastructure/feign/PaymentClient.java` 삭제 후보
- `infrastructure/feign/DeliveryClient.java` 삭제 후보
- `infrastructure/feign/dto/PaymentClientDto.java` 삭제 또는 축소 후보
- `infrastructure/feign/dto/DeliveryClientDto.java` 삭제 또는 축소 후보

### 6.2 payment-service

추가/수정 예상 파일:

- `application/service/PaymentService.java`
- `infrastructure/messaging/events/PaymentCreateResponseEvent.java`

### 6.3 delivery-service

추가/수정 예상 파일:

- `application/service/DeliveryApplicationService.java`
- `application/events/DeliveryCreateResponseEvent.java`

---

## 7. 데이터 모델링 시 주의사항

### 7.1 스냅샷은 원본 데이터의 대체가 아니다

`Order`에 저장하는 결제/배송 정보는 원본 서비스의 정식 소유 데이터가 아니라, **주문 조회를 위한 로컬 복제본**이다.
따라서 다음 원칙을 문서에 명확히 남겨야 한다.

- Payment의 정본은 `payment-service`
- Delivery의 정본은 `delivery-service`
- Commerce의 스냅샷은 조회 최적화와 서비스 자립성 확보 목적

### 7.2 스냅샷 필드 변경 범위 최소화

배송/결제 원본 엔티티의 모든 필드를 복제할 필요는 없다.
현재 주문 상세 응답에 필요한 필드만 우선 포함하는 편이 좋다.

기준:

- 사용자 화면에 노출되는가
- 주문 상태 판단에 필요한가
- 후속 비즈니스 로직에서 참조하는가

### 7.3 이벤트 누락 대응

스냅샷 기반 조회는 이벤트 누락 시 데이터 공백이 생길 수 있다.
이를 완화하기 위해 최소한 다음 대응이 필요하다.

- 스냅샷 필드 nullable 허용
- 응답 DTO에서 `paymentInfo`, `deliveryInfo`가 없을 수 있음을 허용
- 운영 중 필요 시 재동기화 배치 또는 수동 복구 스크립트 고려

---

## 8. 테스트 계획

### 8.1 commerce-service 단위 테스트

추가 권장 케이스:

- `payment-create-response` 수신 시 `Order`에 결제 스냅샷이 저장되는지
- `delivery-create-response` 수신 시 `Order`에 배송 스냅샷이 저장되는지
- `delivery-end-response` 수신 시 `deliveryStatus=DELIVERED`, `orderStatus=COMPLETED`로 반영되는지
- `getOrderById()`가 Feign 없이도 응답 조립 가능한지

### 8.2 계약 검증 관점 테스트

서비스 간 이벤트 DTO가 바뀌므로 다음 검증이 필요하다.

- `payment-service`가 발행한 메시지를 `commerce-service`가 역직렬화 가능한지
- `delivery-service`가 발행한 메시지를 `commerce-service`가 역직렬화 가능한지
- 신규 필드 추가 후에도 기존 필드만 사용하는 소비 로직이 깨지지 않는지

### 8.3 회귀 테스트 포인트

- 주문 생성 → 결제 완료 → 배송 생성 → 주문 상세 조회
- 배송 시작/완료 후 주문 상세 조회 상태 반영
- 주문 취소 시 배송/결제 스냅샷이 어떻게 남는지

---

## 9. 예상 리스크와 대응

### 리스크 1. 이벤트 스키마 변경으로 인한 소비자/생산자 불일치

대응:

- producer/consumer를 같은 브랜치에서 함께 수정
- 기본 생성자 유지
- 새 필드는 역직렬화 친화적으로 nullable 허용

### 리스크 2. Order 엔티티 비대화

대응:

- 이번 단계는 조회 핵심 필드만 저장
- 과도하게 커지면 Phase 3에서 `OrderReadModel`로 분리

### 리스크 3. 취소/실패 상태 표현 불명확

대응:

- `deliveryId`, `paymentId`를 무조건 null로 지우지 말고, 상태 필드 중심으로 의미를 표현
- “생성되지 않음”, “생성됨”, “취소됨”, “완료됨”을 구분해 문서화

---

## 10. 최종 권고

Phase 2는 아래 범위로 수행하는 것이 가장 적절하다.

- `payment-create-response`, `delivery-create-response` 이벤트를 조회 친화적으로 보강한다.
- `commerce-service`의 `Order`에 결제/배송 스냅샷 필드를 추가한다.
- `OrderEventService`가 이벤트 수신 시 스냅샷을 갱신하도록 변경한다.
- `OrderService.getOrderById()`에서 `PaymentClient`, `DeliveryClient` 의존성을 제거한다.
- `cancelOrder()` 등 잔여 사용처는 별도로 정리하되, 이번 단계의 1차 목표는 **주문 조회 경로 Feign 제거**로 한정한다.

이 방식이면 Phase 2 범위 안에서 다음 효과를 얻을 수 있다.

- 주문 상세 조회의 외부 서비스 장애 전파 제거
- 조회 응답 시간 단축
- Commerce Service의 조회 자립성 확보
- 이후 Phase 3 CQRS 도입 시 자연스러운 확장 경로 확보
