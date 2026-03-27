package com.pickple.commerceservice.infrastructure.feign;

import com.pickple.commerceservice.exception.CommerceErrorCode;
import com.pickple.common_module.exception.CustomException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;

@FeignClient(name = "user-service")
public interface UserClient {

    Logger log = LoggerFactory.getLogger(UserClient.class);

    @CircuitBreaker(name = "userService", fallbackMethod = "getUserEmailFallback")
    @GetMapping("/api/v1/users/get-user-email/{username}")
    String getUserEmail(
            @PathVariable("username") String reqUsername,
            @RequestHeader("X-User-Name") String username,
            @RequestHeader("X-User-Roles") String role);

    default String getUserEmailFallback(String reqUsername, String username, String role, Throwable throwable) {
        log.error("user-service 호출 실패 (getUserEmail). reqUsername: {}, 원인: {}", reqUsername, throwable.getMessage(), throwable);
        throw new CustomException(CommerceErrorCode.USER_SERVICE_ERROR);
    }
}
