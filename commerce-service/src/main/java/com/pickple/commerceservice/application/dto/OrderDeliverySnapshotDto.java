package com.pickple.commerceservice.application.dto;

import com.pickple.commerceservice.domain.model.Order;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OrderDeliverySnapshotDto {

    private UUID deliveryId;
    private UUID orderId;
    private String carrierName;
    private String deliveryType;
    private String trackingNumber;
    private String deliveryStatus;
    private String deliveryRequirement;
    private String recipientName;
    private String recipientAddress;
    private String recipientContact;

    public static OrderDeliverySnapshotDto fromOrder(Order order) {
        if (order.getDeliveryId() == null && order.getDeliveryStatus() == null) {
            return null;
        }

        return OrderDeliverySnapshotDto.builder()
                .deliveryId(order.getDeliveryId())
                .orderId(order.getOrderId())
                .carrierName(order.getCarrierName())
                .deliveryType(order.getDeliveryType())
                .trackingNumber(order.getTrackingNumber())
                .deliveryStatus(order.getDeliveryStatus())
                .deliveryRequirement(order.getDeliveryRequirement())
                .recipientName(order.getRecipientName())
                .recipientAddress(order.getRecipientAddress())
                .recipientContact(order.getRecipientContact())
                .build();
    }
}
