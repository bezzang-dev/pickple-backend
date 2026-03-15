package com.pickple.payment_service.application.service;

import com.pickple.common_module.infrastructure.messaging.EventSerializer;
import com.pickple.payment_service.application.events.KafkaOutboxEvent;
import com.pickple.payment_service.infrastructure.messaging.events.PaymentCancelFailureEvent;
import com.pickple.payment_service.infrastructure.messaging.events.PaymentCancelResponseEvent;
import com.pickple.payment_service.infrastructure.messaging.events.PaymentCreateFailureEvent;
import com.pickple.payment_service.infrastructure.messaging.events.PaymentCreateResponseEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PaymentEventService {

    private final ApplicationEventPublisher applicationEventPublisher;

    public void sendCreateSuccessEvent(PaymentCreateResponseEvent event) {
        applicationEventPublisher.publishEvent(
                new KafkaOutboxEvent("payment-create-response", EventSerializer.serialize(event)));
    }

    public void sendCreateFailureEvent(PaymentCreateFailureEvent event) {
        applicationEventPublisher.publishEvent(
                new KafkaOutboxEvent("payment-create-failure", EventSerializer.serialize(event)));
    }

    public void sendCancelSuccessEvent(PaymentCancelResponseEvent event) {
        applicationEventPublisher.publishEvent(
                new KafkaOutboxEvent("payment-cancel-response", EventSerializer.serialize(event)));
    }

    public void sendCancelFailureEvent(PaymentCancelFailureEvent event) {
        applicationEventPublisher.publishEvent(
                new KafkaOutboxEvent("payment-cancel-failure", EventSerializer.serialize(event)));
    }
}
