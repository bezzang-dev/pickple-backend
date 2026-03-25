package com.pickple.auth.infrastructure.feign;

import com.pickple.auth.application.dto.UserDto;
import com.pickple.auth.exception.AuthErrorCode;
import com.pickple.auth.presentation.request.SignUpRequestDto;
import com.pickple.auth.presentation.response.UserResponseDto;
import com.pickple.common_module.exception.CustomException;
import com.pickple.common_module.presentation.dto.ApiResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "user-service")
public interface UserServiceClient {

	Logger log = LoggerFactory.getLogger(UserServiceClient.class);

	@CircuitBreaker(name = "userService", fallbackMethod = "getUserByUsernameFallback")
	@GetMapping("/api/v1/users/user/{username}")
	UserDto getUserByUsername(@PathVariable("username") String username);

	@CircuitBreaker(name = "userService", fallbackMethod = "registerUserFallback")
	@PostMapping("/api/v1/users/sign-up")
	ApiResponse<UserResponseDto> registerUser(@RequestBody SignUpRequestDto signUpDto);

	default UserDto getUserByUsernameFallback(String username, Throwable throwable) {
		log.error("user-service 호출 실패 (getUserByUsername). username: {}, 원인: {}", username, throwable.getMessage(), throwable);
		throw new CustomException(AuthErrorCode.USER_SERVICE_ERROR);
	}

	default ApiResponse<UserResponseDto> registerUserFallback(SignUpRequestDto signUpDto, Throwable throwable) {
		log.error("user-service 호출 실패 (registerUser). 원인: {}", throwable.getMessage(), throwable);
		throw new CustomException(AuthErrorCode.USER_SERVICE_ERROR);
	}
}
