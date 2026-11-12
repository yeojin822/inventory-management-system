package com.example.inventory.product;

public class ProductNameRequiredException extends RuntimeException {

    public ProductNameRequiredException(String sku) {
        super("신규 상품은 상품명이 필요합니다. sku=" + sku);
    }
}
