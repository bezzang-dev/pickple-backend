package com.pickple.commerceservice.infrastructure.messaging;

import com.pickple.commerceservice.application.service.ProductEventService;
import com.pickple.commerceservice.infrastructure.messaging.events.ProductCreatedEvent;
import com.pickple.commerceservice.infrastructure.messaging.events.ProductDeletedEvent;
import com.pickple.commerceservice.infrastructure.messaging.events.ProductUpdatedEvent;
import com.pickple.common_module.infrastructure.messaging.EventSerializer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProductMessagingConsumerService {

    private final ProductEventService productEventService;

    @KafkaListener(topics = "${kafka.topic.product-created}", groupId = "commerce-service")
    public void listenProductCreated(String message) {
        log.info("ProductCreatedEvent 수신: {}", message);
        ProductCreatedEvent event = EventSerializer.deserialize(message, ProductCreatedEvent.class);
        productEventService.handleProductCreated(event);
    }

    @KafkaListener(topics = "${kafka.topic.product-updated}", groupId = "commerce-service")
    public void listenProductUpdated(String message) {
        log.info("ProductUpdatedEvent 수신: {}", message);
        ProductUpdatedEvent event = EventSerializer.deserialize(message, ProductUpdatedEvent.class);
        productEventService.handleProductUpdated(event);
    }

    @KafkaListener(topics = "${kafka.topic.product-deleted}", groupId = "commerce-service")
    public void listenProductDeleted(String message) {
        log.info("ProductDeletedEvent 수신: {}", message);
        ProductDeletedEvent event = EventSerializer.deserialize(message, ProductDeletedEvent.class);
        productEventService.handleProductDeleted(event);
    }

    @KafkaListener(topics = "${kafka.topic.stock-updated}", groupId = "commerce-service")
    public void listenStockUpdated(String message) {
        log.info("StockUpdatedEvent 수신: {}", message);
        com.pickple.commerceservice.infrastructure.messaging.events.StockUpdatedEvent event =
                EventSerializer.deserialize(message, com.pickple.commerceservice.infrastructure.messaging.events.StockUpdatedEvent.class);
        productEventService.handleStockUpdated(event);
    }
}
