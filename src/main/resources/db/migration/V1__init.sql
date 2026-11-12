CREATE TABLE product
(
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sku        VARCHAR(50)  NOT NULL,
    name       VARCHAR(100) NOT NULL,
    quantity   BIGINT       NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_product_sku UNIQUE (sku),
    CONSTRAINT ck_product_sku_upper CHECK (sku = upper(sku)),
    CONSTRAINT ck_product_quantity_non_negative CHECK (quantity >= 0)
);

CREATE TABLE stock_operation
(
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    idempotency_key VARCHAR(100),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_stock_operation_idempotency_key UNIQUE (idempotency_key)
);

CREATE TABLE stock_movement
(
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    operation_id   BIGINT      NOT NULL REFERENCES stock_operation (id),
    product_id     BIGINT      NOT NULL REFERENCES product (id),
    type           VARCHAR(10) NOT NULL,
    quantity       BIGINT      NOT NULL,
    quantity_after BIGINT      NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_stock_movement_type CHECK (type IN ('INBOUND', 'OUTBOUND')),
    CONSTRAINT ck_stock_movement_quantity_positive CHECK (quantity > 0),
    CONSTRAINT ck_stock_movement_quantity_after_non_negative CHECK (quantity_after >= 0)
);

CREATE INDEX ix_stock_movement_product_id ON stock_movement (product_id, id DESC);
CREATE INDEX ix_stock_movement_operation_id ON stock_movement (operation_id);
