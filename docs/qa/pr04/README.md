# PR-04: dashboard night check and definitions

Scope: QA Part 2 pages 4–6, dashboard notes 1–4. Started from main `15221a3`.
Dependencies PR-01 (#11, `6e4c1e0`) and PR-03 (#13, `41808d4`) were merged and
are ancestors of that main commit. No business decision was required.

## Severity mapping

| Existing condition | Presentation | Action / date |
| --- | --- | --- |
| Completed night without a count; server `stale` flag; existing activity-after-close warning; any nonzero drawer variance | Red icon background, “Needs attention”, always expanded | Secondary drawer action, selected business night |
| Cash check fetch failed | Red warning, explicit unavailable text, always expanded | Secondary drawer action, selected business night |
| Current night without a count | Amber drawer icon, visible “Not counted yet” | Secondary drawer action, selected business night |
| Other uncounted nights | Amber icon, “Follow up · all nights”, always expanded | Secondary drawer action, oldest other uncounted night |
| Bills needing checkout / outstanding debt | Amber icon, “Follow up · all nights”, always expanded | Existing end-of-day / unpaid-list destinations |
| Balanced drawer | Green check icon with “Cash balanced” text | Secondary view-count action, selected business night |
| Cash or follow-up queries pending | Neutral loading text; no clear summary until resolved | Drawer remains accessible |
| Unpaid-bill or uncounted-night query failed | Explicit red warning and unavailable summary; retain any successfully fetched conditions | Secondary end-of-day review, selected business night |
| Low stock | Neutral, smaller icons/text under collapsed “Other checks”; product count visible before expansion | Tertiary stock link |

No monetary threshold was introduced: the existing zero/nonzero variance rule stays intact.
Only stock is collapsed. Drawer checks never move into “Other checks”, including a missing
count while a night is still running. The summary “No financial follow-up found” describes
these checks; it does not assert that the server will allow closing. Open sessions and all
close guards remain authoritative on End of day.

The backend already exposes `stale` for cash activity since counting. The dashboard now reads
it so that a zero recorded variance cannot mask a count that blocks closing. No endpoint,
migration, amount calculation, role restriction, or backend behavior changed.

## Definitions popup

The existing shared Modal has no focus containment or anchored positioning. This dashboard-only
popup uses native `dialog.showModal()` for background inertness and keyboard containment,
with explicit Escape, outside-pointer and close-control dismissal and focus restoration.
It is up to 820px wide, positioned near the info trigger and clamped to the viewport, with a
fixed header and internally scrolling definitions. Position updates on viewport resize.

## Evidence

The before captures use unchanged application code at `15221a3`; after captures use this PR.
Read-only synthetic responses cover missing counts, multiple financial follow-ups and 12
low-stock products with long labels. Authentication/navigation use the real scratch app.
These are test fixtures, not live venue figures.

| Width / theme | Night check before → after | Definitions before → after |
| --- | --- | --- |
| 1280 / light | [Before](before-light-1280-night.png) · [After](after-light-1280-night.png) | [Before](before-light-1280-definitions.png) · [After](after-light-1280-definitions.png) |
| 1280 / dark | [Before](before-dark-1280-night.png) · [After](after-dark-1280-night.png) | [Before](before-dark-1280-definitions.png) · [After](after-dark-1280-definitions.png) |
| 400 / light | [Before](before-light-400-night.png) · [After](after-light-400-night.png) | [Before](before-light-400-definitions.png) · [After](after-light-400-definitions.png) |
| 400 / dark | [Before](before-dark-400-night.png) · [After](after-dark-400-night.png) | [Before](before-dark-400-definitions.png) · [After](after-dark-400-definitions.png) |

Additional after views: [expanded Other checks on mobile](after-light-400-other-checks.png),
[full mobile night check](after-dark-400-checks.png), [clear night](after-clear.png),
and [checks unavailable](after-error.png). All four width/theme combinations include full
night-check and expanded-stock captures. Visually reviewed for wrapping, popup alignment,
contrast, and internal scrolling; browser tests also resize the popup to 400 × 480.

## Actual validation (2 October 2026)

| Command | Result |
| --- | --- |
| `npm ci` (frontend) | 93 packages installed; audit found 0 vulnerabilities |
| `npm run typecheck` (frontend) | Passed |
| `npm run build` (frontend) | Passed; existing >500 kB bundle warning |
| `node --test tests/morning.test.mjs` (frontend) | 4 passed, 0 failed |
| `npm run test:reports` (frontend) | 5 passed, 0 failed, 1 skipped (opt-in live-API export test) |
| `DB_URL=jdbc:postgresql://localhost:5432/supreme_pr04_scratch DB_USERNAME=supreme DB_PASSWORD=supreme ./mvnw -B resources:copy-resources@copy-frontend test` | 250 tests, 0 failures, 0 errors, 0 skipped |
| `PR04_CAPTURE=before E2E_DB_NAME=supreme_pr04_e2e E2E_API_PORT=8084 E2E_WEB_PORT=5177 npm --prefix frontend run e2e -- pr04-screenshots.spec.ts` | 1 passed (before application edits) |
| `PR04_CAPTURE=after E2E_DB_NAME=supreme_pr04_e2e E2E_API_PORT=8084 E2E_WEB_PORT=5177 npm --prefix frontend run e2e -- pr04-dashboard.spec.ts pr04-screenshots.spec.ts pr03-analytics.spec.ts` | Final run: 21 passed, 0 failed |
| `git diff --check` | Passed |

The first browser run had 18 passes and one test-selector failure: two loading status messages
matched a strict locator. The locator now targets the summary, and the final run above passes.
The E2E TypeScript project also caught browser globals in the new assertions; these now use
the locator element's document and dimensions, and final typecheck/build both pass.

The full unrelated Playwright suite and the opt-in live-API export test were not run. Browser
coverage is Chromium; Safari/Firefox and a manual screen-reader pass were not run. PostgreSQL
scratch databases and ports 8084/5177 were used; no trading data or production service was used.
