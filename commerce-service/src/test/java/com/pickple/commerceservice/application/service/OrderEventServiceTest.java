package com.pickple.commerceservice.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pickple.commerceservice.domain.model.Order;
import com.pickple.commerceservice.domain.model.OrderStatus;
import com.pickple.commerceservice.domain.repository.OrderRepository;
import com.pickple.commerceservice.infrastructure.facade.RedissonLockStockFacade;
import com.pickple.commerceservice.infrastructure.redis.OrderTimeoutService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class OrderEventServiceTest {

    @Mock private OrderRepository orderRepository;
    @Mock private OrderMessagingProducerService messagingProducerService;
    @Mock private RedissonLockStockFacade redissonLockStockFacade;
    @Mock private OrderTimeoutService orderTimeoutService;

    @InjectMocks
    private OrderEventService orderEventService;

    private UUID orderId;
    private Order order;

    @BeforeEach
    void setUp() {
        orderId = UUID.randomUUID();
        order = Order.builder()
                .orderId(orderId)
                .orderStatus(OrderStatus.PENDING)
                .username("testuser")
                .email("test@example.com")
                .amount(BigDecimal.valueOf(10000))
                .orderDetails(List.of())
                .build();
    }

    @Test
    @DisplayName("결제 완료 이벤트 수신 시 Order에 결제 스냅샷을 저장한다")
    void handlePaymentComplete_assignsPaymentSnapshot() {
        UUID paymentId = UUID.randomUUID();
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TransactionSynchronizationManager.initSynchronization();
        try {
            orderEventService.handlePaymentComplete(orderId, paymentId, BigDecimal.valueOf(10000), "CREDIT-CARD", "COMPLETED");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        assertThat(order.getPaymentId()).isEqualTo(paymentId);
        assertThat(order.getPaymentAmount()).isEqualTo(BigDecimal.valueOf(10000));
        assertThat(order.getPaymentMethod()).isEqualTo("CREDIT-CARD");
        assertThat(order.getPaymentStatus()).isEqualTo("COMPLETED");
        verify(orderTimeoutService).cancelOrderTimeout(orderId);
    }

    @Test
    @DisplayName("배송 생성 이벤트 수신 시 Order에 배송 스냅샷을 저장한다")
    void handleDeliveryComplete_assignsDeliverySnapshot() {
        UUID deliveryId = UUID.randomUUID();
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        orderEventService.handleDeliveryComplete(
                orderId,
                deliveryId,
                "PENDING",
                null,
                null,
                null,
                "문 앞",
                "홍길동",
                "서울시",
                "010-1234-5678"
        );

        assertThat(order.getDeliveryId()).isEqualTo(deliveryId);
        assertThat(order.getDeliveryStatus()).isEqualTo("PENDING");
        assertThat(order.getDeliveryRequirement()).isEqualTo("문 앞");
        assertThat(order.getRecipientName()).isEqualTo("홍길동");
    }

    @Test
    @DisplayName("배송 완료 이벤트 수신 시 주문 상태와 배송 상태를 함께 갱신한다")
    void handleDeliveryEnd_updatesOrderAndDeliveryStatus() {
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        orderEventService.handleDeliveryEnd(orderId, "DELIVERED");

        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(order.getDeliveryStatus()).isEqualTo("DELIVERED");
    }
}
