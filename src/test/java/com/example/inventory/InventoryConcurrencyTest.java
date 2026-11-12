package com.example.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;

class InventoryConcurrencyTest extends ApiTestSupport {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    DataSource dataSource;

    @Test
    void 동시_출고에도_재고는_음수가_되지_않고_정확히_재고만큼만_성공한다() throws Exception {
        String sku = newSku();
        inbound(sku, 50);

        List<ResponseEntity<JsonNode>> responses = runConcurrently(100, i -> outbound(sku, 1));

        assertThat(countByStatus(responses, HttpStatus.OK)).isEqualTo(50);
        assertThat(countByStatus(responses, HttpStatus.CONFLICT)).isEqualTo(50);
        assertThat(stockOf(sku)).isZero();
        assertLedgerMatchesStock(sku);
    }

    @Test
    void 미등록_상품에_동시_입고해도_상품은_하나만_생기고_수량은_모두_합산된다() throws Exception {
        String sku = newSku();

        List<ResponseEntity<JsonNode>> responses = runConcurrently(50, i -> inbound(sku, 2));

        assertThat(countByStatus(responses, HttpStatus.OK)).isEqualTo(50);
        assertThat(jdbc.sql("SELECT count(*) FROM product WHERE sku = :sku").param("sku", sku).query(Long.class).single()).isEqualTo(1);
        assertThat(stockOf(sku)).isEqualTo(100);
        assertLedgerMatchesStock(sku);
    }

    @Test
    void 입고와_출고가_섞여도_최종_재고는_수불_합계와_일치한다() throws Exception {
        String sku = newSku();
        inbound(sku, 10);

        List<ResponseEntity<JsonNode>> responses = runConcurrently(200, i -> isInbound(i) ? inbound(sku, 3) : outbound(sku, 5));

        long inboundOk = countByStatus(filterByIndex(responses, true), HttpStatus.OK);
        long outboundOk = countByStatus(filterByIndex(responses, false), HttpStatus.OK);
        long outboundRejected = countByStatus(filterByIndex(responses, false), HttpStatus.CONFLICT);
        assertThat(inboundOk).isEqualTo(100);
        assertThat(outboundOk + outboundRejected).isEqualTo(100);
        assertThat(stockOf(sku)).isEqualTo(10 + inboundOk * 3 - outboundOk * 5).isNotNegative();
        assertLedgerMatchesStock(sku);
    }

    @Test
    void 같은_멱등키로_동시에_요청해도_한번만_반영된다() throws Exception {
        String sku = newSku();
        inbound(sku, 100);
        String key = UUID.randomUUID().toString();

        List<ResponseEntity<JsonNode>> responses = runConcurrently(20, i -> outbound(sku, 7, key));

        assertThat(responses).allMatch(r -> r.getStatusCode() == HttpStatus.OK);
        assertThat(responses).allMatch(r -> r.getBody().get(0).get("quantity").asLong() == 93);
        assertThat(stockOf(sku)).isEqualTo(93);
        assertLedgerMatchesStock(sku);
    }

    @Test
    void 재고를_전부_출고하는_요청을_같은_멱등키로_동시에_재시도해도_모두_성공_응답을_받는다() throws Exception {
        String sku = newSku();
        inbound(sku, 7);
        String key = UUID.randomUUID().toString();

        List<ResponseEntity<JsonNode>> responses = runConcurrently(20, i -> outbound(sku, 7, key));

        assertThat(responses).allMatch(r -> r.getStatusCode() == HttpStatus.OK);
        assertThat(responses).allMatch(r -> r.getBody().get(0).get("quantity").asLong() == 0);
        assertThat(stockOf(sku)).isZero();
        assertLedgerMatchesStock(sku);
    }

    @Test
    void 다른_트랜잭션이_상품을_오래_잠그고_있으면_무한정_기다리지_않고_503을_반환한다() throws Exception {
        String sku = newSku();
        inbound(sku, 10);

        ResponseEntity<JsonNode> response;
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            lockProduct(connection, sku);
            response = outbound(sku, 1);
            connection.rollback();
        }

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        assertThat(response.getBody().get("code").asText()).isEqualTo("TEMPORARILY_UNAVAILABLE");
        assertThat(stockOf(sku)).isEqualTo(10);
    }

    @Test
    void 한_상품이_잠겨_있어도_다른_상품은_기다리지_않고_처리된다() throws Exception {
        String locked = newSku();
        String other = newSku();
        post(INBOUND, items(receiveLine(locked, 10), receiveLine(other, 10)), null);

        ResponseEntity<JsonNode> response;
        long elapsedMillis;
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            lockProduct(connection, locked);
            long start = System.nanoTime();
            response = outbound(other, 1);
            elapsedMillis = (System.nanoTime() - start) / 1_000_000;
            connection.rollback();
        }

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(elapsedMillis).isLessThan(3_000);
        assertThat(stockOf(other)).isEqualTo(9);
    }

    private static void lockProduct(Connection connection, String sku) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT id FROM product WHERE sku = ? FOR UPDATE")) {
            statement.setString(1, sku);
            statement.executeQuery();
        }
    }

    @Test
    void 여러_상품을_서로_다른_순서로_동시에_출고해도_데드락_없이_모두_처리된다() throws Exception {
        String first = newSku();
        String second = newSku();
        post(INBOUND, items(receiveLine(first, 100), receiveLine(second, 100)), null);

        List<ResponseEntity<JsonNode>> responses = runConcurrently(100, i -> i % 2 == 0
                ? post(OUTBOUND, items(shipLine(first, 1), shipLine(second, 1)), null)
                : post(OUTBOUND, items(shipLine(second, 1), shipLine(first, 1)), null));

        assertThat(countByStatus(responses, HttpStatus.OK)).isEqualTo(100);
        assertThat(stockOf(first)).isZero();
        assertThat(stockOf(second)).isZero();
        assertLedgerMatchesStock(first);
        assertLedgerMatchesStock(second);
    }

    @Test
    void 같은_멱등키를_서로_다른_상품에_동시에_쓰면_하나만_처리되고_나머지는_422다() throws Exception {
        List<String> skus = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String sku = newSku();
            inbound(sku, 10);
            skus.add(sku);
        }
        String key = UUID.randomUUID().toString();

        List<ResponseEntity<JsonNode>> responses = runConcurrently(10, i -> outbound(skus.get(i), 1, key));

        assertThat(countByStatus(responses, HttpStatus.OK)).isEqualTo(1);
        assertThat(countByStatus(responses, HttpStatus.UNPROCESSABLE_ENTITY)).isEqualTo(9);
        assertThat(skus.stream().mapToLong(this::stockOf).sum()).isEqualTo(99);
    }

    private static boolean isInbound(int index) {
        return index % 2 == 0;
    }

    private static <T> List<T> filterByIndex(List<T> results, boolean inbound) {
        return IntStream.range(0, results.size())
                .filter(i -> isInbound(i) == inbound)
                .mapToObj(results::get)
                .toList();
    }

    private void assertLedgerMatchesStock(String sku) {
        long ledger = jdbc.sql("""
                        SELECT COALESCE(SUM(CASE m.type WHEN 'INBOUND' THEN m.quantity ELSE -m.quantity END), 0)
                        FROM stock_movement m JOIN product p ON p.id = m.product_id
                        WHERE p.sku = :sku
                        """)
                .param("sku", sku)
                .query(Long.class)
                .single();
        assertThat(ledger).isEqualTo(stockOf(sku));
    }

    private static long countByStatus(List<ResponseEntity<JsonNode>> responses, HttpStatusCode status) {
        return responses.stream().filter(r -> r.getStatusCode().equals(status)).count();
    }

    private static <T> List<T> runConcurrently(int count, IntFunction<T> task) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int index = i;
                Callable<T> call = () -> {
                    start.await();
                    return task.apply(index);
                };
                futures.add(pool.submit(call));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        }
    }
}
