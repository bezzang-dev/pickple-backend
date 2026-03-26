package com.pickple.notification_service.exception;

import com.pickple.common_module.presentation.advice.GlobalExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * notification-service 전용 예외 핸들러입니다.
 * CustomException, MethodArgumentNotValidException, Throwable 등 공통 예외는
 * 부모 클래스인 GlobalExceptionHandler가 처리합니다.
 * 이 클래스는 notification-service에서만 발생하는 추가 예외가 생길 경우 확장하여 사용합니다.
 */
@RestControllerAdvice
public class NotificationExceptionHandler extends GlobalExceptionHandler {
}
