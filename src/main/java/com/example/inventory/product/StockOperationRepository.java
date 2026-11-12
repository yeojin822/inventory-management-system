package com.example.inventory.product;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockOperationRepository extends JpaRepository<StockOperation, Long> {

    Optional<StockOperation> findByIdempotencyKey(String idempotencyKey);
}
