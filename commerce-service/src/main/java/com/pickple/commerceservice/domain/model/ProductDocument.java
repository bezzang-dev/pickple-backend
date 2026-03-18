package com.pickple.commerceservice.domain.model;

import com.pickple.commerceservice.infrastructure.messaging.events.ProductUpdatedEvent;
import com.pickple.commerceservice.presentation.dto.request.ProductUpdateRequestDto;
import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.math.BigDecimal;
import java.util.UUID;

@Document(indexName = "products")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductDocument {

    @Id
    @Field(name = "product_id", type = FieldType.Keyword)
    private UUID productId;

    @Field(name = "product_name", type = FieldType.Text)
    private String productName;

    @Field(name = "description", type = FieldType.Text)
    private String description;

    @Field(name = "product_price", type = FieldType.Double)
    private BigDecimal productPrice;

    @Field(name = "product_image", type = FieldType.Text)
    private String productImage;

    @Field(name = "is_public", type = FieldType.Boolean)
    private Boolean isPublic;

    @Field(name = "vendor", type = FieldType.Object)
    private VendorInfo vendor;

    @Field(name = "stock", type = FieldType.Object)
    private StockInfo stock;

    @Field(name = "is_delete", type = FieldType.Boolean)
    private Boolean isDelete;

    public void update(ProductUpdatedEvent updateDto) {
        this.productName = updateDto.getProductName();
        this.description = updateDto.getDescription();
        this.productPrice = updateDto.getProductPrice();
        this.productImage = updateDto.getProductImage();
        this.isPublic = updateDto.getIsPublic();
    }

    public void markAsDeleted() {
        this.isDelete = true;
        this.isPublic = false;
    }

    public void updateStock(UUID stockId, Long stockQuantity) {
        this.stock = new StockInfo(stockId, stockQuantity);
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VendorInfo {
        @Field(name = "vendor_id", type = FieldType.Keyword)
        private UUID vendorId;

        @Field(name = "vendor_name", type = FieldType.Text)
        private String vendorName;

        @Field(name = "vendor_address", type = FieldType.Text)
        private String vendorAddress;

        @Field(name = "username", type = FieldType.Text)
        private String username;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StockInfo {
        @Field(name = "stock_id", type = FieldType.Keyword)
        private UUID stockId;

        @Field(name = "stock_quantity", type = FieldType.Long)
        private Long stockQuantity;
    }
}