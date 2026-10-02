# PR-05: Reports summary and financial details

Scope: QA Part 2 pages 8–12, Reports notes 2–3 and 7–11. Inspected all five
reference pages. Started from latest main `48ceabc` in an isolated worktree.
Dependencies PR-01 (#11, `6e4c1e0`) and PR-03 (#13, `41808d4`) are merged ancestors.
The original worktree's untracked plan and `TestDTO.java` were preserved.

## Behavior

The toolbar puts presets, an accessible date range, Export and Refresh on one row
at 1280px. Refresh retains its accessible name when its text is hidden; phone
controls wrap. Date input, automatic application, invalid-draft handling, presets,
URL navigation, filtering, sorting, pagination and export use their existing paths.

A single summary contains the headline, current/prior ranges and all five server
figures: `gross`, `costOfGoods`, `grossProfit`, `operatingExpenses`, `net`. Product
costs are the first subtraction; gross profit is the intermediate result, and
operating expenses are the second subtraction. No amount is newly calculated by
the UI. Negative net and zero-prior handling are retained. Compact comparisons omit
the repeated visible prior range but retain their full accessible context.

Report disclosures opt into shared SVG icons, stacked title/summary and a trailing
chevron; other Disclosure consumers retain their existing presentation. Bills and
averages and Drawer use tiles. Given away keeps related entries in column groups,
with Comps in the second desktop column. Still owed uses the original three age
buckets and totals in an Age / Bills / Amount table. Closed details still print
expanded; print headings stay attached to their content. The mobile shell menu is
hidden in print, including narrow printable areas. PR-03 analytics and PR-01
pagination are reused. No API, schema, accounting, branch or permission change.

## Screenshots

Before captures use unchanged main application code at `48ceabc`; after captures
use this PR. Login uses the real scratch app; report values are synthetic read-only
fixtures for reproducible visual and field-mapping checks, not venue financials.
They include long product names, 40 products, debts, comps and drawer variance.

| Width / theme | Summary | Full report with expanded details |
| --- | --- | --- |
| 1280 / light | [Before](before-light-1280-summary.png) · [After](after-light-1280-summary.png) | [Before](before-light-1280-full.png) · [After](after-light-1280-full.png) |
| 1280 / dark | [Before](before-dark-1280-summary.png) · [After](after-dark-1280-summary.png) | [Before](before-dark-1280-full.png) · [After](after-dark-1280-full.png) |
| 400 / light | [Before](before-light-400-summary.png) · [After](after-light-400-summary.png) | [Before](before-light-400-full.png) · [After](after-light-400-full.png) |
| 400 / dark | [Before](before-dark-400-summary.png) · [After](after-dark-400-summary.png) | [Before](before-dark-400-full.png) · [After](after-dark-400-full.png) |

[Full print output](after-print.pdf), including every night, all 40 products, and
financial details. Browser coverage also exercises a 365-day range, negative net,
empty results, zero prior values, loading, errors and keyboard expansion.

## Actual validation (3 October 2026)

Commands run from the repository root unless noted. Node 24 from the bundled runtime
was used for final checks (the initial install/capture used the system Node 20).

| Command | Result |
| --- | --- |
| `npm ci --prefix frontend` | Passed; 0 vulnerabilities |
| `npm --prefix frontend run typecheck` | Passed |
| `npm --prefix frontend run build` | Passed; existing >500 kB bundle warning |
| `npm --prefix frontend run test:reports` | 5 passed, 0 failed, 1 skipped (opt-in live-API export test) |
| `DB_URL=jdbc:postgresql://localhost:5432/supreme_pr05_scratch DB_USERNAME=supreme DB_PASSWORD=supreme ./mvnw -B resources:copy-resources@copy-frontend test` | 250 tests, 0 failures, 0 errors, 0 skipped |
| `PR05_CAPTURE=before E2E_DB_NAME=supreme_pr05_e2e E2E_API_PORT=8085 E2E_WEB_PORT=5178 npm --prefix frontend run e2e -- pr05-screenshots.spec.ts` | 1 passed, before application edits |
| `PR05_CAPTURE=after E2E_DB_NAME=supreme_pr05_e2e E2E_API_PORT=8085 E2E_WEB_PORT=5178 npm --prefix frontend run e2e -- pr05-reports.spec.ts pr05-screenshots.spec.ts pr01-pagination.spec.ts pr03-analytics.spec.ts` | 18 passed, 0 failed |
| `git diff --check` | Passed |

Regression sensitivity check: temporarily rendered `costOfGoods` as After product
costs, ran `pr05-reports.spec.ts -g 'financial equation'` with the same scratch
settings, and observed the expected failure: expected ₱183,092, received ₱152,574.
Restored `grossProfit` before final validation. The test assertion was not changed.

Initial browser runs exposed test-selector errors (capitalization, multiple status
regions, and a print locator depending on a hidden button). Those selectors were
corrected. Screenshot review prompted figure alignment and print spacing fixes.

The unrelated full Playwright suite, Safari/Firefox, manual screen-reader testing
and opt-in live-API export test were not run. Existing browser regression checks
cover CSV row counts and filtering against the scratch instance. No trading data,
production service, merge or deployment was used.
