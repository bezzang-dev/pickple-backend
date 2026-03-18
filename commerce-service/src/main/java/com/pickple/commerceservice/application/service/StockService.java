package com.pickple.commerceservice.application.service;

import com.pickple.commerceservice.application.dto.StockByProductDto;
import com.pickple.commerceservice.application.dto.StockResponseDto;
import com.pickple.commerceservice.application.events.KafkaOutboxEvent;
import com.pickple.commerceservice.domain.model.OrderDetail;
import com.pickple.commerceservice.domain.model.Product;
import com.pickple.commerceservice.domain.model.Stock;
import com.pickple.commerceservice.domain.repository.StockRepository;
import com.pickple.commerceservice.exception.CommerceErrorCode;
import com.pickple.commerceservice.infrastructure.messaging.events.StockUpdatedEvent;
import com.pickple.commerceservice.presentation.dto.request.StockUpdateRequestDto;
import com.pickple.common_module.exception.CustomException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class StockService {

    private final StockRepository stockRepository;
    private final ApplicationEventPublisher applicationEventPublisher;

    @Value("${kafka.topic.stock-updated}")
    private String stockUpdatedTopic;

    // 재고 생성
    @Transactional
    public Stock createStock(Product product, Long quantity) {
        Stock stock = Stock.builder()
                .stockQuantity(quantity)
                .product(product)
                .build();
        return stockRepository.save(stock);
    }

    // 상품 별 재고 조회
    @Transactional(readOnly = true)
    public StockByProductDto getStockByProductId(UUID productId) {
        Stock stock = findStockByProductId(productId);
        return StockByProductDto.fromEntity(stock);
    }

    // 상품 별 재고 수정
    @Transactional
    public StockByProductDto updateStockQuantity(UUID productId, StockUpdateRequestDto updateDto) {
        Stock stock = findStockByProductId(productId);
        stock.updateStockQuantity(updateDto.getStockQuantity());
        publishStockUpdatedEvent(stock);
        return StockByProductDto.fromEntity(stock);
    }

    // 재고 1 증가 메서드
    @Transactional
    public void increaseStockQuantity(UUID productId) {
        Stock stock = findStockByProductId(productId);
        stock.increaseStock();  // 수량 1 증가
        publishStockUpdatedEvent(stock);
    }

    // 재고 1 감소 메서드
    @Transactional
    public void decreaseStockQuantity(UUID productId) {
        Stock stock = findStockByProductId(productId);
        stock.decreaseStock();  // 수량 1 감소
        stockRepository.save(stock);
        publishStockUpdatedEvent(stock);
    }

    // 주문한 수량만큼 재고 감소 메서드
    @Transactional
    public void decreaseStockQuantityForOrder(OrderDetail orderDetail) {
        UUID productId = orderDetail.getProduct().getProductId();
        Long quantityToReduce = orderDetail.getOrderQuantity();

        // 해당 상품 재고 조회
        Stock stock = findStockByProductId(productId);

        // 재고 수량 차감
        long currentQuantity = stock.getStockQuantity();
        if (currentQuantity < quantityToReduce) {
            throw new CustomException(CommerceErrorCode.INSUFFICIENT_STOCK);
        }
        stock.decreaseStockQuantity(currentQuantity - quantityToReduce);
        publishStockUpdatedEvent(stock);
    }

    // 상품 ID로 재고 조회 메서드
    private Stock findStockByProductId(UUID productId) {
        return stockRepository.findByProduct_ProductId(productId)
                .orElseThrow(() -> new CustomException(CommerceErrorCode.STOCK_DATA_NOT_FOUND_FOR_PRODUCT));
    }

    // 주문한 수량만큼 재고 복구 메서드
    @Transactional
    public void increaseStockQuantityForOrder(OrderDetail orderDetail) {
        UUID productId = orderDetail.getProduct().getProductId();
        Long quantityToIncrease = orderDetail.getOrderQuantity();

        // 해당 상품 재고 조회
        Stock stock = findStockByProductId(productId);

        // 재고 수량 복구
        long currentQuantity = stock.getStockQuantity();
        stock.updateStockQuantity(currentQuantity + quantityToIncrease);

        stockRepository.save(stock);
        publishStockUpdatedEvent(stock);
    }

    /**
     * 재고 변경 시 ES 동기화를 위한 이벤트 발행 (Outbox 패턴)
     */
    private void publishStockUpdatedEvent(Stock stock) {
        StockUpdatedEvent event = StockUpdatedEvent.builder()
                .productId(stock.getProduct().getProductId())
                .stockId(stock.getStockId())
                .stockQuantity(stock.getStockQuantity())
                .build();

        applicationEventPublisher.publishEvent(
                new KafkaOutboxEvent(stockUpdatedTopic,
                        stock.getProduct().getProductId().toString(), event));
    }

}
