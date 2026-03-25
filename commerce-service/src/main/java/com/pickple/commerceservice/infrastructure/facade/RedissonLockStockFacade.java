package com.pickple.commerceservice.infrastructure.facade;

import com.pickple.commerceservice.application.service.StockRetryService;
import com.pickple.commerceservice.exception.CommerceErrorCode;
import com.pickple.common_module.exception.CustomException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Component
@Slf4j
@RequiredArgsConstructor
public class RedissonLockStockFacade {

    private static final long LOCK_WAIT_TIME = 15L;
    private static final long LOCK_LEASE_TIME = 3L;

    private final RedissonClient redissonClient;
    private final StockRetryService stockRetryService;

    /**
     * 예약 구매: 재고 1 감소 (분산 락 적용)
     */
    public void decreaseStockQuantityWithLock(UUID productId) {
        executeWithLock(productId, () -> {
            stockRetryService.decreaseStockQuantityWithRetry(productId);
            return null;
        });
    }

    /**
     * 일반 주문: 지정 수량만큼 재고 감소 (분산 락 적용)
     */
    public void decreaseStockForOrderWithLock(UUID productId, Long quantity) {
        executeWithLock(productId, () -> {
            stockRetryService.decreaseStockQuantityForOrderWithRetry(productId, quantity);
            return null;
        });
    }

    /**
     * 주문 취소: 지정 수량만큼 재고 복구 (분산 락 적용)
     */
    public void increaseStockForOrderWithLock(UUID productId, Long quantity) {
        executeWithLock(productId, () -> {
            stockRetryService.increaseStockQuantityForOrderWithRetry(productId, quantity);
            return null;
        });
    }

    private <T> T executeWithLock(UUID productId, Supplier<T> action) {
        RLock lock = redissonClient.getLock("stockLock:" + productId);

        try {
            boolean isLocked = lock.tryLock(LOCK_WAIT_TIME, LOCK_LEASE_TIME, TimeUnit.SECONDS);
            if (!isLocked) {
                log.error("재고 lock 획득 실패. 상품 ID: {}", productId);
                throw new CustomException(CommerceErrorCode.STOCK_LOCK_FAILED);
            }

            return action.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("재고 작업이 중단되었습니다. 상품 ID: {}, 원인: {}", productId, e.getMessage());
            throw new CustomException(CommerceErrorCode.STOCK_LOCK_FAILED);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
