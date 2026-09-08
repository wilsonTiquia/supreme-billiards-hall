# Frontend — Supreme Billiard Hall POS

React SPA for the POS backend in this repo. Lives in `frontend/`. The backend is finished and
running; this is the layer staff actually touch on a Friday night.

**Go-live: 5 September 2026, at the venue, with paper chits running in parallel for week one.**

---

## 0. Before you write any component

Read, in this order:

1. **`docs/API-CONTRACT.md`** — every route, every shape, every error code. It was extracted from
   the running controllers and verified over HTTP. Trust it over any assumption.
2. **`docs/FRONTEND-SPEC.md`** — screens, states, build order.
3. The backend controllers in `src/main/java/.../controller/` when you need to confirm a shape.
   You have the actual source — use it rather than guessing.

There is **no existing frontend code**, so unlike the backend there is no house style to match.
Establish one in Phase A and hold it for the rest. State it back to me before you build Phase B.

---

## 1. Stack — pinned

| Concern | Choice |
|---|---|
| Build | Vite |
| Language | TypeScript, `strict: true` |
| UI | React 18+, function components, hooks |
| Server state | TanStack Query |
| Routing | React Router |
| Styling | Tailwind + CSS custom properties for the palette |
| Components | Hand-rolled. **No component library** |
| Charts | Hand-authored inline SVG. **No charting library** |

Do not add a dependency without saying why first. A POS needs roughly eight components; a library
is more surface to restyle into the Supreme palette than to build.

---

## 2. The five rules that matter most

These are the ones where a mistake costs money rather than looks.

### 1. The server is the only clock

Compute a clock offset **once** from `serverNow`, then render counters locally:

```ts
const offset = Date.parse(serverNow) - Date.now();
const nowOnServer = () => Date.now() + offset;
```

Render the elapsed counter from `openedAt` and that offset. **Never send a duration, an elapsed
time, or a computed amount to the server.** The authoritative `billedMinutes` and `timeAmount` come
back on every poll; the local counter exists only so the display does not freeze between polls.

If the local counter and the server disagree, **the server is right** — reconcile to it silently.

### 2. Money is never computed in the browser

Every peso figure displayed comes from the API. Do not multiply a rate by minutes, do not sum line
totals, do not compute change. The server does all of it, including change from `tendered`. The
client formats and displays.

Format with `Intl.NumberFormat('en-PH', { style: 'currency', currency: 'PHP' })`.

### 3. Every response is wrapped

`{ data, message, success }`. Write **one** API client that unwraps `data`, throws a typed error
carrying `message`, `status` and the optional `code`, and never let a raw `fetch` appear in a
component.

`credentials: 'include'` on every request — auth is a session cookie, not a token. A 401 anywhere
means the session died: clear the query cache and route to login.

### 4. POST returns 200, never 201

Do not write a `201` branch. Routes are `/api/v1/...`.

### 5. Employees must never see cost or profit

The API already omits those fields for an EMPLOYEE, so the protection is real. Do not build UI that
reads a `cost` or `profit` field on an employee screen, and do not add a client-side role check as
the *only* guard — it is a display convenience, not security.

---

## 3. Error handling

Two coded errors need real UI, not a toast:

| Case | What to do |
|---|---|
| `DUPLICATE_PAYMENT_REFERENCE` | Show the message and offer **"Record anyway"**, resending with `duplicateOverride: true` and the **same** `idempotencyKey`. Not a hard failure — staff mistype references. |
| 409 `"Bill is already CLOSED."` — **no code** | The realistic two-tab race. Another tab settled it first. Refetch, tell the operator it was already paid, and offer the receipt. This is the one to get right; it is a plain coded-less 409, so match on status plus context, not on `code`. |
| 409 `"Amount … does not match the bill total …"` | Another tab changed the bill after you loaded it. Refetch, show the new total, ask again. **Never** resend with the old amount. |
| `STALE_BILL_VERSION` | Build the recovery — refetch and re-confirm — but know it is currently unreachable: `bill.version` only moves at settle, and the already-CLOSED check runs first. It becomes live if the backend ever bumps the version on line changes. |

**Send `billVersion` anyway.** It is defence-in-depth and costs nothing. The protection that actually fires today is the amount-equality check plus the bill-status check.

Everything else: show `message`. It is written for a human.

**409 is normal, not exceptional.** Table already occupied, session already paused, day cannot close
— these are expected states, and the UI should read them as guidance rather than as crashes.

---

## 4. Interaction rules

The counter is a **desktop with mouse and keyboard**, in a dark room, used under time pressure.

- **Keyboard first.** Product search is type-ahead and focused on open. Checkout is reachable
  without the mouse. This is the single biggest speed win available.
- **44px minimum hit target** anyway — costs nothing, faster under pressure, and means a tablet
  works later without a redesign.
- **Destructive actions are never adjacent to frequent ones.** Void sits away from Add item. Close
  session confirms with the amount shown.
- **Never block the floor view on a modal** that cannot be dismissed. Staff must always be able to
  see the room.
- **Optimistic updates are banned on anything involving money or stock.** Show a pending state and
  wait for the server. A line that appears and then vanishes is worse than one that takes 200ms.

---

## 5. Working style

- Build one screen end to end before starting the next. A half-finished floor view and a
  half-finished checkout is worse than one working screen.
- Co-locate by feature: `features/floor/`, `features/session/`, `features/checkout/`.
- Types for API shapes live in one place, derived from `API-CONTRACT.md`. Do not redeclare a
  `Session` type in three files.
- No `TODO` stubs that silently render nothing. If a screen is not built, say so.
- **`npm run build` is the verification command, not `npx tsc --noEmit`.** The two disagree:
  the build runs `tsc -b`, which checks the whole project graph, and it has caught real type
  errors — a missing field on an object literal built to satisfy an interface — that
  `--noEmit` passed clean. If you only ran `--noEmit`, you have not checked the frontend.
- Run it and click through it before reporting a phase done. "It compiles" is not "it works".

## 6. Ask, don't assume

Stop and ask when the API contract is ambiguous, when a business rule is unclear, or when a
dependency would be added. Report honestly — if something does not work, say so with the error.
