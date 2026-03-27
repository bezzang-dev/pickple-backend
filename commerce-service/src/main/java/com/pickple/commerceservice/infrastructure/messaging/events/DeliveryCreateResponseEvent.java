package com.pickple.commerceservice.infrastructure.messaging.events;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Getter
@AllArgsConstructor
@NoArgsConstructor
public class DeliveryCreateResponseEvent {
    private UUID orderId;
    private UUID deliveryId;
    private String deliveryStatus;
    private String deliveryType;
    private String carrierName;
    private String trackingNumber;
    private String deliveryRequirement;
    private String recipientName;
    private String recipientAddress;
    private String recipientContact;
}
