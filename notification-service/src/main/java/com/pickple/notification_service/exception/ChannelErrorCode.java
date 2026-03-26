package com.pickple.notification_service.exception;

import com.pickple.common_module.exception.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum ChannelErrorCode implements ErrorCode {

    CHANNEL_NOT_FOUND(HttpStatus.NOT_FOUND, "알림을 보내려는 Channel을 찾을 수 없습니다.");

    private final HttpStatus status;
    private final String message;

}
