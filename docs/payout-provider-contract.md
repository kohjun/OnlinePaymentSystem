# Payout Provider Contract Check

Reviewed: 2026-10-25 against the public Toss Payments payout (지급대행) documentation.
Status: **not verified** — `TossPayoutsSellerPayoutTransferGateway.providerContractVerified()` returns `false`, so production readiness blocks `PAYOUT_TRANSFER_PROVIDER=TOSS_PAYOUTS`.

Sources:

- API reference: https://docs.tosspayments.com/reference/additional#지급대행
- Integration guide: https://docs.tosspayments.com/guides/v2/payouts

## Gaps Between The Adapter And The Published Contract

| Area | Current adapter | Published contract |
| --- | --- | --- |
| Endpoint | `POST /v1/payouts`, `GET /v1/payouts/{id}` | `POST /v2/payouts` (up to 100 items per call), `GET /v2/payouts/{id}`, `POST /v2/payouts/{id}/cancel` |
| Body security | Plain JSON | Request body encrypted as JWE (`alg=dir`, `enc=A256GCM`, `iat`, `nonce`) with the 64-hex security key; header `TossPayments-api-security-mode: ENCRYPTION`; success and error responses are also JWE |
| Recipient | `accountRef`, `sellerId` | `destination` = Toss seller `id` returned by `POST /v2/sellers` |
| Required fields | `refPayoutId`, numeric `amount`, `currency` | `refPayoutId` (≤50), `destination`, `scheduleType` (`EXPRESS` business days 08:00–15:00, or `SCHEDULED` + `payoutDate`), `amount {currency: KRW, value}`, `transactionDescription` (≤7 chars) |
| Lookup after timeout | `GET /v1/payouts?refPayoutId=` | Not documented. Lookup is by Toss `id`, or list by `payoutDateGte`/`payoutDateLte`; `payout.changed` webhook reports final state |
| Statuses | SUCCEEDED/FAILED/UNKNOWN mapping from v1-style fields | `REQUESTED`, `IN_PROGRESS`, `COMPLETED`, `FAILED`, `CANCELED`, `DELETED`, `REJECTED` |
| Seller onboarding | Stores `accountRef` + last 4 digits; no provider registration | `POST /v2/sellers` with full account, business type, contact; seller states `APPROVAL_REQUIRED`, `PARTIALLY_APPROVED` (<10M KRW/week), `KYC_REQUIRED`, `APPROVED`; `seller.changed` webhook |
| Idempotency | `Idempotency-Key: seller-payout:{payoutId}` + `refPayoutId` | Guide says an idempotency key header prevents duplicate payouts and warns against changing it after an error; duplicate `refPayoutId` behaviour is not documented |

Running the current adapter against the real API would not move money twice: unrecognised responses become `FAILED` (4xx) or stay `UNKNOWN`, and `UNKNOWN` is never resubmitted. It would, however, leave every payout failed or stuck.

## Required Before Enabling

1. Payout service contract and the API security key (separate from the collection secret key).
2. Seller onboarding that registers each seller with `POST /v2/sellers`, stores the returned seller `id` as `accountRef`, and blocks release unless the seller is `PARTIALLY_APPROVED` within limits or `APPROVED`.
3. Adapter rewrite: v2 paths, JWE request/response handling, `destination`/`scheduleType`/`transactionDescription`, status mapping above, and timeout recovery through the `payout.changed` webhook or date-range listing filtered by exact `refPayoutId`.
4. Written confirmation from Toss of duplicate-`refPayoutId` and idempotency-key semantics.
5. Sandbox evidence (test sellers must be `CORPORATE` with a valid business number; live requests are charged), then set `providerContractVerified()` to `true` in the same change.
