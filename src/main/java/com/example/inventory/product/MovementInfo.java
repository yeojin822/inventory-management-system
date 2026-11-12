package com.example.inventory.product;

import java.time.OffsetDateTime;

public record MovementInfo(long id, long operationId, MovementType type, long quantity, long quantityAfter,
                           OffsetDateTime createdAt) {

    static MovementInfo from(StockMovement movement) {
        return new MovementInfo(movement.getId(), movement.getOperationId(), movement.getType(),
                movement.getQuantity(), movement.getQuantityAfter(), movement.getCreatedAt());
    }
}
