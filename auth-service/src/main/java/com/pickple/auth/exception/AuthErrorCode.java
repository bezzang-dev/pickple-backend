package com.pickple.auth.exception;

import com.pickple.common_module.exception.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum AuthErrorCode implements ErrorCode {
    // ------- 4xx --------
    FORBIDDEN(HttpStatus.FORBIDDEN, "접근이 거부되었습니다."),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "해당 유저가 존재하지 않습니다."),
    ALREADY_SAME_ROLE(HttpStatus.BAD_REQUEST, "유저 권한이 이미 동일한 권한으로 설정되어 있습니다."),
    REGISTER_FAILED(HttpStatus.BAD_REQUEST, "회원가입에 실패하였습니다."),
    SIGNUP_FAILED(HttpStatus.BAD_REQUEST, "회원가입 처리 중 오류가 발생했습니다."),
    USER_SERVICE_ERROR(HttpStatus.SERVICE_UNAVAILABLE, "유저 서비스와의 통신 중 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String message;

    @Override
    public String getMessage() {
        return message;
    }
    @Override
    public HttpStatus getStatus() {
        return status;
    }

}

