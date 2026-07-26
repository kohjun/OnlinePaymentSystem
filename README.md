# 에브리세일 (EverySale)

에브리세일은 일반 판매, 선착순 한정 판매, 좌석형 티켓 예매, 실시간 경매, 무작위 래플을 지원하는 C2C 마켓플레이스입니다. Redis 재고 선점, Temporal Saga, Toss Payments 결제, 보상 트랜잭션, Outbox 이벤트 발행을 하나의 거래 흐름으로 연결합니다.

공식 웹 화면은 React 기반 `http://localhost:8080/app/`입니다. 루트 URL과 과거 `index.html`, `shared.html`, `seller.html` 진입점도 모두 `/app/`으로 이동합니다. 구매, 판매자 상품 등록·주문·배송·정산, 관리자 검수 기능은 같은 애플리케이션에서 권한에 따라 표시됩니다. 판매 방식에서 `DROP`은 선착순 한정 판매, `RAFFLE`은 중복 응모를 막은 무작위 추첨을 뜻합니다.

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

`/api/reservations/complete` remains an internal Saga test hook and is disabled by default with `app.checkout.public-complete-enabled=false`. The former public payment process/retry/refund, non-Toss marketplace checkout, simulation, and demo-auth APIs have been removed. The local mock-auth filter is development-only and is disabled by the `prod` profile.

## Implementation Scope

The backend exposes more than the web client drives. That gap is deliberate, not an oversight — the API surface was built out first, and the React client covers the paths a reviewer needs to walk end to end. This table states where the line currently sits.

| Area | Backend | Web client | Notes |
| --- | --- | --- | --- |
| Fixed-price / drop checkout | Yes | Yes | Toss intent → payment window → confirm |
| Raffle entry, draw result, winner checkout | Yes | Yes | Draw itself is admin-only |
| Auction bidding, live status, winner checkout | Yes | Yes | SSE stream with polling fallback |
| Seat-based ticketing with standby queue | Yes | Yes | One held seat per customer |
| Seller listing, review submission, sale events | Yes | Yes | |
| Seller order fulfillment and payout ledger | Yes | Yes | |
| Buyer order confirmation, dispute filing | Yes | Yes | |
| Seller reviews (write and read) | Yes | Yes | Ratings render on the item detail page |
| Listing reports (submit) | Yes | Yes | |
| Admin operations console and audit trail | Yes | Yes | Server declares the allowed actions |
| Shipping addresses | Yes | Yes | |
| Seller payout account submission | Yes | Yes | |
| Dispute resolution (operator side) | Yes | No | Reachable through the admin operations queue |
| Report and review moderation | Yes | Partial | Surfaces in the admin queue, no dedicated screen |
| Reservation and order lookup APIs | Yes | No | Operational and test surface, not a buyer screen |
| Queue clear, raffle draw, auction close | Yes | Partial | Admin-only actions, driven from the operations queue |

Endpoints outside this table are health, readiness, webhook, and internal Saga hooks that have no user-facing screen by design.

## Stack

- Java 17 or newer
- Spring Boot 3.2
- Gradle wrapper
- Postgres for application data
- Redis Lua scripts for atomic inventory counters
- Temporal for Saga orchestration
- Kafka for event publication
- Flyway for schema migration
- Atomic Redis Lua holds and DB active-seat uniqueness for ticket booking
- JMeter and Python scripts for load-test analysis
- React, TypeScript, and Vite for the consumer marketplace

## Local Requirements

Install a JDK and set `JAVA_HOME` before running Gradle.

PowerShell example:

```powershell
$java = (Get-Command java.exe).Source
$env:JAVA_HOME = Split-Path (Split-Path $java -Parent) -Parent
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

- EverySale marketplace and operations app: `http://localhost:8080/app/`
- Root compatibility redirect: `http://localhost:8080/` -> `/app/`
- Postgres: `localhost:5434`, database/user/password `payment`
- Redis: `localhost:6379`
- Kafka: `localhost:9092`
- Temporal gRPC: `localhost:7233`
- Temporal UI: `http://localhost:8088`
- Jaeger UI (distributed traces): `http://localhost:16686`
- OTLP/HTTP trace intake: `localhost:4318`

Create your local `.env`. It is deliberately not tracked, so a fresh clone does not have one:

```powershell
Copy-Item .env.example .env
```

Fill in your own Toss **test** keys (`test_ck_...` / `test_sk_...`) from the Toss Payments dashboard. The application starts without `.env` — the run script warns and falls back to the current process environment — but checkout will not work until the keys are present.

Start the application:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\run-local.ps1 -StartInfrastructure
```

The script resolves JDK 17, loads `.env` values without printing them, verifies required Docker services, and runs Spring Boot in the foreground. If infrastructure is already running, omit `-StartInfrastructure`.

The React marketplace is already built into `src/main/resources/static/app`, so `http://localhost:8080/app/` works on a fresh clone without installing the Node toolchain. Install it only if you intend to change the frontend.

## Frontend Development

```powershell
cd .\frontend
npm.cmd ci
npm.cmd run dev
```

The Vite development server runs at `http://localhost:5173/app/` and proxies `/api` to Spring Boot on port 8080. Build and accessibility checks are combined in one command:

```powershell
npm.cmd run verify
```

The production build is written to `src/main/resources/static/app`. Gradle also exposes `frontendInstall` and `frontendBuild` tasks for packaging workflows.


## Production Profile

Run production with `spring.profiles.active=prod` and provide non-local infrastructure endpoints through environment variables. The prod profile intentionally does not fall back to local Docker defaults.

Required production variables:

```text
DATABASE_URL=jdbc:postgresql://db.example.com:5432/payment
DATABASE_USERNAME=payment_app
DATABASE_PASSWORD=...
REDIS_HOST=redis.example.com
KAFKA_BOOTSTRAP_SERVERS=kafka-1.example.com:9092,kafka-2.example.com:9092
TEMPORAL_TARGET=temporal.example.com:7233
OIDC_ISSUER_URI=https://idp.example.com/realms/everysale
OIDC_AUDIENCE=everysale-api
EVERYSALE_TENANT_ID=everysale
TOSS_CLIENT_KEY=live_...
TOSS_SECRET_KEY=live_...
TOSS_WEBHOOK_PATH_TOKEN=at-least-32-random-characters
CORS_ALLOWED_ORIGINS=https://app.example.com,https://admin.example.com
PAYOUT_TRANSFER_PROVIDER=TOSS_PAYOUTS
PAYOUT_TRANSFER_ADAPTER_ENABLED=true
PAYOUT_TOSS_SECRET_KEY=live_...
```

Use `.env.prod.example` as the deployment-variable inventory. Do not load it as credentials. After injecting real values from the deployment secret manager, run `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-production-env.ps1`; the preflight validates required values without printing secrets.

`GET /api/system/readiness` blocks production when DB, Redis, Kafka, or Temporal still point at localhost, when CORS allows wildcard/local/insecure HTTP origins, when mock auth is enabled, when Toss live keys or webhook token are missing/mismatched, or when external auth/tenant isolation is not configured.
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

Toss webhook recovery endpoint:

```text
POST /api/payments/toss/webhooks/{TOSS_WEBHOOK_PATH_TOKEN}
```

The endpoint stores the raw event first and processes it idempotently. Supported events are `PAYMENT_STATUS_CHANGED` and `CANCEL_STATUS_CHANGED`.

Validate the configured Toss test credentials without creating or approving a payment:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-toss-sandbox.ps1
```

The script loads `.env` into the child process without printing values, requires `test_` keys, and performs only a lookup for a random nonexistent order ID.

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
Non-Toss marketplace checkout endpoints have been removed; all public checkout variants create a Toss intent first.

Admin refund path:

```text
POST /api/admin/payments/{paymentId}/refund
{
  "idempotencyKey": "refund-request-id",
  "reason": "customer requested cancellation"
}
```

Manual refunds are admin-only, idempotent by `(paymentId, idempotencyKey)`, recorded in the `refunds` ledger, and emitted through the outbox publisher.
Raffle draw, auction close, queue clear, and reconciliation endpoints are admin-only and audited.
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

Unit test suite (excludes Docker-backed and Toss-sandbox tests by tag):

```powershell
.\gradlew.bat test --no-daemon
```

Docker-backed integration suite:

```powershell
.\gradlew.bat integrationTest --no-daemon
```

Coverage report, merging unit and integration execution data:

```powershell
.\gradlew.bat test integrationTest jacocoTestReport --no-daemon
```

The report is written to `build_sim_new/reports/jacoco/test/html/index.html`. Merging both execution files currently yields 57% instruction and 44% branch coverage; unit tests alone are 47% / 37%, so the Docker-backed suites carry a real share of it.

CI enforces a ratchet below the measured figure:

```powershell
.\gradlew.bat jacocoTestCoverageVerification --no-daemon
```

The integration suite uses Testcontainers PostgreSQL 16 and Redis. It verifies Flyway migrations, reservation compensation, 50 concurrent auction bids, 30 duplicate raffle entries, 100 distinct raffle entries, and 100 concurrent queue joins. Both suites run in CI: the unit suite and the integration suite execute on an `ubuntu-latest` job, because Testcontainers requires Linux containers that the Windows distribution job cannot start.

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

Docker/Testcontainers-backed scenarios are separated from the default unit test gate:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-integration.ps1
```

Docker Desktop must be running. The suite starts isolated PostgreSQL and Redis containers and does not reuse the local application database.

If an older local Docker volume reports the known V19 checksum mismatch, run the guarded dry-run first. The apply mode creates a custom-format PostgreSQL backup before changing the local index and the single V19 history row:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\repair-local-flyway-v19.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\repair-local-flyway-v19.ps1 -Apply
```

C2C seller onboarding, review, listing publication, Toss intent creation, and operations audit can be exercised against a running local server:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-c2c-commercial-smoke.ps1
```

See [docs/c2c-commercial-e2e.md](docs/c2c-commercial-e2e.md) for the two-browser auction/raffle scenarios and final success criteria.

Operational readiness is also exposed by:

```text
GET /api/system/readiness
```

See [docs/distribution-readiness.md](docs/distribution-readiness.md) for B2B SaaS release criteria, tenant headers, production mode settings, and manual QA.

## Load Test

The historical JMeter scenario targets the internal-only `POST /api/reservations/complete` endpoint and is not a production release gate. Production payment load tests must use the Toss intent/confirm flow and poll workflow status when confirm returns `202 PENDING`.

Accepted final load-test outcomes:

- `200 SUCCESS`
- `202 PENDING` only after workflow polling later resolves to `SUCCESS`

Run:

```powershell
load-test\scripts\run-load-test.bat
python analysis\analyze_temporal_performance.py load-test\results\{timestamp}\results.jtl
```

Use Temporal UI to inspect workflow duration and activity retries. Use the `outbox_events` table to inspect event publication latency, retry state, and failed events.

## Distributed Tracing

One checkout spans an HTTP request, a Temporal Saga, Redis inventory scripts, Postgres writes, and Kafka publication. The existing `X-Correlation-Id` ties log lines together but says nothing about where the time went, so the application also emits OpenTelemetry spans over OTLP.

```text
management.tracing.enabled       TRACING_ENABLED         default true
management.tracing.sampling.probability
                                 TRACING_SAMPLE_RATE     1.0 local, 0.1 in prod
management.otlp.tracing.endpoint OTLP_TRACES_ENDPOINT    http://localhost:4318/v1/traces
```

`docker compose up -d` starts Jaeger; open `http://localhost:16686` and pick the `payment` service. Spans cover inbound HTTP, the security filter chain, scheduled jobs, outbound HTTP through both `RestTemplate` beans, Kafka publish/consume, and the Temporal Saga down to each activity.

A failed checkout looks like this in the trace view, compensation included:

```text
http post /api/reservations/complete
  secured request
    StartWorkflow:CompleteReservationWorkflow
      RunWorkflow:CompleteReservationWorkflow
        ReserveInventory
        CreateOrder
        ProcessPayment          <- fails here
        VerifyPaymentStatus
        CancelOrder             <- compensation
        CancelReservation       <- compensation
        BuildFailureResponse
```

Temporal exposes its instrumentation through the OpenTracing API, so `OpenTracingShim` bridges it to OpenTelemetry. Interceptors are registered on **both** the client and the worker: the span context travels in Temporal headers when the workflow starts, so instrumenting only one side leaves the workflow as an orphan trace.

Both `RestTemplate` beans are built through `RestTemplateBuilder`. Constructing one with `new RestTemplate(...)` skips Spring Boot's observation wiring, which would silently drop the outbound Toss and payout calls from every trace — exactly the hops where external latency turns into user-visible wait.

Log lines carry `[trace:{traceId}/{spanId}]` alongside the correlation id, so a log line found in production can be opened directly in the trace view. `TracingLogCorrelationTest` pins that propagation; without it the pattern would keep rendering while the values quietly became `n/a`.

Production samples 10% by default. Tracing every payment makes the collector and the application pay for data nobody reads; raise `TRACING_SAMPLE_RATE` when investigating a specific incident.

Not yet instrumented: Redis command spans. Inventory Lua scripts appear inside the enclosing activity rather than as their own spans, which is usually enough because the activity boundary already isolates them.

`TemporalTracingConfig` deliberately avoids `@ConditionalOnBean`. A user `@Configuration` is evaluated before auto-configuration registers the `OpenTelemetry` bean, so the condition would always be false, the configuration would drop out silently, and the application would start clean with only the workflow spans missing.

## Operational Notes

- `TOSS_PAYMENTS` is the default payment gateway; local mock beans are disabled unless explicitly enabled for tests.
- Public direct complete and legacy payment APIs are disabled by default.
- Security is deny-by-default for API routes that are not explicitly public; public routes are limited to static pages, health/readiness, and public marketplace/event reads.
- Authorization denials and sensitive admin actions are recorded in `security_audit_events`; production readiness blocks startup if `app.audit.enabled=false`.
- CORS is explicit allowlist based; production readiness blocks wildcard, localhost, unresolved, or plain HTTP origins.
- Local mock authentication grants `CUSTOMER` only by default. Use `X-EverySale-Roles: ADMIN` only for local admin testing; production must use `spring.profiles.active=prod` with external JWT/OIDC auth and `OIDC_ISSUER_URI`. Readiness blocks production when mock auth is enabled.
- The former simulation runner and static demo-auth API have been removed. Local identity headers are accepted only while the development mock-auth filter is enabled.
- Order, payment, reservation, workflow, Toss intent, queue, seat, seller, and admin APIs perform ownership or role checks server-side; do not rely on client-provided `customerId` alone.
- Inventory counters are maintained in Redis and mirrored in Postgres.
- `app.inventory.reconciliation.enabled=true` enables scheduled mismatch detection between Redis and Postgres.
- `app.outbox.enabled=true` enables scheduled outbox publishing to Kafka.
- Production enables Redis Pub/Sub broadcast for marketplace SSE so auction and raffle updates cross application instances.
- Local payout transfer uses `LEDGER_ONLY` for workflow testing, which only writes the ledger and never moves money. Set `PAYOUT_TRANSFER_PROVIDER=TOSS_PAYOUTS` with `PAYOUT_TRANSFER_ADAPTER_ENABLED=true` and `PAYOUT_TOSS_SECRET_KEY` to activate the real transfer adapter; the two gateway beans are mutually exclusive by property value. Production readiness blocks while the stub is still active.
- The payout adapter classifies provider responses the same way the payment gateway does: `4xx` is a terminal `FAILED`, while `5xx`, `408`, `429`, and network timeouts are `UNKNOWN`. `UNKNOWN` never triggers a second transfer — the coordinator resolves it through a provider status lookup using the original idempotency key (`seller-payout:{payoutId}`). Misclassifying an indeterminate result as failed is what would send the money twice.
- Payout transfers use a dedicated HTTP client with a longer read timeout than the shared one, because a timeout on a bank transfer means "result unknown" rather than "failed".
- Production also requires the seller payout reconciliation worker. Stale `PROCESSING` or `UNKNOWN` transfers are resolved through provider status lookup with the original idempotency key; they are never submitted as a second transfer.
- The first commercial deployment uses `SINGLE_TENANT` mode. `EVERYSALE_TENANT_ID` pins anonymous storefront traffic and authenticated JWT tenant claims to one marketplace; spoofed tenant headers are rejected. `MULTI_TENANT_RLS` remains blocked until database row-level security is explicitly implemented and enabled.
- `app.kafka.listeners.payment-events.enabled=false` keeps the sample payment-event listener disabled by default.
