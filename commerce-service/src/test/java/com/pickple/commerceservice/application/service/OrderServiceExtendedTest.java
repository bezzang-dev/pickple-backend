package com.pickple.commerceservice.application.service;

import com.pickple.commerceservice.domain.model.Order;
import com.pickple.commerceservice.domain.model.OrderStatus;
import com.pickple.commerceservice.domain.repository.OrderRepository;
import com.pickple.commerceservice.domain.repository.PreOrderRepository;
import com.pickple.commerceservice.domain.repository.ProductRepository;
import com.pickple.commerceservice.exception.CommerceErrorCode;
import com.pickple.commerceservice.infrastructure.facade.RedissonLockStockFacade;
import com.pickple.commerceservice.infrastructure.feign.DeliveryClient;
import com.pickple.commerceservice.infrastructure.feign.PaymentClient;
import com.pickple.commerceservice.infrastructure.redis.OrderTimeoutService;
import com.pickple.commerceservice.infrastructure.redis.TemporaryStorageService;
import com.pickple.commerceservice.presentation.dto.request.OrderCreateRequestDto;
import com.pickple.common_module.exception.CustomException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceExtendedTest {

    @Mock private OrderRepository orderRepository;
    @Mock private StockService stockService;
    @Mock private RedissonLockStockFacade redissonLockStockFacade;
    @Mock private TemporaryStorageService temporaryStorageService;
    @Mock private OrderTimeoutService orderTimeoutService;
    @Mock private OrderMessagingProducerService messagingProducerService;
    @Mock private ProductRepository productRepository;
    @Mock private PreOrderRepository preOrderRepository;
    @Mock private PaymentClient paymentClient;
    @Mock private DeliveryClient deliveryClient;

    @InjectMocks
    private OrderService orderService;

    private UUID orderId;
    private Order pendingOrder;

    @BeforeEach
    void setUp() {
        orderId = UUID.randomUUID();

        pendingOrder = Order.builder()
                .orderStatus(OrderStatus.PENDING)
                .username("testuser")
                .amount(BigDecimal.valueOf(10000))
                .orderDetails(List.of())
                .build();
    }

    @Test
    @DisplayName("주문 취소 — 주문을 찾을 수 없으면 ORDER_NOT_FOUND 예외")
    void cancelOrder_notFound() {
        when(orderRepository.findById(orderId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.cancelOrder(orderId, "testuser", "USER"))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                        .isEqualTo(CommerceErrorCode.ORDER_NOT_FOUND));
    }

    @Test
    @DisplayName("주문 취소 성공 — 상태가 CANCELED로 전이되고 soft delete 처리")
    void cancelOrder_success() {
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(pendingOrder));
        when(deliveryClient.getDeliveryInfo(any(), any(), any()))
                .thenThrow(feign.FeignException.class);
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        orderService.cancelOrder(orderId, "testuser", "USER");

        assertThat(pendingOrder.getOrderStatus()).isEqualTo(OrderStatus.CANCELED);
        assertThat(pendingOrder.getIsDelete()).isTrue();
    }

    @Test
    @DisplayName("타임아웃 처리 — 결제 미완료 주문이면 CANCELED 처리")
    void handleOrderTimeout_cancelsPendingOrder() {
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(pendingOrder));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        orderService.handleOrderTimeout(orderId);

        assertThat(pendingOrder.getOrderStatus()).isEqualTo(OrderStatus.CANCELED);
        assertThat(pendingOrder.getIsDelete()).isTrue();
    }

    @Test
    @DisplayName("타임아웃 처리 — 이미 결제된 주문이면 취소 처리 안 함")
    void handleOrderTimeout_skipsIfPaymentExists() {
        Order paidOrder = Order.builder()
                .orderStatus(OrderStatus.PENDING)
                .username("testuser")
                .amount(BigDecimal.valueOf(10000))
                .orderDetails(List.of())
                .build();
        paidOrder.assignPaymentId(UUID.randomUUID());

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(paidOrder));

        orderService.handleOrderTimeout(orderId);

        verify(orderRepository, never()).save(any());
    }

    @Test
    @DisplayName("타임아웃 처리 — 주문을 찾을 수 없으면 ORDER_NOT_FOUND 예외")
    void handleOrderTimeout_notFound() {
        when(orderRepository.findById(orderId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.handleOrderTimeout(orderId))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                        .isEqualTo(CommerceErrorCode.ORDER_NOT_FOUND));
    }

    @Test
    @DisplayName("주문 생성 — 상품이 없으면 PRODUCT_NOT_FOUND 예외")
    void createOrder_productNotFound() {
        OrderCreateRequestDto.OrderDetail detailDto =
                new OrderCreateRequestDto.OrderDetail(UUID.randomUUID(), 2L);
        OrderCreateRequestDto.DeliveryInfo deliveryInfo =
                new OrderCreateRequestDto.DeliveryInfo("요청없음", "홍길동", "서울시", "010-1234-5678");
        OrderCreateRequestDto requestDto =
                new OrderCreateRequestDto(List.of(detailDto), deliveryInfo);

        when(productRepository.findById(any(UUID.class))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.createOrder(requestDto, "testuser", "USER"))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                        .isEqualTo(CommerceErrorCode.PRODUCT_NOT_FOUND));
    }
}
