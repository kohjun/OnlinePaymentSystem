# OnlinePaymentSystem

OnlinePaymentSystem powers EverySale, a commerce operations simulator that combines inventory reservation, order creation, Toss Payments checkout, compensation, and reliable event publication.

The public checkout path is Toss Payments intent/confirm:

```text
POST /api/payments/toss/intents
-> Toss Payments payment window
-> POST /api/payments/toss/confirm
```

After Toss confirm, the server invokes the Temporal complete reservation Saga internally:

```text
Reserve inventory in Redis
-> Create order/payment/reservation records in Postgres
-> Confirm Toss payment
-> Confirm inventory
-> Mark order as PAID
-> Record outbox events
-> Publish outbox events to Kafka
```

`/api/reservations/complete`, `/api/payments/process`, `/api/payments/{paymentId}/retry`, and `/api/payments/{paymentId}/refund` are retained only as compatibility/internal paths. The default configuration disables them with `app.checkout.public-complete-enabled=false` and `payment.legacy-api.enabled=false`.

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

## Toss Checkout API

Create a Toss payment intent:

```powershell
$body = @{
  productId = "SAGA-TEST-001"
  customerId = "CUST-1049"
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
  -Uri http://localhost:8080/api/payments/toss/intents `
  -ContentType "application/json" `
  -Body $body
```

Confirm after Toss redirects with `paymentKey`, `orderId`, and `amount`:

```powershell
$confirm = @{
  intentId = "{intentId}"
  paymentKey = "{paymentKey}"
  orderId = "{orderId}"
  amount = 100.00
} | ConvertTo-Json

Invoke-RestMethod `
  -Method Post `
  -Uri http://localhost:8080/api/payments/toss/confirm `
  -ContentType "application/json" `
  -Body $confirm
```

Expected responses:

- `200 OK` with `status: SUCCESS` when the Temporal workflow completes within the synchronous wait window.
- `202 Accepted` with `status: PENDING` and `workflowId` when the workflow is still running.
- `400 Bad Request` with `status: FAILED` when the Saga fails and compensation is attempted.
- `409 Conflict` for amount mismatch or Toss confirm conflicts.

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

## Marketplace APIs

EverySale marketplace read APIs expose public sale events backed by sellers, listings, products, and inventory:

```text
GET /api/marketplace/events?status=LIVE&saleType=RAFFLE&keyword=조던&sort=startsAt
GET /api/marketplace/events/{eventId}
POST /api/marketplace/events/{eventId}/checkout/toss/intents
```

Supported sale types:

- `FIXED_PRICE`
- `DROP`
- `RAFFLE`
- `AUCTION`

The current seed data publishes limited-goods events for raffle, auction, and drop flows so the consumer marketplace can render real catalog data without using `/api/simulation/*`.
Public marketplace checkout creates a Toss intent first, opens the Toss payment window, then calls `POST /api/payments/toss/confirm`; confirm records the marketplace order ledger and seller payout for successful Saga responses.
Legacy non-Toss marketplace checkout endpoints are disabled by default with `app.checkout.legacy-marketplace-enabled=false`.

Raffle flow:

```text
POST /api/marketplace/events/{eventId}/raffle/entries
GET /api/marketplace/events/{eventId}/raffle/status?customerId={customerId}
POST /api/marketplace/events/{eventId}/raffle/draw
POST /api/marketplace/events/{eventId}/raffle/winner-checkout/toss/intents
```

Raffle entry is free and idempotency is enforced by `(saleEventId, customerId)`. Payment is only allowed for selected winners through winner checkout.

Auction flow:

```text
POST /api/marketplace/events/{eventId}/bids
GET /api/marketplace/events/{eventId}/auction/status
GET /api/marketplace/events/{eventId}/auction/stream
POST /api/marketplace/events/{eventId}/auction/close
POST /api/marketplace/events/{eventId}/auction/winner-checkout/toss/intents
```

Auction bids are persisted in Postgres. Closing an auction creates an awaiting-payment settlement for the highest bidder, and successful winner checkout creates a `HELD` seller payout.

Marketplace order ledger and fulfillment APIs:

```text
GET /api/marketplace/customers/{customerId}/orders
GET /api/sellers/{sellerId}/orders
PATCH /api/sellers/{sellerId}/orders/{marketplaceOrderId}/fulfillment
```

Successful direct, raffle winner, and auction winner checkout responses create `marketplace_orders` rows. Paid orders become `READY_TO_FULFILL`; sellers can move them through `PROCESSING`, `SHIPPED`, and `DELIVERED`.

Seller payout APIs:

```text
GET /api/sellers/{sellerId}/payouts?status=HELD
POST /api/sellers/{sellerId}/payouts/{payoutId}/release
```

Every paid marketplace order creates one idempotent `HELD` seller payout using `MARKETPLACE_ORDER + marketplaceOrderId` as the source key. The current platform fee policy is 10%.

Seller console APIs create marketplace-ready inventory, listings, and sale events:

```text
POST /api/sellers
GET /api/sellers/{sellerId}
POST /api/sellers/{sellerId}/listings
GET /api/sellers/{sellerId}/listings
POST /api/sellers/{sellerId}/listings/{listingId}/sale-events
POST /api/sellers/{sellerId}/sale-events/{eventId}/publish
```

New listings start as `PENDING_REVIEW`. They are visible in the seller console but are not exposed in the public marketplace feed until approved.

Marketplace moderation APIs:

```text
GET /api/sellers/moderation/listings?status=PENDING_REVIEW
POST /api/sellers/moderation/listings/{listingId}/approve
POST /api/sellers/moderation/listings/{listingId}/reject
```

The desktop partner console uses the seeded seller `SELLER-EVERYSALE-CURATED` until full seller authentication is connected.

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
  --tests "*MarketplaceOrderServiceTest" `
  --tests "*MarketplaceCheckoutServiceTest" `
  --tests "*RaffleServiceTest" `
  --tests "*AuctionServiceTest" `
  --tests "*OutboxPublisherTest" `
  --tests "*InventoryReconciliationJobTest" `
  --no-daemon
```

Some older integration tests expect local Redis/Kafka and use `app.temporal.enabled=false` from `src/test/resources/application.yml`.

## Distribution Quality Gate

EverySale release candidates should pass the local distribution gate before packaging or handoff:

```powershell
.\scripts\verify-distribution.ps1
```

If Windows execution policy blocks direct script execution:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-distribution.ps1
```

This gate verifies Java 17+, Gradle compile, focused quality tests, EverySale branding/encoding regressions, and the Electron Windows package output at:

```text
desktop-app\dist\EverySale-win32-x64\EverySale.exe
```

Operational readiness is also exposed by:

```text
GET /api/system/readiness
```

See [docs/distribution-readiness.md](docs/distribution-readiness.md) for B2B SaaS release criteria, tenant headers, production mode settings, and manual QA.

## Load Test

The historical JMeter scenario targets `POST /api/reservations/complete`; for release validation, prefer the Toss intent/confirm flow and poll workflow status when confirm returns `202 PENDING`.

Accepted final load-test outcomes:

- `200 SUCCESS`
- `202 PENDING` only after workflow polling later resolves to `SUCCESS`

Run:

```powershell
load-test\scripts\run-load-test.bat
python analysis\analyze_temporal_performance.py load-test\results\{timestamp}\results.jtl
```

Use Temporal UI to inspect workflow duration and activity retries. Use the `outbox_events` table to inspect event publication latency, retry state, and failed events.

## Operational Notes

- `TOSS_PAYMENTS` is the default payment gateway; local mock beans are disabled unless explicitly enabled for tests.
- Public direct complete and legacy payment APIs are disabled by default.
- Inventory counters are maintained in Redis and mirrored in Postgres.
- `app.inventory.reconciliation.enabled=true` enables scheduled mismatch detection between Redis and Postgres.
- `app.outbox.enabled=true` enables scheduled outbox publishing to Kafka.
- `app.kafka.listeners.payment-events.enabled=false` keeps the sample payment-event listener disabled by default.
