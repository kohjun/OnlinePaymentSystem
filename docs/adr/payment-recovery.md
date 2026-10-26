# Payment Recovery

Status: implemented locally, production rollout requires the remaining verification below.
Date: 2026-10-13.

## Approval

The reservation is locked before inserting a PROCESSING payment. Only the transaction that inserts that row may authorize. Activity retries with an existing row use provider lookup, never authorize again. A successful provider response followed by DB failure leaves PROCESSING and is recovered by lookup.

Approval observations must match payment key, order ID, amount and currency. Unknown, unparseable, 404, authentication and transport errors from lookup are not proof that no charge exists. Even HTTP 400 can mean an already-processed request; only explicit rejection codes are terminal. See the [Toss error reference](https://docs.tosspayments.com/reference/error-codes).

```text
PROCESSING -> UNKNOWN -> APPROVED -> order completion
PROCESSING / UNKNOWN -> verified FAILED -> release inventory
APPROVED -> refund ledger -> REFUNDED / PARTIALLY_REFUNDED
```

Temporal stays running during uncertainty. Poll delays are 5, 15, 30, then 60 seconds; after 30 minutes the phase is MANUAL_REVIEW and polling continues every 15 minutes. Timer expiry does not imply rejection. Refund compensation must be confirmed before order cancellation and inventory release.

## Refund

The coordinator performs HTTP outside its ledger transactions. REQUIRES_NEW writer transactions preserve PROCESSING, UNKNOWN, FAILED and SUCCEEDED plus payment state and Outbox even when the caller later fails. A payment lock and unique active_payment_id serialize different refund keys. Retrying an existing operation rechecks provider state; it does not send another cancellation request.

The full remaining balance is refunded. Each provider transactionKey is imported once. The total of verified DONE cancellation receipts must reconcile with totalAmount and balanceAmount before state is changed. Provider partial cancellation is not full refund. The field contract follows the [Toss Payment API](https://docs.tosspayments.com/reference).

When preflight lookup fails, the operation remains UNKNOWN and does not automatically submit a cancellation later. Operations must investigate this conservative hold rather than create a fresh idempotency key blindly.

## Inventory And Settlement

Expiry cannot release APPROVED, PROCESSING, UNKNOWN or unresolved refund payments. An authenticated intent protects inventory before a payment row is inserted. A started transaction is not subjected again to first-authorization expiry checks.

V51 adds an explicit authorization fence to reservations. Newly created canonical checkout/winner holds use NOT_STARTED; existing rows and other creation paths default to UNTRACKED and receive no inferred no-charge proof. PaymentObservationWriter locks the reservation, validates the command's hold/intent ownership, and commits STARTED plus the PROCESSING payment before any provider IO. Failure to insert the payment rolls back both writes. STARTED protects inventory even if a payment row is unexpectedly missing.

On pre-payment workflow failure, the same reservation lock permits only NOT_STARTED with no payment evidence to become CLOSED_WITHOUT_AUTHORIZATION. The workflow ID, timestamp, proof Outbox event, DB inventory return and durable Redis projection commit together. A late authorization claim is rejected. The reservation-cancellation activity also cancels any order created by a delayed retry between compensation activities in that transaction. Duplicate release does not increment stock again. Closure is an internal workflow operation, not an admin override, timeout heuristic or provider lookup interpretation; existing uncertain payments take precedence over any contradictory proof.

Cancellation completion commits core order, marketplace order, payout, cancellation ledger, inventory movement and Redis synchronization issue in one DB transaction. Redis projection occurs after commit and is retried idempotently. A late failed observation cannot overwrite completed cancellation. Shipment-completed and digitally fulfilled orders are not automatically restocked.

Successful cancellation completion clears the prior failure reason in the same transaction. Recovery from UNKNOWN/FAILED and duplicate or late observations must not leave a completed order displaying an unresolved refund warning or repeat payout/inventory finalization.

Fulfillment, purchase confirmation, cancellation and new payout claims use the marketplace order lock. The payout transfer is claimed before provider IO. A transfer already in flight that succeeds after refund blocking is marked RECOVERY_REQUIRED, not silently restored to normal released status.

Dispute creation also locks the marketplace order. Source-based payout mutations and recovery completion lock the payout row; callers that touch both use order-before-payout lock ordering. RECOVERY_REQUIRED and RECOVERED are preserved by late cancellation, dispute and provider-refund updates, and cannot be marked READY_FOR_RELEASE. Only explicit recovery completion may advance RECOVERY_REQUIRED to RECOVERED. These markers record money already sent and must not be replaced by CANCELLED, which would hide the recovery obligation.

Seller-favor dispute resolution uses a dedicated package-internal payout transition with mandatory transaction propagation. The admin controller supplies the authenticated operator; the order service locks the order, checks the refund guard and records the resolution before the payout service accepts DISPUTED -> READY_FOR_RELEASE. Both records commit together. Generic markReadyForRelease continues to reject DISPUTED. Missing resolution evidence, non-PAID orders, cancelled/released/recovery payouts cannot use the dedicated transition. Completed resolution replays return the existing order without replacing audit fields or re-opening the payout. No provider call occurs during this resolution transaction.

Payout status-lookup HTTP errors, including authentication/authorization errors and not-found responses, are not evidence that the original transfer failed. They remain UNKNOWN and eligible for scheduled lookup. The same durable payout idempotency key is used when the provider transfer ID was lost before persistence. Recovery never submits another outgoing transfer. A provider's explicit terminal status is distinct from failure to obtain that status.

Payout observations must match the durable transfer reference key, amount (numeric equality), currency and any previously recorded provider transfer ID. Missing or mismatched receipt evidence stays UNKNOWN without adopting a new provider ID or releasing the payout. This applies to rejection/processing receipts as well as success; an HTTP rejection without a verifiable receipt is not persisted as terminal FAILED. List lookup selects exactly one matching reference, never the first arbitrary item; duplicate matches remain ambiguous. Outgoing requests use the amount and currency captured by the claim transaction. These checks validate the existing local adapter contract and do not certify the live provider API.

Shipment cannot transition back to a pre-shipment status. Cancellation checks persisted shipment/completion timestamps as well as the fulfillment enum, preventing stale status from reopening a shipped order for automatic cancellation. Settlement-guard rejections return HTTP 409 REFUND_VERIFICATION_REQUIRED, not a retryable internal error; payout requests rejected earlier because the payout is still HELD retain their existing HTTP 400 response.

## Idempotency And Webhooks

Workflow IDs include tenant, merchant and operation scope. Request hashes reject key reuse with a different request. Only terminal responses are cached. A late PENDING response must not replace a terminal result.

Webhooks are persisted signals, not authoritative financial state. A worker with an expiring lease performs provider lookup, validates identifiers and applies the same writers. Lease completion is conditional on its token; interrupted PROCESSING events can be reclaimed. Unknown cancellation payloads cannot synthesize refunds.

## Operator Runbook

1. Use the admin paymentRecovery queue and recheck action. Record paymentId, orderId, reservationId, workflowId and correlationId, never secrets or full provider keys.
2. Check provider status and cancellation receipts, active refund and cancellation rows, then Temporal phase. Do not authorize or cancel again under a new key to clear an alert.
3. If provider state is confirmed, let reconciliation finish. Inspect pending/failed inventory_sync_issues before allowing resale. Never overwrite live Redis counters from a scan while holds exist.
4. Monitor everysale.payment.recovery.pending, everysale.payment.recovery.oldest.seconds and everysale.inventory.sync.oldest.seconds. Escalate payment waits above 1800 seconds, exhausted sync retries, and increasing projection lag.
5. Before V50, inspect multiple PROCESSING/UNKNOWN refunds per payment. Migration intentionally fails closed on conflicting active operations; reconcile them with provider evidence, not automatic row deletion.
6. V51 intentionally leaves preexisting reservations UNTRACKED. Do not backfill NOT_STARTED from an absent payment row or clear a STARTED marker. Review old authenticated holds with provider evidence. Deploy only with canonical writers using the shared reservation lock; keep legacy payment APIs disabled and do not mix workers that can authorize outside this protocol.

## Rollout Limits

- Existing Temporal histories need replay/versioning validation before upgrading production workers. Do not reset production history. Only isolated demo/test resources may be reset with approval.
- Long-lived unresolved workflows still need bounded-history/continue-as-new validation.
- New tracked holds can be safely released after proven pre-authorization workflow failure. Old UNTRACKED authenticated holds still require operator verification; V51 does not invent historical proof. A missing PROCESSING payment row after STARTED also requires investigation. Do not bypass these holds by forcing expiry or clearing payment keys.
- Approval-transaction rollback after provider success is covered by a PostgreSQL/Temporal HTTP integration test. Another test stops an isolated WorkerFactory during approval uncertainty, verifies the inventory hold, then creates a new worker to finish the same workflow with one provider authorization. Its newly generated history is replayed against the current implementation. This is same-JVM worker replacement, not application process restart or compatibility with pre-change histories.
- A PostgreSQL process crash during the approval transaction is also covered: pg_ctl immediate stop interrupts the real DB connection before commit, with its container/network endpoint retained. Restarting the database recovers the existing payment through lookup with one authorization; inventory stays held until completion and is restored once after cancellation.
- A separate Spring Boot child JVM is forcibly terminated during approval uncertainty and replaced on the same dedicated Temporal queue. The extended drill passed in the October 20 full regression: owner GET polling observes completion of the same workflow without another confirm request, a non-owner GET returns 403, and authorization occurs once. Refund and inventory restoration are also verified once. The test harness and infrastructure remain running; this is not a whole-stack outage.
- Buyer cancellation now also covers JVM termination after the provider commits a refund but before the app receives its response. With provider lookup unavailable, the new JVM preserves stock and payout; once lookup recovers, its scheduled recovery completes the persisted cancellation without resubmission. One receipt, refund-observed event and inventory restoration are verified, including a completed HTTP replay. The October 20 full regression passed.
- A two-JVM HTTP drill covers both orderings of cancellation versus shipment while both requests wait on the PostgreSQL order lock. Cancellation-first blocks shipment; shipment-first blocks cancellation, refund and stock return. HELD payout release is also rejected without a transfer row.
- Three further two-JVM HTTP cases passed in both the focused payout run and the October 22 full regression: admin refund before payout submission, after provider commitment while its response is held, and after observed transfer success. Scheduled refund recovery finalizes the order. Money already sent is tracked as RECOVERY_REQUIRED, repeated calls do not send again, and shipped goods are not restocked. This uses the existing adapter's local HTTP stub contract, not a certified live payout provider integration or an actual reversal of funds already sent.
- Payout response loss and process termination after provider commitment are covered by three October 22 cases that passed both focused and full regression, including admin refund during the app outage. The scheduled worker tolerates 401/503 lookup errors and finishes by lookup alone, with one outgoing transfer and no restock. This is retained-infrastructure recovery, not a whole-stack outage or proof of every payout crash point.
- Dispute-resolution drills cover both orderings of dispute creation versus buyer confirmation on the same PostgreSQL order lock, plus rejection of seller-favor resolution while a provider-committed refund remains UNKNOWN. They do not prove every concurrent dispute/refund/payout combination. Other refund/payout crash points, storage loss/corruption, Temporal server outage and provider webhooks out of order under contention still need fault drills. The payout adapter's live provider contract and ambiguous POST error semantics require separate review; local receipt validation is not provider certification. See the verification evidence for current executed results; adding a drill alone is not a passing result.
- Tests use stubbed providers. Live PG onboarding, OIDC, payout provider approval, backups/restores and monitoring are separate deployment prerequisites.
