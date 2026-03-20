package com.pickple.delivery.infrastructure.feign;

import com.pickple.common_module.exception.CustomException;
import com.pickple.delivery.application.port.OrderClient;
import com.pickple.delivery.exception.DeliveryErrorCode;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;

@FeignClient(name = "commerce-service")
public interface OrderFeignClient extends OrderClient {

    @CircuitBreaker(name = "orderService", fallbackMethod = "getUsernameByDeliveryIdFallback")
    @GetMapping("/api/v1/orders/deliveries/{deliveryId}/username")
    String getUsernameByDeliveryId(
            @PathVariable("deliveryId") UUID deliveryId,
            @RequestHeader("X-User-Roles") String role,
            @RequestHeader("X-User-Name") String username
    );

    default String getUsernameByDeliveryIdFallback(UUID deliveryId, String role, String username, Throwable throwable) {
        throw new CustomException(DeliveryErrorCode.ORDER_SERVICE_ERROR);
    }
}
