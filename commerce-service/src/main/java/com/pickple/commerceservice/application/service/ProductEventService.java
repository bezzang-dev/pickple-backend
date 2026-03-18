package com.pickple.commerceservice.application.service;

import com.pickple.commerceservice.domain.model.ProductDocument;
import com.pickple.commerceservice.domain.model.ProductDocument.StockInfo;
import com.pickple.commerceservice.domain.model.ProductDocument.VendorInfo;
import com.pickple.commerceservice.domain.repository.ProductSearchRepository;
import com.pickple.commerceservice.exception.CommerceErrorCode;
import com.pickple.commerceservice.infrastructure.messaging.events.ProductCreatedEvent;
import com.pickple.commerceservice.infrastructure.messaging.events.ProductDeletedEvent;
import com.pickple.commerceservice.infrastructure.messaging.events.ProductUpdatedEvent;
import com.pickple.commerceservice.infrastructure.messaging.events.StockUpdatedEvent;
import com.pickple.common_module.exception.CustomException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProductEventService {

    private final ProductSearchRepository productSearchRepository;

    @Transactional
    public void handleProductCreated(ProductCreatedEvent event) {
        ProductDocument productDocument = ProductDocument.builder()
                .productId(event.getProductId())
                .productName(event.getProductName())
                .description(event.getDescription())
                .productPrice(event.getProductPrice())
                .productImage(event.getProductImage())
                .isPublic(event.getIsPublic())
                .isDelete(event.getIsDelete())
                .vendor(new VendorInfo(
                        event.getVendor().getVendorId(),
                        event.getVendor().getVendorName(),
                        event.getVendor().getVendorAddress(),
                        event.getVendor().getUsername()
                ))
                .stock(new StockInfo(
                        event.getStock().getStockId(),
                        event.getStock().getStockQuantity()
                ))
                .build();
        productSearchRepository.save(productDocument);
    }

    @Transactional
    public void handleProductUpdated(ProductUpdatedEvent event) {
        ProductDocument productDocument = productSearchRepository.findById(event.getProductId())
                .orElseThrow(() -> new CustomException(CommerceErrorCode.PRODUCT_DOCUMENT_NOT_FOUND));
        productDocument.update(event);
        productSearchRepository.save(productDocument);
    }

    @Transactional
    public void handleProductDeleted(ProductDeletedEvent event) {
        ProductDocument productDocument = productSearchRepository.findById(event.getProductId())
                .orElseThrow(() -> new CustomException(CommerceErrorCode.PRODUCT_DOCUMENT_NOT_FOUND));
        productDocument.markAsDeleted();
        productSearchRepository.save(productDocument);
    }

    @Transactional
    public void handleStockUpdated(StockUpdatedEvent event) {
        ProductDocument productDocument = productSearchRepository.findById(event.getProductId())
                .orElseThrow(() -> new CustomException(CommerceErrorCode.PRODUCT_DOCUMENT_NOT_FOUND));
        productDocument.updateStock(event.getStockId(), event.getStockQuantity());
        productSearchRepository.save(productDocument);
    }
}