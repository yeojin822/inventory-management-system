package com.example.inventory.product;

import static java.util.stream.Collectors.toMap;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InventoryService {

    private final ProductRepository productRepository;
    private final StockOperationRepository operationRepository;
    private final StockMovementRepository movementRepository;

    @Transactional
    public List<StockInfo> receive(List<ReceiveLine> lines, String idempotencyKey) {
        Map<String, Product> products = lockInSkuOrder(lines, line -> findOrRegisterForUpdate(line.sku(), line.name()));

        return applyOnce(idempotencyKey, MovementType.INBOUND, lines, products, Product::receive);
    }

    @Transactional
    public List<StockInfo> ship(List<ShipLine> lines, String idempotencyKey) {
        Map<String, Product> products = lockInSkuOrder(lines, line -> getProductForUpdate(line.sku()));

        return applyOnce(idempotencyKey, MovementType.OUTBOUND, lines, products, Product::ship);
    }

    @Transactional(readOnly = true)
    public StockInfo getStock(Long productId) {
        return StockInfo.from(getProduct(productId));
    }

    @Transactional(readOnly = true)
    public StockInfo getStockBySku(String sku) {
        return productRepository.findBySku(sku)
                .map(StockInfo::from)
                .orElseThrow(() -> ProductNotFoundException.bySku(sku));
    }

    @Transactional(readOnly = true)
    public List<StockInfo> getStocks(int page, int size) {
        return productRepository.findAll(PageRequest.of(page, size, Sort.by("id"))).stream()
                .map(StockInfo::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MovementInfo> getMovements(Long productId, int limit) {
        Product product = getProduct(productId);
        return movementRepository.findByProductIdOrderByIdDesc(product.getId(), Limit.of(limit)).stream()
                .map(MovementInfo::from)
                .toList();
    }

    private <T extends StockLine> Map<String, Product> lockInSkuOrder(List<T> lines, Function<T, Product> lock) {
        Map<String, Product> products = new HashMap<>();
        lines.stream()
                .sorted(Comparator.comparing(StockLine::sku))
                .forEach(line -> products.put(line.sku(), lock.apply(line)));
        return products;
    }

    private Product findOrRegisterForUpdate(String sku, String name) {
        return productRepository.findBySkuForUpdate(sku).orElseGet(() -> {
            productRepository.saveIfAbsent(Product.register(sku, name));
            return getProductForUpdate(sku);
        });
    }

    private List<StockInfo> applyOnce(String idempotencyKey, MovementType type, List<? extends StockLine> lines,
                                      Map<String, Product> products, StockChange change) {
        Optional<StockOperation> processed = findProcessed(idempotencyKey);
        if (processed.isPresent()) {
            return replay(processed.get(), idempotencyKey, type, lines, products);
        }

        StockOperation operation = startOperation(idempotencyKey);
        List<StockMovement> movements = lines.stream()
                .map(line -> change.apply(products.get(line.sku()), operation, line.quantity()))
                .toList();
        movementRepository.saveAll(movements);

        return lines.stream()
                .map(line -> StockInfo.from(products.get(line.sku())))
                .toList();
    }

    private Optional<StockOperation> findProcessed(String idempotencyKey) {
        if (idempotencyKey == null) {
            return Optional.empty();
        }
        return operationRepository.findByIdempotencyKey(idempotencyKey);
    }

    private StockOperation startOperation(String idempotencyKey) {
        try {
            return operationRepository.save(StockOperation.start(idempotencyKey));
        } catch (DataIntegrityViolationException e) {
            throw new IdempotencyKeyReusedException(idempotencyKey);
        }
    }

    private List<StockInfo> replay(StockOperation processed, String idempotencyKey, MovementType type,
                                   List<? extends StockLine> lines, Map<String, Product> products) {
        List<StockMovement> movements = movementRepository.findByOperationId(processed.getId());
        if (!isSameRequest(movements, type, lines, products)) {
            throw new IdempotencyKeyReusedException(idempotencyKey);
        }

        Map<Long, Long> quantityAfterByProductId = movements.stream()
                .collect(toMap(StockMovement::getProductId, StockMovement::getQuantityAfter));
        return lines.stream()
                .map(line -> products.get(line.sku()))
                .map(product -> StockInfo.of(product, quantityAfterByProductId.get(product.getId())))
                .toList();
    }

    private boolean isSameRequest(List<StockMovement> movements, MovementType type,
                                  List<? extends StockLine> lines, Map<String, Product> products) {
        Map<Long, Long> processedQuantities = movements.stream()
                .collect(toMap(StockMovement::getProductId, StockMovement::getQuantity));
        Map<Long, Long> requestedQuantities = lines.stream()
                .collect(toMap(line -> products.get(line.sku()).getId(), StockLine::quantity));
        boolean sameType = movements.stream().allMatch(movement -> movement.getType() == type);
        return sameType && processedQuantities.equals(requestedQuantities);
    }

    private Product getProductForUpdate(String sku) {
        return productRepository.findBySkuForUpdate(sku).orElseThrow(() -> ProductNotFoundException.bySku(sku));
    }

    private Product getProduct(Long productId) {
        return productRepository.findById(productId).orElseThrow(() -> ProductNotFoundException.byId(productId));
    }

    @FunctionalInterface
    private interface StockChange {

        StockMovement apply(Product product, StockOperation operation, long quantity);
    }
}
