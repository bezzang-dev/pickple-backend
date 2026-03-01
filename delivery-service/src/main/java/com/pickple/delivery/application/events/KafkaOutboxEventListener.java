package com.pickple.delivery.application.events;

import com.pickple.delivery.infrastructure.messaging.DeliveryMessageProducerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * DB 트랜잭션이 성공적으로 커밋된 이후에만 Kafka 메시지를 발행합니다.
 * 트랜잭션 커밋 전 Kafka 발행으로 인한 2PC(이중 커밋) 문제를 해결합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaOutboxEventListener {

    private final DeliveryMessageProducerService deliveryMessageProducerService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onKafkaOutboxEvent(KafkaOutboxEvent event) {
        log.info("Kafka 메시지를 발행합니다 (AFTER_COMMIT). Topic: {}", event.getTopic());
        deliveryMessageProducerService.sendMessage(event.getTopic(), event.getPayload());
    }
}
