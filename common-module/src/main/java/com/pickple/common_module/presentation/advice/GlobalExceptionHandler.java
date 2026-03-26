package com.pickple.common_module.presentation.advice;

import com.pickple.common_module.exception.CommonErrorCode;
import com.pickple.common_module.exception.CustomException;
import com.pickple.common_module.exception.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * 모든 서비스에서 공통으로 사용되는 전역 예외 핸들러 기반 클래스입니다.
 *
 * <p>처리하는 예외 목록:</p>
 * <ul>
 *   <li>{@link CustomException} - 비즈니스 로직 예외</li>
 *   <li>{@link MethodArgumentNotValidException} - @Valid 검증 실패 예외</li>
 *   <li>{@link Throwable} - 처리되지 않은 모든 예외 (fallback)</li>
 * </ul>
 *
 * <p>각 서비스는 이 클래스를 상속받아 {@code @RestControllerAdvice}를 선언하면
 * 공통 예외 처리 로직을 재사용하면서 서비스별 추가 예외를 확장할 수 있습니다.</p>
 */
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(CustomException.class)
    public ResponseEntity<ErrorResponse> handleCustomException(CustomException e) {
        log.warn("CustomException 발생: code={}, message={}",
                e.getErrorCode().getClass().getSimpleName() + "." + e.getErrorCode(),
                e.getMessage());
        return ResponseEntity
                .status(e.getErrorCode().getStatus())
                .body(ErrorResponse.error(e.getErrorCode()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        ErrorResponse errorResponse = ErrorResponse.builder()
                .status(HttpStatus.BAD_REQUEST)
                .message(CommonErrorCode.INVALID_INPUT_VALUE.getMessage())
                .build();
        e.getBindingResult().getFieldErrors().forEach(error ->
                errorResponse.addValidation(error.getField(), error.getDefaultMessage())
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }

    @ExceptionHandler(Throwable.class)
    public ResponseEntity<ErrorResponse> handleThrowable(Throwable e) {
        log.error("처리되지 않은 예외 발생", e);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.error(CommonErrorCode.INTERNAL_SERVER_ERROR));
    }
}
