# 마이크로서비스 데이터 주권 위반 분석 및 아키텍처 개선안

## 1. 현황 요약

pickple-backend는 **Kafka 기반 이벤트 드리븐 아키텍처**를 채택했지만,
일부 서비스에서 **동기 Feign 호출**이 남아있어 서비스 간 런타임 결합이 존재한다.

### 1.1 전체 Feign 호출 맵

| 호출 서비스 | 대상 서비스 | Feign 메서드 | 조회 데이터 | 호출 시점 | 심각도 |
|------------|-----------|-------------|-----------|----------|--------|
| **Delivery** | Commerce | `getUsernameByDeliveryId()` | username | 배송 시작/완료 알림 | **Critical** |
| **Notification** | User | `getUserEmail()` | email | 이메일 발송 | **Critical** |
| **Commerce** | Payment | `getPaymentInfo()` | 결제 상세 | 주문 상세 조회 | Medium |
| **Commerce** | Delivery | `getDeliveryInfo()` | 배송 상세 | 주문 상세 조회/취소 | Medium |
| Auth | User | `getUserByUsername()` | 사용자 인증 정보 | 인증 시 | 허용 (보안 경계) |

```
                    ┌─────────────┐
                    │ User Service│
                    └──────┬──────┘
                           │
              ┌────────────┼────────────────┐
              │ Feign      │ Feign           │ Feign
              ▼            ▼                 ▼
        ┌──────────┐  ┌──────────┐   ┌──────────────┐
        │   Auth   │  │ Gateway  │   │ Notification │
        └──────────┘  └──────────┘   └──────────────┘
                                           ▲
                                           │ Kafka
                           ┌───────────────┤
                           │               │
                    ┌──────┴──────┐  ┌─────┴──────┐
                    │  Commerce   │◄─┤  Delivery   │
                    │  Service    │  │  Service    │
                    └──┬─────┬───┘  └──────┬──────┘
                       │     │             │
              Feign ◄──┘     └──► Feign    └──► Feign
              ▼                   ▼              ▼
        ┌──────────┐       ┌──────────┐   ┌──────────┐
        │ Payment  │       │ Delivery │   │ Commerce │
        │ Service  │       │ Service  │   │ Service  │
        └──────────┘       └──────────┘   └──────────┘
```

---

## 2. 근본 원인 분석

### 2.1 원인 1: 이벤트 설계 시 "소비자의 미래 요구사항" 미고려

이벤트를 설계할 때 **발행자(producer) 관점**에서만 데이터를 구성하고,
**소비자(consumer)가 향후 필요로 할 데이터**를 포함하지 않았다.

```
[실제 흐름]
Commerce ──DeliveryCreateRequestEvent──> Delivery
            (orderId, recipientInfo, username ✅)
                                           │
                                           ▼
                                   Delivery 생성 시 username 버림 ❌
                                           │
                                           ▼ (나중에 알림 필요)
Delivery ──Feign──> Commerce (username 다시 조회) ❌

[있어야 할 흐름]
Commerce ──DeliveryCreateRequestEvent──> Delivery
            (orderId, recipientInfo, username)
                                           │
                                           ▼
                                   Delivery 도메인에 username 저장 ✅
                                           │
                                           ▼ (알림 필요)
                                   delivery.getUsername() 으로 즉시 사용 ✅
```

### 2.2 원인 2: 도메인 모델이 "자기 완결적"이지 않음

각 서비스의 도메인 모델이 **자신의 비즈니스 로직을 수행하는 데 필요한 데이터를 모두 갖고 있지 않다.**

| 서비스 | 도메인 | 누락 필드 | 필요 시점 |
|--------|--------|----------|----------|
| Delivery | Delivery | `username` | 알림 발송 |
| Notification | (이벤트 수신) | `email` | 이메일 발송 |
| Commerce | Order | 결제 상세, 배송 상세 | 주문 상세 조회 |

### 2.3 원인 3: 읽기 경로(Read Path)와 쓰기 경로(Write Path) 미분리

현재 구조는 쓰기(상태 변경)는 Kafka 이벤트로, 읽기(조회)는 Feign 동기 호출로 처리한다.
이는 **CQRS 패턴의 불완전한 적용**이다.

```
[현재 - 읽기/쓰기 혼재]
주문 상세 조회 → OrderService.getOrderById()
  ├─ Order DB 조회 (자체)
  ├─ Feign → Payment Service (결제 정보)
  └─ Feign → Delivery Service (배송 정보)

응답 시간 = Order DB + Payment Feign + Delivery Feign
장애 전파 = 3개 서비스 중 1개라도 다운되면 실패
```

### 2.4 원인 4: 이벤트 응답(Response Event)의 데이터 부족

현재 응답 이벤트는 **최소한의 ID만** 전달하고 있어, 수신 측에서 상세 정보를 알 수 없다.

```java
// DeliveryCreateResponseEvent - deliveryId만 전달
new DeliveryCreateResponseEvent(orderId, deliveryId)

// PaymentCreateResponseEvent - paymentId만 전달
new PaymentCreateResponseEvent(orderId, paymentId, "CREDIT-CARD", "COMPLETED")
```

이 때문에 Commerce Service는 주문 상세 조회 시 결제/배송 정보를 Feign으로 다시 가져와야 한다.

---

## 3. 서비스별 데이터 주권 위반 상세 분석

### 3.1 [Critical] Delivery Service → Commerce Service

**현재 문제:**
```java
// DeliveryApplicationService.sendNotification()
String username = orderClient.getUsernameByDeliveryId(
        delivery.getDeliveryId(), role, sender);
```

**데이터 흐름 추적:**

```
1. 주문 생성 시 → Order에 username 저장
2. 결제 완료 후 → DeliveryCreateRequestEvent에 username 포함하여 Kafka 발행 ✅
3. 배송 생성 시 → DeliveryMapper에서 username을 DTO에 매핑하지 않음 ❌
4. Delivery 도메인 → username 필드 자체가 없음 ❌
5. 배송 알림 시 → Feign으로 다시 조회 ❌
```

**근본 원인:** 이미 이벤트로 전달받은 데이터를 도메인에 저장하지 않는 설계 누락

**위험:**
- Commerce Service 장애 시 배송 시작/완료 자체가 실패
- `@Transactional` 메서드 내 외부 HTTP 호출 → DB 커넥션 점유 + 타임아웃 리스크
- CircuitBreaker fallback이 예외를 던져 핵심 로직까지 실패

### 3.2 [Critical] Notification Service → User Service

**현재 문제:**
```java
// EmailService.sendEmail()
String email = userFeignClient.getUserEmail(username, sender, role);
```

**데이터 흐름 추적:**

```
1. Commerce/Delivery → email-create-request 이벤트에 username만 포함
2. Notification Service → username으로 email을 조회하기 위해 Feign 호출
3. User Service → DB에서 email 반환
```

**근본 원인:** 이벤트 발행 시점에 email을 포함하지 않은 이벤트 설계 문제

**위험:**
- User Service 장애 시 모든 이메일 알림 발송 실패
- 알림은 부수 효과(side effect)인데, 외부 서비스 의존으로 신뢰성 저하

### 3.3 [Medium] Commerce Service → Payment/Delivery Service

**현재 문제:**
```java
// OrderService.getOrderById()
PaymentClientDto payment = paymentClient.getPaymentInfo(authority, username, orderId);
DeliveryClientDto delivery = deliveryClient.getDeliveryInfo(authority, username, orderId);
```

**근본 원인:** 응답 이벤트(`PaymentCreateResponseEvent`, `DeliveryCreateResponseEvent`)가 ID만 전달하여, 상세 조회 시 Feign이 필요

**위험:**
- 읽기 경로에서 3개 서비스 동시 의존
- 주문 상세 조회 응답 시간 = max(Payment Feign, Delivery Feign) + Order DB 조회

---

## 4. 개선안

### 4.1 개선안 A: 이벤트 보강 + 도메인 필드 추가 (권장, 즉시 적용 가능)

**원칙: 이벤트로 전달받은 데이터 중, 자신의 비즈니스 로직에 필요한 것은 반드시 도메인에 저장한다.**

#### A-1. Delivery Service: username 저장

```
변경 대상:
├─ Delivery.java          → username 필드 추가
├─ DeliveryCreateRequestDto.java → username 필드 추가
├─ DeliveryMapper.java    → 이벤트→DTO 변환 시 username 매핑
└─ DeliveryApplicationService.java → Feign 호출 제거, delivery.getUsername() 사용
```

```java
// Before
String username = orderClient.getUsernameByDeliveryId(delivery.getDeliveryId(), role, sender);

// After
String username = delivery.getUsername();
```

제거 가능한 코드:
- `OrderClient` 인터페이스
- `OrderFeignClient` 구현체
- Commerce Service의 `getUsernameByDeliveryId` 엔드포인트

#### A-2. Notification Service: email을 이벤트에 포함

```
변경 대상:
├─ NotificationSendEvent.java     → email 필드 추가
├─ EmailCreateRequestEvent 발행 측 → email 포함하여 발행
└─ EmailService.java              → Feign 호출 제거, event.getEmail() 사용
```

발행 측(Commerce/Delivery)에서 username의 email을 알 수 없는 경우:
- Commerce Service는 주문 생성 시 사용자 정보를 gateway에서 헤더로 받으므로, 이 시점에 email도 함께 전달받도록 gateway 수정
- 또는 Commerce Service에 사용자 email 캐시 테이블을 두고, 이벤트 발행 시 활용

#### A-3. Commerce Service: 응답 이벤트 보강으로 읽기 Feign 감소

```java
// Before: ID만 전달
new PaymentCreateResponseEvent(orderId, paymentId, "CREDIT-CARD", "COMPLETED")

// After: 조회에 필요한 필드 포함
new PaymentCreateResponseEvent(orderId, paymentId, amount, "CREDIT-CARD", "COMPLETED")
```

Commerce Service의 Order 도메인(또는 별도 읽기 모델)에 결제/배송 스냅샷 저장:

```java
// Order.java 또는 별도 OrderReadModel
private BigDecimal paymentAmount;
private String paymentMethod;
private String paymentStatus;
private String deliveryStatus;
private String trackingNumber;
private String carrierName;
```

### 4.2 개선안 B: CQRS 읽기 모델 도입 (중장기)

Commerce Service의 주문 상세 조회에서 Feign 호출을 완전히 제거하려면,
**읽기 전용 비정규화 모델**을 도입한다.

```
[쓰기 경로 - 변경 없음]
주문 생성 → Kafka → 결제 → Kafka → 배송 → Kafka → 주문 완료

[읽기 경로 - 이벤트 기반 읽기 모델]
                                ┌─────────────────────┐
payment-create-response ──────> │                     │
delivery-create-response ─────> │  OrderReadModel     │
delivery-end-response ────────> │  (비정규화 테이블)    │
                                │                     │
                                └─────────┬───────────┘
                                          │
                          주문 상세 조회 ◄──┘  (Feign 호출 0건)
```

```java
@Entity
@Table(name = "p_order_read_model")
public class OrderReadModel {
    // Order 기본 정보
    private UUID orderId;
    private String username;
    private BigDecimal amount;
    private String orderStatus;

    // Payment 스냅샷 (이벤트로 수신)
    private UUID paymentId;
    private String paymentMethod;
    private String paymentStatus;

    // Delivery 스냅샷 (이벤트로 수신)
    private UUID deliveryId;
    private String carrierName;
    private String trackingNumber;
    private String deliveryStatus;
}
```

이벤트 핸들러에서 읽기 모델 업데이트:

```java
@Component
public class OrderReadModelUpdater {

    @KafkaListener(topics = "payment-create-response")
    public void onPaymentCreated(PaymentCreateResponseEvent event) {
        orderReadModelRepository.updatePaymentInfo(
            event.getOrderId(), event.getPaymentId(),
            event.getMethod(), event.getStatus());
    }

    @KafkaListener(topics = "delivery-end-response")
    public void onDeliveryStatusChanged(DeliveryEndEvent event) {
        orderReadModelRepository.updateDeliveryStatus(
            event.getOrderId(), event.getStatus());
    }
}
```

### 4.3 개선안 C: 알림 발송과 핵심 로직 분리 (공통 적용)

어떤 개선안을 선택하든, **알림 실패가 핵심 비즈니스 로직을 실패시키지 않도록** 분리해야 한다.

```java
// 방법 1: 트랜잭션 커밋 후 비동기 발송
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
@Async
public void handleDeliveryNotification(DeliveryNotificationEvent event) {
    sendNotification(event.getDelivery(), event.getSubject(), event.getContent());
}

// 방법 2: 최소한 try-catch로 격리
@Transactional
public DeliveryStartResponseDto startDelivery(DeliveryStartRequestDto dto) {
    // 핵심 로직
    delivery.startDelivery(carrierId, dto.getDeliveryType(), dto);
    Delivery saved = deliveryRepository.save(delivery);

    // 알림은 실패해도 배송 시작은 성공
    try {
        sendNotification(delivery, "배송 시작 알림", "배송이 시작되었습니다.");
    } catch (Exception e) {
        log.warn("알림 발송 실패, 배송 시작은 정상 처리됨. deliveryId={}", delivery.getDeliveryId(), e);
    }
    return DeliveryMapper.convertEntityToStartResponseDto(saved);
}
```

---

## 5. 개선 우선순위 및 로드맵

### Phase 1: Critical 동기 호출 제거 (즉시)

| 작업 | 효과 | 난이도 |
|------|------|--------|
| Delivery에 username 저장 + Feign 제거 | 서비스 결합 제거, 장애 전파 차단 | 낮음 |
| email-create-request에 email 포함 + Feign 제거 | 알림 서비스 독립성 확보 | 낮음 |
| 알림 발송을 핵심 로직에서 분리 | 알림 실패 격리 | 낮음 |

### Phase 2: 읽기 경로 최적화 (단기)

| 작업 | 효과 | 난이도 |
|------|------|--------|
| 응답 이벤트 보강 (결제/배송 상세 포함) | 조회 시 Feign 감소 | 중간 |
| Order에 결제/배송 스냅샷 필드 추가 | 읽기 성능 향상 | 중간 |

### Phase 3: CQRS 읽기 모델 (중장기)

| 작업 | 효과 | 난이도 |
|------|------|--------|
| OrderReadModel 도입 | 조회 Feign 완전 제거 | 높음 |
| 이벤트 핸들러로 읽기 모델 동기화 | Eventual Consistency 기반 읽기 | 높음 |

---

## 6. 아키텍처 원칙 정리

### 6.1 이벤트 설계 원칙

> **"소비자가 자립할 수 있도록 이벤트를 설계하라"**

이벤트에 포함할 데이터를 결정할 때, 다음을 기준으로 판단한다:

| 기준 | 포함 여부 | 예시 |
|------|----------|------|
| 소비자의 핵심 로직에 필요한 데이터 | **반드시 포함** | 배송 생성 이벤트에 username |
| 소비자의 부수 효과에 필요한 데이터 | **가급적 포함** | 알림 이벤트에 email |
| 소비자가 조회 목적으로만 필요한 데이터 | **선택적 포함** | 주문 조회 시 결제 상세 |
| 민감한 데이터 (비밀번호, 토큰 등) | **포함하지 않음** | - |

### 6.2 도메인 모델 설계 원칙

> **"자신의 비즈니스 로직을 수행하는 데 필요한 모든 데이터를 자체 도메인에 보유하라"**

다른 서비스에서 이벤트로 전달받은 데이터라도, 자신의 비즈니스 로직에 필요하다면 도메인 모델에 저장한다.
이는 데이터 중복이 아니라 **Bounded Context 간 데이터 주권 확보**이다.

```
[Anti-pattern]                        [Correct pattern]
Delivery {                            Delivery {
  deliveryId                            deliveryId
  orderId     ← ID만 저장               orderId
  ...                                   username   ← 이벤트로 받은 데이터 저장
}                                       ...
↓ 알림 시 username 필요                }
↓ Feign으로 조회 ❌                   ↓ 알림 시
                                      ↓ delivery.getUsername() ✅
```

### 6.3 동기 호출 허용 기준

| 유형 | 동기 호출 허용 | 이유 |
|------|:---:|------|
| 인증/인가 (보안 경계) | ✅ | 실시간 검증 필수, 캐시 위험 |
| 쓰기 경로의 데이터 조회 | ❌ | 이벤트로 선행 전달 가능 |
| 읽기 경로의 집계 조회 | △ | CQRS 적용 전까지 허용, 장기적으로 제거 |
| 부수 효과 (알림 등) | ❌ | 비동기 처리로 전환 |

---

## 7. 결론

현재 pickple-backend의 서비스 간 동기 Feign 호출은 크게 두 가지 근본 원인에서 비롯된다:

1. **이벤트 설계 시 소비자 관점 누락** → 이벤트로 전달받은 데이터를 도메인에 저장하지 않음
2. **읽기/쓰기 경로 미분리** → 조회 시 다른 서비스의 실시간 데이터에 의존

Phase 1(이벤트로 받은 데이터 저장)만 적용해도 **Critical 수준의 동기 호출 2건을 즉시 제거**할 수 있으며,
이는 코드 변경량이 적으면서도 시스템 안정성에 가장 큰 효과를 가져온다.
