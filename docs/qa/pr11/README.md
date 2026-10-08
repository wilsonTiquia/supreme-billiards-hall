# QA Part 2 PR-11 — table and staff maintenance

Scope: PR-11 only, from the local QA Part 2 plan and visually inspected `qapart2.pdf` pages 18–19. Started from main `4fe4841`; prerequisite PR-01 is merged as `6e4c1e0` ([GitHub #11](https://github.com/wilsonTiquia/supreme-billiards-hall/pull/11)).

Tables and Staff use the existing shared action menu. The setup service still decides whether removal is Delete (unused) or Archive (historical), and supplies self/last-admin refusal reasons. The menu shows those reasons and retains the existing confirmation, error, pending and restore flows. Reset password stays beside the staff menu; administrator targets are disabled, matching the existing API refusal.

Table rates are edited through the existing Edit form. The redundant Change rate form/mutation is removed; the backend rate-history service and API are unchanged. Help supports hover, focus, click/tap pinning, second-click dismissal, Escape, outside clicks and focus departure. It retains the server's precise per-minute and effective-hourly figures and is clamped to the viewport.

## Visual evidence

Images use reproducible read fixtures, with authentication against the isolated local scratch backend. Names and numbers are synthetic. Before images were captured from main before application edits; after images use this branch. Each set includes 1280px and 400px widths, light and dark themes, long names, inactive staff/tables and temporary-password status.

| Screen | Before | After |
|---|---|---|
| Tables, desktop light | [Before](before-light-1280-tables.png) | [After](after-light-1280-tables-menu.png) |
| Staff, desktop dark | [Before](before-dark-1280-staff.png) | [After](after-dark-1280-staff.png) |
| Rate help, phone light | [Before](before-light-400-rate-help.png) | [After](after-light-400-rate-help.png) |
| Staff menu, phone dark | [Before](before-dark-400-staff.png) | [After](after-dark-400-staff-menu.png) |

All 32 screenshots are in this folder. Filenames encode before/after, theme, viewport width and screen.

## Validation

Run on 8 October 2026 (Asia/Manila):

- `cd frontend && npm run typecheck` — passed.
- `cd frontend && npm run build` — passed (215 modules; existing bundle-size advisory).
- `DB_URL=jdbc:postgresql://localhost:5432/supreme_pr11_scratch DB_USERNAME=supreme DB_PASSWORD=supreme ./mvnw resources:copy-resources@copy-frontend test` — **265 tests, 0 failures, 0 errors, 0 skipped**, BUILD SUCCESS. A fresh PostgreSQL scratch database was created for this run and dropped afterward.
- `cd frontend && PR11_CAPTURE=after E2E_DB_NAME=supreme_pr11_e2e E2E_API_PORT=8091 E2E_WEB_PORT=5184 npm run e2e -- pr11-maintenance.spec.ts pr11-screenshots.spec.ts pr01-controls.spec.ts pr03-premium.spec.ts` — **16 passed (1.1m)** in Chromium. The guarded harness created its own scratch database/backend and frontend; no trading data was used.
- Baseline capture: same scratch environment with `PR11_CAPTURE=before npm run e2e -- pr11-screenshots.spec.ts` — **1 passed**, before application edits.
- Mutation check: temporarily removed the hover-open handler and ran `npm run e2e -- pr11-maintenance.spec.ts -g 'pointer, focus and dismissal · 1280 · light'` with the same scratch environment. It failed at the missing rate tooltip as expected; the handler was restored before the final green run.
- `git diff --check` — passed.

The browser checks cover hourly/minute mode read-back, rate changes with an existing session (its rate stays 3.3333), archive protection for occupied tables, table archive/restore, staff edit/password reset/archive/restore/delete, self-removal refusal, disabled admin password reset, keyboard navigation/focus restoration, hover/focus/touch help, outside/Escape dismissal, precise rate equivalents, failed eligibility, loading/errors/empty staff and 60 tables in a 400×480 viewport. The full backend suite also covers last-admin and role/branch restrictions.

Not run: the entire legacy Playwright suite, Firefox/WebKit, or report-specific tests (no report changes). Existing setup and premium screenshot selectors were adjusted to enter the new menus; the broad legacy setup scenario and opt-in premium capture were not rerun. No backend code, migration or API contract change is included. No merge or deployment is part of this PR.
