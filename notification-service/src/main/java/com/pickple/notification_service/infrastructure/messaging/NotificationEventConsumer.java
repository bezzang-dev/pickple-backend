package com.pickple.notification_service.infrastructure.messaging;

import com.pickple.common_module.infrastructure.messaging.EventSerializer;
import com.pickple.notification_service.application.service.NotificationService;
import com.pickple.notification_service.infrastructure.messaging.events.EmailCreateRequestEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationEventConsumer {

    private final NotificationService notificationService;

    @KafkaListener(topics="email-create-request", groupId="notification-group")
    public void handleCreateRequest(String message) {
        try {
            EmailCreateRequestEvent event = EventSerializer.deserialize(message, EmailCreateRequestEvent.class);
            notificationService.sendEmailNotification(event);
        } catch (Exception e) {
            log.error("알림 메시지 처리 실패. message: {}, 원인: {}", message, e.getMessage(), e);
            throw e;
        }
    }
}
