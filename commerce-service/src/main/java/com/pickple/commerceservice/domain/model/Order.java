package com.pickple.commerceservice.domain.model;

import com.pickple.common_module.domain.model.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "p_orders")
@SQLRestriction("is_delete = false")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Order extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name = "order_id", updatable = false, nullable = false)
    private UUID orderId;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "order_status", nullable = false)
    private OrderStatus orderStatus = OrderStatus.PENDING;

    @Builder.Default
    @Column(name = "amount", precision = 10, scale = 2, nullable = false)
    private BigDecimal amount = BigDecimal.ZERO;

    @Column(name = "username", nullable = false)
    private String username;

    @Column(name = "email")
    private String email;

    @Column(name = "payment_id")
    private UUID paymentId;

    @Column(name = "payment_amount", precision = 10, scale = 2)
    private BigDecimal paymentAmount;

    @Column(name = "payment_method")
    private String paymentMethod;

    @Column(name = "payment_status")
    private String paymentStatus;

    @Column(name = "delivery_id")
    private UUID deliveryId;

    @Column(name = "delivery_status")
    private String deliveryStatus;

    @Column(name = "delivery_type")
    private String deliveryType;

    @Column(name = "carrier_name")
    private String carrierName;

    @Column(name = "tracking_number")
    private String trackingNumber;

    @Column(name = "delivery_requirement")
    private String deliveryRequirement;

    @Column(name = "recipient_name")
    private String recipientName;

    @Column(name = "recipient_address")
    private String recipientAddress;

    @Column(name = "recipient_contact")
    private String recipientContact;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<OrderDetail> orderDetails;

    // addOrderDetails 메서드
    public void addOrderDetails(List<OrderDetail> details) {
        this.orderDetails = details;
    }

    // 총 금액 계산
    public void calculateTotalAmount() {
        this.amount = orderDetails.stream()
                .map(OrderDetail::getTotalPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // 결제 ID 연결
    public void assignPaymentId(UUID paymentId) {
        this.paymentId = paymentId;
    }

    public void assignPaymentSnapshot(UUID paymentId, BigDecimal paymentAmount, String paymentMethod, String paymentStatus) {
        this.paymentId = paymentId;
        this.paymentAmount = paymentAmount;
        this.paymentMethod = paymentMethod;
        this.paymentStatus = paymentStatus;
    }

    // 주문 ID 연결
    public void assignDeliveryId(UUID deliveryId) {
        this.deliveryId = deliveryId;
    }

    public void assignDeliverySnapshot(
            UUID deliveryId,
            String deliveryStatus,
            String deliveryType,
            String carrierName,
            String trackingNumber,
            String deliveryRequirement,
            String recipientName,
            String recipientAddress,
            String recipientContact
    ) {
        this.deliveryId = deliveryId;
        this.deliveryStatus = deliveryStatus;
        this.deliveryType = deliveryType;
        this.carrierName = carrierName;
        this.trackingNumber = trackingNumber;
        this.deliveryRequirement = deliveryRequirement;
        this.recipientName = recipientName;
        this.recipientAddress = recipientAddress;
        this.recipientContact = recipientContact;
    }

    public void updateDeliveryStatus(String deliveryStatus) {
        this.deliveryStatus = deliveryStatus;
    }

    public void clearPaymentSnapshot() {
        this.paymentId = null;
        this.paymentAmount = null;
        this.paymentMethod = null;
        this.paymentStatus = null;
    }

    // 주문 상태 변경
    public void changeStatus(OrderStatus newStatus) {
        this.orderStatus = newStatus;
    }

    // soft delete
    public void markAsDeleted() {
        this.isDelete = true;
    }
}
