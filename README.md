# 재고 관리 시스템

상품 입고, 출고, 재고 조회를 제공하는 재고 관리 MVP입니다.

## 기술 스택

Java 21, Spring Boot 3.5, Spring Data JPA, PostgreSQL 16, Flyway, Testcontainers

## 실행

Docker만 있으면 DB와 애플리케이션을 함께 띄울 수 있습니다.

```bash
docker compose up -d --build
```

로컬에서 개발할 때는 DB만 띄우고 애플리케이션은 직접 실행합니다(JDK 21 필요).

```bash
docker compose up -d postgres
./gradlew bootRun
```

DB 접속 정보는 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` 환경변수로 바꿀 수 있습니다. 기본값은 docker compose 설정과 같습니다.

테스트는 Testcontainers로 PostgreSQL을 띄우므로 Docker만 실행 중이면 됩니다.

```bash
./gradlew test
```

실행 후 확인할 수 있는 주소는 다음과 같습니다.

- API 문서: http://localhost:8080/swagger-ui.html
- 헬스체크: http://localhost:8080/actuator/health

## DDL

`src/main/resources/db/migration/V1__init.sql`

애플리케이션을 시작할 때 Flyway가 적용합니다.

- `product`: 상품과 현재 재고
- `stock_operation`: 입고·출고 요청 단위 기록(멱등키 보관)
- `stock_movement`: 상품별 입출고 이력

## API

| Method | Path | 설명 |
|---|---|---|
| POST | `/api/inventory/inbound` | 입고. 등록되지 않은 SKU는 상품을 등록한 뒤 입고합니다. |
| POST | `/api/inventory/outbound` | 출고 |
| GET | `/api/products/{productId}` | 재고 조회 |
| GET | `/api/products/sku/{sku}` | SKU로 재고 조회 |
| GET | `/api/products?page=0&size=20` | 재고 목록 |
| GET | `/api/products/{productId}/movements?limit=20` | 입출고 이력(최신순) |

입고와 출고는 한 요청에 여러 상품을 받습니다. 하나라도 실패하면 요청 전체를 취소하고, 결과는 요청한 순서대로 돌려줍니다.

```bash
curl -X POST localhost:8080/api/inventory/inbound \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 3f2a9c1e-inbound-001' \
  -d '{"items":[{"sku":"A-001","name":"상품 A","quantity":10},{"sku":"B-001","name":"상품 B","quantity":5}]}'

[{"id":1,"sku":"A-001","name":"상품 A","quantity":10},{"id":2,"sku":"B-001","name":"상품 B","quantity":5}]
```

```bash
curl -X POST localhost:8080/api/inventory/outbound \
  -H 'Content-Type: application/json' \
  -d '{"items":[{"sku":"A-001","quantity":3}]}'

[{"id":1,"sku":"A-001","name":"상품 A","quantity":7}]
```

### 요청 규칙

- `items`: 1~100개. 같은 SKU를 한 요청에 두 번 넣을 수 없습니다.
- `sku`: 영문 대문자, 숫자, `-`, `_` 조합 1~50자
- `name`: 신규 상품일 때만 필수이고 100자까지 받습니다. 앞뒤 공백은 제거해서 저장합니다. 이미 등록된 상품이면 보내도 무시합니다.
- `quantity`: 1~1,000,000 사이의 정수. `1.5`나 `"5"`는 받지 않습니다.
- `Idempotency-Key` 헤더: 선택 사항입니다. 공백 없는 1~100자입니다.

### 에러 응답

에러는 Problem Details(RFC 9457) 형식에 `code` 필드를 추가해서 내려줍니다.

```json
{
  "type": "about:blank",
  "title": "Conflict",
  "status": 409,
  "detail": "재고가 부족합니다. sku=A-001, 현재고=7, 요청수량=100",
  "instance": "/api/inventory/outbound",
  "code": "INSUFFICIENT_STOCK"
}
```

| status | code | 상황 |
|---|---|---|
| 400 | `INVALID_REQUEST` | 요청 값 검증 실패. `errors`에 필드별 사유(예: `items[1].quantity`)가 들어갑니다. |
| 400 | `PRODUCT_NAME_REQUIRED` | 신규 상품을 상품명 없이 입고한 경우 |
| 404 | `PRODUCT_NOT_FOUND` | 등록되지 않은 상품을 출고하거나 조회한 경우 |
| 409 | `INSUFFICIENT_STOCK` | 재고보다 많이 출고하려는 경우 |
| 409 | `CONFLICT` | 그 밖에 DB 제약 조건과 충돌한 경우 |
| 422 | `IDEMPOTENCY_KEY_REUSED` | 이미 사용한 `Idempotency-Key`를 다른 요청에 다시 쓴 경우 |
| 503 | `TEMPORARILY_UNAVAILABLE` | 락 대기 시간 초과나 DB 연결 실패. `Retry-After` 헤더를 함께 내려줍니다. |

## 구조

```
product   엔티티(Product, StockOperation, StockMovement), 리포지토리, 서비스, 컨트롤러, 요청/응답, 예외
common    전역 예외 처리
```

MVP 규모에 맞춰 한 패키지에 두고, 업무 규칙(상품 등록, 입고, 출고)은 `Product` 엔티티에 모았습니다. 서비스는 트랜잭션, 락, 멱등 처리 흐름만 담당합니다.

입고·출고 요청 하나는 `StockOperation` 하나로 기록되고, 품목마다 `StockMovement`가 생깁니다.

## 설계

### 상품 식별

신규 상품은 입고 시점에 `id`가 없으므로 업무 키로 SKU를 둡니다. SKU는 대문자만 허용해서 `abc-1`과 `ABC-1`이 다른 상품으로 등록되지 않게 했고, DB에도 `CHECK (sku = upper(sku))`를 걸었습니다.

### 동시성

입고와 출고는 요청에 포함된 상품 행을 모두 비관적 락(JPA `PESSIMISTIC_WRITE`, PostgreSQL에서는 `SELECT ... FOR NO KEY UPDATE`)으로 잠근 뒤 처리합니다.

- 같은 상품에 대한 요청은 순서대로 처리되고, 다른 상품끼리는 서로 막지 않습니다.
- 상품은 항상 SKU 순서로 잠급니다. 요청마다 품목 순서가 달라도 잠금 순서가 같으므로 데드락이 생기지 않습니다.
- 한 트랜잭션에서 처리하므로 하나라도 실패하면 요청 전체가 롤백됩니다.
- 신규 상품은 `INSERT ... ON CONFLICT (sku) DO NOTHING`으로 저장한 뒤 잠가서, 동시에 들어와도 하나만 생성됩니다.
- 잠금 대기는 `lock_timeout` 3초로 제한하고, 넘기면 503을 반환합니다. 이 설정은 해당 트랜잭션에만 적용됩니다(`set_config(..., true)`).

낙관적 락은 같은 상품에 요청이 몰리면 충돌한 요청마다 재시도가 필요해서 쓰지 않았습니다. `UPDATE ... WHERE quantity >= ?` 한 문장으로 처리하면 DB 왕복은 줄지만 재고 규칙이 SQL로 빠져나가므로, 처리량이 문제가 될 때 전환을 검토합니다.

### 중복 요청

스캐너 재전송이나 네트워크 재시도로 같은 요청이 두 번 반영되지 않도록 `Idempotency-Key`를 지원합니다.

- 처리된 키로 다시 요청하면 재고를 바꾸지 않고 처음 결과를 그대로 돌려줍니다.
- 키 확인은 상품 락을 잡은 뒤에 하므로 같은 키로 동시에 요청해도 한 번만 반영됩니다.
- 같은 키를 다른 요청에 쓰면 422를 반환합니다. 다른 상품에 동시에 쓰는 경우는 `stock_operation.idempotency_key` UNIQUE 제약이 막습니다.
- 실패한 요청은 기록하지 않으므로 같은 키로 다시 시도할 수 있습니다.

### DB 제약 조건

재고 규칙은 도메인 코드가 지키지만, 배치나 수동 SQL처럼 코드를 거치지 않는 쓰기에 대비해 DB에도 `CHECK` 제약을 걸었습니다. `CHECK`는 INSERT와 UPDATE 때만 평가되므로 조회 성능에는 영향이 없습니다.

### 입출고 이력

모든 입고와 출고를 재고 변경과 같은 트랜잭션에서 `stock_movement`에 기록합니다. 한 요청으로 처리된 품목은 같은 `operation_id`로 묶이고, 동시성 테스트에서 이력 합계와 현재고가 일치하는지 검증합니다.

## 테스트

- `ProductTest`: 상품 등록과 재고 규칙
- `InventoryApiTest`: API 동작과 에러 응답
- `GlobalExceptionHandlerTest`: DB 장애 시 503, 그 밖의 예외는 500
- `InventoryConcurrencyTest`
  - 재고 50개에 출고 100건을 동시에 보내면 정확히 50건만 성공
  - 여러 상품을 서로 다른 품목 순서로 동시에 출고해도 데드락 없이 모두 처리
  - 신규 상품에 동시 입고해도 상품은 하나만 생성
  - 입고와 출고가 섞여도 이력 합계와 현재고 일치
  - 같은 멱등키로 동시에 재시도해도 한 번만 반영
  - 같은 멱등키를 다른 상품에 동시에 쓰면 하나만 처리되고 나머지는 422
  - 상품 락이 오래 잡혀 있으면 503
  - 한 상품이 잠겨 있어도 다른 상품은 기다리지 않고 처리

## 가정

과제에 정해지지 않은 부분은 다음과 같이 정했습니다.

- 상품은 SKU로 식별합니다. 입고 시 신규 상품 여부를 판단할 업무 키가 필요하기 때문입니다.
- 한 요청의 여러 품목은 전부 성공하거나 전부 실패합니다.
- 이미 등록된 상품을 입고할 때 보낸 상품명은 반영하지 않습니다. 입고는 재고를 바꾸는 요청이기 때문입니다.
- 품목당 수량은 최대 1,000,000, 한 요청의 품목은 최대 100개로 제한합니다.

## 범위에서 제외한 것

- 인증과 인가, 멀티테넌트(`tenant_id`를 두고 UNIQUE를 `(tenant_id, sku)`로 변경)
- 로케이션, 로트, 유통기한 단위 재고와 할당 재고 분리
- 상품 정보 수정 API
- 멱등키 보관 기간 관리
