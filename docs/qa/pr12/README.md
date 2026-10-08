# QA Part 2 PR-12 — Voucher batch cards and readable codes

Reference: `qapart2.pdf`, pages 18–19, and PR-12 in the local QA Part 2 implementation plan.
Started from `origin/main` at `e5f5e43174beb011005c54936538011b39419ec1` in an isolated worktree on `codex/qa-pr12-voucher-cards`. The sole dependency, plan PR-01, is merged as GitHub #11 (`6e4c1e061bdb028454073012613662ecd5cea8ff`), verified as an ancestor of the starting commit.

## Scope

- Batch cards lead with the API's coupon quantity and hours label, then giveaway note, creator/time, three count tiles, and expiry pill. Counts and code statuses remain server authoritative.
- Generated and inspected codes use the same selectable, single-column list. Read-only check indicators, explicit Outstanding/Expired text, and redeemed receipt numbers replace strikethrough codes and unlabeled numbers. A redeemed code without a receipt number says that its receipt is not yet available; receipt numbers remain text.
- Copy all codes copies every display code, one per line, with success/failure feedback. Print codes prints the entire list without app navigation or viewport clipping. Native dialogs preserve Escape/backdrop dismissal, background inertness, and focus restoration when the opener remains mounted.
- Existing archive/delete/restore controls and confirmations are preserved. The plan predates the already-merged V21 migration and setup lifecycle: main already archives batches and prevents redemption of their unused codes. This PR does not decide or implement PR-13's archive policy, add an archive action, or change those server rules.
- No API, permission, migration, accounting, expiry, or redemption changes. No new dependencies.

## Verification

All browser runs use the guarded scratch backend on port **8092**, Vite on **5192**, and database **supreme_pr12_e2e**. Backend tests use the separate **supreme_pr12_scratch** database. Nothing runs against the trading database or port 8080.

- `npm run typecheck` and `npm run build` from `frontend/` — **passed** (final check used bundled Node 24.19.0).
- `PGPASSWORD=supreme createdb -h localhost -U supreme supreme_pr12_scratch`
- `DB_URL=jdbc:postgresql://localhost:5432/supreme_pr12_scratch DB_USERNAME=supreme DB_PASSWORD=supreme ./mvnw resources:copy-resources@copy-frontend test` — **265 tests, 0 failures, 0 errors, 0 skipped**.
- `E2E_DB_NAME=supreme_pr12_e2e E2E_API_PORT=8092 E2E_WEB_PORT=5192 PR12_CAPTURE=before npm --prefix frontend run e2e -- pr12-screenshots.spec.ts` — **1 passed**, captured before implementation.

- `E2E_DB_NAME=supreme_pr12_e2e E2E_API_PORT=8092 E2E_WEB_PORT=5192 PR12_CAPTURE=after npm --prefix frontend run e2e -- pr12-vouchers.spec.ts pr12-screenshots.spec.ts voucher-prize.spec.ts` — **8 passed (1.0m)** on the final implementation; 28 before/after PNGs captured and representative desktop/mobile, generated, expired, and print views visually reviewed.
- Regression sensitivity: temporarily rendered `redeemedReceiptNo + 1`, then ran `E2E_DB_NAME=supreme_pr12_e2e E2E_API_PORT=8092 E2E_WEB_PORT=5192 npm --prefix frontend run e2e -- pr12-vouchers.spec.ts -g 'batch counts'` — **1 failed**, specifically at the expected `Redeemed on receipt #2533` assertion. Restored the correct implementation; the final 8-test run above passed. The deliberate mutation is not committed.
- `git diff --check` — **passed**.

The focused browser tests cover server counts; new, partial, fully redeemed and expired batches; receipt-number fallback; inert indicators; keyboard scrolling; modal dismissal and focus; 500-code copying and full print expansion; clipboard failure; long codes and labels at 1280/400px in both themes; loading/error/empty states; real creation/read-back; real employee API 403 responses and route guarding; and the existing prize redemption, zero-total checkout and reporting journey. That existing journey now also verifies the owner's code list against the actual receipt. Its stale Table use disclosure selector was updated to the always-visible table tiles already on main.

Not run: the entire unrelated Playwright suite, Firefox/WebKit, or a physical printer. Report unit tests are not required for this voucher-only change. The production build retains its existing large-chunk advisory.

## Screenshots

Synthetic display fixtures are used for repeatable visual comparison; separate tests exercise real creation, redemption, receipts, and employee restrictions. No live customer codes are included.

| Surface | Before | After |
|---|---|---|
| Desktop light batches | [Before](before-light-1280-batches.png) | [After](after-light-1280-batches.png) |
| Desktop dark batches | [Before](before-dark-1280-batches.png) | [After](after-dark-1280-batches.png) |
| Mobile light batches | [Before](before-light-400-batches.png) | [After](after-light-400-batches.png) |
| Mobile dark batches | [Before](before-dark-400-batches.png) | [After](after-dark-400-batches.png) |
| Desktop light codes | [Before](before-light-1280-codes.png) | [After](after-light-1280-codes.png) |
| Desktop dark codes | [Before](before-dark-1280-codes.png) | [After](after-dark-1280-codes.png) |
| Mobile light codes | [Before](before-light-400-codes.png) | [After](after-light-400-codes.png) |
| Mobile dark codes | [Before](before-dark-400-codes.png) | [After](after-dark-400-codes.png) |

Additional `after-*-generated-codes.png`, `after-*-expired-codes.png`, and `after-*-print.png` captures cover the generated list, redeemed/expired distinction, and print layout in both themes and widths.
