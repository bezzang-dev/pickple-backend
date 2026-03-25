package com.pickple.commerceservice.infrastructure.redis;

import com.pickple.commerceservice.application.service.OrderService;
import com.pickple.commerceservice.domain.model.OrderStatus;
import com.pickple.commerceservice.domain.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderTimeoutService {

    private final OrderService orderService;
    private final OrderRepository orderRepository;
    private final RedisTemplate<String, Object> redisTemplate;

    private static final String ORDER_TIMEOUT_KEY_PREFIX = "order-timeout:";

    /**
     * 주문 생성 시 타임아웃 키를 Redis에 등록 (TTL 10분)
     */
    public void registerOrderTimeout(UUID orderId) {
        String key = ORDER_TIMEOUT_KEY_PREFIX + orderId;
        redisTemplate.opsForValue().set(key, "PENDING", Duration.ofMinutes(10));
        log.info("주문 타임아웃 등록. orderId: {}, TTL: 10분", orderId);
    }

    /**
     * 결제 완료 시 타임아웃 키 제거
     */
    public void cancelOrderTimeout(UUID orderId) {
        String key = ORDER_TIMEOUT_KEY_PREFIX + orderId;
        redisTemplate.delete(key);
    }

    /**
     * 1분마다 PENDING 상태인 주문 중 타임아웃 키가 만료된 건을 확인하여 취소 처리
     */
    @Scheduled(fixedRate = 60000)
    public void checkPaymentTimeout() {
        orderRepository.findByOrderStatusAndIsDeleteFalse(OrderStatus.PENDING)
                .forEach(order -> {
                    String key = ORDER_TIMEOUT_KEY_PREFIX + order.getOrderId();
                    // 키가 만료되었으면(존재하지 않으면) 타임아웃 처리
                    if (!Boolean.TRUE.equals(redisTemplate.hasKey(key))) {
                        log.info("주문 결제 타임아웃 감지. orderId: {}", order.getOrderId());
                        try {
                            orderService.handleOrderTimeout(order.getOrderId());
                        } catch (Exception e) {
                            log.error("주문 타임아웃 처리 실패. orderId: {}, error: {}",
                                    order.getOrderId(), e.getMessage(), e);
                        }
                    }
                });
    }
}
