# OnlinePaymentSystem

Temporal 기반 한정 재고 예약/결제 PoC입니다. 통합 예약 플로우는 Temporal Workflow가 Saga 상태와 보상 순서를 관리하고, Activity가 Redis Lua, Postgres, PG Mock, Transactional Outbox를 실행합니다.

## Architecture

```text
Reservation API
-> Temporal Workflow
-> Activity: Redis Lua inventory reservation
-> Activity: Postgres order/payment/reservation records
-> Activity: Mock PG payment
-> Activity: Outbox event recording
-> Outbox Publisher -> Kafka
```

기본 선택:

- Application DB: Postgres
- Inventory concurrency: Redis Lua atomic scripts
- Saga orchestration: Temporal
- Event reliability: Transactional Outbox
- Legacy WAL path: retained for compatibility, no longer the default complete-reservation path

## Run

```powershell
docker compose up -d
.\gradlew.bat bootRun
```

Services:

- App: `http://localhost:8080`
- Postgres: `localhost:5432`, database/user/password `payment`
- Redis: `localhost:6379`
- Kafka: `localhost:9092`
- Temporal gRPC: `localhost:7233`
- Temporal UI: `http://localhost:8088`

## Complete Reservation

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

If the workflow does not finish inside the configured synchronous wait window, the API returns `202 PENDING` with a `workflowId`.

```powershell
Invoke-RestMethod http://localhost:8080/api/reservations/workflows/{workflowId}
```

## Test

Fast compile checks:

```powershell
.\gradlew.bat compileJava compileTestJava --no-daemon
```

Focused non-infrastructure tests:

```powershell
.\gradlew.bat test --tests "*PaymentProcessingServiceTest" --tests "*CompleteReservationWorkflowTest" --tests "*ApplicationContextSmokeTest" --no-daemon
```

The older integration tests still depend on local Redis/Kafka and run with `app.temporal.enabled=false` from `src/test/resources/application.yml`.

## Load Test

The default JMeter scenario targets `POST /api/reservations/complete` and treats both `200 SUCCESS` and `202 PENDING` as accepted Temporal responses.

```powershell
load-test\scripts\run-load-test.bat
python analysis\analyze_temporal_performance.py load-test\results\{timestamp}\results.jtl
```

Use Temporal UI to inspect workflow duration and Activity retries, and check `outbox_events` for publish latency or failed events.
