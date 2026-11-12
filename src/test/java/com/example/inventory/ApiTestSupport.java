package com.example.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfig.class)
abstract class ApiTestSupport {

    static final String INBOUND = "/api/inventory/inbound";
    static final String OUTBOUND = "/api/inventory/outbound";

    @Autowired
    TestRestTemplate rest;

    static String newSku() {
        return "SKU-" + UUID.randomUUID().toString().toUpperCase();
    }

    static String items(String... items) {
        return "{\"items\":[" + String.join(",", items) + "]}";
    }

    static String receiveLine(String sku, long quantity) {
        return "{\"sku\":\"%s\",\"name\":\"상품 %s\",\"quantity\":%d}".formatted(sku, sku, quantity);
    }

    static String shipLine(String sku, long quantity) {
        return "{\"sku\":\"%s\",\"quantity\":%d}".formatted(sku, quantity);
    }

    ResponseEntity<JsonNode> inbound(String sku, long quantity) {
        return post(INBOUND, items(receiveLine(sku, quantity)), null);
    }

    ResponseEntity<JsonNode> outbound(String sku, long quantity) {
        return outbound(sku, quantity, null);
    }

    ResponseEntity<JsonNode> outbound(String sku, long quantity, String idempotencyKey) {
        return post(OUTBOUND, items(shipLine(sku, quantity)), idempotencyKey);
    }

    ResponseEntity<JsonNode> post(String path, String json, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return rest.postForEntity(path, new HttpEntity<>(json, headers), JsonNode.class);
    }

    long stockOf(String sku) {
        return rest.getForObject("/api/products/sku/" + sku, JsonNode.class).get("quantity").asLong();
    }
}
