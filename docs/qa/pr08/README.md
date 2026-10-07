# QA Part 2 PR-08 — Stock workspace

Implements only PR-08 from the local `QA-PART2-PR-PLAN.md`. Inspected the original PDF's page 15: one task form selected by Delivery / Count / Giveaway, beside a roughly 70/30 low-stock overview, stacked on phones. The shared instructions and decisions were read; no PR-08 business decision is unresolved.

Started from latest `origin/main`, `de1a70f` (PR-07), in an isolated worktree on `feat/qa-part2-pr08-stock`. Required dependency PR-01 is merged as [#11](https://github.com/wilsonTiquia/supreme-billiards-hall/pull/11), commit `6e4c1e0`, verified as an ancestor. The original checkout's untracked plan and `TestDTO.java` were left untouched.

## Behavior

- One visible task panel, with real tabs, roving focus, Left/Right wraparound, Home/End, connected panel labels, and shared 44px buttons.
- Forms stay mounted across switches. Draft fields, selected products, delivery lines, mutations and task messages survive switches. Drafts are local to this page, not persisted across reload/navigation.
- Pending submissions lock that form's inputs. Errors and successes stay in the relevant panel; retry clears its old message. Every successful operation invalidates products and low stock.
- Count still sends an absolute `newQuantity` to the correction endpoint, which records the difference; no-op corrections still return the original 409. Giveaway and multi-product Delivery retain their endpoints and request meanings. Delivery still requires explicit actual unit costs.
- Low stock is beside the task on desktop and below it on mobile, with readable on-hand/threshold labels, independent loading/error/retry/empty states, and scrolling for dense lists. Long deliveries scroll internally, keeping the action row outside the scroll area.
- No backend, API, database, cost-policy, permission, or accounting changes.

## Validation

Commands run from `frontend/` unless stated otherwise:

- `npm run typecheck` — passed.
- `npm run build` — passed, 210 modules transformed.
- From repository root: `PGPASSWORD=supreme /Library/PostgreSQL/18/bin/createdb -h localhost -U supreme supreme_pr08_scratch`, followed by `DB_URL=jdbc:postgresql://localhost:5432/supreme_pr08_scratch DB_USERNAME=supreme DB_PASSWORD=supreme ./mvnw resources:copy-resources@copy-frontend test` — **265 tests, 0 failures, 0 errors, 0 skipped**, BUILD SUCCESS. Scratch database dropped afterward.
- `PR08_CAPTURE=before E2E_DB_NAME=supreme_pr08_e2e E2E_API_PORT=8088 E2E_WEB_PORT=5188 npm run e2e -- pr08-screenshots.spec.ts` — **1 passed**, capturing the unchanged main implementation.
- `PR08_CAPTURE=after E2E_DB_NAME=supreme_pr08_e2e E2E_API_PORT=8088 E2E_WEB_PORT=5188 npm run e2e -- pr08-stock.spec.ts pr08-screenshots.spec.ts stock-qa.spec.ts` — **10 passed**. Uses the repository's guarded scratch backend workflow; transaction assertions read committed movements over separate API requests.
- Mutation check: temporarily made panel keys depend on the selected task (remounting forms on each switch). The unchanged draft regression failed because quantity became blank instead of `12.125`. Restored stable keys and reran the browser checks successfully.
- `git diff --check` — passed.

Coverage: keyboard switching and draft retention across all three tasks; two-product delivery with fractional quantity and four-decimal cost; absolute count producing the correct compensating delta; giveaway into negative stock; updated low-stock membership/quantities and current-count hint; rejected no-op count with no extra movement; each task's pending/error isolation and successful retry; loading, failed/retried, empty, 60-item and long-label stock lists; existing catalog/opening-stock/single-product workflows in both themes at 400px and 1280px; scrollable ten-line delivery and fully visible submit button at 400×800.

Visual screenshots use deterministic read-only product/low-stock fixtures; mutation tests use the real scratch API. Before captures use 900px-high viewports; final mobile captures use the shorter 800px viewport. Both are full-page captures. No production data was used. Not run: unrelated full browser suite or report tests (no report changes). Existing npm/Node compatibility and Vite chunk-size warnings remain. No merge or deployment.

## Screenshots

| Theme / width | Before | Delivery | Count | Giveaway | Long delivery |
|---|---|---|---|---|---|
| Light / 1280 | [Before](before-light-1280-delivery.png) | [After](after-light-1280-delivery.png) | [Count](after-light-1280-count.png) | [Giveaway](after-light-1280-giveaway.png) | [10 lines](after-light-1280-long-delivery.png) |
| Dark / 1280 | [Before](before-dark-1280-delivery.png) | [After](after-dark-1280-delivery.png) | [Count](after-dark-1280-count.png) | [Giveaway](after-dark-1280-giveaway.png) | [10 lines](after-dark-1280-long-delivery.png) |
| Light / 400 | [Before](before-light-400-delivery.png) | [After](after-light-400-delivery.png) | [Count](after-light-400-count.png) | [Giveaway](after-light-400-giveaway.png) | [10 lines](after-light-400-long-delivery.png) |
| Dark / 400 | [Before](before-dark-400-delivery.png) | [After](after-dark-400-delivery.png) | [Count](after-dark-400-count.png) | [Giveaway](after-dark-400-giveaway.png) | [10 lines](after-dark-400-long-delivery.png) |
