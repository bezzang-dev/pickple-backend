package com.pickple.notification_service.infrastructure.feign;

import com.pickple.common_module.exception.CustomException;
import com.pickple.notification_service.exception.NotificationErrorCode;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;

@FeignClient(name="user-service")
public interface UserFeignClient {

    @CircuitBreaker(name = "userService", fallbackMethod = "getUserEmailFallback")
    @GetMapping("/api/v1/users/get-user-email/{username}")
    String getUserEmail(@PathVariable("username") String reqUsername,
                        @RequestHeader("X-User-Name") String username,
                        @RequestHeader("X-User-Roles") String role);

    default String getUserEmailFallback(String reqUsername, String username, String role, Throwable throwable) {
        throw new CustomException(NotificationErrorCode.USER_SERVICE_ERROR);
    }
}
