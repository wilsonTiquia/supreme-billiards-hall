# QA Part 2 PR-10: expandable audit rows

Reference: `qapart2.pdf`, page 17, Audit item 2. Item 3 already uses PR-01's shared pagination; the date strip belongs to PR-02. Only PR-10 is implemented here.

Started from `origin/main` at `1b2038020159215a93c0dee8d5a1080fc1adccd6` on the isolated `codex/qa-part2-pr10-audit` branch. The required PR-01 was merged as [GitHub PR #11](https://github.com/wilsonTiquia/supreme-billiards-hall/pull/11), commit `6e4c1e061bdb028454073012613662ecd5cea8ff`, verified as an ancestor of the base. The original checkout's untracked plan and `TestDTO.java` were left untouched. No business decision is needed for this brief.

## Behavior

- Click any non-interactive summary cell, tap the trailing 44×44px chevron, or use Tab then Enter/Space to expand/collapse an event. Native table semantics remain intact.
- The named button exposes `aria-expanded` and `aria-controls`; expanded content has a named region spanning all five columns. Its links, buttons and text do not trigger summary-row toggling.
- Expansion is keyed by event source plus ID, survives pagination for the same event, and does not transfer to a different event at the same row position. Rows with no details have no toggle.
- Existing actor/time/entity labels, server amounts, rate-unit formatting, stock deltas, notes, before/after values, filter requests, newest-first order and Previous/Next pagination are preserved.
- Long notes and nested JSON wrap within the table; before/after values align at the top. No API/backend/schema changes or dependencies added.

## Evidence

Screenshots use deterministic browser read fixtures with authentication against the scratch backend. They include product changes, stock corrections, rate changes, a detail-free system event, long labels/notes/nested payloads, and 51 events across three pages. The AUDIT and STOCK fixtures intentionally share an ID: the baseline expands both; the implementation distinguishes them. The separate real-API test creates a product and delivery in the scratch database and checks their audit entries in the browser.

| View | Before | After |
|---|---|---|
| Light, 1280px, collapsed | [Before](before-light-1280-collapsed.png) | [After](after-light-1280-collapsed.png) |
| Light, 1280px, expanded | [Before](before-light-1280-expanded.png) | [After](after-light-1280-expanded.png) |
| Dark, 1280px, collapsed | [Before](before-dark-1280-collapsed.png) | [After](after-dark-1280-collapsed.png) |
| Dark, 1280px, expanded | [Before](before-dark-1280-expanded.png) | [After](after-dark-1280-expanded.png) |
| Light, 400px, collapsed | [Before](before-light-400-collapsed.png) | [After](after-light-400-collapsed.png) |
| Light, 400px, expanded | [Before](before-light-400-expanded.png) | [After](after-light-400-expanded.png) |
| Dark, 400px, collapsed | [Before](before-dark-400-collapsed.png) | [After](after-dark-400-collapsed.png) |
| Dark, 400px, expanded | [Before](before-dark-400-expanded.png) | [After](after-dark-400-expanded.png) |

Long-payload evidence: [light desktop](after-light-1280-long-payload.png), [dark desktop](after-dark-1280-long-payload.png), [light phone](after-light-400-long-payload.png), [dark phone](after-dark-400-long-payload.png).

## Validation

Commands use Node 22 (`PATH=/usr/local/opt/node@22/bin:$PATH`). Browser tests use only local ports 8091/5184 and `supreme_pr10_e2e`, rebuilt by the repository's guarded E2E script. Maven used a separate newly created `supreme_pr10_suite_scratch` database, dropped after the run. No tests target the trading database or port 8080.

- `npm run typecheck` — passed.
- `npm run build` — passed (existing Vite warning for a chunk above 500 kB).
- `DB_URL=jdbc:postgresql://localhost:5432/supreme_pr10_suite_scratch DB_USERNAME=supreme DB_PASSWORD=supreme ./mvnw resources:copy-resources@copy-frontend test` — **265 tests, 0 failures, 0 errors, 0 skipped**.
- Before screenshot command: `PR10_CAPTURE=before E2E_DB_NAME=supreme_pr10_e2e E2E_API_PORT=8091 E2E_WEB_PORT=5184 npx playwright test e2e/pr10-screenshots.spec.ts` — **1 passed** on the unmodified main implementation.

- `PR10_CAPTURE=after E2E_DB_NAME=supreme_pr10_e2e E2E_API_PORT=8091 E2E_WEB_PORT=5184 npx playwright test e2e/pr10-audit.spec.ts e2e/pr10-screenshots.spec.ts e2e/pr01-pagination.spec.ts -g 'Audit|audit|row clicks|expansion follows|loading, empty|long payloads|real scratch'` — **7 passed (45.9s)**. Covers pointer, keyboard, touch, independent detail controls, event identity, pagination/filter requests, loading/empty/error states, responsive layout, and real API records.
- Mutation check: temporarily disabled only the summary-row click handler and ran `E2E_DB_NAME=supreme_pr10_e2e E2E_API_PORT=8091 E2E_WEB_PORT=5184 npx playwright test e2e/pr10-audit.spec.ts -g 'row clicks'`. The test failed at the expected expansion assertion (`aria-expanded` remained `false`). Restored the implementation before the successful final run.
- Visually inspected the before/after evidence, including long payloads. Automated checks confirm no horizontal overflow, stable column positions on expansion, and 44px minimum chevron targets at both widths and themes.

Not run: the complete unrelated browser suite and `npm run test:reports` (no report changes). No merge or deployment performed.
