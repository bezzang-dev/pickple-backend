package com.pickple.notification_service.domain.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum NotificationCategory {
    USER("USER"),
    PRODUCT("PRODUCT"),
    VENDOR("VENDOR"),
    ORDER("ORDER"),
    PAYMENT("PAYMENT"),
    DELIVERY("DELIVERY");

    private final String category;
}
