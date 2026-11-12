package com.example.inventory.product;

import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockMovementRepository extends JpaRepository<StockMovement, Long> {

    List<StockMovement> findByOperationId(Long operationId);

    List<StockMovement> findByProductIdOrderByIdDesc(Long productId, Limit limit);
}
