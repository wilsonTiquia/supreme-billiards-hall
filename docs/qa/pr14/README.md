# PR-14: session and Quick sale navigation

Reference: QA Part 2 PR-14, `qapart2.pdf` pages 20–22, inspected 9 October 2026.

Started from fetched `origin/main` at `0cd6a861ca59ac1c2a7fb24c84a0ca705395a0eb` in an isolated worktree. Both required dependencies are merged and ancestors of this base: PR-01 / GitHub #11 (`6e4c1e0`) and PR-02 / GitHub #12 (`8056d01`). No unresolved business decision applies to PR-14.

The session and Quick sale now have the same upper-right **Back to floor** secondary router link with the shared back icon. Pause uses the shared primary button; Resume and the separate red checkout action keep their existing behavior. Pictures in the shared product grid use 85% of their previous width and height, keeping their aspect ratio and the complete product tile clickable. Catalog thumbnails and upload previews are unaffected. No API, clock, accounting, stock, role or schema behavior changed.

## Visual evidence

Before captures use the unchanged base application. After captures use this implementation. All screenshots are Chromium at 1280×900 or 400×900 in both POS themes; session captures include the full page. The session/bill use the guarded scratch backend. The read-only catalog responses use 30 illustrative products, optional SVG pictures/initials, a long label, and a zero-stock product. These are synthetic visual fixtures, not production data. Interaction tests separately use actual stocked products and server responses.

| Screen | Light 1280 | Dark 1280 | Light 400 | Dark 400 |
|---|---|---|---|---|
| Session | [Before](before-light-1280-session.png) / [After](after-light-1280-session.png) | [Before](before-dark-1280-session.png) / [After](after-dark-1280-session.png) | [Before](before-light-400-session.png) / [After](after-light-400-session.png) | [Before](before-dark-400-session.png) / [After](after-dark-400-session.png) |
| Quick sale | [Before](before-light-1280-quick-sale.png) / [After](after-light-1280-quick-sale.png) | [Before](before-dark-1280-quick-sale.png) / [After](after-dark-1280-quick-sale.png) | [Before](before-light-400-quick-sale.png) / [After](after-light-400-quick-sale.png) | [Before](before-dark-400-quick-sale.png) / [After](after-dark-400-quick-sale.png) |
| Session product dialog | — | — | [Before](before-light-400-session-products.png) / [After](after-light-400-session-products.png) | [Before](before-dark-400-session-products.png) / [After](after-dark-400-session-products.png) |
| Long labels / search | [After](after-light-1280-long-label.png) | [After](after-dark-1280-long-label.png) | [After](after-light-400-long-label.png) | [After](after-dark-400-long-label.png) |

## Actual validation

Run from repository root unless indicated. PostgreSQL 18.6, JDK 25.0.1, Chromium through Playwright. Backend and browser suites use separate disposable databases; browser servers use 8081/5174 through the existing guarded workflow.

- `cd frontend && npm run typecheck && npm run build` — passed. Production build: 217 modules. Existing Vite bundle-size warning remains.
- `PGPASSWORD=supreme /Library/PostgreSQL/18/bin/createdb -h localhost -U supreme supreme_pr14_scratch`
- `DB_URL=jdbc:postgresql://localhost:5432/supreme_pr14_scratch DB_USERNAME=supreme DB_PASSWORD=supreme ./mvnw resources:copy-resources@copy-frontend test` — **275 tests, 0 failures, 0 errors, 0 skipped**. Dropped this scratch database afterward.
- `E2E_DB_NAME=supreme_pr14_e2e npm --prefix frontend run e2e -- pr14-navigation.spec.ts floor-qa.spec.ts worked-trace.spec.ts employee-sees-no-cost.spec.ts` — **11 passed**. Includes all 6 new PR-14 checks plus existing floor/payment/employee-visibility checks.
- `PR14_CAPTURE=before E2E_DB_NAME=supreme_pr14_e2e npm --prefix frontend run e2e -- pr14-screenshots.spec.ts` — **1 passed** against the unchanged application.
- `PR14_CAPTURE=after E2E_DB_NAME=supreme_pr14_e2e npm --prefix frontend run e2e -- pr14-screenshots.spec.ts` — **1 passed**, including 24 before/after evidence images across the capture runs.
- The new `1280px in light` navigation test was first run against unchanged main: **1 expected failure**, because the accessible `Back to floor` link was absent. It passed after implementation.

The new regression checks cover keyboard and pointer navigation, Start → session → pause → Floor → reopen → resume, search/category ordering, persisted bill lines, frozen paused time, server amounts and Quick sale quantity quotes, pending pause/resume buttons, a rejected pause without an optimistic status change, product loading/empty/search-empty states, and returning from a failed session load. The screenshot check also reaches the 30th product and filters to long labels.

No report code changed, so `npm run test:reports` was not run. The entire Playwright repository suite and other browser engines were not run. No merge or deployment was performed.
