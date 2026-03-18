package com.pickple.commerceservice.infrastructure.messaging.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockUpdatedEvent {
    private UUID productId;
    private UUID stockId;
    private Long stockQuantity;
}
