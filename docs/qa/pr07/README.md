# QA Part 2 PR-07 — Single-product stock and default purchase cost

Implemented only PR-07 from the local `docs/qa/QA-PART2-PR-PLAN.md`, using the Products notes and screenshots on pages 12–13 of `qapart2.pdf`. Started from latest main `0d4d3cafcd5ef5de25cf6c842e70b8bc93457da4` in an isolated worktree. Dependency PR-06 is merged as [#17](https://github.com/wilsonTiquia/supreme-billiards-hall/pull/17); its merge commit was verified as an ancestor of this branch. The original checkout's untracked planning file and TestDTO remain untouched.

## Approved policy — 7 October 2026

The owner confirmed a separate admin-editable default purchase cost, no backfill from average cost, and blocking the single-product shortcut while the default is unset. Explicit zero is allowed for genuinely free stock, but saving zero requires confirmation. Blank/unset must never automatically become zero.

- V24 adds a nullable, nonnegative `numeric(12,4)` default. Existing products stay unset. Admin create/edit can set or clear it; employee DTOs omit it entirely. A zero default requires `confirmZeroDefaultCost: true` on every save, enforced by the server and an initially unchecked UI checkbox.
- Product edits audit the default and take the product row lock shared by the stock ledger. Editing the default does not change stock quantity, moving average, historical bill-line costs or receipt snapshots. The optional opening-stock pair and actual bulk-delivery prices remain separate.
- Add stock is locked to the selected product, with a decrement/direct-entry/increment control and 0.001 quantity precision. The read-only unit cost displays up to four decimal places; Edit product supplies its edit path. Unset blocks submission; zero is labeled as free stock.
- The new admin-only `/stock/product-deliveries` endpoint locks the branch-scoped product, resolves its saved cost, and rejects a mismatched displayed cost before creating any delivery. The operator closes/reopens to review refreshed values and submits again. The existing receive-delivery transaction records the movement and weighted average.
- A synchronous UI guard and a page-level mutation prevent duplicate submissions, including while the pending dialog is closed. Pending and failure status remain visible on the catalog. This is UI duplicate suppression, not cross-tab or network-replay idempotency.
- Bulk Stock delivery behavior is retained. No report, accounting formula, pricing-history or permission changes. The API contract documents null, zero, confirmation and stale-cost behavior.

## Actual validation

From `frontend/`:

```sh
npm run typecheck
npm run build
```

Both passed, including after the final product-page change. Build transformed 210 modules. Existing environment warnings: npm 11.11.1 warns about Node 20.13.1, and Vite reports a JS chunk larger than 500 kB. No dependencies were added.

From the repository root, on a newly created PostgreSQL 18 scratch database:

```sh
PGPASSWORD=supreme /Library/PostgreSQL/18/bin/createdb -h localhost -U supreme pr07_scratch
DB_URL=jdbc:postgresql://localhost:5432/pr07_scratch DB_USERNAME=supreme DB_PASSWORD=supreme ./mvnw resources:copy-resources@copy-frontend test
```

**265 tests, 0 failures, 0 errors, 0 skipped — BUILD SUCCESS.** The 15 new backend checks cover persisted default edits, null versus confirmed zero, invalid cost/quantity rejection, weighted delivery valuation, actual bulk costs, stale-price rejection, cross-branch denial, employee omission/write denial and immutable historical sale/receipt data. Every persistence assertion crosses a transaction boundary.

To prove the regression checks detect a real defect, temporarily changed the new column mapping to `updatable = false` and ran:

```sh
DB_URL=jdbc:postgresql://localhost:5432/pr07_scratch DB_USERNAME=supreme DB_PASSWORD=supreme ./mvnw -Dtest=ProductPurchaseCostAcceptanceTest test
```

The unchanged tests failed twice: a saved zero was still null, and a stale expected cost was accepted because the cost edit had never persisted. Restored the mapping and reran the same command: **15 passed, 0 failures/errors/skipped**. The deliberate defect was never committed.

From `frontend/`, using the repository's guarded scratch-server workflow:

```sh
E2E_DB_NAME=pr07_e2e E2E_API_PORT=8087 E2E_WEB_PORT=5177 PR07_CAPTURE=after npm run e2e -- pr07-stock.spec.ts pr07-screenshots.spec.ts pr06-products.spec.ts stock-qa.spec.ts employee-sees-no-cost.spec.ts
```

**19 passed (1.1m)**. Coverage includes zero confirmation/reset/clearing, fractional direct entry and stepper controls, invalid quantities, same-tick duplicate submits, pending dismissal, errors after dismissal, stale-price rejection, explicit service failure/retry, four-decimal cost display, 1280/400px light/dark rendering, long product names, loading/empty/error/150-product catalog states, image creation/retry/edit, archive/restore, existing bulk-stock/give-away controls and employee cost isolation.

Before capture on unchanged main used `PR07_CAPTURE=before` with `pr07-screenshots.spec.ts`: **1 passed (36.0s)**. The initial implementation's stock/browser run passed 9 checks; the final expanded run above is the final browser result.

Scratch databases were dropped after the runs:

```sh
PGPASSWORD=supreme /Library/PostgreSQL/18/bin/dropdb -h localhost -U supreme pr07_scratch
PGPASSWORD=supreme /Library/PostgreSQL/18/bin/dropdb -h localhost -U supreme pr07_e2e
```

`git diff --check` passed.

Initial setup failures were corrected before the successful runs: the new fixture called a nonexistent `CustomerType.setIsActive`; later, a browser assertion needed to distinguish the pending status banner from the success toast. Neither failure is represented as a passing run.

The full unrelated browser suite and `npm run test:reports` were not run; this PR does not change reports. No trading database, merge or deployment was used.

## Screenshots

Before images were captured on the unchanged main UI before implementation. The same read-only catalog fixtures are used for comparable before/after screenshots; stock mutation tests use the real scratch API, with only deliberate failure/latency interception. The cost fixture uses 62.5123 to verify four-decimal display. Screenshots were visually inspected for wrapping, legibility and clipping.

| View | Before | After |
|---|---|---|
| 1280px light stock | [Before](before-light-1280-stock.png) | [After](after-light-1280-stock.png) |
| 1280px dark stock | [Before](before-dark-1280-stock.png) | [After](after-dark-1280-stock.png) |
| 400px light stock | [Before](before-light-400-stock.png) | [After](after-light-400-stock.png) |
| 400px dark stock | [Before](before-dark-400-stock.png) | [After](after-dark-400-stock.png) |
| 1280px light edit | [Before](before-light-1280-edit.png) | [After](after-light-1280-edit.png) |
| 1280px dark edit | [Before](before-dark-1280-edit.png) | [After](after-dark-1280-edit.png) |
| 400px light edit | [Before](before-light-400-edit.png) | [After](after-light-400-edit.png) |
| 400px dark edit | [Before](before-dark-400-edit.png) | [After](after-dark-400-edit.png) |

[Zero confirmation](after-zero-confirmation.png) · [Unset cost](after-unset.png) · [Stale cost](after-stale-cost.png).
Long names: [light desktop](after-light-1280-long-name.png), [dark desktop](after-dark-1280-long-name.png), [light phone](after-light-400-long-name.png), [dark phone](after-dark-400-long-name.png).
