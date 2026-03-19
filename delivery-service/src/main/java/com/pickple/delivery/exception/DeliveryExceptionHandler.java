package com.pickple.delivery.exception;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.pickple.common_module.exception.CommonErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * delivery-service 전용 예외 핸들러입니다.
 * CustomException, MethodArgumentNotValidException 등 공통 예외는
 * common-module의 GlobalExceptionHandler가 처리합니다.
 */
@RestControllerAdvice
public class DeliveryExceptionHandler {

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> handleHttpMessageNotReadable(HttpMessageNotReadableException e) {
        Throwable rootCause = e.getCause();
        if (rootCause instanceof InvalidFormatException invalidFormatException) {
            String fieldName = invalidFormatException.getPath().get(0).getFieldName();
            String message = String.format("유효하지 않는 '%s' 값입니다.", fieldName);
            return ResponseEntity.badRequest().body(message);
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(CommonErrorCode.INVALID_INPUT_VALUE);
    }
}
