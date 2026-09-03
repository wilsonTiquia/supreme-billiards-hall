# Backend Specification — Supreme Billiard Hall POS

Companion to `src/main/resources/db/migration/` (the canonical schema) and `CLAUDE.md` (style and
rules). `docs/schema.sql` is the original design baseline, kept for its rationale — see the header
on that file.
This document is the **what**. `CLAUDE.md` is the **how**.

---

## 1. Domain in one page

A customer takes a **pool table**. Staff open a **session** on it, which creates a **bill**. The
session accrues time in **segments** (one per table occupied — a mid-session transfer opens a
second segment at the new table's rate). **Pauses** are deducted. Food and drink become
**bill lines** on the bill and move **stock** through an append-only ledger. At checkout the time
charge is finalised as a TIME line, the bill totals, takes **one payment**, and issues a
**receipt**. Everything rolls up by **business day** (10:00 → 05:00 Asia/Manila).

**Configured reality:** 7 tables — 4 standard at ₱4.00/min, 3 premium at ₱5.00/min. Exact-minute
billing, no minimum, no rounding. No VAT, no BIR receipting, no senior/PWD discount. Digital
receipts only, never printed.

**Customer types** are admin-managed (`Regular`, `Friend of Owner`). When a type has
`allows_rate_override`, the employee may type **any** rate for that session — there is no floor and
no approval step. The override is recorded with the actor, the standard rate, and therefore the
foregone revenue, and it surfaces on the admin dashboard. Food and drink always charge full price;
only table time is overridable.

---

## 2. API surface

All routes under `/api`. All responses are DTOs. All mutating routes are branch-scoped to the
authenticated user and write an `audit_log` row where the rules below say so.

### Auth
| Method | Path | Notes |
|---|---|---|
| POST | `/auth/login` | username + password → session cookie |
| POST | `/auth/logout` | invalidates the session |
| GET | `/auth/me` | current user, role, branch |

### Time
| Method | Path | Notes |
|---|---|---|
| GET | `/time` | `{ serverNow }`. The client computes its clock offset from this. Every session response also carries `serverNow` so the timer needs no extra round trip. |

### Catalog — categories, products
| Method | Path | Notes |
|---|---|---|
| GET | `/categories` | active by default |
| POST/PUT | `/categories`, `/categories/{id}` | admin |
| DELETE | `/categories/{id}` | **archive**, never hard delete |
| GET | `/products` | filters: `categoryId`, `q`, `activeOnly`. Employee response **omits cost fields** |
| POST/PUT | `/products`, `/products/{id}` | admin. Changing `selling_price` does not touch history |
| DELETE | `/products/{id}` | archive |
| GET | `/products/{id}/movements` | admin. The ledger for one product — answers "why is this count wrong" |

### Pool tables
| Method | Path | Notes |
|---|---|---|
| GET | `/tables` | **the floor view.** Each table with its current session summary: elapsed minutes, running time charge, running item total, customer type. Plus `serverNow` |
| POST/PUT | `/tables`, `/tables/{id}` | admin. Owner can add tables — this is required, not optional |
| DELETE | `/tables/{id}` | archive. Reject if a session is open |
| PUT | `/tables/{id}/rate` | closes the current `pool_table_rate` row and opens a new one. Never mutates the existing row — the exclusion constraint rejects overlaps |

### Customer types
| Method | Path | Notes |
|---|---|---|
| GET/POST/PUT | `/customer-types` | admin manages; employee reads for the session-start dropdown |
| DELETE | `/customer-types/{id}` | archive |

### Sessions
| Method | Path | Notes |
|---|---|---|
| POST | `/sessions` | `{ tableId, customerTypeId, rateOverridePerMinute? }`. Creates bill + session + first segment in one transaction. Snapshots the standard rate. A second open session on the same table must return **409**, surfaced from the unique index |
| GET | `/sessions/{id}` | full state including segments, pauses, live totals, `serverNow` |
| POST | `/sessions/{id}/pause` | `{ reason? }`. Rejects if already paused |
| POST | `/sessions/{id}/resume` | rejects if not paused |
| POST | `/sessions/{id}/transfer` | `{ toTableId, reason? }`. Closes the current segment, opens a new one at the **new table's** rate. Same bill |
| POST | `/sessions/{id}/close` | ends segments, computes billed minutes, writes the TIME line(s) |

**Billed minutes** = sum of segment durations − sum of pause durations, per segment, exact minutes.
One TIME `bill_line` per segment so a transferred session shows both tables and both rates.

**TIME line encoding:** `quantity = 1`, `unit_price` = the whole charge for that segment,
`billed_minutes` = the minutes, rate in the description. **Not** `quantity = minutes` with
`unit_price = rate` — `unit_price` is `numeric(12,2)` and a rate carries four decimals, so a
₱200/hour table (₱3.3333/min) would store 3.33 and undercharge by roughly 30 centavos over 90
minutes. The rate is not lost: `session_segment.rate_per_minute` holds it structurally at full
`numeric(10,4)` precision, and the segment is the billing source of truth.

### Bills
| Method | Path | Notes |
|---|---|---|
| GET | `/bills/{id}` | lines, totals, session summary. Cost fields admin-only |
| POST | `/bills/{id}/lines` | `{ productId, quantity }`. Snapshots name, `selling_price`, `avg_cost`. Writes a `SALE` stock movement and updates `qty_on_hand` **in the same transaction**. Below-zero stock returns the line plus a warning flag — it does not fail |
| POST | `/bills/{id}/lines/{lineId}/void` | `{ reason }` — **required**. Marks the line voided (retained), writes a `SALE_VOID` movement returning stock, writes `audit_log` |
| POST | `/bills/{id}/merge` | `{ absorbedBillId }`. Repoints the absorbed bill's sessions and lines, sets it `MERGED`, writes `bill_merge_event` with the full before/after snapshot **in the same transaction** |
| POST | `/bills/{id}/unmerge` | reverses the live merge event. Rejects once paid |
| GET | `/bills/{id}/checkout` | preview: finalised totals, no writes |
| POST | `/bills/{id}/payment` | see below |
| GET | `/bills/{id}/receipt` | the stored `receipt.payload` snapshot |

### Payment — the most safety-critical endpoint
`POST /bills/{id}/payment`

```
{ method: CASH|GCASH|MAYA, amount, tendered?, referenceNo?,
  duplicateOverride?: boolean, idempotencyKey, billVersion }
```

Rules:
- **CASH** requires `tendered`; the server computes `change_given`. **GCASH/MAYA** require
  `referenceNo` and must not send `tendered`.
- `amount` must equal the bill total. One payment per bill — a second attempt returns 409.
- `idempotencyKey` is unique per branch. A replay returns the **original** payment, not an error and
  not a second charge.
- `billVersion` must match `bill.version` or return 409 — this is what stops two counter tabs
  settling the same bill twice.
- Duplicate `referenceNo` in the same branch returns a **warning**, not a rejection. Re-submitting
  with `duplicateOverride: true` proceeds and records `duplicate_override_by`.
- On success, in one transaction: write the payment, allocate `receipt_no` from
  `branch.next_receipt_no` under row lock, close the bill, write the `receipt` snapshot.

### Quick sale
| Method | Path | Notes |
|---|---|---|
| POST | `/quick-sales` | `{ customerTypeId, lines[], payment }`. A bill with no session, settled immediately. Same stock and payment rules |

### Photos
| Method | Path | Notes |
|---|---|---|
| POST | `/payments/{id}/photo` | multipart. Writes to the configured volume path, stores path + SHA-256 + byte count. Kept indefinitely, **admin-only to read** |
| GET | `/payments/{id}/photo` | admin only |

### Business day
| Method | Path | Notes |
|---|---|---|
| GET | `/business-day/current` | the current business date and whether it can be closed |
| GET | `/business-day/{date}/open-sessions` | what is blocking close |
| POST | `/business-day/{date}/cash-count` | `{ countedCash, note? }`. Server computes `expected_cash` from cash payments for that business date and stores counted, expected and variance |
| POST | `/business-day/{date}/close` | **rejects while any session is open**, listing them. The auto-close safety net closes sessions at business-day end capped at that time, sets `close_kind = AUTO_END_OF_DAY` and `needs_review = true` |

### Stock
| Method | Path | Notes |
|---|---|---|
| POST | `/stock/deliveries` | header + lines with `unitCost`. Recomputes `avg_cost` as a moving weighted average |
| POST | `/stock/corrections` | `{ productId, newQuantity, note }` — note **required**. Writes a `CORRECTION` movement for the delta |
| POST | `/stock/comps` | `{ productId, quantity, note }` → `STAFF_COMP` |
| GET | `/stock/low` | at or below the branch `low_stock_threshold` (10), plus anything negative |

### Reports — admin only
| Method | Path | Notes |
|---|---|---|
| GET | `/reports/daily?date=` | gross sales, cost, gross profit; table-time vs product split; sales by hour; table utilisation %; top items; payment mix; voids and friend-rate comps; low/negative stock; per-employee breakdown. All compared against the previous business day |
| GET | `/reports/daily.pdf?date=` | same figures, same query, OpenPDF |
| GET | `/audit?entity=&actor=&from=&to=` | audit log, paged |

Write reporting queries as **native SQL grouped on `business_date`**. Do not attempt these in JPQL.

---

## 3. Build order

Matches the five-day sprint. Each day ends with a working vertical slice.

**Day 1 — Foundation and catalog.** Flyway baseline from `schema.sql`; Spring Security with session
auth; branch scoping in a base repository; global exception handler; categories, products and pool
tables CRUD including add-a-table and rate changes; seed data.
*Done when: you can log in and manage the catalog and the tables.*

**Day 2 — Sessions and the timer.** Start/close, segments, pause/resume, the floor view with live
totals, `serverNow` on every response, customer types and the friend-rate override. The one-open-
session-per-table 409.
*Done when: you can open a table, watch the clock, and close it with the right amount.*

**Day 3 — Orders and inventory.** Add and void lines, the stock ledger in the same transaction,
below-zero warning, deliveries, corrections, comps, low-stock.
*Done when: drinks and food attach to a table and inventory moves correctly.*

**Day 4 — Checkout and close.** Totals, payment with idempotency and optimistic locking, receipt
snapshot, photo upload, quick sale, end-of-day blocking, auto-close, cash count.
*Done when: a full night can be run end to end.*

**Day 5 — Reports and deploy.** Daily report query, PDF, audit endpoint, Docker image, deploy.

**Deferred to the following week:** table transfer, merge/unmerge, dashboard charts, audit views.
If the week slips, cut in this order: merge/unmerge → transfer → PDF → charts → photo upload →
pause. **Do not cut the stock ledger or the cash count.**

---

## 4. Acceptance tests

Write these as integration tests against a real Postgres (Testcontainers, or a local database if
that is faster to stand up). They are the ones that matter:

1. **The worked trace.** Open Table 3 (₱4.00/min) at 20:00, order 2 beers + 1 sisig at 20:45, void
   one beer at 20:52, close at 21:30, pay by GCash. Assert: time ₱360.00, items ₱270.00, gross
   **₱630.00**, cost ₱157.50, profit **₱472.50**, business_date 2026-08-31, beer on hand 47, the
   voided line still present and excluded.
2. **Price change does not rewrite history.** Re-run the daily report after raising the beer to
   ₱100 — the figures above must be unchanged.
3. **Business day boundary.** 09:59 → previous day; 10:00 → new day; 02:00 and 04:59 → previous day.
4. **Two sessions on one table** → 409.
5. **Double checkout.** Same idempotency key twice → one payment, the second returns the first.
   Stale `billVersion` → 409.
6. **Void requires a reason** → 400, and stock is returned when it succeeds.
7. **Employee cannot see cost.** The product and bill DTOs for an EMPLOYEE contain no cost or profit
   field at all.
8. **Branch scoping.** A user in branch A cannot read or write anything in branch B.
9. **Pause is not billed.** 90 minutes elapsed with a 10-minute pause bills 80 minutes.
10. **End-of-day close is blocked** while a session is open, and lists it.

---

## 5. Local setup

```yaml
# docker-compose.yml — database only; run the app natively for hot reload and the debugger
services:
  db:
    image: postgres:18
    environment:
      POSTGRES_DB: supreme
      POSTGRES_USER: supreme
      POSTGRES_PASSWORD: supreme
    ports: ["5432:5432"]
    volumes: ["pgdata:/var/lib/postgresql/data"]
volumes:
  pgdata:
```

Split the canonical SQL into Flyway migrations:
- `V1__baseline.sql` — everything up to and including the first `COMMIT;`
- `V2__seed.sql` — the seed block (branch, settings, users, customer types, tables, rates,
  categories)

Replace the placeholder password hashes in the seed with real bcrypt hashes on first run.
