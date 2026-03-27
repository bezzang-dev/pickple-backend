package com.pickple.commerceservice.infrastructure.messaging;

import com.pickple.commerceservice.application.service.OrderEventService;
import com.pickple.commerceservice.application.service.OrderMessagingProducerService;
import com.pickple.commerceservice.exception.CommerceErrorCode;
import com.pickple.common_module.infrastructure.messaging.EventSerializer;
import com.pickple.commerceservice.infrastructure.messaging.events.DeliveryCreateResponseEvent;
import com.pickple.commerceservice.infrastructure.messaging.events.DeliveryEndResponseEvent;
import com.pickple.commerceservice.infrastructure.messaging.events.PaymentCancelResponseEvent;
import com.pickple.commerceservice.infrastructure.messaging.events.PaymentCreateResponseEvent;
import com.pickple.common_module.exception.CustomException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.math.BigDecimal;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderMessagingConsumerService {

    private final OrderEventService orderEventService;
    private final OrderMessagingProducerService messagingProducerService;
    private final RedisTemplate<String, Object> redisTemplate;

    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);

    @KafkaListener(topics = "payment-create-response", groupId = "commerce-service")
    public void listenPaymentCreateResponse(String message) {
        PaymentCreateResponseEvent event;
        try {
            event = EventSerializer.deserialize(message, PaymentCreateResponseEvent.class);
        } catch (RuntimeException e) {
            log.error("결제 응답 메시지 역직렬화 실패: {}", e.getMessage());
            throw new CustomException(CommerceErrorCode.INVALID_PAYMENT_MESSAGE_FORMAT);
        }

        UUID orderId = event.getOrderId();
        UUID paymentId = event.getPaymentId();
        BigDecimal amount = event.getAmount();
        String method = event.getMethod();
        String status = event.getStatus();

        // 멱등성 체크
        String idempotencyKey = "idempotent:payment-response:" + orderId;
        if (Boolean.TRUE.equals(redisTemplate.hasKey(idempotencyKey))) {
            log.warn("중복 결제 응답 메시지 무시. orderId: {}", orderId);
            return;
        }

        try {
            orderEventService.handlePaymentComplete(orderId, paymentId, amount, method, status);
            redisTemplate.opsForValue().set(idempotencyKey, "processed", IDEMPOTENCY_TTL);
        } catch (Exception e) {
            log.error("결제 완료 처리 실패, 보상 트랜잭션으로 결제 취소 요청. orderId: {}, error: {}",
                    orderId, e.getMessage(), e);
            messagingProducerService.sendPaymentCancelRequest(orderId);
        }
    }

    @KafkaListener(topics = "delivery-create-response", groupId = "commerce-service")
    public void listenDeliveryCreateResponse(String message) {
        DeliveryCreateResponseEvent event;
        try {
            event = EventSerializer.deserialize(message, DeliveryCreateResponseEvent.class);
        } catch (RuntimeException e) {
            log.error("배송 응답 메시지 역직렬화 실패: {}", e.getMessage());
            throw new CustomException(CommerceErrorCode.INVALID_DELIVERY_MESSAGE_FORMAT);
        }

        UUID orderId = event.getOrderId();
        UUID deliveryId = event.getDeliveryId();

        try {
            orderEventService.handleDeliveryComplete(
                    orderId,
                    deliveryId,
                    event.getDeliveryStatus(),
                    event.getDeliveryType(),
                    event.getCarrierName(),
                    event.getTrackingNumber(),
                    event.getDeliveryRequirement(),
                    event.getRecipientName(),
                    event.getRecipientAddress(),
                    event.getRecipientContact()
            );
        } catch (Exception e) {
            log.error("배송 완료 처리 실패. orderId: {}, error: {}", orderId, e.getMessage(), e);
            throw e;
        }
    }

    @KafkaListener(topics = "payment-cancel-response", groupId = "commerce-service")
    public void listenPaymentCancelResponse(String message) {
        PaymentCancelResponseEvent event;
        try {
            event = EventSerializer.deserialize(message, PaymentCancelResponseEvent.class);
        } catch (RuntimeException e) {
            log.error("결제 취소 응답 메시지 역직렬화 실패: {}", e.getMessage());
            throw new CustomException(CommerceErrorCode.INVALID_PAYMENT_MESSAGE_FORMAT);
        }

        UUID orderId = event.getOrderId();

        try {
            orderEventService.handlePaymentCancel(orderId);
        } catch (Exception e) {
            log.error("결제 취소 처리 실패. orderId: {}, error: {}", orderId, e.getMessage(), e);
            throw e;
        }
    }

    @KafkaListener(topics = "delivery-end-response", groupId = "commerce-service")
    public void listenDeliveryEndResponse(String message) {
        DeliveryEndResponseEvent event;
        try {
            event = EventSerializer.deserialize(message, DeliveryEndResponseEvent.class);
        } catch (RuntimeException e) {
            log.error("배송 종료 응답 메시지 역직렬화 실패: {}", e.getMessage());
            throw new CustomException(CommerceErrorCode.INVALID_DELIVERY_MESSAGE_FORMAT);
        }

        UUID orderId = event.getOrderId();
        String status = event.getStatus();

        try {
            if ("DELIVERED".equalsIgnoreCase(status)) {
                orderEventService.handleDeliveryEnd(orderId, status);
            } else {
                orderEventService.handleDeliveryCancel(orderId, status);
            }
        } catch (Exception e) {
            log.error("배송 종료 처리 실패. orderId: {}, status: {}, error: {}", orderId, status, e.getMessage(), e);
            throw e;
        }
    }
}
