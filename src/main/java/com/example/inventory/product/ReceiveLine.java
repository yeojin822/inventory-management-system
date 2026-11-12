package com.example.inventory.product;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record ReceiveLine(
        @Schema(example = "A-001")
        @NotNull @Pattern(regexp = SKU_PATTERN, message = SKU_MESSAGE) String sku,

        @Schema(description = "신규 상품일 때만 필수", example = "상품 A")
        @Size(max = 100) @Pattern(regexp = ".*\\S.*", message = "공백만 입력할 수 없습니다") String name,

        @Schema(example = "10")
        @NotNull @Positive @Max(MAX_QUANTITY) Long quantity
) implements StockLine {
    static final String SKU_PATTERN = "^[A-Z0-9_-]{1,50}$";
    static final String SKU_MESSAGE = "영문 대문자, 숫자, '-', '_' 조합 1~50자여야 합니다";
    static final long MAX_QUANTITY = 1_000_000;
}
