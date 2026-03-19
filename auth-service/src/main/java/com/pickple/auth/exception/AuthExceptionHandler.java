package com.pickple.auth.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * auth-service 전용 예외 핸들러입니다.
 * CustomException, MethodArgumentNotValidException 등 공통 예외는
 * common-module의 GlobalExceptionHandler가 처리합니다.
 */
@RestControllerAdvice
public class AuthExceptionHandler {

    @ExceptionHandler(CustomAuthException.class)
    public ResponseEntity<String> handleCustomAuthException(CustomAuthException e) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(e.getMessage());
    }
}
