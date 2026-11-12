package com.example.inventory.product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "stock_movement")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockMovement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long operationId;

    private Long productId;

    @Enumerated(EnumType.STRING)
    private MovementType type;

    private long quantity;

    private long quantityAfter;

    @Column(insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    static StockMovement of(StockOperation operation, Product product, MovementType type, long quantity) {
        StockMovement movement = new StockMovement();
        movement.operationId = operation.getId();
        movement.productId = product.getId();
        movement.type = type;
        movement.quantity = quantity;
        movement.quantityAfter = product.getQuantity();
        return movement;
    }
}
