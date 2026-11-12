package com.example.inventory.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ProductTest {

    private final StockOperation operation = StockOperation.start(null);

    @Test
    void 입고하면_재고가_증가하고_처리_후_재고가_이력에_남는다() {
        Product product = Product.register("A-1", "상품 A");

        StockMovement movement = product.receive(operation, 10);

        assertThat(product.getQuantity()).isEqualTo(10);
        assertThat(movement.getType()).isEqualTo(MovementType.INBOUND);
        assertThat(movement.getQuantity()).isEqualTo(10);
        assertThat(movement.getQuantityAfter()).isEqualTo(10);
    }

    @Test
    void 재고만큼은_출고할_수_있다() {
        Product product = Product.register("A-1", "상품 A");
        product.receive(operation, 5);

        StockMovement movement = product.ship(operation, 5);

        assertThat(product.getQuantity()).isZero();
        assertThat(movement.getType()).isEqualTo(MovementType.OUTBOUND);
        assertThat(movement.getQuantityAfter()).isZero();
    }

    @Test
    void 재고보다_많이_출고하면_예외이고_재고는_변하지_않는다() {
        Product product = Product.register("A-1", "상품 A");
        product.receive(operation, 3);

        assertThatThrownBy(() -> product.ship(operation, 4)).isInstanceOf(InsufficientStockException.class);
        assertThat(product.getQuantity()).isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void 수량은_1_이상이어야_한다(long amount) {
        Product product = Product.register("A-1", "상품 A");

        assertThatThrownBy(() -> product.receive(operation, amount)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> product.ship(operation, amount)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void 상품명_없이_상품을_등록할_수_없다(String name) {
        assertThatThrownBy(() -> Product.register("A-1", name)).isInstanceOf(ProductNameRequiredException.class);
    }

    @Test
    void 상품명_앞뒤_공백은_제거해서_등록한다() {
        assertThat(Product.register("A-1", "  상품 A  ").getName()).isEqualTo("상품 A");
    }
}
