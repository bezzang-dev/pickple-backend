package com.pickple.payment_service.infrastructure.messaging;

import com.pickple.common_module.infrastructure.messaging.EventSerializer;
import com.pickple.payment_service.application.service.PaymentService;
import com.pickple.payment_service.infrastructure.messaging.events.PaymentCancelRequestEvent;
import com.pickple.payment_service.infrastructure.messaging.events.PaymentCreateRequestEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventConsumer {

    private final PaymentService paymentService;

    @KafkaListener(topics="payment-create-request", groupId="payment-group")
    public void handleCreateRequest(String message) {
        try {
            PaymentCreateRequestEvent event = EventSerializer.deserialize(message, PaymentCreateRequestEvent.class);
            paymentService.createPayment(event.getOrderId(), event.getUsername(), event.getAmount());
        } catch (Exception e) {
            log.error("결제 생성 메시지 처리 실패. message: {}, 원인: {}", message, e.getMessage(), e);
            throw e;
        }
    }

    @KafkaListener(topics="payment-cancel-request", groupId="payment-group")
    public void handleCancelRequest(String message) {
        try {
            PaymentCancelRequestEvent event = EventSerializer.deserialize(message, PaymentCancelRequestEvent.class);
            paymentService.cancelPayment(event.getOrderId());
        } catch (Exception e) {
            log.error("결제 취소 메시지 처리 실패. message: {}, 원인: {}", message, e.getMessage(), e);
            throw e;
        }
    }
}
