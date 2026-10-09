# QA Part 2 PR-15 — Expenses and Settings

Scope: `qapart2.pdf` pages 19–20 and 22–23, and the PR-15 brief in the owner's local `QA-PART2-PR-PLAN.md`. PR-01 dependency merged as GitHub #11 (`6e4c1e0`), verified as an ancestor of the starting main (`251d42d`, including PR-14). No business decision is outstanding for this brief.

Expenses now places Add expense beside Tonight at desktop widths and stacks them on mobile. What for uses API-backed, labeled native radio pills with Space/arrow-key selection, visible focus, a non-color selected indicator, and 44px minimum targets. Categories show loading, empty, and failure states; long labels/notes wrap. Settings retains its 36rem form cap and centers the card within the available content area. Expense save/void, drawer totals, Settings help and save behavior, permissions, and API contracts are preserved.

## Screenshots

Captured in Chromium against the local guarded scratch application. Read-only API fixtures supply the same categories, expenses, and settings before and after; no trading data is used. Expense screenshots show a selected category, drawer/non-drawer expenses, a retained void, and reconciled totals.

| Screen | Light 1280 | Dark 1280 | Light 400 | Dark 400 |
| --- | --- | --- | --- | --- |
| Expenses before | [View](before-light-1280-expenses.png) | [View](before-dark-1280-expenses.png) | [View](before-light-400-expenses.png) | [View](before-dark-400-expenses.png) |
| Expenses after | [View](after-light-1280-expenses.png) | [View](after-dark-1280-expenses.png) | [View](after-light-400-expenses.png) | [View](after-dark-400-expenses.png) |
| Settings before | [View](before-light-1280-settings.png) | [View](before-dark-1280-settings.png) | [View](before-light-400-settings.png) | [View](before-dark-400-settings.png) |
| Settings after | [View](after-light-1280-settings.png) | [View](after-dark-1280-settings.png) | [View](after-light-400-settings.png) | [View](after-dark-400-settings.png) |

## Coverage

`pr15-expenses-settings.spec.ts` drives employee expense recording/voiding and admin Settings saves against the real scratch backend, checking persistence in separate API requests and after reload at both widths/themes. It covers native keyboard category selection, missing/invalid input, retained void reasons, drawer/non-drawer totals, category loading/empty/error states, pending mutations, server errors, and 30 long categories/expenses. Existing `pr02-shell.spec.ts` covers route access, roles, short-screen navigation, and the mobile drawer.

## Actual checks

- `cd frontend && npm run typecheck` — passed.
- `cd frontend && npm run build` — passed (217 modules; Vite retains the existing >500kB chunk advisory).
- `DB_URL=jdbc:postgresql://localhost:5432/supreme_pr15_scratch DB_USERNAME=supreme DB_PASSWORD=supreme ./mvnw resources:copy-resources@copy-frontend test` — **275 tests, 0 failures, 0 errors, 0 skipped**. A new local PostgreSQL 18 scratch database was created for this run and dropped afterward.
- `PR15_CAPTURE=before E2E_DB_NAME=supreme_pr15_e2e E2E_API_PORT=8086 E2E_WEB_PORT=5179 npm run e2e --prefix frontend -- pr15-screenshots.spec.ts` — **1 passed**, producing eight baseline screenshots from unchanged main application code.

- `PR15_CAPTURE=after E2E_DB_NAME=supreme_pr15_e2e E2E_API_PORT=8086 E2E_WEB_PORT=5179 npm run e2e --prefix frontend -- pr15-expenses-settings.spec.ts pr15-screenshots.spec.ts pr02-shell.spec.ts` — **18 passed (1.4m)** on the restored final implementation, including eight after screenshots.
- Mutation check: temporarily replaced the category selection handler with an empty selection, then ran `E2E_DB_NAME=supreme_pr15_e2e E2E_API_PORT=8086 E2E_WEB_PORT=5179 npm run e2e --prefix frontend -- pr15-expenses-settings.spec.ts --grep 'settings save at 1280px light' --max-failures=1`. It failed at the expected checked-radio assertion. The implementation was restored byte-for-byte before final verification.

No reports were changed, so `npm run test:reports` was not run. The entire browser suite was not run; the focused suite and existing shell checks are the relevant coverage. No backend, schema, or API changes. No merge or deployment.
