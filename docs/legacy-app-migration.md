# Legacy UI to `/app/` migration matrix

This document is the deletion gate for the former `static/index.html`, `static/shared.html`, and `static/seller.html` clients.
An item can be removed only after its replacement contract and regression test are present.

| Legacy capability | Current `/app/` replacement | State | Removal target |
|---|---|---|---|
| Fixed-price and limited-stock checkout | Marketplace event detail and Toss intent/confirm | Migrated | Remove legacy reservation/payment stepper |
| Ticket queue and seat selection | `TicketPanel` with `/api/queue` and marketplace ticket APIs | Migrated | Remove `/api/system/seats/*` UI calls |
| Raffle entry, result and winner checkout | `EventDetail` raffle panel with SSE | Migrated | Remove shared raffle page |
| Auction bid, live status and winner checkout | `EventDetail` auction panel with SSE | Migrated | Remove shared auction page |
| Buyer orders, delivery confirmation, dispute and review | `/app/` purchase history | Migrated | Remove legacy booking/order modal |
| Shipping addresses | `/app/` address book | Migrated | Remove embedded shipping form fallback |
| Seller profile and verification | `/app/` seller management | Migrated | Remove legacy seller banner/forms |
| Product draft, review submission and sale event publishing | `/app/` seller product tab | Migrated | Remove legacy product registration form |
| Seller order fulfillment and tracking | `/app/` seller order/delivery tab | Migrated | Remove legacy merchant order polling |
| Payout account and payout ledger | `/app/` seller settlement tab | Migrated | Remove legacy payout banner/ledger |
| Admin review queues and audited actions | Dedicated `/app/` operations view | Migrated | Keep `/api/admin/operations/*`; legacy renderer removed |
| Readiness, inventory reconciliation and incident status | Dedicated `/app/` admin operations view | Migrated | Keep protected minimum system APIs |
| Simulation VU runner and performance chart | Load-test scripts and reports outside the commercial app | Remove, do not migrate | Delete simulation UI and production controller exposure |
| Simulation username login/subscription | Spring Security local principal or production JWT | Remove, do not migrate | Delete `AuthController` and legacy local storage session |
| Dashboard reset and preset event switching | Test/dev scripts and startup fixtures | Remove, do not migrate | Disable outside test/dev and delete from customer UI |

## Deletion gates

1. Root `/` forwards or redirects to `/app/`; it must not serve the legacy HTML.
2. `shared.html` and `seller.html` have no remaining public link or Electron entry point.
3. The React source and generated bundle contain no `/api/simulation/*` or `/api/system/seats/*` call.
4. Admin review, readiness and reconciliation have authenticated React views and tests.
5. Electron loads `/app/` and distribution verification rejects legacy entry points.
6. Backend tests prove that removing simulation controllers does not remove marketplace, Toss, queue, auction, raffle or ticket contracts.

## API ownership after migration

- Public commerce: `/api/marketplace/*`, `/api/payments/toss/*`, `/api/queue/*`.
- Buyer account: `/api/me`, `/api/c2c/addresses`, `/api/c2c/orders`, buyer-owned marketplace order queries.
- Seller operations: owner-scoped `/api/c2c/*` and `/api/sellers/{sellerId}/*`.
- Admin operations: role-protected `/api/admin/*` and the minimum readiness/reconciliation endpoints.
- Legacy PoC: `/api/simulation/*`, direct seat APIs and dashboard reset endpoints. These must not remain in the production surface.
