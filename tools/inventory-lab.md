# Inventory: competing for the last item

This first milestone adds stock creation, lookup, and atomic deduction. It does not yet create reservations, record who acquired stock, expire holds, or call the payment processor.

## What each part does

- `V2__inventory.sql` creates one row per SKU. The primary key prevents duplicate products, and the quantity constraint rejects negative stock even if SQL bypasses Java.
- `Inventory` maps that row into a Java object. Its quantity is a snapshot, not a promise that another buyer cannot change it.
- `InventoryRepository.tryDecrement` checks availability and subtracts the requested quantity in one SQL statement. Its positive-quantity condition also prevents a negative deduction from adding stock.
- `InventoryService.decrement` validates input and uses the affected-row count to decide success. It uses Read Committed isolation for the PostgreSQL wait-and-recheck behavior discussed in this lesson.
- `InventoryController` exposes the experiment over HTTP. A successful deduction returns 204 after the service transaction commits. A missing SKU returns 404; insufficient stock returns 409; invalid input returns 400.

The key statement is:

```sql
UPDATE inventory
SET available_quantity = available_quantity - :quantity
WHERE sku = :sku
  AND :quantity > 0
  AND available_quantity >= :quantity;
```

`@Modifying` tells Spring Data that the query changes rows. Flushing before the update and clearing the persistence context afterward avoids retaining an inventory entity with an old quantity after native SQL updates it.

The database holds the modified row's write lock until the transaction finishes. A competing update waits. If the first transaction commits, PostgreSQL rechecks the condition against the updated row; if it rolls back, the deduction is undone. See the [PostgreSQL 16 explanation](https://www.postgresql.org/docs/16/transaction-iso.html#XACT-READ-COMMITTED).

Repeated deduction requests are separate attempts. This endpoint has no idempotency key: retrying it can consume additional stock. The next reservation milestone will connect a deduction to a durable record and a logical request identity. A transport timeout here is an uncertain outcome; do not assume it is safe to retry.

## Start the application

From the repository root:

```powershell
docker compose up -d
.\mvnw.cmd spring-boot:run
```

Flyway applies V2 at startup. These inventory endpoints do not require the dev profile.

## Experiment 1: two buyers, one unit

In a second PowerShell terminal:

```powershell
$sku = 'box-' + [guid]::NewGuid().ToString('N')
$baseUrl = 'http://localhost:8081'
$body = @{ sku = $sku; quantity = 1 } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "$baseUrl/inventory" -ContentType 'application/json' -Body $body

# Tony acquires the unit: 204, with no response body.
Invoke-WebRequest -UseBasicParsing -Method Post -Uri "$baseUrl/inventory/$sku/decrement" -ContentType 'application/json' -Body '{"quantity":1}'

# Alice receives 409; PowerShell displays the HTTP error.
Invoke-WebRequest -UseBasicParsing -Method Post -Uri "$baseUrl/inventory/$sku/decrement" -ContentType 'application/json' -Body '{"quantity":1}'

# The remaining quantity is zero.
Invoke-RestMethod -Uri "$baseUrl/inventory/$sku"
```

These manual requests are sequential. The automated test below sends overlapping requests.

Other experiments:

- Create three units and request four: expect 409 and all three units remaining.
- Send zero or negative quantity: expect 400 and unchanged stock.
- Recreate an existing SKU: expect 409, not replenished stock.

## Experiment 2: watch a row lock and rollback

Open two database terminals using this command in each:

```powershell
docker compose exec postgres psql -U payment_user -d payments
```

In terminal A, create a fresh experiment row, then deduct without committing:

```sql
INSERT INTO inventory (sku, available_quantity) VALUES ('lock-demo-1', 1);
BEGIN;
UPDATE inventory SET available_quantity = available_quantity - 1
WHERE sku = 'lock-demo-1' AND available_quantity >= 1;
```

In terminal B:

```sql
BEGIN;
SET LOCAL lock_timeout = '60s';
UPDATE inventory SET available_quantity = available_quantity - 1
WHERE sku = 'lock-demo-1' AND available_quantity >= 1;
```

Terminal B waits. Before 60 seconds elapse, run this in terminal A:

```sql
ROLLBACK;
```

Terminal B now reports `UPDATE 1`. Commit its successful deduction:

```sql
COMMIT;
SELECT * FROM inventory WHERE sku = 'lock-demo-1';
```

The quantity is zero: Tony's deduction was undone, then Alice acquired the unit.

Repeat with a fresh SKU, `lock-demo-2`, but use `COMMIT` in terminal A. Terminal B should report `UPDATE 0`. If the lock timeout expires, run `ROLLBACK` in B before trying again.

## Automated verification

Docker must be running. The test starts its own PostgreSQL 16 container and HTTP server; it does not use your development stock.

```powershell
.\mvnw.cmd '-Dtest=InventoryIntegrationTest' test
```

The test checks:

- 100 concurrent HTTP attempts for 10 units produce exactly 10 successes and 90 conflicts, with zero stock left.
- A waiting buyer succeeds after the first buyer rolls back.
- Insufficient stock cannot cause a partial deduction.
- Invalid quantities and missing SKUs produce the expected errors.
- Duplicate stock creation cannot reset remaining inventory.
- The database rejects a negative quantity independently of the service.

## Explain it back

1. Why is the affected-row count more trustworthy than reading availability before the update?
2. Why would putting the naive read/check/write inside `@Transactional` still allow a lost update at Read Committed?
3. Why can this stock operation be concurrency-safe while still being unsafe to retry after a lost response?

Next, reservations will let us answer who owns the allocated quantity and how to release it exactly once.
