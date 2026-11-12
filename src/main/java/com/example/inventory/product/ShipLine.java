package com.example.inventory.product;

import static com.example.inventory.product.ReceiveLine.MAX_QUANTITY;
import static com.example.inventory.product.ReceiveLine.SKU_MESSAGE;
import static com.example.inventory.product.ReceiveLine.SKU_PATTERN;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

public record ShipLine(
        @Schema(example = "A-001")
        @NotNull @Pattern(regexp = SKU_PATTERN, message = SKU_MESSAGE) String sku,

        @Schema(example = "3")
        @NotNull @Positive @Max(MAX_QUANTITY) Long quantity
) implements StockLine {
}
