# QA Part 2 PR-13 — Voucher archive and cancellation

Reference: `qapart2.pdf`, page 18 (pages 18–19 visually inspected), and [the approved PR-13 brief](../QA-PART2-PR-PLAN.md#pr-13--voucher-batch-archive-behavior). Owner decision: 9 October 2026.

Started from latest `origin/main` at `6a8cb1e01b6268b549dd02f32da5bd9017a42b94`, on `codex/qa-part2-pr13-voucher-archive` in an isolated worktree. Dependency PR-12 is merged as GitHub #23 at that commit; shared-controls PR-01 is merged as #11 (`6e4c1e0`). Both were checked as ancestors. The original checkout's unrelated untracked `TestDTO.java` was untouched; its local plan was updated with the approved decision and included in this PR.

## Behavior

- Archive changes visibility only. Show archived includes full batch cards, counts and Codes. Restore changes visibility only.
- Cancel unused codes is a separate, explicitly confirmed, irreversible admin action. It works on visible or archived batches, records the actor and before/after state, and cannot be undone by Restore. Existing redemptions and stored receipts remain unchanged. A code released from an open bill after cancellation remains unusable.
- The existing server-checked Delete path remains in the menu for genuinely unused batches; it is separate from Archive and Cancel.
- V25 adds `cancelled_at` and backfills **every previously archived batch** from its archive timestamp. Those batches remain both archived and cancelled. Each backfilled batch receives an audit record naming the migration, with no invented human actor. No codes are automatically reactivated. Applied migrations are unchanged.
- Cancellation and redemption use the same branch-scoped batch lock. Redemption committed first is preserved; cancellation committed first blocks redemption. Repeated cancellation returns 409 without duplicating audit history. Employees receive 403 and foreign-branch IDs receive 404.
- Code-status precedence is redeemed, expired, cancelled, outstanding. The four status counts sum to issued. Cancelled counts exclude redeemed and expired codes; the permanent batch cancellation timestamp stays visible even when the cancelled count is zero.
- Existing archive/restore endpoints are reused. The API adds `includeArchived`, batch archive/cancel timestamps, cancelled counts/status, and `POST /voucher-batches/{id}/cancel`. No new dependencies, report arithmetic changes, deployment or merge.

## Verification

Backend runs use PostgreSQL 18 scratch database `supreme_pr13_scratch`. Browser runs use guarded scratch database `supreme_pr13_e2e`, backend port 8093 and Vite port 5193. No test or app process targeted trading data or port 8080.

- `npm --prefix frontend run typecheck` — passed.
- `npm --prefix frontend run build` — passed; existing >500 kB bundle advisory remains. The installed npm also warns that local Node 20.13.1 is outside its supported range; both commands completed successfully.
- `PGPASSWORD=supreme /Library/PostgreSQL/18/bin/createdb -h localhost -U supreme supreme_pr13_scratch`
- `DB_URL=jdbc:postgresql://localhost:5432/supreme_pr13_scratch DB_USERNAME=supreme DB_PASSWORD=supreme ./mvnw resources:copy-resources@copy-frontend test` — **275 tests, 0 failures, 0 errors, 0 skipped** on a fresh scratch database.
- The initial full run on the previously reused scratch database found one last-administrator test failure: earlier committed lifecycle fixtures had left active administrators. The new fixture now deactivates its administrator in `@AfterEach`; the fresh full run above passed. This was test isolation, not a change to staff protections.
- Persistence sensitivity: temporarily marked `cancelled_at` as `updatable = false`, then ran `DB_URL=jdbc:postgresql://localhost:5432/supreme_pr13_scratch DB_USERNAME=supreme DB_PASSWORD=supreme ./mvnw test '-Dtest=VoucherLifecycleAcceptanceTest#archiveCancelAndRestorePersistIndependentlyWithActorAndSnapshots'` — **1 test, 1 expected failure**, specifically because a subsequent HTTP read found no cancellation timestamp. Restored the mapping before the passing full run. The mutation is not committed.
- `E2E_DB_NAME=supreme_pr13_e2e E2E_API_PORT=8093 E2E_WEB_PORT=5193 PR13_CAPTURE=before npm --prefix frontend run e2e -- pr13-screenshots.spec.ts` — **1 passed**, before application edits.

- `DB_URL=jdbc:postgresql://localhost:5432/supreme_pr13_scratch DB_USERNAME=supreme DB_PASSWORD=supreme ./mvnw test '-Dtest=UserAdministrationTest#theLastAdministratorCannotBeArchivedDemotedOrDeactivated'` — **1 passed** after the full suite on the same database, verifying fixture isolation.
- `E2E_DB_NAME=supreme_pr13_e2e E2E_API_PORT=8093 E2E_WEB_PORT=5193 PR13_CAPTURE=after npm --prefix frontend run e2e -- pr13-vouchers.spec.ts pr13-screenshots.spec.ts pr12-vouchers.spec.ts voucher-prize.spec.ts` — **11 passed (1.2m)** on the final implementation.
- `E2E_DB_NAME=supreme_pr13_e2e E2E_API_PORT=8093 E2E_WEB_PORT=5193 npm --prefix frontend run e2e -- setup-qa.spec.ts` — **1 passed (1.3m)**, covering the existing Delete/Restore path and other setup screens after the menu change. Its regenerated historical screenshots were discarded; PR-13 evidence stays in this folder.
- `git diff --check` — passed.

32 PR-13 before/after screenshots were captured. Representative desktop/mobile menus, archive/cancel/restore confirmations, archived cards and cancelled code lists were visually inspected in both themes. [Actual output excerpts](test-results.txt) are included.

The backend coverage includes committed HTTP read-back, migration SQL against isolated pre-V25 table shapes, migration audit/backfill, role/branch restrictions, counts/status filters, cancelled and expired Restore behavior, receipt equality before/after cancellation, release after cancellation, and both cancellation/redemption lock orders. Browser coverage includes real archive/cancel/restore reloads; explicit confirmations and dismissal/focus; pending double-submit protection; failed cancellation; archived loading/error/empty states; existing 500-code copy/print tests; and an archived voucher redeemed through zero-total checkout and read back against its actual receipt.

Not run: the entire unrelated Playwright suite, Firefox/WebKit or a physical printer. `test:reports` is not required because no report implementation changed. Production was not accessed, merged or deployed.

## Screenshots

Synthetic display fixtures make before/after comparisons repeatable and do not expose live customer codes. Separate real-API tests cover the behavior. Screenshots cover 1280px and 400px in both themes.

| Surface | Light | Dark |
|---|---|---|
| Desktop before | [Batches](before-light-1280-batches.png) | [Batches](before-dark-1280-batches.png) |
| Desktop after | [Batches](after-light-1280-batches.png) | [Batches](after-dark-1280-batches.png) |
| Mobile before | [Batches](before-light-400-batches.png) | [Batches](before-dark-400-batches.png) |
| Mobile after | [Batches](after-light-400-batches.png) | [Batches](after-dark-400-batches.png) |
| Desktop overflow | [Menu](after-light-1280-menu.png) | [Menu](after-dark-1280-menu.png) |
| Mobile archive confirmation | [Archive](after-light-400-archive.png) | [Archive](after-dark-400-archive.png) |
| Mobile cancellation confirmation | [Cancel](after-light-400-cancel.png) | [Cancel](after-dark-400-cancel.png) |
| Mobile archived and cancelled batches | [Show archived](after-light-400-archived.png) | [Show archived](after-dark-400-archived.png) |
| Mobile restore confirmation | [Restore](after-light-400-restore.png) | [Restore](after-dark-400-restore.png) |
| Mobile code statuses | [Codes](after-light-400-cancelled-codes.png) | [Codes](after-dark-400-cancelled-codes.png) |

The corresponding desktop confirmations, archived lists and code views are also included as `after-*-1280-*.png`.
