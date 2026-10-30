# Reservations: save the allocation and deduct stock together

## Scenario and invariant

You request two boxes. The backend deducts two units, but the response disappears. Retrying the raw inventory decrement would deduct two more units.

A reservation records the allocation: its ID, SKU, quantity, idempotency key, status, and creation time.

For this milestone:

- A successful reservation deducts its quantity exactly once.
- Matching retries return the same reservation, even when remaining stock is zero.
- A reused key with a different SKU or quantity returns 409.
- If creating a reservation fails, neither its record nor its stock deduction commits.

There is no customer authentication yet. The key identifies a logical request globally within the reservation API, not a verified customer. It is independent of the payment API's keys.

## Why the order matters

The service performs this sequence inside one PostgreSQL transaction:

```text
Validate SKU, quantity, and key.
If the key already exists, compare the payload and return the reservation.
Check that the SKU exists (this does not decide stock availability).
INSERT reservation ON CONFLICT (idempotency_key) DO NOTHING.
If another request won, load and validate its committed reservation.
Otherwise, atomically deduct the requested inventory.
COMMIT both writes.
```

Inserting before deducting settles duplicate requests before they compete for stock. The unique constraint makes one request win. The initial lookup is just a fast path; it cannot guarantee uniqueness by itself.

The insert is uncommitted until the deduction succeeds. If the deduction throws an insufficient-stock exception, Spring rolls back the transaction, including the inserted reservation. If a later step throws after the deduction, both writes roll back.

`ReservationService.create` owns the transaction. `InventoryService.decrement` uses Spring's default REQUIRED propagation, so it joins the existing transaction rather than committing stock independently. Both use Read Committed isolation.

Requests racing with the same key wait for the competing insert to commit or roll back. At Read Committed, the subsequent lookup can see the winner's committed record. An aborted attempt leaves no saved reservation or key: a later request can try again. Failed responses are not cached by this implementation.

## Files to read, in order

1. `src/main/resources/db/migration/V3__reservations.sql`: unique key, positive quantity, and a foreign key tying reservations to inventory.
2. `src/main/java/payment_infra/reservation/ReservationRepository.java`: the insert that arbitrates concurrent retries.
3. `src/main/java/payment_infra/reservation/ReservationService.java`: transaction boundary, payload comparison, and stock deduction.
4. `src/main/java/payment_infra/reservation/ReservationController.java`: input validation and HTTP responses.
5. `src/test/java/payment_infra/ReservationIntegrationTest.java`: overlapping requests and rollback experiments.

The SQL constraints protect individual records. The transaction and service flow protect the relationship between the deduction and the reservation. Direct SQL or the old `/inventory/{sku}/decrement` experiment can still reduce stock without a reservation; use the reservation endpoint for this workflow.

## Manual experiment

Start infrastructure and the backend from the repository root. Flyway applies V3 on startup:

```powershell
docker compose up -d
.\mvnw.cmd spring-boot:run
```

In a second PowerShell terminal, create three units and reserve two:

```powershell
$baseUrl = 'http://localhost:8081'
$sku = 'box-' + [guid]::NewGuid().ToString('N')
$key = [guid]::NewGuid().ToString()
$headers = @{ 'Idempotency-Key' = $key }
$stock = @{ sku = $sku; quantity = 3 } | ConvertTo-Json
$request = @{ sku = $sku; quantity = 2 } | ConvertTo-Json

Invoke-RestMethod -Method Post -Uri "$baseUrl/inventory" -ContentType 'application/json' -Body $stock
$first = Invoke-RestMethod -Method Post -Uri "$baseUrl/reservations" -Headers $headers -ContentType 'application/json' -Body $request
$first
Invoke-RestMethod -Uri "$baseUrl/inventory/$sku"
```

Expected: a reservation with `status = RESERVED`, `quantity = 2`, and one unit left in inventory. New reservations return HTTP 201 with a Location header.

Repeat the same request:

```powershell
$retry = Invoke-RestMethod -Method Post -Uri "$baseUrl/reservations" -Headers $headers -ContentType 'application/json' -Body $request
$first.id -eq $retry.id
Invoke-RestMethod -Uri "$baseUrl/inventory/$sku"
Invoke-RestMethod -Uri "$baseUrl/reservations/$($first.id)"
```

Expected: `True`, the same reservation, and still one unit available. A matching replay returns HTTP 200.

Change the quantity but reuse the key:

```powershell
$different = @{ sku = $sku; quantity = 1 } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "$baseUrl/reservations" -Headers $headers -ContentType 'application/json' -Body $different
```

Expected: 409; PowerShell displays the HTTP error. Stock stays at one.

Try a different buyer requesting two units, with a fresh key:

```powershell
$otherHeaders = @{ 'Idempotency-Key' = [guid]::NewGuid().ToString() }
Invoke-RestMethod -Method Post -Uri "$baseUrl/reservations" -Headers $otherHeaders -ContentType 'application/json' -Body $request
Invoke-RestMethod -Uri "$baseUrl/inventory/$sku"
```

Expected: 409 for insufficient inventory and one unit still available. No reservation is committed for this failed attempt.

## Automated experiments

With Docker running:

```powershell
.\mvnw.cmd '-Dtest=ReservationIntegrationTest' test
```

The test uses its own PostgreSQL 16 container and HTTP server. It checks:

- 100 different buyers and 10 units: 10 reservations, 90 conflicts, zero available.
- 30 simultaneous retries for a previously unused key and one unit: one new reservation, 29 matching replays, one deduction.
- Conflicting payloads racing with one key: one winner, one conflict.
- Insufficient quantity: no leftover reservation and no partial deduction.
- An exception after both writes: stock restored, reservation gone, retry possible.
- Missing keys, invalid quantities, missing SKUs, and reservation lookup.

## Current boundary and next lesson

Reservations hold stock indefinitely in this milestone. There is no expiry, cancellation, confirmation, payment integration, or reservation control in the Swing console yet.

Next, cancellation must transition a reservation and return its quantity exactly once, even when two cancellation requests race.

Before moving on, explain:

1. Why does a failed stock deduction also remove the reservation we inserted first?
2. Why does a matching retry succeed even when the SKU is now sold out?
3. What would break if the inventory deduction used its own independent transaction?
