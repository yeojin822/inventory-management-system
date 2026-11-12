package com.example.inventory.product;

public class ProductNotFoundException extends RuntimeException {

    private ProductNotFoundException(String message) {
        super(message);
    }

    public static ProductNotFoundException bySku(String sku) {
        return new ProductNotFoundException("등록되지 않은 상품입니다. sku=" + sku);
    }

    public static ProductNotFoundException byId(Long id) {
        return new ProductNotFoundException("등록되지 않은 상품입니다. id=" + id);
    }
}
