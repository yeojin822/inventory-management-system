package com.example.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

class InventoryApiTest extends ApiTestSupport {

    @Test
    void 미등록_상품_입고시_신규_등록된다() {
        String sku = newSku();

        ResponseEntity<JsonNode> response = inbound(sku, 10);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode stock = response.getBody().get(0);
        assertThat(stock.get("id").asLong()).isPositive();
        assertThat(stock.get("sku").asText()).isEqualTo(sku);
        assertThat(stock.get("quantity").asLong()).isEqualTo(10);
    }

    @Test
    void 기존_상품_입고시_수량이_증가하고_상품명은_유지된다() {
        String sku = newSku();
        inbound(sku, 10);

        ResponseEntity<JsonNode> response = post(INBOUND,
                items("{\"sku\":\"%s\",\"name\":\"다른 이름\",\"quantity\":5}".formatted(sku)), null);

        JsonNode stock = response.getBody().get(0);
        assertThat(stock.get("quantity").asLong()).isEqualTo(15);
        assertThat(stock.get("name").asText()).isEqualTo("상품 " + sku);
    }

    @Test
    void 여러_상품을_한번에_입고하고_요청_순서대로_결과를_반환한다() {
        String existing = newSku();
        String created = newSku();
        inbound(existing, 1);

        ResponseEntity<JsonNode> response = post(INBOUND, items(receiveLine(existing, 2), receiveLine(created, 3)), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(2);
        assertThat(response.getBody().get(0).get("sku").asText()).isEqualTo(existing);
        assertThat(response.getBody().get(1).get("sku").asText()).isEqualTo(created);
        assertThat(stockOf(existing)).isEqualTo(3);
        assertThat(stockOf(created)).isEqualTo(3);
    }

    @Test
    void 출고시_수량이_감소한다() {
        String sku = newSku();
        inbound(sku, 10);

        ResponseEntity<JsonNode> response = outbound(sku, 10);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(stockOf(sku)).isZero();
    }

    @Test
    void 여러_상품을_한번에_출고한다() {
        String first = newSku();
        String second = newSku();
        post(INBOUND, items(receiveLine(first, 5), receiveLine(second, 5)), null);

        ResponseEntity<JsonNode> response = post(OUTBOUND, items(shipLine(first, 2), shipLine(second, 5)), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(stockOf(first)).isEqualTo(3);
        assertThat(stockOf(second)).isZero();
    }

    @Test
    void 재고보다_많이_출고하면_409이고_재고는_그대로다() {
        String sku = newSku();
        inbound(sku, 3);

        ResponseEntity<JsonNode> response = outbound(sku, 4);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code").asText()).isEqualTo("INSUFFICIENT_STOCK");
        assertThat(stockOf(sku)).isEqualTo(3);
    }

    @Test
    void 한_상품이라도_재고가_부족하면_전체_출고가_취소된다() {
        String enough = newSku();
        String lacking = newSku();
        post(INBOUND, items(receiveLine(enough, 10), receiveLine(lacking, 1)), null);

        ResponseEntity<JsonNode> response = post(OUTBOUND, items(shipLine(enough, 5), shipLine(lacking, 2)), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stockOf(enough)).isEqualTo(10);
        assertThat(stockOf(lacking)).isEqualTo(1);
    }

    @Test
    void 미등록_상품이_섞인_출고는_404이고_전체가_취소된다() {
        String sku = newSku();
        inbound(sku, 10);

        ResponseEntity<JsonNode> response = post(OUTBOUND, items(shipLine(sku, 1), shipLine(newSku(), 1)), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code").asText()).isEqualTo("PRODUCT_NOT_FOUND");
        assertThat(stockOf(sku)).isEqualTo(10);
    }

    @Test
    void 미등록_상품_조회는_404다() {
        ResponseEntity<JsonNode> response = rest.getForEntity("/api/products/sku/" + newSku(), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code").asText()).isEqualTo("PRODUCT_NOT_FOUND");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"sku\":\"A-1\",\"name\":\"상품\",\"quantity\":0}",
            "{\"sku\":\"A-1\",\"name\":\"상품\",\"quantity\":-1}",
            "{\"sku\":\"A-1\",\"name\":\"상품\",\"quantity\":1.5}",
            "{\"sku\":\"A-1\",\"name\":\"상품\",\"quantity\":1000001}",
            "{\"sku\":\"A-1\",\"name\":\"상품\"}",
            "{\"sku\":\"\",\"name\":\"상품\",\"quantity\":1}",
            "{\"sku\":\"a-1\",\"name\":\"상품\",\"quantity\":1}",
            "{\"sku\":\"A 1\",\"name\":\"상품\",\"quantity\":1}",
            "{\"sku\":\"A-1\",\"name\":\" \",\"quantity\":1}",
            "{\"sku\":\"A-1\",\"name\":\"상품\",\"quantity\":\"abc\"}",
            "{\"sku\":\"A-1\",\"name\":\"상품\",\"quantity\":\"5\"}",
            "null"
    })
    void 잘못된_품목이_있으면_400이다(String item) {
        ResponseEntity<JsonNode> response = post(INBOUND, items(item), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code").asText()).isEqualTo("INVALID_REQUEST");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"items\":[]}", "{\"items\":null}", "{not json"})
    void 품목이_없거나_형식이_잘못된_요청은_400이다(String body) {
        ResponseEntity<JsonNode> response = post(INBOUND, body, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code").asText()).isEqualTo("INVALID_REQUEST");
    }

    @Test
    void 한_요청에_같은_SKU가_중복되면_400이다() {
        String sku = newSku();

        ResponseEntity<JsonNode> response = post(INBOUND, items(receiveLine(sku, 1), receiveLine(sku, 2)), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("errors").get(0).get("field").asText()).isEqualTo("skuUnique");
    }

    @Test
    void 한_요청의_품목이_100개를_넘으면_400이다() {
        String[] lines = IntStream.range(0, 101).mapToObj(i -> receiveLine(newSku(), 1)).toArray(String[]::new);

        ResponseEntity<JsonNode> response = post(INBOUND, items(lines), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("errors").get(0).get("field").asText()).isEqualTo("items");
    }

    @Test
    void 검증_실패시_품목과_필드별_사유를_반환한다() {
        ResponseEntity<JsonNode> response = post(OUTBOUND, items(shipLine("A-1", 1), shipLine("A-2", 0)), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("errors").get(0).get("field").asText()).isEqualTo("items[1].quantity");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "a b"})
    void 공백이_포함된_멱등키는_400이다(String idempotencyKey) {
        ResponseEntity<JsonNode> response = outbound(newSku(), 1, idempotencyKey);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 같은_멱등키_재요청은_한번만_반영되고_같은_결과를_반환한다() {
        String sku = newSku();
        inbound(sku, 10);
        String key = UUID.randomUUID().toString();

        ResponseEntity<JsonNode> first = outbound(sku, 3, key);
        inbound(sku, 100);
        ResponseEntity<JsonNode> retry = outbound(sku, 3, key);

        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retry.getBody()).isEqualTo(first.getBody());
        assertThat(stockOf(sku)).isEqualTo(107);
    }

    @Test
    void 여러_상품_입고를_같은_멱등키로_재요청하면_한번만_반영된다() {
        String first = newSku();
        String second = newSku();
        String key = UUID.randomUUID().toString();
        String body = items(receiveLine(first, 5), receiveLine(second, 7));

        ResponseEntity<JsonNode> original = post(INBOUND, body, key);
        ResponseEntity<JsonNode> retry = post(INBOUND, body, key);

        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retry.getBody()).isEqualTo(original.getBody());
        assertThat(stockOf(first)).isEqualTo(5);
        assertThat(stockOf(second)).isEqualTo(7);
    }

    @Test
    void 실패한_요청은_기록되지_않아_같은_멱등키로_다시_시도할_수_있다() {
        String sku = newSku();
        inbound(sku, 1);
        String key = UUID.randomUUID().toString();

        ResponseEntity<JsonNode> failed = outbound(sku, 3, key);
        inbound(sku, 5);
        ResponseEntity<JsonNode> retry = outbound(sku, 3, key);

        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(stockOf(sku)).isEqualTo(3);
    }

    @Test
    void 같은_멱등키를_다른_요청에_쓰면_422다() {
        String first = newSku();
        String second = newSku();
        post(INBOUND, items(receiveLine(first, 10), receiveLine(second, 10)), null);
        String key = UUID.randomUUID().toString();
        post(OUTBOUND, items(shipLine(first, 3), shipLine(second, 3)), key);

        ResponseEntity<JsonNode> response = post(OUTBOUND, items(shipLine(first, 3)), key);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().get("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(stockOf(first)).isEqualTo(7);
        assertThat(stockOf(second)).isEqualTo(7);
    }

    @Test
    void 입출고_이력이_최신순으로_기록된다() {
        String sku = newSku();
        long productId = inbound(sku, 10).getBody().get(0).get("id").asLong();
        outbound(sku, 4);

        JsonNode movements = rest.getForObject("/api/products/%d/movements".formatted(productId), JsonNode.class);

        assertThat(movements).hasSize(2);
        assertThat(movements.get(0).get("type").asText()).isEqualTo("OUTBOUND");
        assertThat(movements.get(0).get("quantityAfter").asLong()).isEqualTo(6);
        assertThat(movements.get(1).get("type").asText()).isEqualTo("INBOUND");
    }

    @Test
    void 한_요청으로_처리된_품목의_이력은_같은_작업으로_묶인다() {
        String first = newSku();
        String second = newSku();
        JsonNode stocks = post(INBOUND, items(receiveLine(first, 1), receiveLine(second, 1)), null).getBody();

        long firstOperation = latestMovement(stocks.get(0).get("id").asLong()).get("operationId").asLong();
        long secondOperation = latestMovement(stocks.get(1).get("id").asLong()).get("operationId").asLong();

        assertThat(firstOperation).isEqualTo(secondOperation);
    }

    @Test
    void 상품_id로_재고를_조회한다() {
        String sku = newSku();
        long productId = inbound(sku, 5).getBody().get(0).get("id").asLong();

        JsonNode stock = rest.getForObject("/api/products/" + productId, JsonNode.class);

        assertThat(stock.get("sku").asText()).isEqualTo(sku);
        assertThat(stock.get("quantity").asLong()).isEqualTo(5);
    }

    @Test
    void 없는_상품_id_조회는_404이고_숫자가_아니면_400이다() {
        assertThat(rest.getForEntity("/api/products/" + Long.MAX_VALUE, JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rest.getForEntity("/api/products/abc", JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 기존_상품은_상품명_없이_입고할_수_있다() {
        String sku = newSku();
        inbound(sku, 1);

        ResponseEntity<JsonNode> response = post(INBOUND, items(shipLine(sku, 2)), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get(0).get("quantity").asLong()).isEqualTo(3);
    }

    @Test
    void 신규_상품을_상품명_없이_입고하면_400이고_전체가_취소된다() {
        String existing = newSku();
        String created = newSku();
        inbound(existing, 1);

        ResponseEntity<JsonNode> response = post(INBOUND, items(shipLine(existing, 2), shipLine(created, 2)), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code").asText()).isEqualTo("PRODUCT_NAME_REQUIRED");
        assertThat(stockOf(existing)).isEqualTo(1);
        assertThat(rest.getForEntity("/api/products/sku/" + created, JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void 상품명_앞뒤_공백은_제거된다() {
        String sku = newSku();

        ResponseEntity<JsonNode> response = post(INBOUND,
                items("{\"sku\":\"%s\",\"name\":\"  상품 A  \",\"quantity\":1}".formatted(sku)), null);

        assertThat(response.getBody().get(0).get("name").asText()).isEqualTo("상품 A");
    }

    @Test
    void API_문서와_헬스체크를_제공한다() {
        assertThat(rest.getForEntity("/v3/api-docs", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rest.getForObject("/actuator/health", JsonNode.class).get("status").asText()).isEqualTo("UP");
    }

    @Test
    void 프레임워크_에러도_공통_code를_포함한다() {
        ResponseEntity<JsonNode> response = rest.exchange("/api/products/1", HttpMethod.DELETE, null, JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody().get("code").asText()).isEqualTo("METHOD_NOT_ALLOWED");
    }

    @Test
    void 없는_경로는_404_NOT_FOUND다() {
        ResponseEntity<JsonNode> response = rest.getForEntity("/api/unknown", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code").asText()).isEqualTo("NOT_FOUND");
    }

    @Test
    void 검증_메시지는_요청_언어와_상관없이_한국어다() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.ACCEPT_LANGUAGE, "en");

        ResponseEntity<JsonNode> response = rest.postForEntity(OUTBOUND,
                new HttpEntity<>(items(shipLine("A-1", 0)), headers), JsonNode.class);

        assertThat(response.getBody().get("errors").get(0).get("message").asText()).isEqualTo("0보다 커야 합니다");
    }

    @Test
    void 목록_조회_페이지_크기_제한을_넘으면_400이다() {
        ResponseEntity<JsonNode> response = rest.getForEntity("/api/products?size=101", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("errors").get(0).get("field").asText()).isEqualTo("size");
    }

    private JsonNode latestMovement(long productId) {
        return rest.getForObject("/api/products/%d/movements?limit=1".formatted(productId), JsonNode.class).get(0);
    }
}
