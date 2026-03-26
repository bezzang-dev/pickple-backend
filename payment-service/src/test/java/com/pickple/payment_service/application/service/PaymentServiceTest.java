package com.pickple.payment_service.application.service;

import com.pickple.common_module.exception.CustomException;
import com.pickple.payment_service.application.dto.PaymentRespDto;
import com.pickple.payment_service.domain.model.Payment;
import com.pickple.payment_service.domain.model.PaymentStatus;
import com.pickple.payment_service.domain.repository.PaymentRepository;
import com.pickple.payment_service.exception.PaymentErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.AuditorAware;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private PaymentEventService paymentEventService;
    @Mock
    private AuditorAware<String> auditorProvider;

    @InjectMocks
    private PaymentService paymentService;

    private UUID orderId;
    private UUID paymentId;
    private Payment payment;

    @BeforeEach
    void setUp() {
        orderId = UUID.randomUUID();
        paymentId = UUID.randomUUID();
        payment = new Payment(orderId, "testuser", BigDecimal.valueOf(10000));
    }

    @Test
    @DisplayName("결제 생성 성공")
    void createPayment_success() {
        // Given
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        doNothing().when(paymentEventService).sendCreateSuccessEvent(any());

        // When
        paymentService.createPayment(orderId, "testuser", BigDecimal.valueOf(10000));

        // Then
        verify(paymentRepository, times(2)).save(any(Payment.class));
        verify(paymentEventService).sendCreateSuccessEvent(any());
    }

    @Test
    @DisplayName("결제 생성 시 orderId가 null이면 INVALID_MESSAGE_FORMAT 예외")
    void createPayment_nullOrderId() {
        assertThatThrownBy(() -> paymentService.createPayment(null, "testuser", BigDecimal.valueOf(10000)))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.INVALID_MESSAGE_FORMAT));
    }

    @Test
    @DisplayName("결제 취소 성공")
    void cancelPayment_success() {
        // Given
        payment.success();
        when(paymentRepository.findByOrderIdAndIsDeleteIsFalse(orderId)).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        doNothing().when(paymentEventService).sendCancelSuccessEvent(any());

        // When
        paymentService.cancelPayment(orderId);

        // Then
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELED);
        verify(paymentEventService).sendCancelSuccessEvent(any());
    }

    @Test
    @DisplayName("결제 취소 시 결제가 없으면 PAYMENT_NOT_FOUND 예외")
    void cancelPayment_notFound() {
        when(paymentRepository.findByOrderIdAndIsDeleteIsFalse(orderId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.cancelPayment(orderId))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.PAYMENT_NOT_FOUND));
    }

    @Test
    @DisplayName("결제 단건 조회 성공 — 본인 결제")
    void getPaymentDetails_success() {
        // Given
        payment.success();
        when(paymentRepository.findByPaymentIdAndIsDeleteIsFalse(paymentId)).thenReturn(Optional.of(payment));

        // When
        PaymentRespDto result = paymentService.getPaymentDetails(paymentId, "testuser");

        // Then
        assertThat(result.getUsername()).isEqualTo("testuser");
    }

    @Test
    @DisplayName("결제 단건 조회 시 다른 사람 결제이면 AUTHORIZATION_ERROR 예외")
    void getPaymentDetails_unauthorizedUser() {
        payment.success();
        when(paymentRepository.findByPaymentIdAndIsDeleteIsFalse(paymentId)).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> paymentService.getPaymentDetails(paymentId, "otheruser"))
                .isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("결제 단건 조회 시 결제가 없으면 PAYMENT_NOT_FOUND 예외")
    void getPaymentDetails_notFound() {
        when(paymentRepository.findByPaymentIdAndIsDeleteIsFalse(paymentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.getPaymentDetails(paymentId, "testuser"))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.PAYMENT_NOT_FOUND));
    }
}
