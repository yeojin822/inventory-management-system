package com.example.inventory.product;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ProductRepository extends JpaRepository<Product, Long> {

    Optional<Product> findBySku(String sku);

    default Optional<Product> findBySkuForUpdate(String sku) {
        applyLockTimeout();
        return lockBySku(sku);
    }

    default void saveIfAbsent(Product product) {
        insertIfAbsent(product.getSku(), product.getName());
    }

    @Query(value = "SELECT set_config('lock_timeout', '3s', true)", nativeQuery = true)
    String applyLockTimeout();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.sku = :sku")
    Optional<Product> lockBySku(String sku);

    @Modifying
    @Query(value = "INSERT INTO product (sku, name) VALUES (:sku, :name) ON CONFLICT (sku) DO NOTHING", nativeQuery = true)
    void insertIfAbsent(String sku, String name);
}
