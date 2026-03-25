package com.pickple.commerceservice.infrastructure.feign;

import com.pickple.commerceservice.exception.CommerceErrorCode;
import com.pickple.commerceservice.infrastructure.feign.dto.DeliveryClientDto;
import com.pickple.common_module.exception.CustomException;
import com.pickple.common_module.presentation.dto.ApiResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.annotation.Primary;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.UUID;

@Primary
@FeignClient(name = "delivery-service")
public interface DeliveryClient {

    Logger log = LoggerFactory.getLogger(DeliveryClient.class);

    @CircuitBreaker(name = "deliveryService", fallbackMethod = "getDeliveryInfoFallback")
    @GetMapping("/api/v1/deliveries/orders/{orderId}")
    ApiResponse<DeliveryClientDto> getDeliveryInfo(
            @RequestHeader("X-User-Roles") String authority,
            @RequestHeader("X-User-Name") String username,
            @PathVariable("orderId") UUID orderId);

    default ApiResponse<DeliveryClientDto> getDeliveryInfoFallback(String authority, String username, UUID orderId, Throwable throwable) {
        log.error("delivery-service 호출 실패 (getDeliveryInfo). orderId: {}, 원인: {}", orderId, throwable.getMessage(), throwable);
        throw new CustomException(CommerceErrorCode.DELIVERY_SERVICE_ERROR);
    }
}
