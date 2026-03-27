package com.pickple.commerceservice.application.service;

import com.pickple.commerceservice.application.dto.*;
import com.pickple.commerceservice.domain.model.Order;
import com.pickple.commerceservice.domain.model.OrderDetail;
import com.pickple.commerceservice.domain.model.OrderStatus;
import com.pickple.commerceservice.domain.model.Product;
import com.pickple.commerceservice.domain.repository.OrderRepository;
import com.pickple.commerceservice.domain.repository.PreOrderRepository;
import com.pickple.commerceservice.domain.repository.ProductRepository;
import com.pickple.commerceservice.exception.CommerceErrorCode;
import com.pickple.commerceservice.infrastructure.facade.RedissonLockStockFacade;
import com.pickple.commerceservice.infrastructure.feign.DeliveryClient;
import com.pickple.commerceservice.infrastructure.feign.PaymentClient;
import com.pickple.commerceservice.infrastructure.feign.UserClient;
import com.pickple.commerceservice.infrastructure.feign.dto.DeliveryClientDto;
import com.pickple.commerceservice.infrastructure.feign.dto.PaymentClientDto;
import com.pickple.commerceservice.infrastructure.redis.OrderTimeoutService;
import com.pickple.commerceservice.infrastructure.redis.TemporaryStorageService;
import com.pickple.commerceservice.presentation.dto.request.OrderCreateRequestDto;
import com.pickple.commerceservice.presentation.dto.request.PreOrderRequestDto;
import com.pickple.common_module.exception.CustomException;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final StockService stockService;
    private final RedissonLockStockFacade redissonLockStockFacade;
    private final TemporaryStorageService temporaryStorageService;
    private final OrderTimeoutService orderTimeoutService;
    private final OrderMessagingProducerService messagingProducerService;
    private final ProductRepository productRepository;
    private final PreOrderRepository preOrderRepository;
    private final PaymentClient paymentClient;
    private final DeliveryClient deliveryClient;
    private final UserClient userClient;

    /**
     * 주문 생성
     */
    @Transactional
    public OrderCreateResponseDto createOrder(OrderCreateRequestDto requestDto, String username, String role) {
        // 주문 생성 시점에 이메일 조회 (이후 비동기 이벤트 체인에서 Feign 호출 없이 사용)
        String email = userClient.getUserEmail(username, username, role);

        // 주문 정보 생성
        Order order = Order.builder()
                .orderStatus(OrderStatus.PENDING)
                .username(username)
                .email(email)
                .build();

        // OrderDetail 정보 생성
        List<OrderDetail> orderDetails = requestDto.getOrderDetails().stream()
                .map(detail -> {
                    Product product = productRepository.findById(detail.getProductId())
                            .orElseThrow(() -> new CustomException(CommerceErrorCode.PRODUCT_NOT_FOUND));

                    // 재고 확인
                    StockByProductDto stockDto = stockService.getStockByProductId(product.getProductId());
                    if (stockDto.getStockQuantity() < detail.getOrderQuantity()) {
                        throw new CustomException(CommerceErrorCode.INSUFFICIENT_STOCK);
                    }

                    OrderDetail orderDetail = OrderDetail.builder()
                            .order(order)
                            .product(product)
                            .orderQuantity(detail.getOrderQuantity())
                            .unitPrice(product.getProductPrice())
                            .build();

                    orderDetail.calculateTotalPrice(); // 단가*수량 계산
                    return orderDetail;
                })
                .collect(Collectors.toList());

        order.addOrderDetails(orderDetails);
        order.calculateTotalAmount();

        orderRepository.save(order);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // 주문 타임아웃 등록 (10분 내 결제 미완료 시 자동 취소)
                orderTimeoutService.registerOrderTimeout(order.getOrderId());

                // 결제 요청 (Kafka)
                messagingProducerService.sendPaymentRequest(order.getOrderId(), order.getAmount(), username);

                // 주문 완료 알림 전송
                messagingProducerService.sendNotificationCreateRequest(
                        username,
                        role,
                        "System",
                        "주문 완료",
                        "주문이 성공적으로 완료되었습니다. 주문 번호: " + order.getOrderId(),
                        "ORDER"
                );
            }
        });

        // 배송 정보 저장 (Redis)
        temporaryStorageService.storeDeliveryInfoWithTTL(order.getOrderId(), requestDto.getDeliveryInfo());

        // OrderCreateResponseDto 반환 (fromEntity 메서드 활용)
        return OrderCreateResponseDto.fromEntity(order);
    }

    /**
     * 주문 단건 조회
     */
    @Transactional(readOnly = true)
    public OrderResponseDto getOrderById(UUID orderId, String role, String username) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new CustomException(CommerceErrorCode.ORDER_NOT_FOUND));

        // 결제 정보 가져오기
        PaymentClientDto paymentInfo = null;
        if (order.getPaymentId() != null) {
            try {
                paymentInfo = paymentClient.getPaymentInfo(role, username, orderId);
            } catch (FeignException e) {
                // 결제 정보를 가져오지 못했을 경우 CustomException 발생
                throw new CustomException(CommerceErrorCode.PAYMENT_SERVICE_ERROR);
            }
        }

        // 배송 정보 가져오기
        DeliveryClientDto deliveryInfo = null;
        if (order.getDeliveryId() != null) {
            try {
                deliveryInfo = deliveryClient.getDeliveryInfo(role, username, orderId).getData();
            } catch (FeignException e) {
                // 배송 정보를 가져오지 못했을 경우 CustomException 발생
                throw new CustomException(CommerceErrorCode.DELIVERY_SERVICE_ERROR);
            }
        }

        // OrderResponseDto 반환 (fromEntity 메서드 활용)
        return OrderResponseDto.fromEntity(order, paymentInfo, deliveryInfo);
    }

    /**
     * 예약 구매 주문 생성
     */
    @Transactional
    public void createPreOrder(PreOrderRequestDto requestDto, String username) {
        // 상품 조회
        Product product = productRepository.findById(requestDto.getProductId())
                .orElseThrow(() -> new CustomException(CommerceErrorCode.PRODUCT_NOT_FOUND));

        // 예약구매 상품 여부 확인
        preOrderRepository.findByProduct_ProductIdAndIsDeleteFalse(product.getProductId())
                .orElseThrow(() -> new CustomException(CommerceErrorCode.INVALID_PRODUCT_FOR_PREORDER));

        // 재고 확인 및 차감
        try {
            redissonLockStockFacade.decreaseStockQuantityWithLock(product.getProductId());
        } catch (CustomException e) {
            log.error("주문 생성 실패: 재고가 부족합니다.");
            throw e;
        }

        // 주문 및 주문 정보 생성
        Order order = Order.builder()
                .username(username)
                .amount(product.getProductPrice())     // 주문 총액은 곧 상품 가격
                .build();

        // 주문 상태 변경
        order.changeStatus(OrderStatus.COMPLETED);

        // 주문 저장
        orderRepository.save(order);
    }

    /**
     * 주문 취소 메소드
     */
    @Transactional
    public OrderResponseDto cancelOrder(UUID orderId, String username, String role) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new CustomException(CommerceErrorCode.ORDER_NOT_FOUND));

        // 배송 정보 조회 (Feign) — Kafka 발행 전에 먼저 수행하여, 실패 시 트랜잭션 롤백만 발생
        DeliveryClientDto deliveryInfo = null;
        try {
            deliveryInfo = deliveryClient.getDeliveryInfo(role, username, orderId).getData();
        } catch (FeignException e) {
            log.warn("배송 정보 조회 실패 (배송이 아직 생성되지 않았을 수 있음). orderId: {}", orderId);
        }

        order.changeStatus(OrderStatus.CANCELED);
        order.markAsDeleted();
        orderRepository.save(order);

        // 트랜잭션 커밋 후 Kafka 이벤트 발행 — 롤백 시 메시지가 발행되지 않음
        final DeliveryClientDto finalDeliveryInfo = deliveryInfo;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                messagingProducerService.sendPaymentCancelRequest(orderId);

                if (finalDeliveryInfo != null && finalDeliveryInfo.getDeliveryId() != null) {
                    messagingProducerService.sendDeliveryDeleteRequest(
                            finalDeliveryInfo.getDeliveryId(), orderId, username);
                }
            }
        });

        // OrderResponseDto 반환 (fromEntity 메서드 활용)
        return OrderResponseDto.fromEntity(order, null, null);
    }

    /**
     * 업체별 주문 조회
     */
    @Transactional(readOnly = true)
    public Page<OrderByVendorResponseDto> findByVendorId(UUID vendorId, Pageable pageable) {
        return orderRepository.findOrdersByVendorId(vendorId, pageable)
                .map(OrderByVendorResponseDto::fromEntity);
    }

    /**
     * 전체 주문 조회
     */
    @Transactional(readOnly = true)
    public Page<OrderSummaryResponseDto> getAllOrders(Pageable pageable) {
        return orderRepository.findAll(pageable)
                .map(OrderSummaryResponseDto::fromEntity);
    }

    /**
     * 내 주문 조회
     */
    @Transactional(readOnly = true)
    public Page<OrderSummaryResponseDto> getOrdersByUsername(String username, Pageable pageable) {
        return orderRepository.findByUsernameAndIsDeleteFalse(username, pageable)
                .map(OrderSummaryResponseDto::fromEntity);
    }

    /**
     * 주문 검색 (주문 상태)
     */
    @Transactional(readOnly = true)
    public Page<OrderSummaryResponseDto> findOrdersByOrderStatus(OrderStatus orderStatus, Pageable pageable) {
        return orderRepository.findOrdersByOrderStatus(orderStatus, pageable)
                .map(OrderSummaryResponseDto::fromEntity);
    }

    /**
     * order timeout
     */
    @Transactional
    public void handleOrderTimeout(UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new CustomException(CommerceErrorCode.ORDER_NOT_FOUND));

        // 결제가 이루어지지 않았고
        if (order.getPaymentId() == null) {
            log.info("주문이 결제되지 않았으므로 취소 처리됩니다. orderId: {}", orderId);

            // 주문 상태를 CANCELED로 변경
            order.changeStatus(OrderStatus.CANCELED);
            order.markAsDeleted();
            orderRepository.save(order);

            // 트랜잭션 커밋 후 결제 취소 요청 전송
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    messagingProducerService.sendPaymentCancelRequest(orderId);
                }
            });
        }
    }
}