package com.example.inventory.product;

public class InsufficientStockException extends RuntimeException {

    public InsufficientStockException(String sku, long currentQuantity, long requestedQuantity) {
        super("재고가 부족합니다. sku=%s, 현재고=%d, 요청수량=%d".formatted(sku, currentQuantity, requestedQuantity));
    }
}
