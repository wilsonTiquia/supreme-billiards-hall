# Floor premium follow-up evidence

Base: latest main `41808d492e63a3f35f6ea790654c1f54ee2449d5`, merged PR-03 (#13). Work is isolated on `codex/floor-premium-followup`; the original checkout's unrelated untracked files are preserved.

## Merged implementation inspected and reused

PR-03 already supplies V23's verified Tables 1–3 backfill, Standard defaults for other existing/new records, editable `isPremium` in table administration, API/report fields, audit snapshots, and Dashboard/Reports stars. Those implementations are reused without a new migration, API change or classification backfill.

The remaining defect was `TableCard`: it derived both rack color and a tier badge from a parenthesized table-name suffix. This incorrectly marked seeded Standard Tables 5–7 Premium and missed Premium Tables 1–3. Floor now uses `table.isPremium` for the existing gold rack on free tables and an opaque Premium badge on free, running and paused tables. Name parsing only preserves the existing large table numeral. Running/paused illustrations, status pills, rails, timers and all price rendering remain separate and unchanged.

The only production file changed is `frontend/src/features/floor/TableCard.tsx`. No rates, billing, discounts, session code or backend code changed.

## Actual validation

- `npm --prefix frontend run typecheck` — passed.
- `npm --prefix frontend run build` — passed. Existing Vite bundle-size advisory remains; local npm warns about the Node 20.13.1 patch version.
- `npm --prefix frontend run test:reports` — 5 passed, 0 failed, 1 skipped (`REAL_REPORT_JSON` not configured).
- `DB_URL=jdbc:postgresql://localhost:5432/supreme_floor_premium_scratch DB_USERNAME=supreme DB_PASSWORD=supreme ./mvnw -B resources:copy-resources@copy-frontend test` — **250 tests, 0 failures, 0 errors, 0 skipped**. This includes existing premium migration/persistence/billing tests.
- `FLOOR_PREMIUM_CAPTURE=after E2E_DB_NAME=supreme_floor_premium_e2e E2E_API_PORT=8083 E2E_WEB_PORT=5176 npm --prefix frontend run e2e -- floor-premium.spec.ts pr03-premium.spec.ts worked-trace.spec.ts` — **5 passed**.
- Before the production edit, `E2E_DB_NAME=supreme_floor_premium_e2e E2E_API_PORT=8083 E2E_WEB_PORT=5176 npm --prefix frontend run e2e -- floor-premium.spec.ts --grep 'follows the flag'` failed as expected: Table 1's API flag was true but its Floor Premium badge count was 0. The same regression passes after the fix.
- Before screenshots use merged main with `FLOOR_PREMIUM_CAPTURE=before` and the focused `--grep 'separate from session'` case: 1 passed. After screenshots use the same focused case on a fresh scratch database: **1 passed**, avoiding an unrelated unpaid-bill strip from the earlier lifecycle test.

`git diff --check` passed. Both task-owned scratch databases were removed after validation.

The guarded browser harness was unchanged; all sessions and payments were created only in the scratch database. Browser coverage checks all seven seeded flags and misleading names, gold/green free racks, employee-visible badges across start/pause/resume/close, separate status indicators, unchanged ₱4/min active-session rate, flat/promo pricing displays, both widths/themes with no horizontal overflow, existing admin default/edit behavior, Dashboard/Reports stars, and the real ₱630 checkout trace. The full unrelated browser suite was not run.

## Floor screenshots

Each mixed-state capture shows Premium Table 1 free, Table 2 paused at a ₱321.09 flat fee, and Table 3 running at a ₱2.50/min promo rate. Standard Tables 4/7 are free, Table 5 paused at the same flat fee and Table 6 running at the same promo rate. The illustration on free Premium tables is gold; the Premium badge remains visible on occupied cards alongside the existing session-status label.

Captures use real scratch API records and session setup. The fixture alone backdates session starts ten minutes using the existing test helper; this is not a POS action. Before/after business dates differ because the captures span the business-day boundary; rates and scenarios are the same.

| Viewport / theme | Before | After |
|---|---|---|
| 1280 light | [Before](before-light-1280.png) | [After](after-light-1280.png) |
| 1280 dark | [Before](before-dark-1280.png) | [After](after-dark-1280.png) |
| 400 light | [Before](before-light-400.png) | [After](after-light-400.png) |
| 400 dark | [Before](before-dark-400.png) | [After](after-dark-400.png) |
