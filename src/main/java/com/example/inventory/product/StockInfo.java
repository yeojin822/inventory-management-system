package com.example.inventory.product;

public record StockInfo(long id, String sku, String name, long quantity) {

    static StockInfo from(Product product) {
        return of(product, product.getQuantity());
    }

    static StockInfo of(Product product, long quantity) {
        return new StockInfo(product.getId(), product.getSku(), product.getName(), quantity);
    }
}
