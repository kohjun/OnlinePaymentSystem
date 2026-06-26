# EverySale Distribution Readiness

This document defines the release gate for running EverySale as a production-style payment and reservation platform instead of a local demo.

## Distribution Modes

`app.distribution.mode` controls how strict readiness checks are.

- `DEMO`: local development and portfolio demos. Local Docker infrastructure and mock auth may be used, but readiness should report warnings.
- `PILOT`: limited partner validation. External auth, real payment gateway keys, observability, and non-local infrastructure are strongly recommended.
- `PRODUCTION`: commercial mode. Local infrastructure, mock auth, demo auth, legacy checkout paths, gateway fallback, and missing tenant isolation are blockers.

The production profile sets:

```yaml
spring:
  profiles:
    active: prod
app:
  distribution:
    mode: PRODUCTION
    fail-fast-on-blockers: true
    require-real-payment-gateway: true
    require-external-auth: true
    require-tenant-isolation: true
  tenancy:
    require-tenant-header: true
  security:
    mock-auth:
      enabled: false
    external-auth:
      enabled: true
payment:
  default-gateway: TOSS_PAYMENTS
  allow-gateway-fallback: false
  legacy-api:
    enabled: false
  toss:
    webhook:
      enabled: true
      path-token: ${TOSS_WEBHOOK_PATH_TOKEN}
```

## Required Production Environment

The `prod` profile intentionally requires explicit external endpoints and credentials.

```text
DATABASE_URL=jdbc:postgresql://db.example.com:5432/payment
DATABASE_USERNAME=payment_app
DATABASE_PASSWORD=...
REDIS_HOST=redis.example.com
REDIS_PORT=6379
REDIS_PASSWORD=...
KAFKA_BOOTSTRAP_SERVERS=kafka-1.example.com:9092,kafka-2.example.com:9092
TEMPORAL_TARGET=temporal.example.com:7233
TEMPORAL_NAMESPACE=payment
TEMPORAL_TASK_QUEUE=payment-reservation-task-queue
OIDC_ISSUER_URI=https://idp.example.com/realms/everysale
TOSS_CLIENT_KEY=live_...
TOSS_SECRET_KEY=live_...
TOSS_WEBHOOK_PATH_TOKEN=at-least-32-random-characters
CORS_ALLOWED_ORIGINS=https://app.example.com,https://admin.example.com
DEFAULT_TENANT_ID=...
DEFAULT_PARTNER_ID=...
```

Optional Kafka security variables:

```text
KAFKA_SECURITY_PROTOCOL=SASL_SSL
KAFKA_SASL_MECHANISM=PLAIN
KAFKA_SASL_JAAS_CONFIG=...
```

## Readiness API

Check readiness before release:

```text
GET /api/system/readiness
```

Statuses:

- `READY`: no blocking issue and no warning.
- `ATTENTION_REQUIRED`: warnings exist, but release is not blocked in the current mode.
- `BLOCKED`: at least one blocking issue exists. In production this should be treated as a failed release gate.

Production blockers include:

- Redis connectivity failure.
- Temporal workflow path disabled.
- Outbox publisher disabled.
- Legacy WAL path enabled.
- Java version below the required version.
- Missing or mismatched Toss live keys.
- Toss webhook disabled or missing a strong path token.
- Public direct `/api/reservations/complete` enabled.
- Legacy payment or marketplace checkout APIs enabled.
- Demo auth API enabled.
- Mock authentication filter enabled.
- Security audit trail disabled.
- Payment gateway fallback enabled.
- External auth disabled or missing OIDC issuer/JWK configuration.
- Tenant header isolation disabled.
- DB, Redis, Kafka, or Temporal endpoint missing, unresolved, or pointing at localhost.
- CORS allowlist containing `*`, localhost, unresolved placeholders, or plain `http://` origins.
- Release channel still marked as local.

## API Contract

Production requests should include tenant and trace headers:

```text
X-Tenant-Id: partner-company
X-Partner-Id: commerce-team
X-Correlation-Id: request-or-trace-id
```

`app.tenancy.require-tenant-header=true` rejects missing tenant headers in production.

## Manual QA Checklist

- Start the app with `spring.profiles.active=prod` and production-like env vars.
- Confirm `/api/system/readiness` returns `READY` or intentionally reviewed `ATTENTION_REQUIRED` outside production.
- Confirm `POST /api/payments/toss/intents` creates an intent with server-side price validation.
- Complete Toss redirect and call `POST /api/payments/toss/confirm`.
- Send a Toss payment status webhook to `POST /api/payments/toss/webhooks/{TOSS_WEBHOOK_PATH_TOKEN}` and confirm duplicate delivery is idempotent.
- Confirm Temporal workflow status via `GET /api/reservations/workflows/{workflowId}`.
- Confirm order, payment, reservation, refund, and outbox rows are written to Postgres.
- Confirm sensitive admin actions write rows to `security_audit_events`.
- Confirm raffle draw, auction close, queue clear, simulation reset, reconciliation, and refund APIs require admin role.
- Confirm a non-owner customer cannot read another customer's order, payment, reservation, workflow, Toss intent, seat, or queue state.
- Confirm outbox events progress through `PENDING`, `IN_PROGRESS`, and `PUBLISHED`, or retry to `FAILED` after max attempts.

## Automated Verification

Run the fast release gate before packaging:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-distribution.ps1
```

Run Docker-backed integration scenarios separately when Docker Desktop is available:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-integration.ps1
```

## Remaining Platform Work

These items are outside the current local code gate but required for a real production rollout:

- Managed secret storage and key rotation.
- TLS termination, WAF/rate-limit policy, and strict CORS for public domains.
- Centralized metrics, logs, traces, alerts, and SLO dashboards.
- Database backup/restore drills and migration rollback procedures.
- Redis persistence/HA policy and reconciliation runbook.
- Kafka dead-letter topic monitoring and replay runbook.
- Formal PCI/privacy review for payment metadata and audit logs.
