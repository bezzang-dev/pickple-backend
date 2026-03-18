# CQRS + ElasticSearch 데이터 정합성 개선 보고서

**작성일**: 2026-03-18
**대상 모듈**: commerce-service

---

## 1. 배경

Pickple 백엔드는 CQRS 패턴을 적용하여 상품 데이터의 쓰기(RDB/JPA)와 검색(ElasticSearch)을 분리하고 있습니다.
RDB와 ES 간의 동기화는 Kafka 이벤트를 통해 비동기로 수행됩니다.

기존 구현에서 데이터 정합성에 영향을 줄 수 있는 설계적 취약점을 분석하고, 우선순위별로 개선을 수행했습니다.

---

## 2. 기존 문제점 분석

### 2.1 [P0] Product 이벤트에 Outbox 패턴 미적용

**기존 흐름:**
```
ProductService.createProduct()          // @Transactional 없음
  ├─ productCommandService.createProduct()  // @Transactional → 커밋
  └─ productMessagingProducerService.sendProductCreatedEvent()  // Kafka 직접 발행
```

**문제점:**
- `ProductCommandService`의 트랜잭션이 먼저 커밋된 후, Kafka 발행이 별도로 수행됨
- RDB 저장 성공 후 Kafka 발행 실패 시 ES에 데이터가 영원히 반영되지 않음
- Payment/Delivery 서비스는 이미 `@TransactionalEventListener(AFTER_COMMIT)` 기반 Outbox 패턴을 사용하고 있었으나, Product 이벤트는 미적용

### 2.2 [P0] Kafka Consumer 예외 무시 (Silent Swallow)

**기존 코드:**
```java
@KafkaListener(topics = "${kafka.topic.product-created}", groupId = "commerce-service")
public void listenProductCreated(String message) {
    try {
        ProductCreatedEvent event = EventSerializer.deserialize(message, ProductCreatedEvent.class);
        productEventService.handleProductCreated(event);
    } catch (RuntimeException e) {
        log.error("Failed to deserialize ProductCreatedEvent: {}", e.getMessage());
        // 예외를 삼킴 → Kafka offset 커밋 → 메시지 영구 유실
    }
}
```

**문제점:**
- 역직렬화 실패뿐 아니라 ES 저장 실패도 모두 catch하여 삼김
- Kafka offset이 커밋되어 해당 메시지의 재처리가 불가능
- Dead Letter Queue가 없어 실패 메시지 추적/복구 불가
- 3개 리스너(`product-created`, `product-updated`, `product-deleted`) 모두 동일 패턴

### 2.3 [P1] 재고(Stock) 변경이 ES에 미반영

**문제점:**
- `ProductUpdatedEvent`에 stock 정보가 포함되지 않음
- 주문 처리 시 `OrderEventService.handlePaymentComplete()`에서 재고 차감 후 ES 동기화 없음
- ES의 `stockQuantity`는 상품 최초 생성 시점 값에서 영원히 고정

### 2.4 [P2] Kafka 메시지 키 미설정

**문제점:**
- Product 이벤트 발행 시 파티션 키 없이 전송
- 동일 상품의 Create → Update → Delete 이벤트가 서로 다른 파티션에 분배될 수 있음
- 파티션이 다르면 순서 보장이 깨져 Create 전에 Update가 처리될 수 있음

### 2.5 [P2] 비일관적 에러 처리 패턴

| 서비스 | Consumer | 에러 처리 방식 |
|--------|----------|---------------|
| Payment/Delivery | `OrderMessagingConsumerService` | 역직렬화 실패 시 `CustomException` throw (재시도 가능) |
| Product ES 동기화 | `ProductMessagingConsumerService` | 모든 예외 catch → log만 (재시도 불가) |

---

## 3. 개선 내용

### 3.1 [P0] Product 이벤트에 Outbox 패턴 적용

#### 변경 파일
| 파일 | 변경 유형 |
|------|-----------|
| `application/events/KafkaOutboxEvent.java` | **신규** |
| `application/events/KafkaOutboxEventListener.java` | **신규** |
| `application/service/ProductService.java` | **수정** |
| `application/service/ProductMessagingProducerService.java` | **삭제** |

#### 개선된 흐름
```
ProductService.createProduct()                    // @Transactional
  ├─ productCommandService.createProduct()         // 동일 트랜잭션에 참여
  └─ applicationEventPublisher.publishEvent(       // Spring 내부 이벤트 발행
         new KafkaOutboxEvent(topic, key, event))

KafkaOutboxEventListener.onKafkaOutboxEvent()      // AFTER_COMMIT 시점에 Kafka 발행
  └─ kafkaTemplate.send(topic, key, payload)
```

#### 핵심 변경 사항
- `ProductService`에 `@Transactional` 추가 → `ProductCommandService`와 동일 트랜잭션에서 실행
- `ApplicationEventPublisher`를 통해 `KafkaOutboxEvent` 발행
- `KafkaOutboxEventListener`가 `@TransactionalEventListener(phase = AFTER_COMMIT)`으로 트랜잭션 커밋 이후에만 Kafka 메시지 발행
- 트랜잭션 롤백 시 Kafka 메시지가 발행되지 않아 RDB-ES 간 불일치 방지
- 기존 `ProductMessagingProducerService`는 더 이상 사용되지 않아 삭제

### 3.2 [P0+P1] Consumer 예외 처리 개선 및 Dead Letter Queue 도입

#### 변경 파일
| 파일 | 변경 유형 |
|------|-----------|
| `infrastructure/configuration/KafkaConsumerConfig.java` | **신규** |
| `infrastructure/messaging/ProductMessagingConsumerService.java` | **수정** |

#### 개선 내용

**KafkaConsumerConfig:**
- `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` 조합으로 DLQ 기반 에러 처리
- 최대 3회 재시도 (1초 간격) → 모든 재시도 실패 시 `{원본토픽}.DLT` 토픽으로 메시지 전송
- `DeserializationException`은 재시도 없이 즉시 DLQ로 전송 (비복구성 에러)

**ProductMessagingConsumerService:**
- **기존**: try-catch로 모든 예외를 삼김 → 메시지 유실
- **개선**: 예외를 throw하여 Spring Kafka 에러 핸들러에 위임 → 재시도 후 DLQ

```java
// 개선 전
try {
    productEventService.handleProductCreated(event);
} catch (RuntimeException e) {
    log.error("Failed: {}", e.getMessage());  // 예외 삼킴
}

// 개선 후
ProductCreatedEvent event = EventSerializer.deserialize(message, ProductCreatedEvent.class);
productEventService.handleProductCreated(event);
// 예외 발생 시 → DefaultErrorHandler → 3회 재시도 → DLQ
```

### 3.3 [P1] Stock 변경 시 ES 동기화 이벤트 추가

#### 변경 파일
| 파일 | 변경 유형 |
|------|-----------|
| `infrastructure/messaging/events/StockUpdatedEvent.java` | **신규** |
| `application/service/StockService.java` | **수정** |
| `application/service/ProductEventService.java` | **수정** |
| `domain/model/ProductDocument.java` | **수정** |
| `infrastructure/messaging/ProductMessagingConsumerService.java` | **수정** |
| `application-dev.yml`, `application-prod.yml` | **수정** |

#### 개선 흐름
```
StockService.decreaseStockQuantityForOrder()       // @Transactional
  ├─ stock.decreaseStockQuantity()                  // RDB 재고 차감
  └─ publishStockUpdatedEvent(stock)                // Outbox 이벤트 발행
       └─ KafkaOutboxEvent(stock-updated, productId, StockUpdatedEvent)

ProductMessagingConsumerService.listenStockUpdated() // Kafka Consumer
  └─ ProductEventService.handleStockUpdated()        // ES 문서 업데이트
       └─ productDocument.updateStock(stockId, stockQuantity)
```

#### 핵심 변경 사항
- `StockUpdatedEvent` 신규 생성 (`productId`, `stockId`, `stockQuantity`)
- `StockService`의 모든 재고 변경 메서드에서 `publishStockUpdatedEvent()` 호출
- 재고 이벤트도 Outbox 패턴 사용 → 트랜잭션 커밋 후에만 Kafka 발행
- `ProductDocument`에 `updateStock()` 메서드 추가
- `ProductEventService`에 `handleStockUpdated()` 핸들러 추가
- `application-dev.yml`, `application-prod.yml`에 `stock-updated` 토픽 추가

### 3.4 [P2] Kafka 메시지 키 설정으로 순서 보장

#### 개선 내용
`KafkaOutboxEvent`에 `key` 필드를 추가하고, 모든 Product/Stock 이벤트 발행 시 `productId`를 파티션 키로 설정합니다.

```java
// KafkaOutboxEventListener
kafkaTemplate.send(event.getTopic(), event.getKey(), event.getPayload());
```

**효과:**
- 동일 `productId`의 이벤트는 항상 같은 파티션으로 전송
- Kafka의 파티션 내 순서 보장에 의해 Create → Update → Delete 순서가 보장됨
- ES에서 순서 역전으로 인한 데이터 불일치 방지

### 3.5 [P2] Consumer 에러 처리 패턴 통일

`KafkaConsumerConfig`에서 정의한 `DefaultErrorHandler`가 `kafkaListenerContainerFactory`에 등록되어, 모든 `@KafkaListener`에 동일한 에러 처리 전략이 적용됩니다.

---

## 4. 변경 파일 전체 목록

| 파일 경로 | 유형 | 설명 |
|-----------|------|------|
| `application/events/KafkaOutboxEvent.java` | 신규 | Outbox 패턴 이벤트 클래스 |
| `application/events/KafkaOutboxEventListener.java` | 신규 | AFTER_COMMIT Kafka 발행 리스너 |
| `infrastructure/configuration/KafkaConsumerConfig.java` | 신규 | DLQ + 재시도 에러 핸들러 설정 |
| `infrastructure/messaging/events/StockUpdatedEvent.java` | 신규 | 재고 변경 이벤트 클래스 |
| `application/service/ProductService.java` | 수정 | Outbox 패턴 적용, @Transactional 추가 |
| `application/service/StockService.java` | 수정 | 재고 변경 시 ES 동기화 이벤트 발행 |
| `application/service/ProductEventService.java` | 수정 | handleStockUpdated 핸들러 추가 |
| `domain/model/ProductDocument.java` | 수정 | updateStock 메서드 추가 |
| `infrastructure/messaging/ProductMessagingConsumerService.java` | 수정 | 예외 처리 개선, 재고 리스너 추가 |
| `application-dev.yml` | 수정 | stock-updated 토픽 추가 |
| `application-prod.yml` | 수정 | stock-updated 토픽 추가 |
| `application/service/ProductMessagingProducerService.java` | 삭제 | Outbox 패턴으로 대체 |

---

## 5. 개선 전/후 비교

| 위험 요소 | 개선 전 | 개선 후 |
|-----------|---------|---------|
| RDB 커밋 후 Kafka 발행 실패 | 데이터 영구 불일치 | Outbox 패턴으로 트랜잭션 연계 보장 |
| Consumer 예외 시 메시지 유실 | 예외 삼킴 → offset 커밋 → 유실 | 3회 재시도 → DLQ 전송 → 복구 가능 |
| 재고 변경 ES 미반영 | ES stockQuantity 고정 | StockUpdatedEvent로 실시간 동기화 |
| 이벤트 순서 역전 | 파티션 키 없음 → 순서 미보장 | productId 기반 파티션 키 → 순서 보장 |
| 에러 처리 비일관성 | Consumer별 상이한 에러 처리 | KafkaConsumerConfig로 통일 |

---

## 6. 아키텍처 다이어그램

### 개선 후 Product CQRS 흐름

```
[Client Request]
       │
       ▼
┌─────────────────────────────┐
│     ProductService          │  @Transactional
│  ┌──────────────────────┐   │
│  │ ProductCommandService │   │  RDB 저장
│  └──────────────────────┘   │
│  ┌──────────────────────┐   │
│  │ ApplicationEvent      │   │  KafkaOutboxEvent 발행
│  │ Publisher             │   │
│  └──────────────────────┘   │
└─────────────────────────────┘
       │ (AFTER_COMMIT)
       ▼
┌─────────────────────────────┐
│ KafkaOutboxEventListener    │  Kafka 메시지 발행 (with key)
└─────────────────────────────┘
       │
       ▼
   [ Kafka Topic ]
   (product-created/updated/deleted/stock-updated)
       │
       ▼
┌─────────────────────────────┐
│ ProductMessagingConsumer     │  예외 → 3회 재시도 → DLQ
│  └─ ProductEventService     │  ES 문서 저장/수정
│      └─ ProductSearch       │
│         Repository          │
└─────────────────────────────┘
       │
       ▼
  [ ElasticSearch ]
```
