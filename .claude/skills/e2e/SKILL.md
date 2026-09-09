---
name: e2e
description: Run, debug or extend the Playwright browser tests for the POS. Use whenever a change needs checking in a real browser rather than as JSON, when a test fails, or when adding a new path to the suite. States the database rule first, because these tests write committed rows.
---

# Browser tests (Playwright)

## The database rule — read this before running anything

**These tests drive a real running app, and every row they write is committed.** Real bills,
real payments, real stock movements, real vouchers spent. There is no transaction to roll them
back, because the browser is a separate process going through HTTP. This is the third face of
the hazard `docs/RUNBOOK.md` already records for `./mvnw test` and for the IDE Run button.

So the suite runs against **its own instance on 8081** and **its own throwaway database
`supreme_e2e`**, never the till on 8080 and never `supreme`.

That is enforced in code, not by remembering. `frontend/playwright.config.ts` throws at module
scope — before a browser or a JVM exists — when:

- the base URL is on port 8080;
- **the API target is on port 8080** (a dev server on 5174 proxying to 8080 has an innocent base
  URL and writes real bills into the till — this is the one that catches people);
- the database is `supreme`, or is not named `*_e2e`, or is not on this machine;
- the database already holds `payment` rows and carries no scratch marker (the only check that
  does not depend on somebody having named things right).

`scripts/e2e-backend.sh` repeats all of them, because it can be run by hand.

**If a refusal fires, it is working. Never set the variable it names to get past it.** The only
legitimate reason to set `E2E_DB_NAME` is to use a *different* scratch database, and it still
has to end in `_e2e`.

## Running them

```
cd ~/SupremeBilliards/frontend
npm run e2e
```

One command. Playwright starts both halves of the scratch instance, runs the four tests, and
stops them again. About 45 seconds, most of it Spring booting.

| What | Command |
|---|---|
| Everything | `npm run e2e` |
| One file | `npm run e2e -- worked-trace` |
| One file, watching it happen | `npm run e2e:headed -- worked-trace` |
| Step through it | `npm run e2e -- worked-trace --debug` |
| Look at a failure afterwards | `npx playwright show-trace test-results/<dir>/trace.zip` |

A failure also drops a screenshot and a `error-context.md` page snapshot in `test-results/` —
the snapshot is usually faster to read than the trace.

## What the scratch instance is

Two processes, both owned by the Playwright run (`webServer` in the config):

1. **`scripts/e2e-backend.sh`** — drops and recreates `supreme_e2e`, stamps it with the scratch
   marker, and starts the app on **8081** against it with `SUPREME_BOOTSTRAP_ADMIN_PASSWORD` set
   and the photo/image paths pointed at a temp folder. Flyway builds the schema on boot.
2. **`npm run dev -- --port 5174`** with `VITE_API_TARGET=http://localhost:8081` — the SPA,
   proxying `/api` to that instance so the session cookie stays first-party.

`spring-boot:run`, deliberately **not** `package`: packaging rewrites `target/*.jar`, which is
the file the live 8080 JVM is running from. That also means the frontend build never runs during
a test, which is why the SPA is served by vite rather than from `static/`.

Like every other script in `scripts/`, `e2e-backend.sh` is not in git — that whole directory is
gitignored and lives on the POS machine only.

## Logging in — the bit that is not obvious

A database built fresh from the migrations **has no usable login**. V9 wrote a non-bcrypt
sentinel over both seeded accounts, so no password authenticates them.

- **`owner`** comes back through `AdminPasswordBootstrap`: the backend script boots with
  `SUPREME_BOOTSTRAP_ADMIN_PASSWORD` set, and the app initialises the owner password once, only
  while the account still carries the sentinel. It does not set `must_change_password`.
- **`counter` takes two steps.** The owner sets a temporary password, and then the counter must
  change it themselves — `AuthServiceImpl.resetPassword` **always** sets `must_change_password`,
  so that the owner never holds a working credential for someone else's account, and V10 then
  gates that account to nothing but `/auth/me`, its own password and logging out. The fixture
  does both steps. Skip the second and every counter test lands on the "Set a new password" wall
  instead of the floor.

Both are in `e2e/fixtures.ts`. HELP.md describes the same sequence for a real install.

## What the fixtures do, and the one cheat

`e2e/fixtures.ts` gives every spec a stocked hall (worker-scoped, runs once):

- turns the counter login on, as above;
- creates the two products the project's arithmetic is quoted in — beer ₱90.00 / cost ₱62.50,
  sisig ₱180.00 / cost ₱95.00 — because **V2 seeds categories but no products**;
- receives one delivery, which is what gives them a unit cost.

It is find-or-create: Playwright restarts the worker after a failure and re-runs worker fixtures,
and setup that cannot run twice turns one real failure into four fake ones.

**`backdateSession(sessionId, minutes)` is a fixture cheat, and the only one.** It moves
`table_session.opened_at` and the segment's `started_at` back with a `psql` UPDATE. It exists
because ninety minutes of table time cannot be produced through the UI at all: the open-session
request carries no start time on purpose ("the server is the only clock"), and the billed-minutes
override only ever *reduces* — charging more than was played is refused as an overcharge. **No
user can do this.** Do not read it as a user action and do not build anything on the assumption
that a session's clock can be moved.

Setup goes over the API; assertions go through the page. Building a catalogue by clicking admin
forms would test those forms in every spec and make all four fail together when one changes.

## The four paths

| File | What it proves |
|---|---|
| `voucher-prize.spec.ts` | Owner generates a batch, counter redeems the code at checkout, bill closes at ₱0.00 with no payment row, and the night shows it under Vouchers with the forfeited minutes stated |
| `discount.spec.ts` | A bill charged down with a reason, the amount off shown — and the refusal when the full amount is typed, word for word |
| `worked-trace.spec.ts` | BACKEND-SPEC §4.1: Table 3, two beers and a sisig, void one, ninety minutes, GCash, **₱630.00** |
| `employee-sees-no-cost.spec.ts` | A counter's screens carry no cost or profit **in the markup**, not merely out of sight |

## Writing a new one

- **Prove it can fail.** Break the assertion on purpose, watch it go red, put it back. A test
  that structurally cannot fail reports a bug as fixed — that has already happened once on this
  project, and it cost a business day that could not be closed.
- Locators come from what the operator sees: `getByRole`, `getByLabel`, the server's own
  sentences. There are no test ids in this SPA and it does not need any.
- **Names collide.** `Cash` matches `GCash`; `Charge` matches `Charge less time`; a checkout
  button survives into the receipt through the morph animation. Use `exact: true` or the full
  label, and let strict mode tell you — it is doing its job when it complains.
- Assert the server's figures, never ones computed in the test. The browser does not do
  arithmetic here and neither should the spec.
