package com.pickple.commerceservice.infrastructure.feign;

import com.pickple.commerceservice.exception.CommerceErrorCode;
import com.pickple.commerceservice.infrastructure.feign.dto.PaymentClientDto;
import com.pickple.common_module.exception.CustomException;
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
@FeignClient(name = "payment-service")
public interface PaymentClient {

    Logger log = LoggerFactory.getLogger(PaymentClient.class);

    @CircuitBreaker(name = "paymentService", fallbackMethod = "fallbackGetPaymentInfo")
    @GetMapping("/api/v1/payments/getPaymentInfo/{orderId}")
    PaymentClientDto getPaymentInfo(
            @RequestHeader("X-User-Roles") String authority,
            @RequestHeader("X-User-Name") String username,
            @PathVariable UUID orderId);

    default PaymentClientDto fallbackGetPaymentInfo(String authority, String username, UUID orderId, Throwable throwable) {
        log.error("payment-service 호출 실패 (getPaymentInfo). orderId: {}, 원인: {}", orderId, throwable.getMessage(), throwable);
        throw new CustomException(CommerceErrorCode.PAYMENT_SERVICE_ERROR);
    }
}
