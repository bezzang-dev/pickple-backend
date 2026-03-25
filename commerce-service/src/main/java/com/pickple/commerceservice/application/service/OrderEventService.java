package com.pickple.commerceservice.application.service;

import com.pickple.commerceservice.domain.model.Order;
import com.pickple.commerceservice.domain.model.OrderDetail;
import com.pickple.commerceservice.domain.model.OrderStatus;
import com.pickple.commerceservice.domain.repository.OrderRepository;
import com.pickple.commerceservice.exception.CommerceErrorCode;
import com.pickple.commerceservice.infrastructure.facade.RedissonLockStockFacade;
import com.pickple.commerceservice.infrastructure.redis.OrderTimeoutService;
import com.pickple.common_module.exception.CustomException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderEventService {

    private final OrderRepository orderRepository;
    private final OrderMessagingProducerService messagingProducerService;
    private final RedissonLockStockFacade redissonLockStockFacade;
    private final OrderTimeoutService orderTimeoutService;

    /**
     * payment-create-response
     */
    @Transactional
    public void handlePaymentComplete(UUID orderId, UUID paymentId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new CustomException(CommerceErrorCode.ORDER_NOT_FOUND));

        // 재고 차감 (분산 락 적용)
        decreaseStockForOrder(order);

        // 결제 ID 지정
        order.assignPaymentId(paymentId);

        // 주문 상태 저장
        orderRepository.save(order);

        // 결제 완료 → 타임아웃 키 제거
        orderTimeoutService.cancelOrderTimeout(orderId);

        // 트랜잭션 커밋 후 배송 생성 요청
        String username = order.getUsername();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                messagingProducerService.sendDeliveryCreateRequest(orderId, username);
            }
        });
    }

    /**
     * delivery-create-response
     */
    @Transactional
    public void handleDeliveryComplete(UUID orderId, UUID deliveryId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new CustomException(CommerceErrorCode.ORDER_NOT_FOUND));

        // 주문 ID 지정
        order.assignDeliveryId(deliveryId);

        orderRepository.save(order);
    }

    /**
     * delivery-end-response(complete)
     */
    @Transactional
    public void handleDeliveryEnd(UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new CustomException(CommerceErrorCode.ORDER_NOT_FOUND));

        order.changeStatus(OrderStatus.COMPLETED);
    }

    /**
     * payment-cancel-response
     */
    @Transactional
    public void handlePaymentCancel(UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new CustomException(CommerceErrorCode.ORDER_NOT_FOUND));

        increaseStockForOrder(order); // 재고 복구 (분산 락 적용)
        order.changeStatus(OrderStatus.CANCELED);  // 주문 취소 처리
        order.assignPaymentId(null);  // 결제 ID 연관성 제거
        orderRepository.save(order);  // 변경된 주문 저장
    }

    /**
     * delivery-end-response(cancel)
     */
    @Transactional
    public void handleDeliveryCancel(UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new CustomException(CommerceErrorCode.ORDER_NOT_FOUND));

        order.assignDeliveryId(null); // 배송 ID 연관성 제거
        orderRepository.save(order);  // 변경된 주문 저장

        // 트랜잭션 커밋 후 결제 취소 요청 전송
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                messagingProducerService.sendPaymentCancelRequest(orderId);
            }
        });
    }

    // 재고 차감 (분산 락 적용)
    private void decreaseStockForOrder(Order order) {
        List<OrderDetail> orderDetails = order.getOrderDetails();
        for (OrderDetail detail : orderDetails) {
            redissonLockStockFacade.decreaseStockForOrderWithLock(
                    detail.getProduct().getProductId(), detail.getOrderQuantity());
        }
    }

    // 재고 복구 (분산 락 적용)
    private void increaseStockForOrder(Order order) {
        List<OrderDetail> orderDetails = order.getOrderDetails();
        for (OrderDetail detail : orderDetails) {
            redissonLockStockFacade.increaseStockForOrderWithLock(
                    detail.getProduct().getProductId(), detail.getOrderQuantity());
        }
    }
}
