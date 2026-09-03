# Frontend Specification — Supreme Billiard Hall POS

Companion to `docs/API-CONTRACT.md` (authoritative for shapes) and `frontend/CLAUDE.md` (rules).
This is the **what**. Build order is §4 and it matters.

---

## 1. Who uses this, and where

**Employee, at the counter.** A desktop with mouse and keyboard, in a dim room, under time
pressure, all night. Dark theme. Speed and legibility beat everything else.

**Admin (the owner), anywhere.** Reads the dashboard in daylight and may print it. Light theme.
Reached from the same app after logging in as ADMIN.

Both themes toggleable, dark is the default.

---

## 2. Design tokens

Derived from the venue and logo; every text pairing below was measured, not estimated. Define once
as CSS custom properties, map into Tailwind, never hardcode a hex in a component.

### Dark — the POS

```css
:root {
  --bg:        #0F1311;  /* app ground — near-black, faint green cast */
  --surface:   #171C19;  /* cards, table grid, order panel */
  --raised:    #1F2622;  /* inputs, dropdowns, active table card */
  --border:    #2C352F;  /* dividers, card edges — never text */
  --text:      #EDF2EE;  /* 16.53:1 AAA */
  --text-dim:  #A3AFA7;  /*  8.25:1 AAA — labels, timestamps */
  --green:     #2FB457;  /*  6.94:1 AA  — running sessions, confirm, positive */
  --gold:      #F2C230;  /* 11.18:1 AAA — running totals, the live timer */
  --danger:    #E5484D;  /*  4.78:1 AA  — voids, negative stock */
  --info:      #4C8DF6;  /*  5.75:1 AA  — neutral notices only */
  --brand:     #1B8A3A;  /* surfaces and fills ONLY — never text */
  --ink:       #0D0D0D;  /* label on gold (11.60:1) and green (7.21:1) buttons */
}
```

### Light — the admin dashboard

```css
--bg: #FFFFFF;  --surface: #F6F8F6;  --border: #DDE4DF;
--text: #14201A;      /* 16.78:1 AAA */
--text-dim: #55635B;  /*  6.32:1 AA  */
--green: #14702E;     /*  6.20:1 AA  — darkened; white label on it also 6.20:1 */
--danger: #C0343A;    /*  5.54:1 AA  */
```

**Two rules that are not negotiable.** The brand green `#1B8A3A` is a *surface*, never text — it
scores 4.23:1 and fails AA. The gold `#F2C230` never sits on white — 1.68:1, effectively invisible.
Both stay as fills, borders, active states and chart bars, with the derived tints doing the reading.

### Type and space

System font stack, no webfonts — the venue machine may have no internet and system fonts are the
most legible at small sizes anyway.

| Role | Size / weight | Used for |
|---|---|---|
| Display | 40 / 700 | Dashboard headline figures |
| Amount | 28 / 650, `tabular-nums` | Running total, checkout total |
| Heading | 20 / 650 | Panel titles |
| Body | 16 / 400 | Baseline — deliberately not 14, this is read in low light |
| Label | 13 / 600, `.06em` | Field labels, table headers |

**`tabular-nums` on every peso amount and every timer.** Without it the digits jitter as the clock
ticks, which looks broken.

Spacing scale: 4, 8, 12, 16, 24, 32, 48, 64. Minimum interactive height 44px.

### The logo

Only JPEGs exist. Use a CSS/SVG wordmark — the gold-ringed "8" and the Supreme name — rather than
pasting a photo onto a coloured panel. Leave a marked slot in the login screen and dashboard header
for the vector when it arrives.

---

## 3. Screens

### A. Login
Username, password, submit. On success route by role: EMPLOYEE → floor, ADMIN → dashboard. Bad
credentials → 401, show the message. Full-bleed dark with the wordmark.

### B. Floor view — the main screen
`GET /tables` every ~10s. One card per table, large (~180×140), showing:

- **Free:** table name, rate, a clear "Start" affordance.
- **Occupied:** name, elapsed timer ticking locally, running time charge, item total, customer type.
  Green accent border.
- **Paused:** the same, timer visibly stopped, gold or dim treatment so it reads as different at a
  glance from across the counter.

The timer ticks locally from the offset; `billedMinutes` and `timeAmount` reconcile on each poll.
Tapping an occupied card opens the session screen; a free card opens Start.

Header carries the business date, a link to end-of-day, quick sale, and — importantly — an
**unsettled bills** indicator. A session can close without being paid (browser dies, staff misclick),
and once the session closes the table shows free, so the bill becomes unreachable. That is a lost
sale. The strip lists them and links straight to checkout.

### C. Start session (modal)
Table (prefilled), customer type (dropdown, default marked `isDefault`), and — only when the chosen
type has `allowsRateOverride` — a rate field and reason. Show the standard rate next to the override
input so the giveaway is visible as it is entered.

`POST /sessions`. **409 means another tab already started that table** — show it plainly and refresh
the floor rather than retrying.

### D. Session / order screen
Left: the running session — timer, time charge, pause/resume, close. Right: product grid with
type-ahead search, tap to add.

- Adding a line: `POST /bills/{id}/lines`. If the response flags below-zero stock, show a
  non-blocking warning that the count needs correcting — **the sale still succeeded**.
- Void: requires a reason, and the voided line stays visible, struck through, excluded from the
  total. Never remove it from the list — staff need to see what happened.
- Close session: confirm showing the final amount, then route to checkout.

### E. Checkout
`GET /bills/{id}/checkout` for the preview. Receipt-style summary: time lines, item lines, voided
lines shown struck, total large in gold.

Payment: Cash / GCash / Maya.
- **Cash** — tendered input, change displayed **from the server response**, not computed locally.
- **GCash / Maya** — reference number required, optional photo upload.

Send `idempotencyKey` (generate once per checkout attempt, reuse on retry) and `billVersion`.

- `STALE_BILL_VERSION` → refetch, show the new total, ask again.
- `DUPLICATE_PAYMENT_REFERENCE` → "Record anyway" resends with `duplicateOverride: true`.

### F. Receipt
On-screen from `GET /bills/{id}/receipt`. Clean, printable, marked **"Not an official receipt"**.
Return to floor.

### G. Quick sale
Product grid, lines, customer type, payment — no table. Same stock and payment rules.

### H. End of day
Lists open sessions blocking the close. Cash count: server-computed expected cash, counted input,
variance shown immediately with sign. Then close. **Both preconditions are enforced server-side** —
surface them as guidance, not errors.

### I. Admin — catalog
Products (with cost and margin, admin-only), categories, pool tables with rate changes, customer
types. Standard CRUD. Archive rather than delete, and say "Archive" in the UI.

### J. Admin — stock
Deliveries (multi-line with unit costs), corrections (reason mandatory), comps, low-stock list.

### K. Admin — dashboard
`GET /reports/daily?date=`, one call. Layout by importance:

1. **Headline row:** gross sales, cost, gross profit — Display size, each with the delta vs the
   previous business day.
2. **Split:** table-time vs product revenue.
3. **Sales by hour** — bar chart across the 10:00–05:00 day, hand-authored SVG.
4. **Table utilisation %.**
5. **Secondary tiles:** top items, payment mix, low/negative stock.
6. **Losses together:** voids, friend-rate forgone revenue, comps. Mark `compEstimatedCost` as an
   estimate — the other two are exact.
7. **Per-employee breakdown.**

Date picker defaults to the current business day. Light theme.

### L. Admin — audit
`GET /audit` paged, filterable by entity and actor. Newest first.

---

## 4. Build order

**POS first, dashboard last.** If the week slips, the missing piece must be the screen the owner
can live without for a week — not the screen staff need on Saturday.

| Phase | Contents | Gate |
|---|---|---|
| **A** | Vite + TS + Tailwind, tokens, API client with envelope unwrapping and typed errors, auth context, routing, login | Log in as both roles, land on the right screen, 401 routes back |
| **B** | Floor view, live timers, start session, **plus a small backend addition: `GET /api/v1/bills/unsettled`** returning unpaid bills for the current business day, and the floor-header strip that consumes it | Open a table, watch it tick, see it on a second browser; close a session without paying and find the bill again from the floor |
| **C** | Session screen, ordering, void, pause/resume, close | Run a session with items and a void end to end |
| **D** | Checkout, all three payment methods, both coded errors, receipt | Take a payment; stale version and duplicate reference both behave |
| **E** | End of day, cash count | Close a business day |
| **F** | Admin catalog + stock | Manage products and tables |
| **G** | Admin dashboard + audit | Numbers match the API, and the trace day shows ₱630.00 / ₱472.50 |

**Phases A–E are Saturday. F–G are the following week if needed.**

---

## 5. Acceptance checks

Click these through in a browser, not just in tests:

1. **The worked trace, by hand.** Table 3, two beers and a sisig, void one beer, close, pay GCash.
   The screen must show **₱630.00**, and the dashboard for that business day must agree.
2. **Refresh mid-session.** The timer resumes at the right elapsed time — no state was in the browser.
3. **Two tabs, same table.** Both press Start; exactly one wins and the other shows a clean message.
4. **Two tabs, same bill.** Both check out. The second gets a plain 409 `"Bill is already CLOSED."`
   — *not* `STALE_BILL_VERSION` — and must recover gracefully: refetch, say it was already paid,
   offer the receipt. Also test the stale-total variant: load checkout in two tabs, add a line in
   one, pay in the other, and confirm the amount-mismatch 409 is handled without resending the old
   amount.
5. **Employee login shows no cost or profit anywhere** — check the network responses, not just the UI.
6. **Pause for a minute.** The charge does not advance while paused.
7. **Kill the backend mid-shift.** The UI degrades with a clear message rather than white-screening,
   and recovers when it returns.
