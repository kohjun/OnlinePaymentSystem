# Remaining Recovery Work

Reviewed: 2026-10-25 (Asia/Seoul).
Baseline: current working tree over 806b59af15bba4bff4bfff7f9f15eee885ced83d.

This is a prioritized follow-up list, not a claim that commercial deployment is approved or that all repository defects have been enumerated. Preserve existing uncommitted work, uploads, .env and Toss keys. Use new migrations when needed; do not reset demo data to make tests pass.

## Completed October 25

- Generic intent bypass closed: `POST /api/payments/toss/intents` and its confirm reject products owned by a marketplace listing, so draft, paused, auction and raffle inventory can no longer be bought outside the sale-event checkout (unit tests plus an HTTP assertion at the start of the drop lifecycle).
- One clock: the JVM default time zone is pinned to UTC before any Spring context starts, test JVMs run in UTC, API `LocalDateTime` values carry `Z`, Toss `approvedAt` is converted to UTC, and notification deadlines render in KST. The UTC serializer lives on the primary mapper (`JacksonConfig`) and the DTO `@JsonFormat` pattern is `yyyy-MM-dd'T'HH:mm:ss['Z']`, which still reads stored bodies without `Z`; Redis storage keeps its previous format. Previously the SPA showed sale deadlines nine hours early in Korean browsers. Before this, a KST host computed winner holds with a negative TTL and raffle draws / auction closes failed (`WinnerHoldTimeZoneRegressionTest` reproduces both failures without the pin).
- PR-A follow-up: the partial-refund review queue no longer offers a seller-payout action that the settlement guard always rejects.
- Follow-up fixes: `JacksonConfig` now provides the `@Primary` Boot-built `ObjectMapper` for MVC and services while Redis components keep `redisObjectMapper` (Lombok copies `@Qualifier`). Before this, ProblemDetail errors nested `code` under `properties` and the SPA never saw error codes. Checkout `successUrl`/`failUrl` must be a same-site path or a configured CORS origin (`CheckoutRedirectPolicy`); the public bundle ships without source maps and the gate enforces it; the local Kafka container restarts after a stale ZooKeeper registration. Verification: 405 unit and 48 integration cases, coverage 68.8% instruction / 54.3% branch.
- Policy decided (2026-10-25): partially refunded orders allow payout cancellation or a further buyer refund only. Any settlement of the remaining amount to the seller is handled manually by operations outside the payout ledger; the system does not recalculate or release such payouts.
- PR-B confirmed in the working tree: `ReservationAuthorizationFence` records durable no-authorization proof under the reservation lock; `noAuthorizationProofReleasesFailedCheckoutAtomically` (order-create failure, closure rollback, authorization race) passes.
- PR-C: six pre-change histories are stored under `src/test/resources/temporal-histories` and replayed by `CompleteReservationWorkflowReplayTest`; a command-order change fails it, a timer-length change does not. Manual-review polling now backs off 15 min → 2 h, so 60 days of uncertainty stays under 10,240 history events instead of reaching the 51,200 limit in roughly 45 days.
- PR-D: published-contract comparison recorded in [payout provider contract check](payout-provider-contract.md); readiness blocks `TOSS_PAYOUTS` until the adapter is rebuilt and verified.
- PR-E: `winnerCheckoutCompletesThroughTemporalTossAndPayoutLedger` covers raffle draw and auction close through winner hold, Toss confirm (normal and lost response), order, held payout and replay without a second provider call; it is a mandatory integration scenario.
- PR-F: the distribution gate now rebuilds the backend jar for the desktop package and fails when the packaged jar differs. The previous package shipped a June 22 jar.

## Completed October 23

- Payout receipt reference, amount, currency and provider-ID verification under the existing ledger locks.
- Exact reference selection for payout list lookup, with ambiguous/missing evidence retained as UNKNOWN.
- Response-loss and JVM-restart recovery tests now inject mismatched receipts before successful lookup-only recovery.
- PR-A completed: dedicated transactional seller-favor dispute resolution, generic payout guard preserved, audit replay and refund blocking checked against real services. Two independent JVMs verify both dispute/confirmation orderings; the refund uncertainty case preserves payout and stock.
- Full verification completed October 23 (run started October 22): 349 unit cases passed, one existing manual stress case disabled; 40 integration cases passed with no skips. Coverage gates passed (instruction 66.59%, branch 52.32%). See [verification evidence](payment-recovery-verification.md).

## Next PRs

| Priority | Scope | Completion Evidence |
| --- | --- | --- |
| P1 / PR-D | Rebuild the payout adapter on the published v2 contract: seller registration and KYC states, JWE request/response, `destination`/`scheduleType`/`transactionDescription`, timeout recovery via `payout.changed` or date-range listing. Blocked on the payout contract and security key. | Provider-confirmed idempotency semantics, sandbox evidence with a `CORPORATE` test seller, then `providerContractVerified()` returns `true` in the same change. |
| P1 / PR-C follow-up | Bounded history is now ~1 year of manual review, not unlimited. Add continue-as-new with an explicit resume phase, or an operator re-check signal, if longer uncertainty is possible. | Replay tests still pass; rollover preserves workflow ID, holds and phase. |
| P1 / PR-E | Remaining fault coverage: payout before submission/during finalization, admin refund origins, webhook contention, Temporal outage and restore drills; uncertainty during raffle/auction refund paths. | Final DB/Redis/Outbox/order/payout invariants, no duplicate financial operation or stock release, no skipped mandatory scenarios. |
| P2 / PR-F | Manual Toss test-window success/cancel/reconnect, production auth/authorization, backup restore and operational alerts. | Desktop/mobile browser scenarios and a separately reviewed production checklist. Local test success alone is not deployment approval. |

PR-A, PR-B and the scoped parts of PR-C/E/F are complete within their tested scope; they are not proof of every crash combination. PR-D requires external provider access.

## Process Cleanup

- The latest combined Gradle run completed successfully; its fixture-owned child JVMs are no longer running. Post-run inspection found no Java process.
- After the user restarted Docker, Engine 28.4.0 was reachable. Inspection before and after the latest run found no running Testcontainers-labelled containers. The earlier unavailable-pipe cleanup check is superseded by this October 23 verification.
- No user application, demo Compose service or volume was stopped/deleted as cleanup. No factory reset or Docker reconfiguration was performed.
