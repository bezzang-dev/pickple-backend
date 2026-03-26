package com.pickple.payment_service.domain.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum PaymentStatus {
    PENDING("결제 대기"),
    COMPLETED("결제 완료"),
    CANCELED("결제 취소");

    private final String status;
}
