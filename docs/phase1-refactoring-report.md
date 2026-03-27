# Phase 1 리팩토링 수행 보고서

> 참조 문서: [data-sovereignty-analysis.md](data-sovereignty-analysis.md) §5 Phase 1

---

## 1. 개요

데이터 주권 분석 문서의 Phase 1 개선안(Critical 동기 Feign 호출 제거)을 수행했다.
핵심 목표는 **서비스 간 런타임 결합 제거**와 **알림 실패 격리**다.

---

## 2. 변경 파일 목록

### 2.1 delivery-service

| 파일 | 변경 유형 | 변경 내용 |
|------|-----------|-----------|
| `domain/model/Delivery.java` | 수정 | `username`, `email` 필드 추가; `createFrom()`에서 두 필드 매핑 |
| `application/dto/request/DeliveryCreateRequestDto.java` | 수정 | `username`, `email` 필드 추가 |
| `application/mapper/DeliveryMapper.java` | 수정 | `convertCreateRequestEventToDto()`에 `username`, `email` 매핑 추가 |
| `application/service/DeliveryApplicationService.java` | 수정 | `OrderClient` 의존성 제거; `delivery.getUsername()` / `delivery.getEmail()` 직접 사용; 알림 발송 try-catch 격리 |
| `application/events/NotificationSendEvent.java` | 수정 | `email` 필드 추가 |
| `infrastructure/messaging/events/DeliveryCreateRequestEvent.java` | 수정 | `email` 필드 추가 |
| `application/port/OrderClient.java` | **삭제** | Commerce 서비스 Feign 포트 인터페이스 제거 |
| `infrastructure/feign/OrderFeignClient.java` | **삭제** | Commerce 서비스 Feign 구현체 제거 |

### 2.2 commerce-service

| 파일 | 변경 유형 | 변경 내용 |
|------|-----------|-----------|
| `domain/model/Order.java` | 수정 | `email` 필드 추가 |
| `infrastructure/messaging/events/DeliveryCreateRequestEvent.java` | 수정 | `email` 필드 추가 |
| `infrastructure/feign/UserClient.java` | **신규** | User 서비스 email 조회 Feign 클라이언트 (주문 생성 시 1회 호출용) |
| `exception/CommerceErrorCode.java` | 수정 | `USER_SERVICE_ERROR` 추가 |
| `application/service/OrderService.java` | 수정 | `createOrder()` 진입 시 `UserClient`로 email 1회 조회 후 Order에 저장; `findUsernameByDeliveryId()` 메서드 제거 |
| `application/service/OrderEventService.java` | 수정 | `handlePaymentComplete()`에서 `order.getEmail()` 추출 후 `sendDeliveryCreateRequest()`에 전달 |
| `application/service/OrderMessagingProducerService.java` | 수정 | `sendDeliveryCreateRequest()` 시그니처에 `email` 파라미터 추가; `DeliveryCreateRequestEvent` 생성 시 email 포함 |
| `presentation/controller/OrderController.java` | 수정 | `getUsernameByDeliveryId()` 엔드포인트 제거 |
| `domain/repository/OrderRepository.java` | 수정 | `findByDeliveryId()` 메서드 제거; 미사용 `Optional` import 제거 |

### 2.3 notification-service

| 파일 | 변경 유형 | 변경 내용 |
|------|-----------|-----------|
| `infrastructure/messaging/events/EmailCreateRequestEvent.java` | 수정 | `email` 필드 추가 |
| `application/service/EmailService.java` | 수정 | `UserFeignClient` 의존성 제거; `event.getEmail()` 직접 사용; 불필요한 `@Autowired` 제거 |
| `infrastructure/feign/UserFeignClient.java` | **삭제** | User 서비스 Feign 클라이언트 제거 |

---

## 3. 개선 전/후 비교

### 3.1 배송 알림 흐름 (Critical 제거)

**Before**
```
startDelivery() / endDelivery()
  └─ @Transactional 내부
       └─ OrderFeignClient.getUsernameByDeliveryId()  ← HTTP 동기 호출
            └─ Commerce Service 장애 시 배송 자체 실패
```

**After**
```
startDelivery() / endDelivery()
  └─ @Transactional 내부
       └─ delivery.getUsername()  ← 도메인 자체 보유 데이터
  └─ try { sendNotification() } catch { log.warn() }  ← 알림 실패 격리
```

### 3.2 이메일 발송 흐름 (Critical 제거)

**Before**
```
EmailService.sendEmail()
  └─ UserFeignClient.getUserEmail()  ← HTTP 동기 호출
       └─ User Service 장애 시 모든 이메일 발송 실패
```

**After**
```
EmailService.sendEmail()
  └─ event.getEmail()  ← 이벤트에 포함된 데이터 직접 사용
```

### 3.3 email 전달 경로

```
createOrder(username, role)
  └─ UserClient.getUserEmail()  ← Feign 1회 호출 (주문 생성 시점만)
  └─ Order.email 저장
       └─ handlePaymentComplete()
            └─ sendDeliveryCreateRequest(orderId, username, email)
                 └─ DeliveryCreateRequestEvent { email }  ← Kafka
                      └─ Delivery.email 저장
                           └─ sendNotification()
                                └─ NotificationSendEvent { email }  ← Kafka
                                     └─ EmailCreateRequestEvent { email }
                                          └─ EmailService.sendEmail()
                                               └─ event.getEmail()  ✅
```

> **설계 의도**: email 조회 Feign은 주문 생성(동기 API 경계)에서 **딱 1회**만 발생한다.
> 이후 결제→배송→알림의 비동기 이벤트 체인에서는 Feign 호출이 전혀 없다.

---

## 4. 제거된 Feign 호출

| 제거된 호출 | 호출 서비스 → 대상 | 대체 방법 |
|-------------|-----------------|-----------|
| `OrderFeignClient.getUsernameByDeliveryId()` | Delivery → Commerce | `Delivery.username` 도메인 필드 직접 참조 |
| `UserFeignClient.getUserEmail()` | Notification → User | `EmailCreateRequestEvent.email` 이벤트 필드 직접 참조 |

---

## 5. 효과

- **Commerce Service 장애가 배송 시작/완료를 실패시키지 않음**: `OrderFeignClient` 완전 제거
- **User Service 장애가 이메일 발송을 실패시키지 않음**: `UserFeignClient` 완전 제거
- **알림 실패가 핵심 비즈니스 로직을 롤백시키지 않음**: try-catch 격리 적용
- **`@Transactional` 내부 외부 HTTP 호출 제거**: DB 커넥션 점유 및 타임아웃 리스크 해소

---

## 6. 잔여 Feign 호출 (Phase 1 범위 외)

| 호출 | 서비스 | 분류 | 다음 단계 |
|------|--------|------|----------|
| `PaymentClient.getPaymentInfo()` | Commerce → Payment | Medium | Phase 2 (응답 이벤트 보강) |
| `DeliveryClient.getDeliveryInfo()` | Commerce → Delivery | Medium | Phase 2 (응답 이벤트 보강) |
| `getUserByUsername()` | Auth → User | 허용 | 보안 경계, 변경 불필요 |
