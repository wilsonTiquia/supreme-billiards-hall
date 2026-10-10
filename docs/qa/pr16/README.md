# QA Part 2 PR-16 — Unsettled and End of day

Implements only PR-16 from the owner's `QA-PART2-PR-PLAN.md`, using the text and screenshots on pages 23–25 of `qapart2.pdf`. Started from latest main `36256ce` on a separate worktree/branch. Dependencies PR-01 (#11, `6e4c1e0`) and PR-02 (#12, `8056d01`) are merged and are ancestors of the branch. The original checkout's untracked plan and `TestDTO.java` are untouched.

## Behavior and decisions

- Group Unsettled by the API's `businessDate` (the night played), retaining first-seen date order and the server's newest-first order within each date. Existing note/date filters and totals remain intact. A filter with no matches no longer claims that nothing is owed globally.
- The API has one `latestNote.body`, not separate customer-name and promise-to-pay fields. Show the entire note without parsing or truncation beside the age pill; place tables and receipt access beneath it. Remove the per-row played date and counter attribution. Use a real Settle link to the existing payment flow.
- Both screens share the server-provided age and existing danger boundary: 0 days is Tonight; 1–13 days is neutral; 14+ days uses danger styling. The accessible label retains days outstanding. No browser clock computes age.
- Earlier nights are collapsed into one keyboard-operable banner with the actual count. Expansion shows date tags, bill-count pills and chevrons in a responsive 1/2/3-column grid. Compact dates omit weekdays and keep the existing Manila date-label convention.
- Opening float has a persistent heading and secondary Different action. The usual-value reset, blind count, corrections, recounts and server close guards remain intact. The unpaid-list link uses the shared tertiary treatment and retains its dynamic count label.
- Reset local count, mutation errors and draft fields when the selected End of day route date changes, so a saved count from one night cannot appear against another night.

No backend, API, schema, library or business-policy changes. No unresolved business decision is needed for this brief.

## Screenshots

Chromium against the guarded local scratch app, with identical read-only API fixtures before and after. Fixtures cover Tonight, 1/13/14/69-day debts, a long combined note, missing note/table, shared played dates and 20 earlier nights. Baseline images were captured before application source edits.

| Screen | Light 1280 | Dark 1280 | Light 400 | Dark 400 |
| --- | --- | --- | --- | --- |
| Unsettled before | [View](before-light-1280-unsettled.png) | [View](before-dark-1280-unsettled.png) | [View](before-light-400-unsettled.png) | [View](before-dark-400-unsettled.png) |
| Unsettled after | [View](after-light-1280-unsettled.png) | [View](after-dark-1280-unsettled.png) | [View](after-light-400-unsettled.png) | [View](after-dark-400-unsettled.png) |
| End of day before | [View](before-light-1280-end-of-day.png) | [View](before-dark-1280-end-of-day.png) | [View](before-light-400-end-of-day.png) | [View](before-dark-400-end-of-day.png) |
| End of day after | [View](after-light-1280-end-of-day.png) | [View](after-dark-1280-end-of-day.png) | [View](after-light-400-end-of-day.png) | [View](after-dark-400-end-of-day.png) |
| Earlier nights expanded | [View](after-light-1280-earlier-nights.png) | [View](after-dark-1280-earlier-nights.png) | [View](after-light-400-earlier-nights.png) | [View](after-dark-400-earlier-nights.png) |

## Coverage

`pr16-debts.spec.ts` checks date grouping and within-date order, combined note/date filtering, Tonight and the 13/14-day boundary, receipt/collection destinations, keyboard expansion, all 20 date URLs and selection, selected-night count reset, missing/long notes, loading/empty/filter-empty/error states, float reset/pending/error/retry and stale-night recount. Real scratch API/browser tests collect an employee's frozen debt and verify persistence in a separate request, then record/correct/close an earlier night's drawer. `pr02-shell.spec.ts` provides existing navigation, role and mobile-drawer regression coverage.

## Actual checks

- `npm run typecheck --prefix frontend` — passed.
- `npm run build --prefix frontend` — passed (218 modules; existing >500 kB chunk advisory).
- `DB_URL=jdbc:postgresql://localhost:5432/supreme_pr16_scratch DB_USERNAME=supreme DB_PASSWORD=supreme ./mvnw resources:copy-resources@copy-frontend test` — **275 tests, 0 failures, 0 errors, 0 skipped**. The first attempt began resource copying before the SPA build completed and had one missing `static/index.html` error; running after the completed build passed.
- `PR16_CAPTURE=before E2E_DB_NAME=supreme_pr16_e2e E2E_API_PORT=8087 E2E_WEB_PORT=5180 npm run e2e --prefix frontend -- pr16-screenshots.spec.ts` — **1 passed**, eight baseline screenshots.

- `PR16_CAPTURE=after E2E_DB_NAME=supreme_pr16_e2e E2E_API_PORT=8087 E2E_WEB_PORT=5180 npm run e2e --prefix frontend -- pr16-debts.spec.ts pr16-screenshots.spec.ts pr02-shell.spec.ts` — **20 passed (1.3m)**, including 12 final screenshots. An earlier run caught End of day's mobile overflow with an unbroken long note; the explicit single-column grid fixes it, and the final run passes in both themes and widths.
- Mutation check: temporarily grouped by `unsettledAt.slice(0, 10)` instead of `businessDate`, then ran `E2E_DB_NAME=supreme_pr16_e2e E2E_API_PORT=8087 E2E_WEB_PORT=5180 npm run e2e --prefix frontend -- pr16-debts.spec.ts --grep 'grouped debts and earlier-night navigation at 1280px light' --max-failures=1`. It failed at the date-group assertion (one October 10 group instead of the five stored played dates). The source was restored byte-for-byte before the final green run.

Both task-specific PostgreSQL scratch databases were dropped after testing. The browser runner used only ports 8087/5180. No trading database or production endpoint was used.

No report code changed, so `npm run test:reports` was not run. The entire browser suite was not run. No merge or deployment.
