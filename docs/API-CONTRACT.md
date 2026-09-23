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
| `SESSION_NOTE_REQUIRED` | 409 | Leaving a bill unpaid when the session carries nobody's name | Mark the note field required and focus it — do not just show the message |

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

**Bean validation runs BEFORE the role check**, though, so an employee POSTing a malformed or empty
body to an admin route gets **400** and the field messages rather than 403. Send a body that binds
and the 403 is what comes back, on all eleven admin write routes, with nothing changed. This is the
framework's ordering: `@PreAuthorize` is an AOP interceptor on the handler method, and arguments
are bound and validated before the method is reached. It is documented rather than fixed because
the alternative is moving the admin rules out of `@PreAuthorize` and into URL matchers in the
security chain — which duplicates the authorisation away from the methods it guards, and a route
added later with the annotation but missing from the matcher list would be silently unprotected.
That trades a disclosure for an escalation. What leaks is the existence of a route the SPA bundle
already names to both roles.

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

On **POST only**, optional `openingStock: { "quantity": 12, "unitCost": 62.50 }` records the
initial inventory through the existing stock delivery service. Omit it (or send `null`) for no
opening stock. When present, both fields are required: quantity ≥ 0.001 (up to three decimal
places), unit cost ≥ 0 (up to four decimal places). Incomplete or invalid pairs return **400**.
The product, creation audit, delivery header and ledger movement commit together; a delivery
failure leaves none of them behind. The response includes the resulting quantity and average
cost. Sending opening stock on **PUT returns 409**; use `/stock/deliveries` for later receipts.

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
      "ratePerMinute": 4.0, "ratePerHour": 240.00, "effectiveRatePerHour": 240.0000,
      "session": null }
  ],
  "serverNow": "2026-08-31T12:37:26.610539Z" }
```

`session` is `null` when the table is free. When occupied:

```json
"session": { "sessionId", "billId", "poolTableName", "status", "customerTypeId", "customerTypeName",
             "openedAt", "billedMinutes", "billedSeconds", "ratePerMinute",
             "flatAmount", "rateOverrideKind",
             "timeAmount", "itemCount", "itemTotal", "runningTotal" }
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

### Rates: two ways to type the same number

`ratePerMinute` is the only figure that bills. `ratePerHour` is an **input convenience** — the
owner configures tables in pesos per hour — and a **record of what was typed**, so the screen can
read the admin's own figure back. Nothing prices from it.

- `ratePerMinute` — what bills. `numeric(10,4)`. Always present on a table that has a rate.
- `ratePerHour` — what the admin typed, when they typed an hourly figure. `numeric(12,2)`.
  **Null** on a table configured per minute, which is every table predating this feature. Do not
  fill that null in by multiplying: it would show a number nobody entered.
- `effectiveRatePerHour` — `60 × ratePerMinute`, computed server-side. What an hour is *actually*
  priced at.

`ratePerHour` and `effectiveRatePerHour` differ whenever the hourly figure does not divide by 60
exactly. ₱240/hour stores `4.0000`/min and both read `240`. ₱200/hour stores `3.3333`/min, whose
effective hourly rate is `199.9980`. Show the difference rather than hiding it — a full hour still
bills ₱200.00 because the line rounds to centavos, but three hours bills ₱599.99 against ₱600.00
nominal.

**POST/PUT `/tables`** → `{ "name", "tableNumber"?, "ratePerMinute"?, "ratePerHour"?, "isActive"? }`.
**Exactly one** of the two rates is required — neither is a 400 ("a table with no rate cannot host a
session"), both is a 400. On PUT, a changed rate opens a new rate period exactly as the dedicated
endpoint does; switching a table between the two input modes counts as a change, so send back the
figure the table is currently configured with when you are only renaming it.

**PUT `/tables/{id}/rate`** → `{ "ratePerMinute"?, "ratePerHour"?, "effectiveFrom"? }`, again
exactly one of the two rates. Closes the current rate period and opens a new one. Never mutates
history — a session already running keeps the rate snapshotted onto its segment. A backdated
overlap → 409.

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
| GET | `/api/v1/sessions/{id}/notes` | authenticated |
| POST | `/api/v1/sessions/{id}/notes` | authenticated |

**POST `/sessions`** → `{ "tableId", "customerTypeId", "rateOverridePerMinute"?, "rateOverridePerHour"?, "rateOverrideKind"?, "rateOverrideReason"?, "flatAmount"?, "flatRateReason"? }`

No start time — the server stamps it. Creates the bill, the session and its first segment in one
transaction.

- A **second open session on the same table returns 409** `That table already has an open session`.
  This comes from a database index, so it is race-proof: if two tabs both press start, exactly one
  wins.
- The friend rate takes **at most one** of `rateOverridePerMinute` and `rateOverridePerHour` —
  both in one request is a 400, and **neither is the ordinary case**, meaning "charge the table's
  standard rate". This differs from the table endpoints, where a rate is mandatory.
- When `rateOverridePerHour` is given, the server derives `rateOverridePerMinute = rateOverridePerHour / 60`
  at HALF_UP to four decimals and stores both. The derived per-minute figure is what is snapshotted
  onto `session_segment.rate_per_minute` and what bills; the hourly figure is a record of what was
  typed and prices nothing.
- `rateOverrideKind` ∈ `FRIEND | PROMO` says **which kind of override** the rate is. It is a
  pricing mode, not a kind of customer, and both kinds bill through the same two rate fields —
  this only decides the gate, the reason rule, and which figure the report counts it under.
  Sending it without a rate is a 400: a kind labels an override, it does not create one.
- **`FRIEND`** — a favour. Only accepted when the chosen customer type has
  `allowsRateOverride: true`; otherwise 409. The reason is optional. **This is the default**:
  omitting `rateOverrideKind` alongside a rate means `FRIEND`, which is what every override
  written before this field existed was, and what they were all backfilled to.
- **`PROMO`** — happy hour. **Not gated on the customer type**, exactly as the flat rate is not:
  an event is not a kind of customer, and requiring a "Promo" customer type to run one would file
  a happy-hour walk-in as customer type Promo and destroy the record of who they actually were.
  `rateOverrideReason` is **required** — an unlabelled promo is unmeasurable, which is the whole
  point of recording it apart. Missing reason is a 400.
- Under either kind, any value is allowed, with no floor and no approval — **including zero**,
  which is a comped game.
- `flatAmount` is **tournament pricing**: a fixed charge for the whole session however long it
  runs, set at session start. `flatRateReason` is **required** with it, and zero is allowed — the
  reason is the whole control on a fee somebody chose. Unlike the friend rate it is **not gated on
  the customer type**: a tournament is an event, not a kind of customer, and requiring a
  "Tournament" customer type would mean remembering to switch it back.
- A session is priced **one way**. `flatAmount` together with either friend-rate field is a 400.
- On a flat session the timer still runs and the minutes are still recorded — `billedMinutes` and
  `billedSeconds` behave exactly as they do on a metered session, and utilisation counts them. What
  stops growing is the charge: `timeAmount` equals `flatAmount` from the first second. Checkout
  writes **one** TIME line for the whole session, described as `"Table 3 - flat rate (192 min)"`.
- **`POST /sessions/{id}/billed-minutes` is refused with 409 on a flat session.** "Charge fewer
  minutes" has no meaning against a fee that was never per-minute.
- Pausing works normally: it stops the clock and lowers the recorded minutes, and does not change
  the charge.

**Session response** (returned by all five routes):

```json
{ "id", "billId", "poolTableId", "poolTableName", "customerTypeId", "customerTypeName",
  "status", "openedAt", "closedAt", "closeKind",
  "standardRatePerMinute", "rateOverridePerMinute",
  "standardRatePerHour", "rateOverridePerHour", "rateOverrideKind",
  "flatAmount", "flatRateReason",
  "billedMinutes", "billedSeconds", "timeAmount", "itemTotal", "runningTotal",
  "segments": [ { "id", "poolTableId", "poolTableName", "seq", "ratePerMinute",
                  "startedAt", "endedAt", "billedMinutes", "amount" } ],
  "pauses":   [ { "id", "pausedAt", "resumedAt", "reason" } ],
  "serverNow": "..." }
```

`status` ∈ `OPEN | PAUSED | CLOSED | AUTO_CLOSED | VOIDED`.
`closeKind` ∈ `MANUAL | AUTO_END_OF_DAY | null`.

`flatAmount` and `flatRateReason` are null on a metered session. When `flatAmount` is set,
`rateOverridePerMinute` is always null (the two are mutually exclusive) and the session's segments
carry `ratePerMinute: 0` — **which means priced at session level, not that the table was free**.
Read `flatAmount` for what was actually charged; the same applies to `TableSessionSummary`, which
is why it carries `flatAmount` alongside its zero `ratePerMinute`.

`rateOverrideKind` ∈ `FRIEND | PROMO | null`, null exactly when there is no override. Use it for
the label: a friend rate is named after the customer type it was given on ("Happy Hour rate"),
and a **promo is named after itself** — it runs on any customer type, so that rule would render
"Regular rate" on a happy-hour table. The audit feed applies the same rule to `actionLabel`.

`standardRatePerHour` and `rateOverridePerHour` are read-back only, both snapshotted when the
session opened. `standardRatePerHour` is null if the table was configured per minute at that
moment; `rateOverridePerHour` is null if the friend rate was typed per minute or there was none.
Quote the pair only when **both** are non-null — half an hourly comparison reads worse than a
per-minute one. Neither figure ever enters an amount.

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

### Notes — who was on the table

Free text attaching people to a session, and so to its bill. The unpaid strip in §7 shows the most
recent one, which is the only thing that tells three unpaid "Table 1" bills apart.

**Not admin-only.** The counter is who knows the names, and a note they cannot write is a note
nobody writes.

**GET `/sessions/{id}/notes`** — the thread for one session, **oldest first**.

```json
[ { "id", "sessionId", "kind": "STAFF", "body": "Marco + 2",
    "authorId", "authorUsername": "counter", "createdAt": "..." } ]
```

`kind` ∈ `STAFF | SYSTEM`. A `SYSTEM` note is written by the server at an event — currently
settlement, which appends `"Settled by <user> — CASH 1350.00"`. **Mark the two apart in the UI.**
The kind is never accepted from the client, so a staff member cannot forge one by typing the
sentence, and that distinction is worth nothing if the screen does not show it.

**POST `/sessions/{id}/notes`** → `{ "body" }` — required, non-blank after trimming, at most 280
characters, or 400. Nothing else in the body is read: the author is the authenticated user and the
kind is always `STAFF`.

**Notes are append-only. There is no PUT and no DELETE, and there will not be one.** The database
blocks UPDATE and DELETE by trigger. A note about money owed that an employee can quietly remove
defeats the point of writing it, so a wrong note is corrected by a later note and both stay in the
thread.

A note can be added to a session in **any** state, including a closed one — staff forget during a
busy shift and put the name on when they see the unpaid card. Another branch's session is 404, for
both the read and the write.

---

## 7. Bills, orders and voids

| Method | Path | Who |
|---|---|---|
| GET | `/api/v1/bills?businessDate=&page=0&size=50` | **ADMIN** |
| GET | `/api/v1/bills/{id}` | authenticated |
| POST | `/api/v1/bills/{id}/lines` | authenticated |
| POST | `/api/v1/bills/{id}/lines/{lineId}/void` | authenticated |
| GET | `/api/v1/bills/{id}/notes` | authenticated |

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

**`method` and `takenByUsername` are `null`** on a bill closed with nothing to pay — see
`POST /bills/{id}/no-charge`. Render the absence ("Nothing to pay"); do not default to `CASH`.

**GET `/bills/{id}`**

```json
{ "id", "status", "customerTypeId", "customerTypeName", "openedAt", "closedAt",
  "businessDate", "version",
  "subtotalTime", "subtotalItems",
  "discountAmount", "discountReason", "discountByUsername", "discountAt",
  "voucherAmount", "voucherCode", "voucherMinutes", "voucherMinutesCovered", "totalAmount",
  "lines": [ { "id", "lineKind", "seq", "productId", "sessionId", "description",
               "unitPrice", "quantity", "billedMinutes", "lineTotal",
               "voidedAt", "voidReason" } ],
  "sessions": [ <session summary, as in the floor view> ] }
```

An ADMIN additionally receives `totalCost` and `grossProfit` at the top level, and `unitCost` and
`lineCost` on each line. An EMPLOYEE receives neither key.

`totalAmount` is `subtotalTime + subtotalItems − discountAmount − voucherAmount`. **The discount
and voucher fields are on the base shape, not the admin one** — neither is a cost or a profit
figure, and the counter who entered them has to be able to read them back. `discountAmount` and
`voucherAmount` are `0.00` and the other fields `null` when nothing was given away. See
`POST /bills/{id}/discount` and `POST /bills/{id}/voucher` below.

`voucherCode` is the **display form** — `SB-7K4-M2Q`. Returning it here is safe where the admin
code list is not: this code has already been spent, on this bill, by the person reading the screen.
`voucherMinutes` is what the code was worth and `voucherMinutesCovered` is what it reached; the
difference is what the customer forfeited.

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
    "tableNames": ["Table 3"], "totalAmount": 5054.00,
    "latestNote": { "id", "sessionId", "kind", "body", "authorId", "authorUsername", "createdAt" } } ]
```

`latestNote` is the most recent note on the bill, or **null** when nobody has written one — the
name of who owes the money, so three unpaid bills on the same table are told apart. It is one more
line on the card; nothing about how these bills are detected, totalled or collected changed. The
whole thread is at `GET /bills/{id}/notes`.

**Bills totalling `0.00` are listed**, and were excluded until vouchers existed. The old reason was
that payment validation requires at least 0.01, so such a bill could never be settled and would sit
in the strip for ever — teaching staff to ignore it, which is the one thing this strip cannot
afford. `POST /bills/{id}/no-charge` closes it now, and a prize winner's night ending at zero is
ordinary rather than stuck. Filtering it would be the worse failure of the two: their table reads
free, the bill is still `OPEN`, and nothing anywhere would prompt anyone to finish it — the sale
would never reach a report. Carries no cost field, so one shape serves both roles.

**GET `/bills/unpaid`** — every bill with status `UNSETTLED`, **newest first, all business dates**.
A different list from `/bills/unsettled` above, and the two must never be merged in the UI:

| | `/bills/unsettled` | `/bills/unpaid` |
|---|---|---|
| Status | `OPEN`, no live session | `UNSETTLED` |
| What it means | **A mistake** — staff forgot to check out | **A debt** — the hall agreed to wait |
| Totals | Computed from live lines; `total_amount` still reads `0.00` | **Frozen**; has a receipt number |
| In the night's gross | No — it is not a finished sale | **Yes** |
| Label on screen | "Not checked out" | "Unpaid" |

```json
[ { "id", "receiptNo": 412, "businessDate": "2026-09-05", "unsettledAt", "unsettledByUsername",
    "daysOutstanding": 35, "tableNames": ["Table 3"], "totalAmount": 654.00,
    "latestNote": { ...same shape as above... } } ]
```

`businessDate` is the night it was **played**, not the night it will be collected.
`daysOutstanding` is counted between business dates server-side — the browser never dates money.
Carries no cost field, so one shape serves both roles.

**POST `/bills/{id}/leave-unpaid`** → `{ "note", "billVersion" }` — records the sale without the
money. Runs the **same finalisation a checkout runs**: totals recomputed from live lines and frozen,
`receipt_no` allocated under the branch row lock, `closed_at`/`closed_by` stamped, the `receipt`
snapshot written. It then sets status `UNSETTLED` and takes no payment. Returns the `UnpaidBill`
shape above.

- **`note` is conditionally required.** The session must carry at least one `STAFF` note naming who
  owes the money — either one already written during the session, or this one, which is written
  through the ordinary note path in the same transaction and authored by the caller. Missing and
  needed → 409 `SESSION_NOTE_REQUIRED`. A `SYSTEM` note does not satisfy this.
- **`billVersion`** behaves exactly as at checkout: mismatch → 409 `STALE_BILL_VERSION`.
- **A quick sale cannot be left unpaid** → 409. It has no session, so there is nowhere to record who
  owes it, and it is created and settled in one transaction anyway.
- The same blockers as checkout apply: a session still running → 409.
- Audited as `BILL_LEFT_UNPAID` with the amount. No `SYSTEM` note is written — unlike settlement,
  this event always has a staff note beside it already.

**`closed_at` is stamped here and never written again.** `bill.business_date` is generated from it,
so this is the single write that decides which night the sale reports under. Settlement records
`settled_at` instead — see §8.

**GET `/bills/{id}/notes`** — every note on every session this bill carried, oldest first,
including the settlement note. Same shape as `/sessions/{id}/notes` in §6.

**Nothing is dropped when the debt is collected.** The bill leaves the unpaid strip through the
existing status logic and its notes travel with it, which is what makes "who keeps playing on
credit" answerable a year from now. A quick sale carries no session and so has no thread — it is
created and settled in one transaction, and never had a debt to attribute.

**POST `/bills/{id}/lines/{lineId}/void`** → `{ "reason" }` — **required**, non-blank, or 400. The
line is retained, the stock is returned, and an audit row is written. Voiding twice → 409. A TIME
line cannot be voided on its own → 409.

Adding or voiding lines on a bill that is not `OPEN` → 409.

**POST `/bills/{id}/discount`** → `{ "chargeAmount", "reason" }` — knocks money off the **whole**
bill, food and drink included. Returns the `Bill` shape.

Independent of the "charge less time" control in §6 (`POST /sessions/{id}/billed-minutes`), which
only ever reaches the TIME lines. **Both can apply to one bill**, both appear separately on the
receipt and in the dashboard's losses band, and they do not double-count: a time reduction rewrites
the TIME lines first, so the subtotal a discount is computed against is already the reduced one.

- **`chargeAmount` is what is being CHARGED, not what is coming off.** The server computes
  `discount_amount = subtotal - chargeAmount`; the browser never sends a discount and never
  computes a total. The figures shown after saving must be the ones that came back.
- **`reason` is required**, non-blank, or 400. **Any role may do this** — the owner is not at the
  hall most nights — so the reason, the recorded actor and the dashboard figure are the whole
  control. Detection, not prevention: the same posture the friend rate takes.
- `chargeAmount` below `0.01` → 400.
- `chargeAmount` **above** the subtotal → 409. That is a surcharge, not a discount.
- `chargeAmount` **equal to** the subtotal → 409 *"That is the full amount, so there is no discount
  to record."* This is a **zero discount**, not a zero bill — charging the whole subtotal is a
  no-op, and recording it would put a ₱0.00 giveaway in the losses drill-down and the audit feed.
  A free game is a zero friend or flat rate set when the table is **opened**, not a discount at
  checkout. (Comping a whole bill at checkout has no route today.)
- Bill not `OPEN` → 409.
- Audited as `BILL_DISCOUNTED` with before/after snapshots and the reason.

**The stored discount is a FIXED PESO AMOUNT, not a percentage.** Add a ₱50 beer to a bill
discounted by ₱54 and the total goes from ₱600 to ₱650 — the discount stays at ₱54. Say so on
screen; it is the thing that surprises people.

Applying a discount bumps `bill.version`, so a checkout tab still holding the undiscounted total
gets 409 `STALE_BILL_VERSION` rather than charging the old amount.

**DELETE `/bills/{id}/discount`** — clears it and restores the full amount. All four columns are
nulled together. No discount to clear → 409; bill not `OPEN` → 409. Audited as
`BILL_DISCOUNT_CLEARED`, carrying the cleared discount's own reason.

**POST `/bills/{id}/no-charge`** — finishes a bill that comes to **nothing**. Returns the
`Receipt` shape. Any role.

Not a payment of `0.00`: `payment_amount_chk` refuses one and `PaymentRequestDTO` will not carry
one, because a payment of zero is not something that happened. The bill closes, takes its receipt
number and lands on the night's report like any other sale — it simply had nothing to collect, and
**no `payment` row is written**.

- **Refuses any bill with a figure on it** → 409, naming the amount. This is the one route that
  completes a sale without money, and that refusal is all that separates it from a way to give any
  bill away. The gate is checked after the totals are finalised, where the figure is real.
- Bill not `OPEN` → 409. Running session → 409, same blockers as payment.
- Audited as `BILL_CLOSED_NO_CHARGE`.

Reached by a voucher covering the whole of a bill with nothing else on it — the prize winner who
plays ninety minutes on a two-hour code and buys no drinks — and by a comped zero flat or friend
rate. The receipt payload carries `"noCharge": true`, so the chit says **"Nothing to pay"** rather
than showing an empty payment block.

**`method` and `takenByUsername` are `null` on such a bill in `GET /bills?businessDate=`.** Render
the absence; do not default to `CASH`, which would show money in the owner's list that never went
in the drawer.

**POST `/bills/{id}/voucher`** → `{ "code" }` — spends a giveaway voucher against this bill.
**Any role**: making a batch is the owner's job, spending one is the cashier's. Returns

```json
{ "bill": <Bill>, "code", "voucherMinutes", "minutesCovered", "minutesForfeited", "voucherAmount" }
```

A voucher is **TIME, not money**. "2 hours" covers up to two hours of this bill's table time at
whatever rate that time was actually billed at, so the same code is worth ₱480 on a ₱240/hour table
and ₱300 on a ₱150/hour one. Play longer and the customer pays the difference; **play less and the
rest is forfeited** — no change, no residual balance, the code is spent. `minutesForfeited` is the
field that says so, and the screen must say it at the moment of redemption. The cashier is the one
who has to tell the customer.

- `code` is sent **as typed**. The server uppercases it, strips spaces and dashes and adds the `SB`
  prefix if it was left off, so `sb 7k4-m2q` and `SB7K4M2Q` both find `SB-7K4-M2Q`.
- **Six refusals, each with its own message.** Render the server's `message`; "invalid code" for all
  six is useless to a cashier holding up a queue.
  - No such code in this branch → **404**. A code from another branch is simply not found.
  - Already redeemed → 409, naming the date and the receipt it was used on.
  - Expired → 409, naming the date. Judged against the **business** date, so a code expiring on the
    31st still works at 2am on the 1st.
  - Bill not `OPEN` → 409.
  - Any session on the bill priced at a **promo, friend rate or flat fee** → 409, naming which and
    which table. One pricing story per session, the same rule the flat rate already follows.
  - No table time on the bill yet → 409. Close the session first; the code is not spent.
- A bill already discounted so far that the voucher would take it below zero → 409, naming the
  order that works: voucher first, then agree the discount on what is left.
- Audited as `VOUCHER_REDEEMED`. Bumps `bill.version`.

**Single use is enforced by the database, not by a pre-check.** Redemption is a conditional
`UPDATE ... WHERE redeemed_at IS NULL`, and zero rows affected *is* the already-redeemed refusal —
two tills racing one code is exactly what a read-then-write loses.

**DELETE `/bills/{id}/voucher`** — un-redeems it, for a code entered against the wrong bill. Returns
the same shape, the code to unredeemed and the bill to its full amount. No voucher on the bill →
409; bill not `OPEN` → 409. Audited as `VOUCHER_RELEASED`.

---

## 7a. Voucher batches — ADMIN only

| Method | Path | Who |
|---|---|---|
| POST | `/api/v1/voucher-batches` | **ADMIN** |
| GET | `/api/v1/voucher-batches` | **ADMIN** |
| GET | `/api/v1/vouchers?batchId=&status=` | **ADMIN** |

**ADMIN is a security boundary here, not a layout choice: whoever can read a list of unredeemed
codes can redeem them.** The counter never sees these routes; redeeming needs a code the customer
already holds.

**POST `/voucher-batches`** → `{ "hours", "quantity", "expiresOn", "note"? }`. Generates the whole
batch in one transaction and returns it **with the codes**, which is the only response that ever
carries a list of live codes — the owner needs them to copy or print.

- **`hours`**, because that is how the prize was advertised. The server converts to minutes and
  stores minutes; a fractional minute is refused (409) rather than rounded.
- `quantity` 1–500. Above → 400. `expiresOn` must be in the future → 400.
- Audited as `VOUCHER_BATCH_CREATED`. **The codes are not in the audit row** — an audit log is never
  pruned, and live codes in it make every future reader of that screen able to spend them.

```json
{ "id", "minutes", "hoursLabel", "quantity", "expiresOn", "note",
  "createdByUsername", "createdAt",
  "issued", "redeemed", "expired", "outstanding",
  "codes": [ <Voucher>, ... ] }
```

**GET `/voucher-batches`** returns the same shape with **`codes: null`** — a screen rendering every
code of every batch leaks the whole giveaway to anyone looking over a shoulder. The four counts are
exclusive and sum to `issued`; `outstanding` is the one that is still a liability.

**GET `/vouchers`** — the individual codes. Both filters optional; `status` is `OUTSTANDING`,
`REDEEMED` or `EXPIRED`.

```json
{ "id", "batchId", "code", "minutes", "expiresOn", "status",
  "redeemedAt", "redeemedByUsername", "redeemedBillId", "redeemedReceiptNo" }
```

`code` is always the display form. `status` is **resolved against the current business date, never
stored**: a code expiring tonight is `OUTSTANDING` until the night ends at 05:00, which is while the
customer is still playing. A code redeemed before its expiry that has since passed it reads
`REDEEMED`, not `EXPIRED` — it was spent while it was good.

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

#### Settling a debt

The same endpoint collects an `UNSETTLED` bill. Everything above still applies — idempotency, the
duplicate-reference warning, server-computed change, one payment per bill — with three differences:

- **Totals are not recomputed.** They were frozen when the bill was left unpaid, and `amount` must
  equal that frozen `total_amount`. Anything else → 409 *"This debt is … and must be settled in
  full."* **Partial settlement does not exist**; a debt is paid in full or stays outstanding.
- **`closed_at` is not touched.** The bill records `settled_at` and moves to `CLOSED`. The sale
  still reports under the night it was played; only `payment.business_date`, generated from
  `taken_at`, follows the money into today's drawer and today's cash count.
- **No second receipt is written.** `receipt` is append-only and unique per bill, so the chit issued
  on the night is the only one. See `GET /receipt` below.

The `SYSTEM` settlement note is written on this path exactly as on a normal checkout.

**GET `/receipt`** returns the stored snapshot, never re-rendered:
`{ "id", "billId", "receiptNo", "issuedAt", "payload": { ... }, "settlement": null }`. `payload` is
the customer-facing document: lines, subtotals, total, `status`, and — on a bill that was paid at
the counter — method, tendered, change, reference. It carries no cost.

`payload.status` is `CLOSED` or `UNSETTLED`: read it rather than inferring the state from a missing
`method` key. A chit issued against a debt carries no payment keys at all, because nothing was paid.

`settlement` is **not part of the snapshot** and is null unless a debt was collected later:
`{ "method", "amount", "takenAt", "takenByUsername" }`, derived from the payment row at read time.
Render it as a separate block. The payload is append-only and said "unpaid" because the bill was
unpaid; annotating it would make the record claim something untrue of the night it was issued.

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
  "promos":      { "overrideSessions", "forgoneRevenue", "lines": [ ...override lines... ] },
  "friendRates": { "overrideSessions", "forgoneRevenue",
                   "lines": [ { "poolTableName", "standardRatePerMinute", "chargedRatePerMinute",
                                "standardRatePerHour", "chargedRatePerHour",
                                "billedMinutes", "forgoneRevenue", "actorUsername",
                                "reason", "openedAt", "rateOverrideKind" } ] },
  "flatRates":   { "flatSessions", "flatForgone",
                   "lines": [ { "poolTableName", "billedMinutes", "standardRatePerMinute",
                                "meteredRevenue", "flatAmount", "forgoneRevenue",
                                "actorUsername", "reason", "openedAt" } ] },
  "timeReductions": { "reducedSessions", "forgoneRevenue",
                   "lines": [ { "poolTableName", "actualMinutes", "chargedMinutes",
                                "ratePerMinute", "forgoneRevenue", "actorUsername",
                                "reason", "closedAt" } ] },
  "discounts":   { "discountBills", "discountAmount",
                   "lines": [ { "billId", "receiptNo", "subtotal", "discountAmount",
                                "chargedAmount", "reason", "actorUsername", "discountAt" } ] },
  "vouchers":    { "voucherCount", "voucherAmount",
                   "lines": [ { "billId", "receiptNo", "code", "batchNote", "voucherMinutes",
                                "minutesCovered", "minutesForfeited", "voucherAmount",
                                "poolTableName", "actorUsername", "redeemedAt" } ] } }
```

Each list is newest first. **Each section repeats its own tile's figure under the same field
name**, summed from the very rows listed beneath it — so a mismatch between the detail and
`/reports/daily`'s `losses` is a bug, and the screen says so rather than showing both quietly.

`promos` and `friendRates` are **the same shape**: one list of rate overrides split on
`rateOverrideKind`, which every line also carries so it reads on its own. Their scoped field
names stay `overrideSessions` / `forgoneRevenue` — inside `promos` those can only mean the
promos' own — and they map onto the top-level `promoSessions` / `promoForgone` and
`friendSessions` / `friendForgone`, where nothing scopes them.

The predicates mirror the daily report exactly, including that comps are dated by the stock
movement's `business_date` while voids and friend rates are dated by their bill's.

`receiptNo` is null while the voided line's bill is still open. `reason` can be null on a friend
rate (the field is optional at session start); it is never null on a comp, which the schema
requires.

In `timeReductions`, `actualMinutes` is what the table played and `chargedMinutes` what was billed
after the reduction — `forgoneRevenue` is the difference times the rate that was in force, exact
rather than estimated. It keeps a scoped name like `promos` and `friendRates` do, and maps onto
`/reports/daily`'s top-level `reducedSessions` / `timeReductionForgone`. Its actor field is
`actorUsername`, like every other section here; it was `actualUsername`, which read as a sibling
of `actualMinutes` and is not one.

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

**comps** → `{ "productId", "quantity", "note" }` — `note` required. A counter action. **An archived
product is a 409**, as on `/stock/comps/batch`, the bill line and the quick-sale quote: a give-away
is sale-shaped, and archiving is what stops a product being sold.

**`deliveries` and `corrections` accept an archived product on purpose.** Neither is a sale. A
correction is how the last of a discontinued line gets counted down to nothing, and refusing it
would leave that quantity uncorrectable for ever, since `stock_movement` is append-only and there
is no way to record it afterwards.

**low** → `[ { "productId", "name", "qtyOnHand", "threshold" } ]` — at or below the branch
threshold (10), which sweeps up anything negative.

---

## 11. Reports and audit — ADMIN only

| Method | Path | Who |
|---|---|---|
| GET | `/api/v1/reports/daily?date=YYYY-MM-DD` | ADMIN |
| GET | `/api/v1/reports/period?from=YYYY-MM-DD&to=YYYY-MM-DD` | ADMIN |
| GET | `/api/v1/audit?entity=&actor=&from=&to=&page=&size=` | ADMIN |
| GET | `/api/v1/audit/feed?action=&actor=&entity=&from=&to=&page=&size=` | ADMIN |
| GET | `/api/v1/audit/filters` | ADMIN |

**`/reports/daily`** — omit `date` for the night currently running. `comparedTo` is the
**same weekday a week earlier** (a Saturday against last Saturday), never the night before.

```json
{ "businessDate": "2026-08-31", "comparedTo": "2026-08-24",
  "totals":         { "bills", "gross", "cost", "profit", "timeRevenue", "itemRevenue" },
  "previousTotals": { ...same shape, zeros when that night did not trade... },
  "salesByHour":      [ { "hour": 20, "bills": 5, "amount": 1310.00 } ],
  "timeRevenueByMode":[ { "mode": "STANDARD", "sessions": 6, "amount": 2880.00 },
                        { "mode": "PROMO",    "sessions": 3, "amount": 450.00  },
                        { "mode": "FRIEND",   "sessions": 1, "amount": 120.00  },
                        { "mode": "FLAT",     "sessions": 1, "amount": 500.00  } ],
  "tableUtilisation": [ { "tableName", "occupiedMinutes", "utilisationPercent" } ],
  "topItems":         [ { "description", "quantity", "revenue" } ],
  "paymentMix":       [ { "method", "payments", "amount" } ],
  "losses":           { "voidCount", "voidAmount",
                        "promoSessions", "promoForgone",
                        "friendSessions", "friendForgone",
                        "flatSessions", "flatForgone",
                        "reducedSessions", "timeReductionForgone",
                        "discountBills", "discountAmount",
                        "voucherCount", "voucherAmount",
                        "compQuantity", "compEstimatedCost" },
  "expenses":         { "total", "previousTotal",
                        "byCategory": [ { "category", "amount" } ] },
  "lowStock":         [ { "name", "qtyOnHand" } ],
  "perEmployee":      [ { "username", "fullName", "bills", "gross", "cost", "profit" } ],
  "unsettledTonight": { "count": 1, "amount": 654.00 },
  "collectedToday":   { "count": 0, "amount": 0 },
  "outstanding":      { "count": 3, "amount": 2310.00 } }
```

- `hour` is the Asia/Manila hour, 10 through 4 across the business day.
- **`timeRevenueByMode` is `totals.timeRevenue` taken apart, and sums back to it exactly.**
  Always four rows in the order `STANDARD, PROMO, FRIEND, FLAT`, zeros included, each with both
  the amount and the session count. If the four ever fail to reconcile with `timeRevenue`, that
  is a bug: show it rather than picking one of the two figures.
- `promoForgone` and `friendForgone` are the two kinds of rate override, reported apart — same
  arithmetic, `(standard − charged) × billedMinutes`, and different facts. They were one pair
  called `overrideSessions` / `forgoneRevenue` before promos existed; renamed rather than
  quietly narrowed, because "override" at the top level reads as all of them.
- **`occupiedMinutes` is wall clock, pauses included** — how long the table was HELD, not what it
  was charged for. A paused table is nobody else's, and `utilisationPercent` divides by 19 hours of
  wall clock, so the numerator has to be the same kind of minute. It will therefore not match the
  session's own `billedMinutes`, and deducting pauses would not make it match either: a flat
  session ignores minutes entirely, `billedMinutesOverride` rewrites them at checkout, and a rate
  override changes what a minute is worth. Subtracting only pauses gives a third figure that
  reconciles with neither occupancy nor money. What the time SOLD is lives in `totals.timeRevenue`
  and `timeRevenueByMode`; what was given away lives in the losses band. (It was called
  `billedMinutes`, which is what made it look like a bug three separate times.)
- `utilisationPercent` is against a 19-hour day.
- `perEmployee` sums exactly to `totals` — attributed to whoever took the payment.
- **`totals` counts `UNSETTLED` bills as sales.** The regular who plays tonight and pays next
  month was still served tonight, so `gross`, `cost`, `profit`, `salesByHour`, `topItems`,
  `tableUtilisation` and `perEmployee` all include the night's unpaid bills. **`paymentMix` does
  not** — an unsettled bill has no payment row.
- `unsettledTonight` says how much of `gross` is still a promise. It is **already inside** `gross`,
  not additional to it. A **historical** figure keyed on when the bill was left unpaid, so
  collecting the debt later does not rewrite a night the owner has already read.
- `collectedToday` is money taken on this business date against an **earlier** night's sale. It is
  **not** in `gross` — that revenue was recognised when it was earned — but it **is** in today's
  drawer, which is what makes the cash count add up.
- `outstanding` is every open debt across all dates, for the Attention band. The one **live**
  figure here: it answers "right now", so unlike everything else it moves on an old night's report
  when a debt is collected.
- `discountAmount` is money knocked off whole bills — the one giveaway here that is not about
  table time. Reported **beside** `timeReductionForgone` and never folded into it: a bill can carry
  both, and they cannot double-count, because a time reduction rewrites the TIME lines before the
  discount is computed against the subtotal.
- **`totals.gross` reports the DISCOUNTED figure**, and that is deliberate: gross has to reconcile
  to the drawer. ₱600 went in the till, and a gross of ₱654 would leave the cash count ₱54 short
  every time with nothing explaining it. The losses band is what accounts for the gap.
- `voucherAmount` is table time given away as a prize and redeemed against a bill — a third
  giveaway beside the discount and the time reduction, overlapping neither. Like `discountAmount`
  it is already out of `totals.gross`, for the same reason: gross has to reconcile to the drawer.
- `expenses` is operating cost for the night, dated by the expense's **own** `business_date` — an
  expense has no bill, and the water man paid at 02:00 belongs to the night still running. Voided
  expenses are excluded, exactly as voided lines are excluded from revenue. `previousTotal` is the
  comparison night and is `0` rather than null, so the client can always subtract. `byCategory`
  keeps archived categories, or the breakdown would stop adding up to the total beside it.
- `compEstimatedCost` is an **estimate** valued at current average cost; `voidAmount`,
  `promoForgone`, `friendForgone`, `flatForgone` and `discountAmount` are exact.
- `flatForgone` is the metered figure less the flat fee — `standardRatePerMinute × billedMinutes
  − flatAmount` — **clamped at zero per session**. A flat fee above what the meter would have
  charged is not a loss and contributes zero rather than netting off against a real giveaway
  elsewhere, so this figure never understates the night.

**`/reports/period`** — the owner's month. Any range of business dates, inclusive, against the
equivalent range before it. Both parameters required; `to` before `from`, or a range over 366
days, is a **400**. Built from the SAME definitions as `/reports/daily` — a sale is a bill in
`CLOSED` or `UNSETTLED` by its `business_date`, live lines and live expenses have `voided_at`
null — so the nights of a range sum to the range, to the centavo. Reads only.

```json
{ "from": "2026-09-01", "to": "2026-09-14",
  "previousFrom": "2026-08-01", "previousTo": "2026-08-14",
  "comparison": "SAME_DAYS_OF_PREVIOUS_MONTH",
  "headline":         { "bills", "gross", "costOfGoods", "grossProfit", "operatingExpenses", "net",
                        "tradingDays", "grossPerTradingDay", "netPerTradingDay", "grossMarginPercent" },
  "previousHeadline": { ...same shape... },
  "breakEven":        { "requiredGrossPerTradingDay", "actualGrossPerTradingDay", "computable" },
  "byDay":            [ { "businessDate", "trading", "bills", "gross", "costOfGoods", "grossProfit",
                          "operatingExpenses", "net",
                          "expenses": [ { "category", "amount" } ] } ],
  "byDayOfWeek":      [ { "isoDay": 1, "tradingDays", "avgGross", "avgBills", "avgNet" } ],
  "byHour":           [ { "hour", "bills", "amount" } ],
  "expensesByCategory": [ { "category", "amount", "previousAmount", "percentOfGross" } ],
  "expensesByMonth":  { "months": [ "2026-04", "…", "2026-09" ],
                        "rows": [ { "category", "amounts": [ 0, 0, 0, 0, 45000.00, 45000.00 ], "total" } ] },
  "tables":           [ { "tableName", "occupiedMinutes", "utilisationPercent", "timeRevenue",
                          "revenuePerOccupiedHour" } ],
  "products":         [ { "name", "quantity", "revenue", "cost", "margin", "marginPercent" } ],
  "unsoldProducts":   [ { "name", "qtyOnHand", "avgCost", "capitalOnShelf" } ],
  "givenAway":        { ...the sixteen `losses` fields of /reports/daily..., "total", "percentOfGross" },
  "cash":             { "varianceTotal", "nightsWithVariance", "countedNights", "uncountedTradingDays",
                        "unsettled": { "thisPeriod":           { "count", "amount" },
                                       "oneToFourWeeksBefore": { "count", "amount" },
                                       "older":                { "count", "amount" } } } }
```

- **Two words, used precisely.** `grossProfit` is gross less cost of goods — what `/reports/daily`
  calls `profit`. `net` is gross profit less operating expenses, and exists only here.
- **A trading day is a business date with at least one sale or a `cash_count` row.** Every per-day
  figure divides by `tradingDays`, never by calendar days. `grossPerTradingDay`, `netPerTradingDay`
  and `grossMarginPercent` are **null**, not zero, when undefined (no trading days; gross of zero).
- **The previous period is the server's decision**, returned as `previousFrom`/`previousTo` so the
  client never computes a date. `comparison` says which rule chose it, by the shape of the range:
  `SAME_DAYS_OF_PREVIOUS_MONTH` when `from` is the 1st and `to` is in the same month (1–14 Sep
  against 1–14 Aug; a complete month against the complete month before it, so September is
  against all 31 days of August); `SAME_DAYS_OF_PREVIOUS_WEEK` when `from`
  is a Monday and the range is within that week; otherwise `PRECEDING_DAYS`, the same number of
  days immediately before `from`. A bookmarked `from`/`to` therefore always reproduces the same
  comparison.
- `breakEven.requiredGrossPerTradingDay` is `operatingExpenses ÷ (grossProfit ÷ gross) ÷ tradingDays`
  — the gross a trading day must take for the margin on it to cover the period's operating cost.
  `computable` is false, and both figures null, when there are no sales or the margin is not
  positive; the page says "not enough sales to compute" rather than dividing.
- `byDay` has a row for **every calendar night** in the range, `trading: false` and zeros on a
  night the hall did not trade, so the trend line shows a closed Tuesday as a gap.
- `byDayOfWeek` is always seven rows, Monday first, averaged over the trading days of that weekday
  only; null averages where the weekday never traded.
- `byHour` is the same `extract()` as `salesByHour`, summed across the period.
- `expensesByCategory` covers both windows: a category paid in only one of them still appears,
  with zero on the other side. `expensesByMonth` is the six calendar months ending in the month
  `to` falls in; the last column runs only to `to` and is partial unless `to` is a month end.
  Voided expenses excluded throughout; archived categories kept, as on the daily.
- `tables` is sorted **weakest first** — ascending `revenuePerOccupiedHour`, tables nobody played
  (null) before all. `occupiedMinutes` is the daily's definition (wall clock, pauses included);
  `utilisationPercent` is over 19 hours × trading days. `timeRevenue` is what the time on that
  table was charged — the sessions' TIME lines, a moved session split between its tables by
  minutes — and sums across tables to the period's time revenue.
- `products` is sorted **thinnest margin first**, from the prices and costs snapshotted on the
  lines. `unsoldProducts` is every unarchived product with stock on hand that sold nothing in the
  period, `capitalOnShelf` = `qtyOnHand × avgCost` at the current average cost, most first.
- `givenAway` is the daily's eight loss lines from the same CTEs with the date widened, plus their
  `total` and the total as `percentOfGross`. The comps estimate is inside the total.
- `cash.unsettled` is **live** by bill status like the daily's `outstanding`, aged by the night
  the bill was played: inside the period, in the 28 days before `from`, or older. Bills dated
  after `to` are excluded.

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

## 12a. Staff — ADMIN only

| Method | Path | Who |
|---|---|---|
| GET | `/api/v1/users` | **ADMIN** |
| POST | `/api/v1/users` | **ADMIN** |
| PUT | `/api/v1/users/{id}` | **ADMIN** |
| DELETE | `/api/v1/users/{id}` | **ADMIN** |
| PUT | `/api/v1/users/{id}/password` | **ADMIN** |
| DELETE | `/api/v1/users/lockouts` | **ADMIN** |

`{ "id", "username", "fullName", "role", "active", "mustChangePassword" }` on every response.

**GET** returns **this branch's staff plus every global admin**. A global admin has no branch and
is therefore staff of every branch rather than of none; with more than one branch they appear on
each branch's list, which is the answer rather than a leak. Without them no admin would be listed
at all — the seeded owner is global — and Edit, Archive and the last-admin warning would be dead
for exactly the accounts this screen exists to manage.

**POST** → `{ "username", "fullName", "role", "temporaryPassword" }`. `username` is letters,
numbers, dots, dashes and underscores, ≤ 50 chars; `temporaryPassword` is ≥ 8 chars, the same
rule `PUT /{id}/password` uses. The server always sets `mustChangePassword: true`, so the value
the admin reads out stops working the moment it is used — see the gate in §2.

- A **new EMPLOYEE is given the current branch; a new ADMIN is global** (`branch_id` null),
  following `app_user_branch_required_for_employee` and the seeded pair.
- A username already in live use → **409** naming the fix. Archived rows do not collide:
  `app_user_username_key` is partial on `archived_at IS NULL`, so archiving frees the name.

**PUT `/{id}`** → `{ "fullName", "role", "isActive" }`. No username (it is what someone types
every night) and no password (that is its own route, which ends their sessions). A user who is
demoted or deactivated has their sessions invalidated, so the change takes effect at once rather
than whenever they next log out.

**DELETE `/{id}`** **archives** — never deletes, because `audit_log`, `bill_line.created_by` and
every payment they took point at the row for ever. Sessions are invalidated. The username becomes
reusable.

**Three refusals, all 409**, and the UI should disable rather than let the user meet them:

- Archiving or deactivating **yourself**.
- Demoting **yourself** from ADMIN.
- Archiving, demoting or deactivating the **last active administrator**, counted system-wide
  because an admin may be global. This is the one invariant in this schema the database cannot
  enforce: a `CHECK` constraint sees a single row and cannot say "at least one row must survive".
  It matters because `DELETE /users/lockouts` is ADMIN-only, so a hall with no administrator has
  no way to clear a login lockout and only the break-glass procedure in `HELP.md` to get back in.

**`PUT /{id}/password` refuses an ADMIN target** — no admin resets another admin. A forgotten
administrator password is break-glass, not something the other administrator can fix; a second
admin covers **lockouts**, not amnesia.

Audited as `USER_CREATED`, `USER_UPDATED`, `USER_ARCHIVED`, and `USER_ROLE_CHANGED` as its own
action — promoting somebody is the most consequential act in this system and should not be a
field inside a diff. No password or hash ever reaches an audit snapshot.

---

## 13. What is NOT built

Do not code against these; they do not exist:

- `POST /sessions/{id}/transfer` — table transfer
- `POST /bills/{id}/merge`, `/unmerge`
- `GET /reports/daily.pdf` — there is no server-side PDF. The period report page
  (`/admin/reports`) carries a print stylesheet, and the browser's Print → Save as PDF is the
  export.
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

`{ "standardCashFloat": 1000.00, "checkoutAnimation": true }` both ways. **Both fields are
required on PUT** — a body carrying only `standardCashFloat` is a 400, so send the current
`checkoutAnimation` back with it. Zero float means the hall keeps no float and the drawer is
expected to hold takings alone — which is the seeded default, so nothing changes until the owner
sets a figure. `checkoutAnimation` turns the settle confirmation flourish on and off.

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
  "actionLabel": "Happy Hour rate",          // the same thing in words; display this
  "entityLabel": "Table",                    // what kind of thing changed
  "subject": "Table 5",                      // which one — never an id
  "actorName": "Front Counter",
  "note": "regular customer",
  "quantityDelta": -2,                       // stock rows only; negative took stock out
  "customerTypeName": "Happy Hour",          // table_session rows only; joined, not snapshotted
  "before": { }, "after": { },               // audit rows only
  "occurredAt", "businessDate" }
```

`actionLabel` is usually the fixed wording for the action — `SESSION_RATE_OVERRIDE` is
`"Rate overridden"`. The one exception is that same action **when the session carries a customer
type**, where it is named after the type instead: `"Happy Hour rate"`. The owner has customer
types beyond friends, and one fixed phrase misnames all but one of them.

`subject` on a row about a **bill** — `BILL_DISCOUNTED`, `VOUCHER_REDEEMED`, `BILL_LEFT_UNPAID`,
`BILL_CLOSED_NO_CHARGE` — is the **receipt number**, written `#412` as it is on Sales, Unsettled and
the losses detail. Until the bill has one it is the table and the opening time instead
(`Table 3 · 21:34`): the number is allocated at checkout, and a discount or a redemption is always
agreed before that. Resolved at read time like `customerTypeName` below, so those rows acquire the
number as soon as the bill closes rather than being frozen as null by the append-only log. A bill
carrying more than one table lists them all.

`customerTypeName` is **joined at read time** from `table_session`, not taken from the audit
snapshot. Two reasons: `audit_log` is append-only, so rows already written could never be
backfilled, whereas the join names the history correctly too; and the customer type is not a
before/after *change*, so putting it in the snapshot would render a meaningless
`Customer type name  —  →  Happy Hour` row in the diff. It is null on every non-session row, and
on a session opened without a type — `table_session.customer_type_id` is nullable.

Two display rules the Audit screen applies, worth knowing if anything else renders this feed:
`entityLabel` is suppressed when `subject` already starts with it on a word boundary, so a row
reads `· Table 2` rather than `· Table Table 2` while a table named `Corner` still reads
`· Table Corner`. And in `before`/`after`, `ratePerMinute` and `ratePerHour` are one rate stated
two ways — render a single row in the unit each side was set in, never both.

Stock movements appear as `STOCK_DELIVERY`, `STOCK_CORRECTION` and `STOCK_STAFF_COMP`. **`SALE` and
`SALE_VOID` are excluded**: every sold bottle writes a movement, so including sales would bury the
one row that matters under thousands a night, and a void already writes its own audit row.

`GET /audit/filters` → `{ "actions": [ { "action", "label" } ], "actors": [ { "id", "name" } ] }`,
built from what is actually in this branch's history — so the filters offer a list to pick from
rather than a constant the reader would have to already know.
