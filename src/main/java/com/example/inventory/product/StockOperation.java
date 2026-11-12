package com.example.inventory.product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "stock_operation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockOperation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String idempotencyKey;

    @Column(insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    public static StockOperation start(String idempotencyKey) {
        StockOperation operation = new StockOperation();
        operation.idempotencyKey = idempotencyKey;
        return operation;
    }
}
