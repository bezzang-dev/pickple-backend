package com.pickple.commerceservice.application.dto;

import com.pickple.commerceservice.domain.model.Order;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OrderPaymentSnapshotDto {

    private UUID orderId;
    private UUID paymentId;
    private BigDecimal amount;
    private String method;
    private String status;

    public static OrderPaymentSnapshotDto fromOrder(Order order) {
        if (order.getPaymentId() == null) {
            return null;
        }

        return OrderPaymentSnapshotDto.builder()
                .orderId(order.getOrderId())
                .paymentId(order.getPaymentId())
                .amount(order.getPaymentAmount())
                .method(order.getPaymentMethod())
                .status(order.getPaymentStatus())
                .build();
    }
}
