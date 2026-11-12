package com.example.inventory.product;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Objects;

public record StockRequest<T extends StockLine>(
        @NotEmpty @Size(max = 100) List<@Valid @NotNull T> items
) {

    @JsonIgnore
    @AssertTrue(message = "같은 SKU를 한 요청에 두 번 넣을 수 없습니다")
    public boolean isSkuUnique() {
        if (items == null) {
            return true;
        }
        List<String> skus = items.stream().filter(Objects::nonNull).map(StockLine::sku).toList();
        return skus.size() == skus.stream().distinct().count();
    }
}
