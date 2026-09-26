# Payment lab console

A small Swing desktop client using Java 21 and the project's existing Jackson dependency. It runs separately from Spring Boot and calls the existing HTTP API. No backend changes are needed.

## Run

Start PostgreSQL and Kafka from the repository root:

```powershell
docker compose up -d
```

Start the backend in one terminal, keeping its logs visible:

```powershell
$env:SPRING_PROFILES_ACTIVE = 'dev'
.\mvnw.cmd spring-boot:run
```

Open the console from another PowerShell terminal:

```powershell
.\tools\start-lab.ps1
```

The launcher resolves the existing Maven dependencies, then runs the Java source directly. The API URL defaults to `http://localhost:8081` and is editable. Click **Check health** to inspect Actuator health.

## Experiments

- **Happy path:** apply SUCCESS, create a payment, authorize, capture, view ledger/outbox, then refund and inspect again.
- **Idempotency:** create, then repeat last create. Compare the returned IDs. Replay preserves the original URL, body, and key even if fields change. Create does not automatically rotate the key; use New key for another logical payment.
- **Conflict:** change the amount and click Create with the same key. Observe the API's conflict response.
- **Decline:** apply FAILURE, select New key, create, then authorize.
- **Ambiguous outcome:** apply TIMEOUT, select New key, create, then authorize. Watch UNKNOWN, then click Reconcile or enable periodic refresh to observe scheduled reconciliation. The scheduler may resolve UNKNOWN before your next lookup.
- **Existing payment:** paste its UUID and click Look up.

Mode changes affect the entire fake processor, including requests from other clients. The dropdown is a requested mode; only a successful Apply response confirms the change. Restore SUCCESS when finished.

## How it works

`SwingWorker` sends HTTP requests off Swing's event dispatch thread so timeouts do not freeze the window. Controls are disabled while a request is in flight; polling skips busy periods. Each click performs one operation, letting you inspect the intermediate states. Use k6 for concurrent load tests.

The green log shows requests and actual API responses, including errors and elapsed time. It does not stream server logs. View ledger and View outbox query backend records; keep the backend terminal open for internal logging. A client transport timeout does not prove a payment failed.

Polling only reads payment state. Reconcile explicitly calls the reconciliation endpoint. Closing the console stops polling and leaves the backend running.
