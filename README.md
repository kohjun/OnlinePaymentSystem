# OnlinePaymentSystem

OnlinePaymentSystem is a Spring Boot proof-of-concept for an online payment flow that combines inventory reservation, order creation, payment processing, compensation, and reliable event publication.

The primary path is the Temporal-based complete reservation API:

```text
POST /api/reservations/complete
```

This path orchestrates the Saga:

```text
Reserve inventory in Redis
-> Create order/payment/reservation records in Postgres
-> Process payment through MockPaymentGateway
-> Confirm inventory
-> Mark order as PAID
-> Record outbox events
-> Publish outbox events to Kafka
```

Legacy WAL-based APIs are retained for compatibility, but the default configuration keeps the legacy scheduler disabled with `app.legacy-wal.enabled=false`.

## Stack

- Java 17 or newer
- Spring Boot 3.2
- Gradle wrapper
- Postgres for application data
- Redis Lua scripts for atomic inventory counters
- Temporal for Saga orchestration
- Kafka for event publication
- Flyway for schema migration
- JMeter and Python scripts for load-test analysis

## Local Requirements

Install a JDK and set `JAVA_HOME` before running Gradle.

PowerShell example:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
java -version
```

Any JDK 17+ is acceptable. The project compiles with the Gradle wrapper:

```powershell
.\gradlew.bat compileJava compileTestJava --no-daemon
```

## Run Locally

Start the infrastructure:

```powershell
docker compose up -d
```

Services:

- App: `http://localhost:8080`
- Postgres: `localhost:5432`, database/user/password `payment`
- Redis: `localhost:6379`
- Kafka: `localhost:9092`
- Temporal gRPC: `localhost:7233`
- Temporal UI: `http://localhost:8088`

Start the application:

```powershell
.\gradlew.bat bootRun
```

## Complete Reservation API

Recommended request:

```powershell
$body = @{
  productId = "SAGA-TEST-001"
  customerId = "CUS-001"
  quantity = 1
  clientId = "web"
  idempotencyKey = [guid]::NewGuid().ToString()
  correlationId = "COR-demo"
  paymentInfo = @{
    amount = 100.00
    currency = "KRW"
    paymentMethod = "CREDIT_CARD"
  }
} | ConvertTo-Json -Depth 5

Invoke-RestMethod `
  -Method Post `
  -Uri http://localhost:8080/api/reservations/complete `
  -ContentType "application/json" `
  -Body $body
```

Expected responses:

- `200 OK` with `status: SUCCESS` when the Temporal workflow completes within the synchronous wait window.
- `202 Accepted` with `status: PENDING` and `workflowId` when the workflow is still running.
- `400 Bad Request` with `status: FAILED` when the Saga fails and compensation is attempted.

Check a pending workflow:

```powershell
Invoke-RestMethod http://localhost:8080/api/reservations/workflows/{workflowId}
```

## Lookup APIs

Useful read endpoints:

```text
GET /api/reservations/{reservationId}
GET /api/reservations/{reservationId}/complete
GET /api/reservations/customer/{customerId}/active?page=0&size=10
GET /api/reservations/customer/{customerId}/complete?page=0&size=10
GET /api/reservations/product/{productId}/stats
GET /api/reservations/system/status
GET /api/payments/{paymentId}
GET /api/payments/reservation/{reservationId}
GET /api/orders/{orderId}
GET /api/orders/customer/{customerId}?page=0&size=10
GET /api/system/health
GET /api/payments/health
```

## Tests

Fast compile check:

```powershell
.\gradlew.bat compileJava compileTestJava --no-daemon
```

Focused unit tests:

```powershell
.\gradlew.bat test `
  --tests "*PaymentProcessingServiceTest" `
  --tests "*CompleteReservationWorkflowTest" `
  --tests "*OutboxPublisherTest" `
  --tests "*InventoryReconciliationJobTest" `
  --no-daemon
```

Some older integration tests expect local Redis/Kafka and use `app.temporal.enabled=false` from `src/test/resources/application.yml`.

## Load Test

The default JMeter scenario targets `POST /api/reservations/complete`.

Accepted load-test outcomes:

- `200 SUCCESS`
- `202 PENDING`

Run:

```powershell
load-test\scripts\run-load-test.bat
python analysis\analyze_temporal_performance.py load-test\results\{timestamp}\results.jtl
```

Use Temporal UI to inspect workflow duration and activity retries. Use the `outbox_events` table to inspect event publication latency, retry state, and failed events.

## Operational Notes

- `MockPaymentGateway` is the default payment gateway for this PoC.
- Inventory counters are maintained in Redis and mirrored in Postgres.
- `app.inventory.reconciliation.enabled=true` enables scheduled mismatch detection between Redis and Postgres.
- `app.outbox.enabled=true` enables scheduled outbox publishing to Kafka.
- `app.kafka.listeners.payment-events.enabled=false` keeps the sample payment-event listener disabled by default.
