package com.pickple.commerceservice.infrastructure.messaging;

import com.pickple.commerceservice.application.service.OrderEventService;
import com.pickple.commerceservice.application.service.OrderMessagingProducerService;
import com.pickple.commerceservice.exception.CommerceErrorCode;
import com.pickple.commerceservice.infrastructure.configuration.EventSerializer;
import com.pickple.commerceservice.infrastructure.messaging.events.DeliveryCreateResponseEvent;
import com.pickple.commerceservice.infrastructure.messaging.events.DeliveryEndResponseEvent;
import com.pickple.commerceservice.infrastructure.messaging.events.PaymentCancelResponseEvent;
import com.pickple.commerceservice.infrastructure.messaging.events.PaymentCreateResponseEvent;
import com.pickple.common_module.exception.CustomException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderMessagingConsumerService {

    private final OrderEventService orderEventService;
    private final OrderMessagingProducerService messagingProducerService;

    @KafkaListener(topics = "payment-create-response", groupId = "commerce-service")
    public void listenPaymentCreateResponse(String message) {
        PaymentCreateResponseEvent event;
        try {
            event = EventSerializer.deserialize(message, PaymentCreateResponseEvent.class);
        } catch (RuntimeException e) {
            throw new CustomException(CommerceErrorCode.INVALID_PAYMENT_MESSAGE_FORMAT);
        }

        UUID orderId = event.getOrderId();
        UUID paymentId = event.getPaymentId();

        try {
            orderEventService.handlePaymentComplete(orderId, paymentId);
        } catch (Exception e) {
            // 보상 트랜잭션: 재고 차감 등 실패 시 결제 취소 요청
            log.error("결제 완료 처리 실패, 보상 트랜잭션으로 결제 취소 요청. orderId: {}, error: {}",
                    orderId, e.getMessage());
            messagingProducerService.sendPaymentCancelRequest(orderId);
        }
    }

    @KafkaListener(topics = "delivery-create-response", groupId = "commerce-service")
    public void listenDeliveryCreateResponse(String message) {
        DeliveryCreateResponseEvent event;
        try {
            event = EventSerializer.deserialize(message, DeliveryCreateResponseEvent.class);
        } catch (RuntimeException e) {
            throw new CustomException(CommerceErrorCode.INVALID_DELIVERY_MESSAGE_FORMAT);
        }

        UUID orderId = event.getOrderId();
        UUID deliveryId = event.getDeliveryId();

        orderEventService.handleDeliveryComplete(orderId, deliveryId);
    }

    @KafkaListener(topics = "payment-cancel-response", groupId = "commerce-service")
    public void listenPaymentCancelResponse(String message) {
        PaymentCancelResponseEvent event;
        try {
            event = EventSerializer.deserialize(message, PaymentCancelResponseEvent.class);
        } catch (RuntimeException e) {
            throw new CustomException(CommerceErrorCode.INVALID_PAYMENT_MESSAGE_FORMAT);
        }

        UUID orderId = event.getOrderId();

        orderEventService.handlePaymentCancel(orderId);
    }

    @KafkaListener(topics = "delivery-end-response", groupId = "commerce-service")
    public void listenDeliveryEndResponse(String message) {
        DeliveryEndResponseEvent event;
        try {
            event = EventSerializer.deserialize(message, DeliveryEndResponseEvent.class);
        } catch (RuntimeException e) {
            throw new CustomException(CommerceErrorCode.INVALID_DELIVERY_MESSAGE_FORMAT);
        }

        UUID orderId = event.getOrderId();
        String status = event.getStatus();

        if ("DELIVERED".equalsIgnoreCase(status)) {
            orderEventService.handleDeliveryEnd(orderId);
        } else {
            orderEventService.handleDeliveryCancel(orderId);
        }
    }
}
