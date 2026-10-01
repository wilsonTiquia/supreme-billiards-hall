# QA Part 2 — PR-03 evidence

Scope: analytics tooltips, dashboard sales-source donut, shared table tiles and top-five product summaries. Reference: `qapart2.pdf`, pages 6–10, and the local QA-PART2-PR-PLAN.md shared instructions / PR-03 brief.

Base: `8056d01225ca18ac8ca972cb7fcd8bb6a16195d9` (latest `origin/main`, checked again before submission). Required PR-01 landed in #11 at `6e4c1e0`; `git merge-base --is-ancestor` confirmed it is in the base. PR-02 (#12) is also present. Work was isolated on `codex/qa-part2-pr03`; the original checkout's untracked plan and TestDTO.java were preserved.

## Pending business decision

There is no premium flag in PoolTable, TableUtilisation or PeriodTable. Seeded names alone are not confirmed classification metadata. Stars and the Premium legend are not implemented pending the owner's answer: defer this part to a metadata prerequisite, or add an explicit flag with an approved default and backfill. No schema, API, rate or table-name changes were made. The PR remains a draft until that scope decision is resolved.

## Implementation

- Night/hour bars show exact peso amounts (including centavos), date/hour and bill count. Pointer hover, keyboard focus with arrow/Home/End navigation, and touch selection work. Escape dismisses the tooltip. Captions remain unchanged during selection.
- A 44px native selector provides a touch and keyboard alternative to narrow bars, including 366-date periods. Tooltip placement stays inside the chart's horizontal bounds. The report break-even label is hidden only when its rendered rectangle overlaps the tooltip; the line remains and the label is restored afterward and in print.
- The SVG donut encodes only positive table/product source amounts. The existing discount/voucher reconciliation stays in the separate numeric ledger; empty and single-source donuts are explicit.
- Table tiles preserve dashboard held time/utilisation and report revenue/occupied-hour context. Two-line product entries preserve ordering and the five-item limit. Detailed report margin figures remain in the existing report detail views.
- Print-only tables expose every night/hour amount and bill count without requiring hover. Print media was exercised in Chromium; no physical printer was used.

## Actual validation

All browser runs used `E2E_DB_NAME=supreme_pr03_e2e E2E_API_PORT=8083 E2E_WEB_PORT=5176`. The repository's Playwright guards and scratch-backend script remained enabled. Visual report fixtures intercept read-only responses from this scratch app; they are synthetic display scenarios, not production data or accounting reconciliation fixtures.

- `npm --prefix frontend run typecheck` — passed.
- `npm --prefix frontend run build` — passed. Existing Vite chunk-size advisory remains. Installed npm also warns that the local Node 20.13.1 is below its supported patch range; commands completed successfully.
- `npm --prefix frontend run test:reports` — 5 passed, 0 failed, 1 skipped. The optional real-response CSV reconciliation test was not configured (`REAL_REPORT_JSON` unset).
- `DB_URL=jdbc:postgresql://localhost:5432/supreme_pr03_checks_scratch DB_USERNAME=supreme DB_PASSWORD=supreme ./mvnw -B resources:copy-resources@copy-frontend test` — 245 tests, 0 failures, 0 errors, 0 skipped. Database was separately created for this run.
- `PR03_CAPTURE=before ... npm --prefix frontend run e2e -- pr03-screenshots.spec.ts` — 1 passed; captured unmodified main with the same fixtures.
- `PR03_CAPTURE=after ... npm --prefix frontend run e2e -- pr03-analytics.spec.ts pr03-screenshots.spec.ts pr01-pagination.spec.ts worked-trace.spec.ts` — 12 passed. Includes existing report/filter/export/pagination behavior and the real scratch checkout worked trace at ₱630.00.
- Mutation check: temporarily changed tooltip formatting from exact centavos to rounded pesos. `... npm --prefix frontend run e2e -- pr03-analytics.spec.ts --grep 'night tooltips'` failed at the expected `₱1,234.56` assertion. Restored the exact formatter before final checks.
- Final focused run after collision detection/hour-chart sizing: `PR03_CAPTURE=after ... npm --prefix frontend run e2e -- pr03-analytics.spec.ts pr03-screenshots.spec.ts` — 7 passed, 0 failed.
- Final keyboard-range and stationary-pointer regression: `E2E_DB_NAME=supreme_pr03_e2e E2E_API_PORT=8083 E2E_WEB_PORT=5176 npm --prefix frontend run e2e -- pr03-analytics.spec.ts` — 6 passed, 0 failed. Typecheck/build also passed after this final interaction fix.
- `git diff --check` — passed. Both task-owned scratch databases were removed after validation.

Browser coverage includes stable captions; mouse, keyboard, touch and Escape; overlapping/non-overlapping break-even labels; exact centavos; zero/single-source sales; bill discounts/vouchers; zero-valued hours; long product names; top-five ordering; 366 dates; loading/error states; and printable night/hour figures.

The full unrelated Playwright suite was not run. No merge or deployment was performed.

## Visual review and existing limitation

Before and after screenshots cover Dashboard and Reports in light/dark themes at 1280px and 400px viewport widths. The existing Reports page has document-level horizontal overflow: its 400px-viewport full-page captures are 750px wide both before and after. PR-03's changed chart/tooltip/summary regions fit the viewport. This pre-existing Reports layout issue was not expanded into this PR; focused chart screenshots below show the actual mobile width.

| Screen | Width / theme | Before | After |
|---|---|---|---|
| Dashboard | 1280 light | [Before](before-dashboard-light-1280.png) | [After](after-dashboard-light-1280.png) |
| Dashboard | 1280 dark | [Before](before-dashboard-dark-1280.png) | [After](after-dashboard-dark-1280.png) |
| Dashboard | 400 light | [Before](before-dashboard-light-400.png) | [After](after-dashboard-light-400.png) |
| Dashboard | 400 dark | [Before](before-dashboard-dark-400.png) | [After](after-dashboard-dark-400.png) |
| Reports | 1280 light | [Before](before-reports-light-1280.png) | [After](after-reports-light-1280.png) |
| Reports | 1280 dark | [Before](before-reports-dark-1280.png) | [After](after-reports-dark-1280.png) |
| Reports | 400 light | [Before](before-reports-light-400.png) | [After](after-reports-light-400.png) |
| Reports | 400 dark | [Before](before-reports-dark-400.png) | [After](after-reports-dark-400.png) |

### Focused interactions and print

![Report tooltip, mobile light](after-reports-tooltip-light-400.png)
![Hourly tooltip, mobile dark](after-hour-tooltip-dark-400.png)
![Dashboard tooltip, desktop light](after-dashboard-tooltip-light-1280.png)
![Printed report with exact figures](after-report-print.png)

Additional tooltip captures for both screens, both themes and both widths are in this directory.
