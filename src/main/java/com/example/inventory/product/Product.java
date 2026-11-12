package com.example.inventory.product;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "product")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String sku;

    private String name;

    private long quantity;

    private Product(String sku, String name) {
        this.sku = sku;
        this.name = name;
    }

    public static Product register(String sku, String name) {
        if (name == null || name.isBlank()) {
            throw new ProductNameRequiredException(sku);
        }
        return new Product(sku, name.strip());
    }

    public StockMovement receive(StockOperation operation, long amount) {
        requirePositive(amount);
        quantity = Math.addExact(quantity, amount);
        return StockMovement.of(operation, this, MovementType.INBOUND, amount);
    }

    public StockMovement ship(StockOperation operation, long amount) {
        requirePositive(amount);
        if (quantity < amount) {
            throw new InsufficientStockException(sku, quantity, amount);
        }
        quantity -= amount;
        return StockMovement.of(operation, this, MovementType.OUTBOUND, amount);
    }

    private static void requirePositive(long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("수량은 1 이상이어야 합니다. amount=" + amount);
        }
    }
}
