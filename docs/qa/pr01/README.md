# QA Part 2 — PR-01

Implements only shared actions, icons, menu and pagination. Base: `336cd295e89897f7b48d858f9ea08b96f19ac096`, freshly fetched `origin/main` on 30 September 2026. PR-01 has no prerequisite PRs or blocking business decisions. Work is isolated on `codex/qa-part2-pr01`; the planning checkout's untracked plan and TestDTO are unchanged (SHA-1 verified).

Read the shared implementation instructions, decisions and PR-01 brief from `QA-PART2-PR-PLAN.md` in the planning checkout. Rendered and visually inspected pages 1, 4, 5, 10, 17, 19 and 25 of `/Users/wilsontiqua/Downloads/qapart2.pdf`. The source plan/PDF remain local and are not added to this PR.

## Scope and behavior

- Shared `Button`/`ButtonLink` variants include tertiary. Sales receipt controls now use actual links without nested buttons, preserving route state. Filled buttons use readable labels in both themes.
- A small typed decorative SVG set reuses the sun/moon glyphs. Accessible names belong on their controls.
- `ActionMenu` is ready for later screen PRs: keyboard/touch, focus restoration, native top-layer positioning, scrolling, outside/Escape dismissal, disabled and permission-hidden items. Existing action callbacks retain responsibility for confirmations. No feature action menus adopted here.
- Reports, Audit and Sales use one `Pagination`. Report filtering, sorting, grouping, CSV and one-based indexing remain unchanged; Audit/Sales keep zero-based requests and existing loading/error handling. Reports also disables pagination during refresh. Count/page text wraps above the two full-width controls on phones.
- No API, accounting, role, schema, dependency or deployment changes. See [control usage](../../../frontend/src/components/CONTROLS.md).

## Actual validation

Run from this worktree; npm commands below run in `frontend/` unless prefixed otherwise.

- `npm run typecheck` — passed.
- `npm run build` — passed (206 modules). Existing Vite >500 kB chunk warning remains; installed npm also warns that local Node 20.13.1 is older than its supported range.
- `npm run test:reports` — 5 passed, 0 failed, 1 skipped. The optional real-response reconciliation case requires `REAL_REPORT_JSON`, which was not supplied. Browser report traversal/export checks did run against a real scratch report response.
- `PR01_CAPTURE=after E2E_DB_NAME=supreme_pr01_e2e E2E_API_PORT=8087 E2E_WEB_PORT=5180 npm run e2e -- pr01-pagination.spec.ts pr01-screenshots.spec.ts pr01-controls.spec.ts worked-trace.spec.ts employee-sees-no-cost.spec.ts` — **11 passed**. Includes 4 shared-control tests, 4 page integration tests, 1 screenshot capture and 2 existing POS/role regressions.
- `PGPASSWORD=supreme /Library/PostgreSQL/18/bin/createdb -h localhost -U supreme supreme_pr01_scratch`, then from repository root `DB_URL=jdbc:postgresql://localhost:5432/supreme_pr01_scratch DB_USERNAME=supreme DB_PASSWORD=supreme ./mvnw -B resources:copy-resources@copy-frontend test` — **245 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS**.
- Mutation checks: bypassing the disabled menu-item guard failed the menu test after Enter activated the disabled item; replacing Next with a no-op failed the pagination test at `Page 2 of 3`. Both production mutations were restored. `E2E_DB_NAME=supreme_pr01_e2e E2E_API_PORT=8087 E2E_WEB_PORT=5180 npm run e2e -- pr01-controls.spec.ts` then passed all 4 tests.
- `git diff --check` — passed.

Browser assertions cover exact first/middle/last rows and boundaries; Sales URL/date/reload behavior; Audit action/actor filters; all 31 report dates, filtered empty results, grouping and full CSV from a middle page; initial loading/error and refresh pending state; keyboard, touch, disabled/hidden menu items, navigation, confirmation focus, dismissal, long labels and 30-item menus on a short viewport. Reports use the actual scratch API. Sales/Audit list responses are explicitly intercepted read-only fixtures for deterministic multi-page coverage, not proof of server paging internals. The existing worked trace and employee-role regressions use the real scratch backend.

Not run: remaining unrelated Playwright specs, other browser engines, and manual assistive-technology testing. No merge or deployment.

## Screenshots

28 unedited browser screenshots: 12 before + 12 after for the three adopted pages (400×800 and 1280×800, both themes), plus 4 new-control fixture images. The fixtures use fake Sales/Audit rows, real empty August report data from the scratch server, and no trading data. Screenshots scroll to the changed pagination; report tables retain their existing horizontal scrolling on phones. The new-control fixture is development-only and does not add a production route.

Before images were captured on unmodified main UI with `PR01_CAPTURE=before` and `pr01-screenshots.spec.ts`. After images use the command above. Reviewed all after images for wrapping, clipping, menu positioning and theme contrast.

| Screen / width / theme | Before | After |
| --- | --- | --- |
| Reports / 400 / light | [Before](before-reports-light-400.png) | [After](after-reports-light-400.png) |
| Reports / 400 / dark | [Before](before-reports-dark-400.png) | [After](after-reports-dark-400.png) |
| Reports / 1280 / light | [Before](before-reports-light-1280.png) | [After](after-reports-light-1280.png) |
| Reports / 1280 / dark | [Before](before-reports-dark-1280.png) | [After](after-reports-dark-1280.png) |
| Audit / 400 / light | [Before](before-audit-light-400.png) | [After](after-audit-light-400.png) |
| Audit / 400 / dark | [Before](before-audit-dark-400.png) | [After](after-audit-dark-400.png) |
| Audit / 1280 / light | [Before](before-audit-light-1280.png) | [After](after-audit-light-1280.png) |
| Audit / 1280 / dark | [Before](before-audit-dark-1280.png) | [After](after-audit-dark-1280.png) |
| Sales / 400 / light | [Before](before-sales-light-400.png) | [After](after-sales-light-400.png) |
| Sales / 400 / dark | [Before](before-sales-dark-400.png) | [After](after-sales-dark-400.png) |
| Sales / 1280 / light | [Before](before-sales-light-1280.png) | [After](after-sales-light-1280.png) |
| Sales / 1280 / dark | [Before](before-sales-dark-1280.png) | [After](after-sales-dark-1280.png) |

| New controls | Light | Dark |
| --- | --- | --- |
| 400px | [Light](after-controls-light-400.png) | [Dark](after-controls-dark-400.png) |
| 1280px | [Light](after-controls-light-1280.png) | [Dark](after-controls-dark-1280.png) |
