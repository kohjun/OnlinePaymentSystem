# Remaining Recovery Work

Reviewed: 2026-10-23 (Asia/Seoul).
Baseline: current working tree over 806b59af15bba4bff4bfff7f9f15eee885ced83d.

This is a prioritized follow-up list, not a claim that commercial deployment is approved or that all repository defects have been enumerated. Preserve existing uncommitted work, uploads, .env and Toss keys. Use new migrations when needed; do not reset demo data to make tests pass.

## Completed In The Latest Round

- Payout receipt reference, amount, currency and provider-ID verification under the existing ledger locks.
- Exact reference selection for payout list lookup, with ambiguous/missing evidence retained as UNKNOWN.
- Response-loss and JVM-restart recovery tests now inject mismatched receipts before successful lookup-only recovery.
- PR-A completed: dedicated transactional seller-favor dispute resolution, generic payout guard preserved, audit replay and refund blocking checked against real services. Two independent JVMs verify both dispute/confirmation orderings; the refund uncertainty case preserves payout and stock.
- Full verification completed October 23 (run started October 22): 349 unit cases passed, one existing manual stress case disabled; 40 integration cases passed with no skips. Coverage gates passed (instruction 66.59%, branch 52.32%). See [verification evidence](payment-recovery-verification.md).

## Next PRs

| Priority | Scope | Completion Evidence |
| --- | --- | --- |
| P0 / PR-B | Safely resolve an authenticated intent whose order creation fails before a payment row exists. Introduce durable proof of no authorization and an idempotent release path. Never treat timeout, lookup 404 or elapsed time alone as proof. | Fail before/after the authorization boundary; unresolved approval retains inventory, proven non-authorization releases once, and competing recovery/expiry workers cannot double-release. |
| P1 / PR-C | Validate Temporal upgrade compatibility and bounded recovery history. Review version markers/continue-as-new with existing query and workflow-ID contracts. | Replay representative pre-change histories, exercise long UNKNOWN/COMPENSATION_PENDING runs and verify preserved identity, holds and recovery phase after rollover/restart. |
| P1 / PR-D | Certify the actual payout-provider contract separately from the local stub. Check endpoint/schema, account onboarding, idempotency/status guarantees, receipt evidence and ambiguous POST error codes. | Provider-approved contract and sandbox evidence; mismatches fail closed; production readiness rejects unsupported setup. No real credentials committed and no unapproved real-money test. |
| P1 / PR-E | Extend remaining fault coverage: payout before submission/during finalization, Saga/dispute/admin refund origins, webhook contention, Temporal outage and restore drills. Expand uncertainty tests across RAFFLE/AUCTION as well as DROP. | Final DB/Redis/Outbox/order/payout invariants, no duplicate financial operation or stock release, no skipped mandatory scenarios. Document retained-infrastructure recovery separately from disaster recovery. |
| P2 / PR-F | Refresh frontend/packaged-app and deployment evidence after backend changes. Verify buyer/seller pending states, manual Toss test-window success/cancel/reconnect, production auth/authorization, backup restore and operational alerts. | npm verify, desktop/mobile browser scenarios, full distribution gate, packaged executable smoke, and a separately reviewed production checklist. Local test success alone is not deployment approval. |

PR-B is the next implementation priority. PR-A is complete within the tested dispute/confirmation and refund-blocking scope; it is not proof of every dispute/refund/payout crash combination. PR-D may require external provider access; continue independent local work while recording that dependency explicitly.

## Process Cleanup

- The latest combined Gradle run completed successfully; its fixture-owned child JVMs are no longer running. Post-run inspection found no Java process.
- After the user restarted Docker, Engine 28.4.0 was reachable. Inspection before and after the latest run found no running Testcontainers-labelled containers. The earlier unavailable-pipe cleanup check is superseded by this October 23 verification.
- No user application, demo Compose service or volume was stopped/deleted as cleanup. No factory reset or Docker reconfiguration was performed.
