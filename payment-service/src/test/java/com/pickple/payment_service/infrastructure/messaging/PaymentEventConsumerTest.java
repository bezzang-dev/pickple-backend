package com.pickple.payment_service.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pickple.common_module.exception.CustomException;
import com.pickple.payment_service.application.service.PaymentService;
import com.pickple.payment_service.exception.PaymentErrorCode;
import com.pickple.payment_service.infrastructure.messaging.events.PaymentCancelRequestEvent;
import com.pickple.payment_service.infrastructure.messaging.events.PaymentCreateRequestEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentEventConsumerTest {

    @Mock
    private PaymentService paymentService;

    @InjectMocks
    private PaymentEventConsumer paymentEventConsumer;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ──────────────────────────────────────────────
    // handleCreateRequest
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("결제 생성 메시지 정상 처리")
    void handleCreateRequest_success() throws Exception {
        // Given
        UUID orderId = UUID.randomUUID();
        PaymentCreateRequestEvent event = new PaymentCreateRequestEvent(orderId, "testuser", BigDecimal.valueOf(10000));
        String message = objectMapper.writeValueAsString(event);

        // When
        paymentEventConsumer.handleCreateRequest(message);

        // Then
        verify(paymentService).createPayment(orderId, "testuser", BigDecimal.valueOf(10000));
    }

    @Test
    @DisplayName("결제 생성 메시지가 잘못된 JSON이면 RuntimeException 발생")
    void handleCreateRequest_invalidJson() {
        String invalidMessage = "{ invalid json }";

        assertThatThrownBy(() -> paymentEventConsumer.handleCreateRequest(invalidMessage))
                .isInstanceOf(RuntimeException.class);

        verifyNoInteractions(paymentService);
    }

    @Test
    @DisplayName("결제 생성 처리 중 서비스 예외가 발생하면 예외를 재던짐")
    void handleCreateRequest_serviceThrows() throws Exception {
        // Given
        UUID orderId = UUID.randomUUID();
        PaymentCreateRequestEvent event = new PaymentCreateRequestEvent(orderId, "testuser", BigDecimal.valueOf(10000));
        String message = objectMapper.writeValueAsString(event);

        doThrow(new CustomException(PaymentErrorCode.INVALID_MESSAGE_FORMAT))
                .when(paymentService).createPayment(any(), any(), any());

        // When / Then
        assertThatThrownBy(() -> paymentEventConsumer.handleCreateRequest(message))
                .isInstanceOf(CustomException.class);
    }

    // ──────────────────────────────────────────────
    // handleCancelRequest
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("결제 취소 메시지 정상 처리")
    void handleCancelRequest_success() throws Exception {
        // Given
        UUID orderId = UUID.randomUUID();
        PaymentCancelRequestEvent event = new PaymentCancelRequestEvent(orderId, "Request payment cancellation due to order cancellation.");
        String message = objectMapper.writeValueAsString(event);

        // When
        paymentEventConsumer.handleCancelRequest(message);

        // Then
        verify(paymentService).cancelPayment(orderId);
    }

    @Test
    @DisplayName("결제 취소 메시지가 잘못된 JSON이면 RuntimeException 발생")
    void handleCancelRequest_invalidJson() {
        String invalidMessage = "not-json-at-all";

        assertThatThrownBy(() -> paymentEventConsumer.handleCancelRequest(invalidMessage))
                .isInstanceOf(RuntimeException.class);

        verifyNoInteractions(paymentService);
    }

    @Test
    @DisplayName("결제 취소 처리 중 서비스 예외가 발생하면 예외를 재던짐")
    void handleCancelRequest_serviceThrows() throws Exception {
        // Given
        UUID orderId = UUID.randomUUID();
        PaymentCancelRequestEvent event = new PaymentCancelRequestEvent(orderId, "Request payment cancellation due to order cancellation.");
        String message = objectMapper.writeValueAsString(event);

        doThrow(new CustomException(PaymentErrorCode.PAYMENT_NOT_FOUND))
                .when(paymentService).cancelPayment(any());

        // When / Then
        assertThatThrownBy(() -> paymentEventConsumer.handleCancelRequest(message))
                .isInstanceOf(CustomException.class);
    }
}
