# API Contract — Supreme Billiard Hall POS

The handoff to the frontend phase. Every route below exists and was exercised over HTTP against
PostgreSQL 18; field names are taken from the DTO classes, not from memory.

Companion to `src/main/resources/db/migration/` (the canonical data model) and
`docs/BACKEND-SPEC.md` (business rules).

---

## 0. The two conventions you must know

1. **Routes are `/api/v1/...`**, not `/api/...`. `BACKEND-SPEC.md` §2 writes them without the
   version prefix; the running server has it. The spec's paths are relative to `/api/v1`.
2. **POST returns `200 OK`, never `201 Created`** — including for creates. This follows the
   `Category` reference slice the backend was written against. Do not code a `201` branch.

Both are deliberate consistency decisions, not oversights.

---

## 1. Envelope

Every JSON response is wrapped:

```json
{ "data": <payload or null>, "message": "human-readable", "success": true|false }
```

On some errors a fourth field appears. **It is absent unless present** — do not expect the key:

```json
{ "data": null, "message": "...", "success": false, "code": "STALE_BILL_VERSION" }
```

**The two exceptions:** `GET /api/v1/payments/{id}/photo` and `GET /api/v1/products/{id}/image`
return raw image bytes with the stored content type. There is nothing to wrap an image in.

### Error codes

| `code` | HTTP | Meaning | What the UI should do |
|---|---|---|---|
| `STALE_BILL_VERSION` | 409 | The bill changed since you loaded it | Reload the bill, show the new total, ask again |
| `DUPLICATE_PAYMENT_REFERENCE` | 409 | That reference is already recorded in this branch | Offer "record anyway" → resend with `duplicateOverride: true` |

Every other error carries no `code`; show `message`.

### Status codes

| Status | When |
|---|---|
| 200 | Success, including creates and idempotent replays |
| 400 | Bean validation failed, or a malformed UUID / date in the path |
| 401 | Not authenticated, or bad credentials on login |
| 403 | Authenticated but not an ADMIN on an admin route |
| 404 | Not found — **including a row that exists in another branch** |
| 409 | Conflicts with current state: duplicate name, session already paused, bill already paid, stale version, database constraint |
| 500 | A bug. Report it |

**404 for cross-branch access is deliberate**: a resource in another branch must not exist as far
as you are concerned. Note the precedence — on an ADMIN-only route the role check runs first, so an
employee gets **403** whether or not the row is in their branch.

---

## 2. Auth and session

Authentication is a **server-side session cookie** (`JSESSIONID`), not a JWT. Send
`credentials: 'include'` on every request. CSRF is disabled; the client is a same-origin SPA.

| Method | Path | Who |
|---|---|---|
| POST | `/api/v1/auth/login` | anyone |
| GET | `/api/v1/auth/me` | authenticated |
| POST | `/api/v1/auth/logout` | authenticated |
| PUT | `/api/v1/auth/branch` | ADMIN |

**POST `/auth/login`** → `{ "username", "password" }`

```json
{ "id": "uuid", "username": "counter", "fullName": "Front Counter",
  "role": "EMPLOYEE", "branchId": "uuid", "branchName": "Supreme Billiard Hall" }
```

`role` is `EMPLOYEE` or `ADMIN`. Bad credentials → 401.

**GET `/auth/me`** returns the same shape. `branchId` and `branchName` can be **null** for a global
admin when more than one branch is active and none is selected — in that case every other call
returns 409 `No branch selected` until `PUT /auth/branch` is called. With one branch (today) this
never happens.

**PUT `/auth/branch`** → `{ "branchId": "uuid" }`. ADMIN only, and only for an admin not bound to a
branch. The branch switcher UI is deferred; this is the mechanism under it.

**POST `/auth/logout`** — handled by Spring Security; returns the envelope with `data: null`.

---

## 3. Time

| Method | Path | Who |
|---|---|---|
| GET | `/api/v1/time` | authenticated |

```json
{ "serverNow": "2026-08-31T12:37:26.610539Z" }
```

**The server is the only clock.** Compute an offset from this once, render counters locally from
it, and never send a duration or an amount back. Session and floor-view responses carry
`serverNow` too, so the timer needs no extra round trip.

All timestamps everywhere are **ISO-8601 UTC** (`...Z`). Render in Asia/Manila locally.

---

## 4. Catalog

### Categories

| Method | Path | Who |
|---|---|---|
| GET | `/api/v1/categories` | authenticated |
| POST | `/api/v1/categories` | ADMIN |
| PUT | `/api/v1/categories/{id}` | ADMIN |
| DELETE | `/api/v1/categories/{id}` | ADMIN |

Request: `{ "name": "Beer", "sortOrder": 1 }` — `name` required, ≤100 chars; `sortOrder` optional.
Response: `{ "id", "name", "sortOrder" }`.

`DELETE` **archives**; it never hard-deletes, and the archived name becomes reusable.

### Products

| Method | Path | Who |
|---|---|---|
| GET | `/api/v1/products?categoryId=&q=&activeOnly=true&includeArchived=false` | authenticated |
| POST | `/api/v1/products` | ADMIN |
| PUT | `/api/v1/products/{id}` | ADMIN |
| DELETE | `/api/v1/products/{id}` | ADMIN |
| POST | `/api/v1/products/{id}/unarchive` | ADMIN |
| GET | `/api/v1/products/{id}/movements` | ADMIN |
| POST | `/api/v1/products/{id}/image` | ADMIN |
| GET | `/api/v1/products/{id}/image` | authenticated |
| DELETE | `/api/v1/products/{id}/image` | ADMIN |

Request: `{ "name", "categoryId"?, "sellingPrice", "isActive"? }`. `avgCost` and `qtyOnHand` are
**not settable** — stock moves only through the ledger, and the image moves only through its own
routes below.

**Response shape depends on the caller's role.** An EMPLOYEE receives:

```json
{ "id", "name", "categoryId", "sellingPrice", "qtyOnHand", "isActive",
  "archivedAt", "imageSha256" }
```

An ADMIN receives the same **plus `avgCost`**. The cost key is *absent* for an employee, not null —
do not write UI that expects it and hides it.

There is **no `sku`**. It was removed on 1 September 2026; the hall identifies a product by name.

#### Archiving and restoring

`DELETE` **archives** — it is never a hard delete, because historical bill lines reference the
row. An archived product leaves the POS grid, and adding it to a bill (or quoting it) is a 409.
Its snapshot on past bills is unaffected, and **its image file is kept**, so a restore brings the
picture back with it.

`archivedAt` is `null` for a live product and set for an archived one.

**`includeArchived=true`** (default `false`) returns archived rows too. The server honours it for
an **ADMIN only** and ignores it for an employee, so an archived product can never reach the
counter. The admin catalogue's "Include archived" toggle is the only caller — without it,
archiving put a product beyond reach of the very screen that has to undo it.

Archiving and restoring each write an `audit_log` row — `PRODUCT_ARCHIVED` and
`PRODUCT_RESTORED` — carrying the actor and a before/after snapshot including the name. Taking
something off the menu is a bigger change than editing its price, which already wrote one.

**`POST /products/{id}/unarchive`** (ADMIN) clears `archivedAt` and returns the product.

- Not archived → **409** `"Product 'X' is not archived."`
- The name was taken while it was away → **409** naming the fix: *"Cannot restore 'X': another
  product is using that name now. Rename that one first, then restore this."* `product_name_key`
  covers unarchived rows only, so the name becomes reusable the moment a product is archived.
  This is the collision that would otherwise surface as a raw constraint violation.

#### Product images

`imageSha256` is `null` when the product has no picture, which is the ordinary case — **every
screen must render without one.** When it is set, it is also the cache-buster for the image URL:
request `GET /products/{id}/image?v={imageSha256}` so a replaced picture is picked up at once and
an unchanged one is served from the browser cache.

| | |
|---|---|
| POST | `multipart/form-data`, field name **`file`**. ADMIN. JPEG, PNG or WebP, **2 MB max**. Uploading over an existing picture replaces it and deletes the old file. Returns `{ "productId", "imageSha256", "imageBytes" }`. |
| GET | **Raw bytes**, not the envelope. Any authenticated user — this differs deliberately from payment photos, because the counter needs the picture to find the product. Sends `ETag: "<sha256>"` and `Cache-Control: private, max-age=86400`; a repeat request answers **304**. A product with no picture is **404**. |
| DELETE | ADMIN. Removes the file and clears the three columns. Enveloped, `data: null`. A product with no picture is **404**. |

A rejected upload is a **409** carrying a message written for a human — `"That image is 4.2 MB.
The limit is 2 MB — scale it down and try again."` or `"That file is not an image the POS can
show. Use a JPEG, PNG or WebP."` A file so large the container refuses to read it at all is a
**400** with the same limit named. Neither is ever a 500. Show `message`.

`GET /products/{id}/movements` (ADMIN) returns the ledger for one product, newest first:
`{ id, productId, productName, reason, quantityDelta, qtyAfter, unitCost, billLineId, deliveryId,
note, occurredAt, businessDate }`. `reason` ∈ `SALE | SALE_VOID | DELIVERY | CORRECTION | STAFF_COMP`.

### Customer types

| Method | Path | Who |
|---|---|---|
| GET | `/api/v1/customer-types` | authenticated |
| POST / PUT / DELETE | `/api/v1/customer-types[/{id}]` | ADMIN |

`{ "id", "name", "allowsRateOverride", "isDefault", "sortOrder" }`. The session-start dropdown
reads this. `allowsRateOverride` is what enables the friend-rate field.

---

## 5. Floor view and tables

| Method | Path | Who |
|---|---|---|
| GET | `/api/v1/tables` | authenticated |
| POST | `/api/v1/tables` | ADMIN |
| PUT | `/api/v1/tables/{id}` | ADMIN |
| PUT | `/api/v1/tables/{id}/rate` | ADMIN |
| DELETE | `/api/v1/tables/{id}` | ADMIN |

**GET `/tables` is the floor view** — the main POS screen. Note it is an *object*, not an array:

```json
{ "tables": [
    { "id": "uuid", "name": "Table 3", "tableNumber": 3, "isActive": true,
      "ratePerMinute": 4.0,
      "session": null }
  ],
  "serverNow": "2026-08-31T12:37:26.610539Z" }
```

`session` is `null` when the table is free. When occupied:

```json
"session": { "sessionId", "billId", "poolTableName", "status", "customerTypeId", "customerTypeName",
             "openedAt", "billedMinutes", "billedSeconds", "ratePerMinute",
             "timeAmount", "itemTotal", "runningTotal" }
```

`status` ∈ `OPEN | PAUSED`. `billedMinutes` and `timeAmount` are the **running** figures the
customer will actually be charged — elapsed less pauses, floored to whole minutes.

`billedSeconds` is the *same* elapsed, exact to the second and **not floored**. Nothing is ever
billed on it. It exists so the client can animate its counter from an exact number: anchoring a
local tick on the floored minute leaves the display up to 59 seconds behind real elapsed, which
shows up as the timer jumping backwards on every refresh and then forwards again when the server's
minute catches up. `billedMinutes === Math.floor(billedSeconds / 60)`, always.

`poolTableName` names the table the session is on. It is redundant on the floor view, where the
card already says so, but the same summary shape is returned by `/business-day/{date}/open-sessions`
and inside `GET /bills/{id}` — where without it there is no way to say *which* table is still
running.

`runningTotal` is `timeAmount + itemTotal`, added **server-side**. It exists because the bill row
carries no total until checkout writes the TIME lines — `bill.totalAmount` reads `0.00` on a live
session — so this is the only trustworthy answer to "how much so far?". Display it; never add the
two components together in the client.

Poll this endpoint for the floor; it is one query regardless of how many tables are occupied.

**POST/PUT `/tables`** → `{ "name", "tableNumber"?, "ratePerMinute", "isActive"? }`. `ratePerMinute`
is required: a table with no rate cannot host a session. On PUT, a changed rate opens a new rate
period exactly as the dedicated endpoint does.

**PUT `/tables/{id}/rate`** → `{ "ratePerMinute", "effectiveFrom"? }`. Closes the current rate
period and opens a new one. Never mutates history. A backdated overlap → 409.

**DELETE** archives, and is **rejected with 409 while a session is open** on that table.

---

## 6. Sessions — the timer

| Method | Path | Who |
|---|---|---|
| POST | `/api/v1/sessions` | authenticated |
| GET | `/api/v1/sessions/{id}` | authenticated |
| POST | `/api/v1/sessions/{id}/pause` | authenticated |
| POST | `/api/v1/sessions/{id}/resume` | authenticated |
| POST | `/api/v1/sessions/{id}/close` | authenticated |

**POST `/sessions`** → `{ "tableId", "customerTypeId", "rateOverridePerMinute"?, "rateOverrideReason"? }`

No start time — the server stamps it. Creates the bill, the session and its first segment in one
transaction.

- A **second open session on the same table returns 409** `That table already has an open session`.
  This comes from a database index, so it is race-proof: if two tabs both press start, exactly one
  wins.
- `rateOverridePerMinute` is only accepted when the chosen customer type has
  `allowsRateOverride: true`; otherwise 409. Any value is allowed, with no floor and no approval.

**Session response** (returned by all five routes):

```json
{ "id", "billId", "poolTableId", "poolTableName", "customerTypeId", "customerTypeName",
  "status", "openedAt", "closedAt", "closeKind",
  "standardRatePerMinute", "rateOverridePerMinute",
  "billedMinutes", "billedSeconds", "timeAmount", "itemTotal", "runningTotal",
  "segments": [ { "id", "poolTableId", "poolTableName", "seq", "ratePerMinute",
                  "startedAt", "endedAt", "billedMinutes", "amount" } ],
  "pauses":   [ { "id", "pausedAt", "resumedAt", "reason" } ],
  "serverNow": "..." }
```

`status` ∈ `OPEN | PAUSED | CLOSED | AUTO_CLOSED | VOIDED`.
`closeKind` ∈ `MANUAL | AUTO_END_OF_DAY | null`.

While the session is live, `billedMinutes` and `timeAmount` are recomputed on every read. Once
closed they are the stored finals. **Billing is exact-minute, truncated** — 90m59s bills 90 minutes,
never 91. Pauses are deducted.

`billedSeconds` carries the unfloored elapsed for the client's counter, as in §5. On a closed
session it reports exactly `billedMinutes × 60`: there is nothing still running to be precise
about, and the stored minutes are authoritative.

`runningTotal` is `timeAmount + itemTotal`, computed server-side, as in §5.

**pause** takes `{ "reason"? }`; pausing an already-paused session → 409, as does resuming one that
is not paused, or acting on a closed session. Closing while paused is allowed and ends the pause at
the close instant.

**close** ends the segments, computes billed minutes and writes one TIME line per segment. It does
**not** take payment.

---

## 7. Bills, orders and voids

| Method | Path | Who |
|---|---|---|
| GET | `/api/v1/bills?businessDate=&page=0&size=50` | **ADMIN** |
| GET | `/api/v1/bills/{id}` | authenticated |
| POST | `/api/v1/bills/{id}/lines` | authenticated |
| POST | `/api/v1/bills/{id}/lines/{lineId}/void` | authenticated |

**GET `/bills?businessDate=`** — one business day's **settled** sales, newest receipt first,
paged (`size` capped at 200). `businessDate` is required, `YYYY-MM-DD`.

```json
{ "content": [ { "id", "receiptNo", "closedAt", "totalAmount",
                 "method", "takenByUsername", "quickSale" } ],
  "page", "size", "totalElements", "totalPages" }
```

`quickSale` is true when the bill never carried a session. **No cost and no profit**, even
though the route is ADMIN — this list is for finding a receipt, and margin belongs on the
dashboard. Open the stored snapshot with `GET /bills/{id}/receipt`.

**GET `/bills/{id}`**

```json
{ "id", "status", "customerTypeId", "customerTypeName", "openedAt", "closedAt",
  "businessDate", "version",
  "subtotalTime", "subtotalItems", "totalAmount",
  "lines": [ { "id", "lineKind", "seq", "productId", "sessionId", "description",
               "unitPrice", "quantity", "billedMinutes", "lineTotal",
               "voidedAt", "voidReason" } ],
  "sessions": [ <session summary, as in the floor view> ] }
```

An ADMIN additionally receives `totalCost` and `grossProfit` at the top level, and `unitCost` and
`lineCost` on each line. An EMPLOYEE receives neither key.

**Lines are returned one row per `bill_line`, and always will be.** Four of the same beer are
four rows. The UI groups identical non-voided lines for display — "4 × San Miguel Pale Pilsen
₱800.00" — but that is presentation only, done at render in `lib/billLines.ts`; the API and the
database keep them separate because each row is an individually voidable record with its own
actor, timestamp and `seq`.

**Voided lines are never grouped**, anywhere. A void is an event someone has to see, with its
reason, not a number to fold away. The same rule holds on the receipt: the stored jsonb payload
keeps its faithful line-by-line record — it is the legal document — and grouping happens only
when it is rendered, so a receipt written last month still renders correctly today.

`lineKind` ∈ `TIME | PRODUCT`. `status` ∈ `OPEN | CLOSED | VOIDED | MERGED`.

**Voided lines stay in `lines`** with `voidedAt` set, and are excluded from every total. Render them
struck through; do not filter them out silently.

**TIME lines** carry `quantity: 1`, `unitPrice` = the whole charge for that segment, and the rate in
`description` (e.g. `"Table 3 - 90 min @ 4.0000/min"`). Do not compute minutes × unitPrice.

**POST `/bills/{id}/lines`** → `{ "productId", "quantity" }`. No price: the server snapshots the
name, selling price and cost at the moment of sale.

```json
{ "line": { ... }, "belowZeroStock": false, "qtyOnHand": 47.0, "warning": null }
```

**Selling below zero succeeds.** When `belowZeroStock` is `true`, `warning` carries a message and the
line is still recorded. Show the warning; do not block the sale. A stale count must never stop a
paying customer.

**GET `/bills/unsettled`** — every bill still `OPEN` with no live session, **newest first and not
scoped to a business day**. A day closes on open *sessions*, not unpaid bills, so an unsettled bill
outlives the close; scoping this to today would hide it the moment the date rolled and the money
would never be collected. Each row carries its own `businessDate` so an old one reads as old.

```json
[ { "id", "openedAt", "sessionEndedAt", "businessDate", "customerTypeName",
    "tableNames": ["Table 3"], "totalAmount": 5054.00 } ]
```

Bills totalling `0.00` are excluded: payment validation requires at least 0.01, so they can never
be settled and would sit in the floor strip for ever. Carries no cost field, so one shape serves
both roles.

**POST `/bills/{id}/lines/{lineId}/void`** → `{ "reason" }` — **required**, non-blank, or 400. The
line is retained, the stock is returned, and an audit row is written. Voiding twice → 409. A TIME
line cannot be voided on its own → 409.

Adding or voiding lines on a bill that is not `OPEN` → 409.

---

## 8. Checkout and payment — the safety-critical path

| Method | Path | Who |
|---|---|---|
| GET | `/api/v1/bills/{id}/checkout` | authenticated |
| POST | `/api/v1/bills/{id}/payment` | authenticated |
| GET | `/api/v1/bills/{id}/receipt` | authenticated |

**GET `/checkout`** previews and **writes nothing**:

```json
{ "bill": { ...as GET /bills/{id}... }, "canCheckout": true, "blockers": [] }
```

`blockers` explains a `false`, e.g. `"Close the table session before charging: 1 still running."`

**POST `/payment`**

```json
{ "method": "CASH|GCASH|MAYA",
  "amount": 630.00,
  "tendered": 1000.00,
  "referenceNo": "GC-001",
  "duplicateOverride": false,
  "idempotencyKey": "client-generated-per-attempt",
  "billVersion": 0 }
```

Rules the UI must respect:

- **`amount` must equal the bill total exactly.** No partial, no overpayment. Cash overpayment is
  `tendered`, never `amount`. Mismatch → 409.
- **CASH** requires `tendered` ≥ amount and must **not** send `referenceNo`. The server computes
  `changeGiven`.
- **GCASH / MAYA** require `referenceNo` and must **not** send `tendered`.
- **`billVersion`** must be the `version` you read from the bill. Mismatch → 409
  `STALE_BILL_VERSION`. Reload and re-confirm the total with the customer.
- **`idempotencyKey`** — generate once per checkout attempt and **reuse it on retry**. A replay
  returns `200` with the original payment and `replayed: true`. The money moves once.
- **Duplicate `referenceNo`** → 409 `DUPLICATE_PAYMENT_REFERENCE`. Offer "record anyway"; resend the
  same request with `duplicateOverride: true`, **reusing the same `idempotencyKey`**. The
  duplicate-reference check throws inside the transaction before the payment row is persisted, so
  the rollback leaves the key unused — reusing it is safe and keeps the "Record anyway" button
  protected against a double-click.

Response:

```json
{ "id", "billId", "method", "amount", "tendered", "changeGiven", "referenceNo",
  "receiptNo", "takenAt", "businessDate",
  "duplicateReferenceOverridden": false, "replayed": false }
```

**GET `/receipt`** returns the stored snapshot, never re-rendered:
`{ "id", "billId", "receiptNo", "issuedAt", "payload": { ... } }`. `payload` is the customer-facing
document: lines, subtotals, total, method, tendered, change, reference. It carries no cost.

### Give-aways (batched comps)

| Method | Path | Who |
|---|---|---|
| POST | `/api/v1/stock/comps/batch` | authenticated |

```json
{ "lines": [ { "productId": "uuid", "quantity": 2 } ], "note": "Round for table 3, on the owner" }
```

Several products given away at once. **One transaction — all or nothing**: a basket of four
never half-commits.

A give-away is **not a sale**. It writes `STAFF_COMP` movements and nothing else: no bill, no
payment, no receipt, and it never reaches revenue. It is stock leaving the building for free.
The dashboard's losses tile values it at current average cost.

`note` is **required** (`@NotBlank`) and is written onto every movement, so a single ledger row
still explains itself. One note covers the batch because it is one decision by one person. The
actor is taken from the session. An archived product is a 409, like anywhere else.

Returns the movements: `[ { id, productId, productName, reason, quantityDelta, qtyAfter, ... } ]`.

### Quick sale

| Method | Path | Who |
|---|---|---|
| POST | `/api/v1/quick-sales/quote` | authenticated |
| POST | `/api/v1/quick-sales` | authenticated |

**`POST /quote`** prices a basket without selling it. Reading it writes nothing — no bill, no
stock movement, no payment.

```json
{ "lines": [ { "productId": "uuid", "quantity": 2 } ] }
```

→ `{ "lines": [ { "productId", "description", "unitPrice", "quantity", "lineTotal" } ], "total" }`

It exists so the counter screen never multiplies a price by a quantity itself
(frontend/CLAUDE.md §2): the `amount` the sale then sends must equal the bill total **exactly**,
and both figures come from here. Lines are priced from the live product row, the same way
`addLine` snapshots them. An archived product is a 409, matching what the sale would do. No cost
fields — an employee reads this.

**`POST /quick-sales`** — a bill with no session, rung up and settled in one transaction. Same
stock and payment rules.

```json
{ "customerTypeId": "uuid",
  "lines": [ { "productId": "uuid", "quantity": 2 } ],
  "payment": { ...as above... } }
```

`payment.billVersion` is required by validation but ignored: the bill is created and settled in
the same transaction, so there is no earlier version the client could have read.

**Re-sending a used `idempotencyKey` here is a 409** (`"That checkout has already been
recorded"`), not the replay that `POST /bills/{id}/payment` returns. The unique constraint stops
the second charge and the whole transaction rolls back, so the money still moves exactly once and
nothing is left behind — but the client gets the refusal rather than the original payment. Show
the message.
`billVersion` is required by validation but ignored — the bill has no earlier version to read.
Returns the same payment response.

### Payment photos

| Method | Path | Who |
|---|---|---|
| POST | `/api/v1/payments/{id}/photo` | authenticated |
| GET | `/api/v1/payments/{id}/photo` | **ADMIN** |

POST is `multipart/form-data` with field name **`file`**. Returns
`{ "paymentId", "photoSha256", "photoBytes" }`. One photo per payment; a second → 409.

GET returns **raw bytes**, not the envelope. Employees get 403.

---

### Losses drill-down

| Method | Path | Who |
|---|---|---|
| GET | `/api/v1/reports/losses?businessDate=` | **ADMIN** |

The rows behind the dashboard's three loss figures, for one business day. `businessDate` is
optional and defaults to the night currently running, same as `/reports/daily`.

```json
{ "businessDate",
  "comps":       { "compQuantity", "compEstimatedCost",
                   "lines": [ { "productName", "quantity", "reason",
                                "actorUsername", "occurredAt", "estimatedCost" } ] },
  "voids":       { "voidCount", "voidAmount",
                   "lines": [ { "description", "quantity", "lineTotal", "reason",
                                "actorUsername", "voidedAt", "billId", "receiptNo" } ] },
  "friendRates": { "overrideSessions", "forgoneRevenue",
                   "lines": [ { "poolTableName", "standardRatePerMinute", "chargedRatePerMinute",
                                "billedMinutes", "forgoneRevenue", "actorUsername",
                                "reason", "openedAt" } ] } }
```

Each list is newest first. **Each section repeats its own tile's figure under the same field
name**, summed from the very rows listed beneath it — so a mismatch between the detail and
`/reports/daily`'s `losses` is a bug, and the screen says so rather than showing both quietly.

The predicates mirror the daily report exactly, including that comps are dated by the stock
movement's `business_date` while voids and friend rates are dated by their bill's.

`receiptNo` is null while the voided line's bill is still open. `reason` can be null on a friend
rate (the field is optional at session start); it is never null on a comp, which the schema
requires.

## 9. Business day

| Method | Path | Who |
|---|---|---|
| GET | `/api/v1/business-day/current` | authenticated |
| GET | `/api/v1/business-day/uncounted` | authenticated |
| GET | `/api/v1/business-day/{date}/open-sessions` | authenticated |
| GET | `/api/v1/business-day/{date}/cash-count` | authenticated |
| POST | `/api/v1/business-day/{date}/cash-count` | authenticated |
| PUT | `/api/v1/business-day/{date}/cash-count` | **ADMIN** |
| POST | `/api/v1/business-day/{date}/close` | authenticated |

**`POST /business-day/{date}/recount`** — counts the drawer again after the day was closed and
then traded on. Authenticated, **not** ADMIN: the shift that closed up and served a straggler has
to be able to finish the night. Refused unless the day is closed *and* something has been sold
since. Recomputes `expectedCash` from the payments as they stand, takes the new count, reopens the
day, and writes a `CASH_COUNT_SUPERSEDED` audit row carrying the original count, its variance and
who closed it. `POST /close` then signs it off again.

`GET /{date}/cash-count` carries `salesAfterClose`, `amountAfterClose` and `cashAfterClose` —
trading recorded on that business day *after* `closedAt`. The night runs to 05:00, so a sale at
03:30 lands on a day closed at 03:00; `expectedCash` was frozen at the count and no longer
describes the drawer. **When `salesAfterClose > 0` the variance is measured against a total that
has moved, and no screen may show it without saying so.**

### Charging less table time than was played

| Method | Path | Who |
|---|---|---|
| POST | `/api/v1/sessions/{id}/billed-minutes` | authenticated |

`{ "billedMinutes": 120, "reason": "Owner's friend, two hours as agreed" }` — taken at checkout,
before payment. `reason` is required.

**It may only go down.** Charging above the actual minutes is a 409 naming both figures: it would
be an overcharge, not a discount. `session_segment` and `table_session.billed_minutes` are never
rewritten — they are the record of what happened at the table. The override, its actor and its
reason live in their own columns, the session's TIME lines are re-priced to the reduced figure
(apportioned across segments so each keeps its own snapshotted rate), and the line description
states the adjustment: `"Table 1 - 120 min @ 4.00/min (reduced from 180 min)"`.

The forgone revenue — (actual − charged) × rate — joins comps and friend rates in
`GET /reports/losses` under `timeReductions`, and on the dashboard's losses tile as
`reducedSessions` / `timeReductionForgone`.

**`GET /uncounted`** → `[ { "businessDate", "bills" } ]` — earlier business days that traded and
were never counted, oldest first. The 05:00 job auto-closes leftover *sessions* but not the day
itself, so an uncounted night stays open and silent: no drawer reconciliation, and nothing
anywhere saying so. The end-of-day screen surfaces these the same way it surfaces unsettled
bills — as information, never as a block on closing tonight.

It carries **no cash figure on purpose.** Expected cash is not readable before counting, and
putting a total on a notice anyone can see would defeat the blind count.

`{date}` is `YYYY-MM-DD`. Response for `current` and `open-sessions`:

```json
{ "businessDate": "2026-08-31", "canClose": true,
  "openSessions": [ <session summary> ], "serverNow": "..." }
```

**The business day runs 10:00 → 05:00 Asia/Manila.** The date rolls at **10:00**: everything from
00:00 to 09:59 belongs to the previous business day. A 02:00 sale reports under the night before.
Never compute this in the client — read `businessDate` from the server.

**GET `/cash-count`** returns the recorded count, or `data: null` when the day has not been counted
yet — that is an ordinary state of the evening, not a missing resource, so it is not a 404. Without
it the closer cannot see a variance entered earlier in the shift by someone else.

**POST `/cash-count`** → `{ "countedCash", "openingFloat"?, "note"? }`. `cashSales` is **computed
server-side** from that day's cash payments and frozen; do not send it. There is deliberately no
way to read it before counting: a blind count is the control, since a counter who can see the
target first is not really counting.

**The drawer does not start empty.** It holds a change float at open, so:

    expectedCash = openingFloat + cashSales
    variance     = countedCash - expectedCash

`openingFloat` is optional and almost always omitted — leave it out and the server applies the
branch standard (`standardCashFloat`, see §Settings). Send it only when the night genuinely ran a
different float; the server compares it to the standard itself and sets `floatOverridden`, so the
client cannot declare a night normal that was not. An override also writes a `CASH_FLOAT_OVERRIDDEN`
audit row carrying the standard, the float used and the resulting variance.

The float is **stored on the row**, not looked up later: a reconciliation from March has to still
add up in March's terms after the standard changes.

```json
{ "id", "businessDate", "openingFloat": 1000.00, "cashSales": 50.00,
  "expectedCash": 1050.00, "floatOverridden": false, "countedCash": 1050.00,
  "variance": 0.00, "countedAt", "note", "closedAt", "closedByUsername" }
```

Negative `variance` is a shortfall. Counting twice → 409.

Showing the float to an EMPLOYEE does not weaken the blind count: it is a constant they put in the
drawer themselves at open, and it says nothing about the takings.

`closedAt` and `closedByUsername` are null while the drawer is counted but the night is still
open. The cash count row **is** the day-end record: a day cannot close without a count, so it is
the only row guaranteed to exist for a closed day.

**PUT `/cash-count`** → `{ "countedCash", "openingFloat"?, "note"? }` — **ADMIN only**, for
correcting a mistyped count. `openingFloat` is correctable too — "we ran 500 last night, not
1,000" is as likely a mistake as a mistyped total — and omitting it leaves the recorded float
alone. 409 only when *both* figures are unchanged. An employee gets 403; a cashier who can re-count until the variance reads zero has defeated
the only check on the drawer. Refused with 409 once the day is closed, and 409 if the figure is
unchanged. The original is not destroyed — it is written to `audit_log` as `CASH_COUNT_CORRECTED`
with both the old and new values.

**POST `/close`** → 409 while any session is open, with the message **naming the tables**, and 409
if the drawer has not been counted. Both are hard preconditions.

Closing a day that is already closed → 409 naming when and by whom. The close is recorded on the
cash count row, so it happens exactly once.

---

## 10. Stock

| Method | Path | Who |
|---|---|---|
| POST | `/api/v1/stock/deliveries` | ADMIN |
| POST | `/api/v1/stock/corrections` | ADMIN |
| POST | `/api/v1/stock/comps` | authenticated |
| GET | `/api/v1/stock/low` | authenticated |

**deliveries** → `{ "supplierName"?, "reference"?, "note"?, "lines": [ { "productId", "quantity",
"unitCost" } ] }`. Recomputes each product's moving weighted average cost.

**corrections** → `{ "productId", "newQuantity", "note" }` — `note` required. Records the *delta*.
Correcting to the quantity already held → 409.

**comps** → `{ "productId", "quantity", "note" }` — `note` required. A counter action.

**low** → `[ { "productId", "name", "qtyOnHand", "threshold" } ]` — at or below the branch
threshold (10), which sweeps up anything negative.

---

## 11. Reports and audit — ADMIN only

| Method | Path | Who |
|---|---|---|
| GET | `/api/v1/reports/daily?date=YYYY-MM-DD` | ADMIN |
| GET | `/api/v1/audit?entity=&actor=&from=&to=&page=&size=` | ADMIN |
| GET | `/api/v1/audit/feed?action=&actor=&entity=&from=&to=&page=&size=` | ADMIN |
| GET | `/api/v1/audit/filters` | ADMIN |

**`/reports/daily`** — omit `date` for the night currently running.

```json
{ "businessDate": "2026-08-31", "comparedTo": "2026-08-30",
  "totals":         { "bills", "gross", "cost", "profit", "timeRevenue", "itemRevenue" },
  "previousTotals": { ...same shape, zeros when there is no previous day... },
  "salesByHour":      [ { "hour": 20, "bills": 5, "amount": 1310.00 } ],
  "tableUtilisation": [ { "tableName", "billedMinutes", "utilisationPercent" } ],
  "topItems":         [ { "description", "quantity", "revenue" } ],
  "paymentMix":       [ { "method", "payments", "amount" } ],
  "losses":           { "voidCount", "voidAmount",
                        "overrideSessions", "forgoneRevenue",
                        "compQuantity", "compEstimatedCost" },
  "lowStock":         [ { "name", "qtyOnHand" } ],
  "perEmployee":      [ { "username", "fullName", "bills", "gross", "cost", "profit" } ] }
```

- `hour` is the Asia/Manila hour, 10 through 4 across the business day.
- `utilisationPercent` is against a 19-hour day.
- `perEmployee` sums exactly to `totals` — attributed to whoever took the payment.
- `compEstimatedCost` is an **estimate** valued at current average cost; `voidAmount` and
  `forgoneRevenue` are exact.

**`/audit`** — all filters optional. `entity` is a table name (`bill_line`, `product`,
`pool_table_rate`, `table_session`, `cash_count`); `actor` is a user UUID; `from`/`to` are business
dates. `size` is capped at 200; default page 0, size 50. Newest first.

```json
{ "content": [ { "id", "action", "entityTable", "entityId", "actorId", "actorUsername",
                 "before", "after", "note", "occurredAt", "businessDate" } ],
  "page": 0, "size": 50, "totalElements": 6, "totalPages": 1 }
```

`before` / `after` are free-form JSON objects whose keys differ per `action`. Render generically.
Actions currently written: `PRODUCT_UPDATED`, `POOL_TABLE_RATE_CHANGED`, `SESSION_RATE_OVERRIDE`,
`BILL_LINE_VOIDED`, `BUSINESS_DAY_CLOSED`.

---

## 12. Money and numbers

- Every money field is a **JSON number with 2 decimal places**; rates carry 4.
  Parse as decimal, never as a float you then round. `630.00` may serialise as `630.0`.
- Quantities carry 3 decimals.
- **Never compute a total client-side and send it.** The server recomputes everything and rejects a
  mismatch.

---

## 13. What is NOT built

Do not code against these; they do not exist:

- `POST /sessions/{id}/transfer` — table transfer
- `POST /bills/{id}/merge`, `/unmerge`
- `GET /reports/daily.pdf`
- Branches CRUD (`/api/v1/branches`) **exists and is ADMIN-only**, but is not in the product spec
  and has no UI need today.

The seed logins are `owner` / `owner123` (ADMIN) and `counter` / `counter123` (EMPLOYEE). **Change
these before go-live.**

---

## Settings — ADMIN only

| Method | Path | Who |
|---|---|---|
| GET | `/api/v1/settings` | ADMIN |
| PUT | `/api/v1/settings` | ADMIN |

`{ "standardCashFloat": 1000.00 }` both ways. Zero means the hall keeps no float and the drawer is
expected to hold takings alone — which is the seeded default, so nothing changes until the owner
sets a figure.

Deliberately **not** a generic key/value endpoint. `branch_setting` also holds keys the till
reasons about (rate floors, negative stock), and those are not a text box.

A change writes a `SETTING_CHANGED` audit row with both figures. It applies to nights counted from
then on; nights already counted keep the float stored on their own row. Setting it to the value it
already has → 409.

`standardCashFloat` is also returned on `GET /business-day/current` and
`GET /business-day/{date}/open-sessions`, where the close-out reads it to prefill the float. That
copy is readable by an EMPLOYEE — see the cash-count notes above for why that is safe.

---

## The audit feed — ADMIN only

`GET /audit` remains the raw view of `audit_log`. **`GET /audit/feed` is what the Audit screen
reads**: the same history with the stock ledger unioned in and every id already resolved to a name.

```json
{ "id", "source": "AUDIT" | "STOCK",
  "action": "SESSION_RATE_OVERRIDE",        // the stored constant; send it back as a filter
  "actionLabel": "Friend rate given",        // the same thing in words; display this
  "entityLabel": "Table",                    // what kind of thing changed
  "subject": "Table 5",                      // which one — never an id
  "actorName": "Front Counter",
  "note": "regular customer",
  "quantityDelta": -2,                       // stock rows only; negative took stock out
  "before": { }, "after": { },               // audit rows only
  "occurredAt", "businessDate" }
```

Stock movements appear as `STOCK_DELIVERY`, `STOCK_CORRECTION` and `STOCK_STAFF_COMP`. **`SALE` and
`SALE_VOID` are excluded**: every sold bottle writes a movement, so including sales would bury the
one row that matters under thousands a night, and a void already writes its own audit row.

`GET /audit/filters` → `{ "actions": [ { "action", "label" } ], "actors": [ { "id", "name" } ] }`,
built from what is actually in this branch's history — so the filters offer a list to pick from
rather than a constant the reader would have to already know.
