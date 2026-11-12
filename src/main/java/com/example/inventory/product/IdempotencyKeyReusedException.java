package com.example.inventory.product;

public class IdempotencyKeyReusedException extends RuntimeException {

    public IdempotencyKeyReusedException(String idempotencyKey) {
        super("이미 다른 요청에 사용된 Idempotency-Key입니다. key=" + idempotencyKey);
    }
}
