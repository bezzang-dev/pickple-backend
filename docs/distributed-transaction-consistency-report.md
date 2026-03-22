# 분산 트랜잭션 데이터 정합성 개선 보고서

## 1. 개요

Pickple MSA 프로젝트의 주문-결제-배송 Saga 흐름에서 **데이터 정합성이 깨질 수 있는 4가지 문제**를 식별하고 수정했습니다.

**주문 처리 Saga 흐름:**

```
주문 생성 → 결제 요청 → 결제 완료 → 재고 차감 → 배송 생성 → 배송 완료 → 주문 완료
```

---

## 2. Critical #1 — Payment Service의 Kafka 이벤트가 트랜잭션 커밋 전에 발행되는 문제

**파일**: `PaymentService.java`, `PaymentEventService.java`

### 수정 전 문제

```java
// PaymentService.createPayment() — 수정 전
@Transactional
public void createPayment(UUID orderId, String userName, BigDecimal amount) {
    Payment payment = new Payment(orderId, userName, amount);
    paymentRepository.save(payment);       // ① DB 저장 (아직 커밋 안 됨)

    payment.success();
    paymentRepository.save(payment);       // ② 상태 변경 (아직 커밋 안 됨)

    PaymentCreateResponseEvent event = new PaymentCreateResponseEvent(...);
    paymentEventService.sendCreateSuccessEvent(event);  // ③ Kafka 즉시 발행!
    // → 이 시점에서 트랜잭션이 롤백되면?
}
```

```java
// PaymentEventService — 수정 전: KafkaTemplate 직접 사용
public void sendCreateSuccessEvent(PaymentCreateResponseEvent event) {
    kafkaTemplate.send("payment-create-response", EventSerializer.serialize(event));
}
```

### 장애 시나리오

```
① Payment DB에 결제 저장 (미커밋)
② Kafka에 payment-create-response 발행
③ Commerce Service가 메시지를 수신하여 재고 차감 + 배송 요청
④ Payment 트랜잭션이 DB 제약조건 등으로 롤백
⑤ 결과: 결제 데이터 없음 / 재고는 차감됨 / 배송은 생성됨
```

### 수정 후

```java
// PaymentEventService — 수정 후: ApplicationEventPublisher 사용
@Service
@RequiredArgsConstructor
public class PaymentEventService {

    private final ApplicationEventPublisher applicationEventPublisher;

    public void sendCreateSuccessEvent(PaymentCreateResponseEvent event) {
        applicationEventPublisher.publishEvent(
                new KafkaOutboxEvent("payment-create-response",
                        EventSerializer.serialize(event)));
    }
}
```

```java
// KafkaOutboxEventListener — 신규: AFTER_COMMIT 시점에만 발행
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void onKafkaOutboxEvent(KafkaOutboxEvent event) {
    kafkaTemplate.send(event.getTopic(), event.getPayload());
}
```

### 변경 파일

| 파일 | 변경 내용 |
|------|----------|
| `KafkaOutboxEvent.java` | 신규 — Spring 내부 이벤트 객체 |
| `KafkaOutboxEventListener.java` | 신규 — `AFTER_COMMIT` 리스너 |
| `PaymentEventService.java` | `KafkaTemplate` → `ApplicationEventPublisher`로 교체 |
| `PaymentService.java` | 실패 시 catch 블록에서 이벤트 발행 + 예외 throw 패턴 제거 |

### 효과

트랜잭션이 롤백되면 `AFTER_COMMIT` 리스너가 실행되지 않으므로, Kafka 메시지가 발행되지 않습니다. Delivery Service에 이미 적용된 Outbox 패턴과 동일한 방식으로 통일되었습니다.

---

## 3. Critical #2 — 주문 취소 시 트랜잭션 내 Kafka + Feign 호출 혼재

**파일**: `OrderService.java`

### 수정 전 문제

```java
// OrderService.cancelOrder() — 수정 전
@Transactional
public OrderResponseDto cancelOrder(UUID orderId, String username, String role) {
    Order order = orderRepository.findById(orderId)...;

    order.changeStatus(OrderStatus.CANCELED);     // ① 주문 취소
    orderRepository.save(order);                  // ② DB 저장 (미커밋)

    messagingProducerService.sendPaymentCancelRequest(orderId);  // ③ Kafka 즉시 발행!

    DeliveryClientDto deliveryInfo =
        deliveryClient.getDeliveryInfo(role, username, orderId).getData();  // ④ Feign 호출

    if (deliveryInfo != null) {
        messagingProducerService.sendDeliveryDeleteRequest(...);  // ⑤ Kafka 즉시 발행!
    }
}
```

### 장애 시나리오

```
① 주문 CANCELED 설정 (미커밋)
② Kafka: payment-cancel-request 발행됨
③ Feign: deliveryClient.getDeliveryInfo() 호출 → 타임아웃/500 에러 발생
④ 트랜잭션 롤백 → 주문은 다시 PENDING 상태
⑤ 결과: 주문은 PENDING / 결제는 취소됨 — 정합성 깨짐
```

### 수정 후

```java
// OrderService.cancelOrder() — 수정 후
@Transactional
public OrderResponseDto cancelOrder(UUID orderId, String username, String role) {
    Order order = orderRepository.findById(orderId)...;

    // ① Feign 조회를 먼저 수행 — 실패 시 여기서 롤백, Kafka 미발행
    DeliveryClientDto deliveryInfo = null;
    try {
        deliveryInfo = deliveryClient.getDeliveryInfo(role, username, orderId).getData();
    } catch (FeignException e) {
        log.warn("배송 정보 조회 실패. orderId: {}", orderId);
    }

    order.changeStatus(OrderStatus.CANCELED);
    orderRepository.save(order);

    // ② 트랜잭션 커밋 후에만 Kafka 발행
    final DeliveryClientDto finalDeliveryInfo = deliveryInfo;
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
            messagingProducerService.sendPaymentCancelRequest(orderId);
            if (finalDeliveryInfo != null && finalDeliveryInfo.getDeliveryId() != null) {
                messagingProducerService.sendDeliveryDeleteRequest(...);
            }
        }
    });
}
```

### `handleOrderTimeout()`도 동일한 패턴 적용

```java
// 수정 전: 트랜잭션 내 Kafka 직접 발행
messagingProducerService.sendPaymentCancelRequest(orderId); // Kafka 즉시 발행
order.changeStatus(OrderStatus.CANCELED);

// 수정 후: afterCommit으로 이동
order.changeStatus(OrderStatus.CANCELED);
orderRepository.save(order);
TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
    @Override
    public void afterCommit() {
        messagingProducerService.sendPaymentCancelRequest(orderId);
    }
});
```

### 효과

Feign 호출 실패 시 트랜잭션만 롤백되고, 커밋이 성공한 경우에만 Kafka 메시지가 발행됩니다.

---

## 4. Critical #3 — OrderEventService의 직접 Kafka 발행 제거

**파일**: `OrderEventService.java`

### 수정 전 문제 — 두 가지 메서드

```java
// handlePaymentComplete() — 수정 전
@Transactional
public void handlePaymentComplete(UUID orderId, UUID paymentId) {
    decreaseStockForOrder(order);           // 재고 차감
    order.assignPaymentId(paymentId);
    orderRepository.save(order);            // 미커밋
    messagingProducerService.sendDeliveryCreateRequest(orderId, ...);  // Kafka 즉시 발행!
}

// handleDeliveryCancel() — 수정 전: KafkaTemplate 직접 사용
@Transactional
public void handleDeliveryCancel(UUID orderId) {
    sendPaymentCancelRequest(orderId);      // kafkaTemplate.send() 직접 호출!
    order.assignDeliveryId(null);
    orderRepository.save(order);            // 미커밋
}
```

### 장애 시나리오 (handlePaymentComplete)

```
① 재고 차감 성공
② Kafka: delivery-create-request 발행됨
③ orderRepository.save() 후 DB 커밋 실패
④ 트랜잭션 롤백 → 재고 복구, paymentId 미할당
⑤ 결과: 재고는 원래대로 / 배송은 생성됨 — 정합성 깨짐
```

### 수정 후

```java
// handlePaymentComplete() — 수정 후
@Transactional
public void handlePaymentComplete(UUID orderId, UUID paymentId) {
    decreaseStockForOrder(order);
    order.assignPaymentId(paymentId);
    orderRepository.save(order);

    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
            messagingProducerService.sendDeliveryCreateRequest(orderId, username);
        }
    });
}

// handleDeliveryCancel() — 수정 후: KafkaTemplate 의존성 제거
@Transactional
public void handleDeliveryCancel(UUID orderId) {
    order.assignDeliveryId(null);
    orderRepository.save(order);

    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
            messagingProducerService.sendPaymentCancelRequest(orderId);
        }
    });
}
```

### 효과

`OrderEventService`에서 `KafkaTemplate` 의존성을 완전히 제거하고, 모든 Kafka 발행이 `afterCommit` 시점에 수행됩니다.

---

## 5. High — Consumer 실패 시 보상 트랜잭션 부재

**파일**: `OrderMessagingConsumerService.java`

### 수정 전 문제

```java
// listenPaymentCreateResponse() — 수정 전
@KafkaListener(topics = "payment-create-response", groupId = "commerce-service")
public void listenPaymentCreateResponse(String message) {
    PaymentCreateResponseEvent event = EventSerializer.deserialize(message, ...);
    orderEventService.handlePaymentComplete(event.getOrderId(), event.getPaymentId());
    // → 재고 부족으로 예외 발생 시? 보상 로직 없음!
}
```

### 장애 시나리오

```
① Payment Service에서 결제 완료 (COMPLETED)
② Commerce Service가 payment-create-response 수신
③ handlePaymentComplete() 내 재고 차감 실패 (INSUFFICIENT_STOCK)
④ 예외 발생 → Kafka 재시도 → 반복 실패 → 메시지 유실
⑤ 결과: 결제는 완료 / 재고 미차감 / 배송 미생성 — 보상 없이 방치
```

### 수정 후

```java
// listenPaymentCreateResponse() — 수정 후
@KafkaListener(topics = "payment-create-response", groupId = "commerce-service")
public void listenPaymentCreateResponse(String message) {
    PaymentCreateResponseEvent event = EventSerializer.deserialize(message, ...);

    try {
        orderEventService.handlePaymentComplete(event.getOrderId(), event.getPaymentId());
    } catch (Exception e) {
        log.error("결제 완료 처리 실패, 보상 트랜잭션으로 결제 취소 요청. orderId: {}", orderId);
        messagingProducerService.sendPaymentCancelRequest(orderId);
    }
}
```

### 효과

`handlePaymentComplete()` 실패 시 `payment-cancel-request`를 발행하여 Payment Service가 결제를 롤백하도록 자동 보상합니다.

---

## 6. 수정 전후 비교 요약

| 문제 | 수정 전 | 수정 후 |
|------|---------|---------|
| Payment Kafka 발행 | 트랜잭션 내 `kafkaTemplate.send()` 직접 호출 | `@TransactionalEventListener(AFTER_COMMIT)` |
| 주문 취소 | Kafka 발행 → Feign 호출 (순서 역전, 커밋 전 발행) | Feign 조회 → DB 저장 → `afterCommit`에서 Kafka 발행 |
| OrderEventService | `kafkaTemplate.send()` 직접 호출, `KafkaTemplate` 의존 | `afterCommit` 콜백, `KafkaTemplate` 의존성 제거 |
| Consumer 실패 | 예외 발생 시 무한 재시도 후 유실 | catch로 `payment-cancel-request` 보상 트랜잭션 발행 |

## 7. 적용된 패턴 통일

수정 후 전체 서비스에서 Kafka 발행 패턴이 통일되었습니다:

| 서비스 | 패턴 | 상태 |
|--------|------|------|
| **Delivery Service** | `@TransactionalEventListener(AFTER_COMMIT)` + Outbox Event | 기존 구현 |
| **Payment Service** | `@TransactionalEventListener(AFTER_COMMIT)` + Outbox Event | 이번에 적용 |
| **Commerce Service** | `TransactionSynchronizationManager.afterCommit()` | 이번에 통일 |
