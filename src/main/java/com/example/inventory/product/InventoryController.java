package com.example.inventory.product;

import static com.example.inventory.product.ReceiveLine.SKU_MESSAGE;
import static com.example.inventory.product.ReceiveLine.SKU_PATTERN;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "재고")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class InventoryController {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String IDEMPOTENCY_KEY_PATTERN = "^\\S{1,100}$";
    private static final String IDEMPOTENCY_KEY_MESSAGE = "공백 없는 1~100자여야 합니다";

    private final InventoryService inventoryService;

    @Operation(summary = "입고", description = "여러 상품을 한 번에 입고합니다. 등록되지 않은 SKU는 상품을 등록한 뒤 입고하며, 하나라도 실패하면 전체를 취소합니다.")
    @PostMapping("/inventory/inbound")
    public List<StockInfo> receive(
            @Parameter(description = "중복 처리 방지 키")
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false)
            @Pattern(regexp = IDEMPOTENCY_KEY_PATTERN, message = IDEMPOTENCY_KEY_MESSAGE) String idempotencyKey,
            @Valid @RequestBody StockRequest<ReceiveLine> request) {
        return inventoryService.receive(request.items(), idempotencyKey);
    }

    @Operation(summary = "출고", description = "여러 상품을 한 번에 출고합니다. 하나라도 재고가 부족하면 전체를 취소합니다.")
    @PostMapping("/inventory/outbound")
    public List<StockInfo> ship(
            @Parameter(description = "중복 처리 방지 키")
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false)
            @Pattern(regexp = IDEMPOTENCY_KEY_PATTERN, message = IDEMPOTENCY_KEY_MESSAGE) String idempotencyKey,
            @Valid @RequestBody StockRequest<ShipLine> request) {
        return inventoryService.ship(request.items(), idempotencyKey);
    }

    @Operation(summary = "재고 조회")
    @GetMapping("/products/{productId}")
    public StockInfo getStock(@PathVariable Long productId) {
        return inventoryService.getStock(productId);
    }

    @Operation(summary = "SKU로 재고 조회")
    @GetMapping("/products/sku/{sku}")
    public StockInfo getStockBySku(@PathVariable @Pattern(regexp = SKU_PATTERN, message = SKU_MESSAGE) String sku) {
        return inventoryService.getStockBySku(sku);
    }

    @Operation(summary = "재고 목록")
    @GetMapping("/products")
    public List<StockInfo> getStocks(
            @RequestParam(defaultValue = "0") @Min(0) @Max(100_000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return inventoryService.getStocks(page, size);
    }

    @Operation(summary = "입출고 이력", description = "최신순으로 조회합니다.")
    @GetMapping("/products/{productId}/movements")
    public List<MovementInfo> getMovements(
            @PathVariable Long productId,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return inventoryService.getMovements(productId, limit);
    }
}
