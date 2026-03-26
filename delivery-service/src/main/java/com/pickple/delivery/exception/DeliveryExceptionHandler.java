package com.pickple.delivery.exception;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.pickple.common_module.exception.ErrorResponse;
import com.pickple.common_module.presentation.advice.GlobalExceptionHandler;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * delivery-service 전용 예외 핸들러입니다.
 * CustomException, MethodArgumentNotValidException, Throwable 등 공통 예외는
 * 부모 클래스인 GlobalExceptionHandler가 처리합니다.
 */
@RestControllerAdvice
public class DeliveryExceptionHandler extends GlobalExceptionHandler {

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleHttpMessageNotReadable(HttpMessageNotReadableException e) {
        Throwable rootCause = e.getCause();
        if (rootCause instanceof InvalidFormatException invalidFormatException) {
            String fieldName = invalidFormatException.getPath().get(0).getFieldName();
            String message = String.format("유효하지 않는 '%s' 값입니다.", fieldName);
            ErrorResponse errorResponse = ErrorResponse.builder()
                    .status(HttpStatus.BAD_REQUEST)
                    .message(message)
                    .build();
            return ResponseEntity.badRequest().body(errorResponse);
        }
        ErrorResponse errorResponse = ErrorResponse.builder()
                .status(HttpStatus.BAD_REQUEST)
                .message("잘못된 요청 형식입니다.")
                .build();
        return ResponseEntity.badRequest().body(errorResponse);
    }
}
