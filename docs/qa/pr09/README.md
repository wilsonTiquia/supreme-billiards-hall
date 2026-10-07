# PR-09 — Sales receipts

Scope: QA Part 2 PR-09, `qapart2.pdf` pages 15–17. Started from main
`f058881dac3eb29954a4584c22c5815fbb47566b` in an isolated worktree. Dependencies
PR-01 (#11, `6e4c1e0`) and PR-02 (#12, `8056d01`) are merged and ancestors of that base.
The original checkout's untracked plan and `TestDTO.java` were left untouched.

## Behavior

- Sales receipt links open `/admin/sales/receipt/:billId` with the current query string.
  The Sales page stays mounted. Close, Escape, backdrop dismissal and browser Back return
  to the list; browser Forward reopens the receipt. Focus and scroll return to the opener.
- A directly opened modal URL can close to Sales with its date/page. Standalone
  `/receipt/:billId` remains available for checkout and cold links, including existing
  origin links, animation, payment replay messages and payment-photo upload recovery.
- Both views use `ReceiptView` and `ReceiptDocument`. The stored payload supplies receipt
  lines and amounts; a later settlement stays separate. No API or accounting contract changed.
- Receipt and notes use approximately 70/30 columns, stacked on phones. Controls are above
  the content. Native dialog modality blocks interaction with the background list;
  explicit Tab wrapping and focus restoration keep keyboard operation within the dialog.
- Printing includes one receipt, including its heading, and excludes controls, notes,
  the backdrop and the Sales list. Print layout releases the dialog's height/scroll limits.
- Admins can find Sales under “Look up” in the Floor workspace. Employees have no link,
  remain blocked by the route guard, and still receive HTTP 403 from the Sales endpoint.

## Visual evidence

These are screenshots of the running app. Before images use the original main implementation;
read-only response fixtures make the receipt/notes/list reproducible. Authentication uses the
scratch backend. The separate snapshot regression creates and settles a real scratch sale,
changes the catalog name/price, then checks the unchanged receipt in both views.

| View | Before | After |
|---|---|---|
| Sales, light, 1280px | [Before](before-light-1280-sales-receipt.png) | [After](after-light-1280-sales-receipt.png) |
| Sales, dark, 1280px | [Before](before-dark-1280-sales-receipt.png) | [After](after-dark-1280-sales-receipt.png) |
| Sales, light, 400px | [Before](before-light-400-sales-receipt.png) | [Receipt](after-light-400-sales-receipt.png), [notes below](after-light-400-sales-notes.png) |
| Sales, dark, 400px | [Before](before-dark-400-sales-receipt.png) | [Receipt](after-dark-400-sales-receipt.png), [notes below](after-dark-400-sales-notes.png) |
| Standalone, light, 1280px | [Before](before-light-1280-standalone.png) | [After](after-light-1280-standalone.png) |
| Standalone, dark, 400px | [Before](before-dark-400-standalone.png) | [After](after-dark-400-standalone.png) |
| Receipt-only print | — | [Desktop](after-light-1280-print.png), [phone](after-dark-400-print.png) |

The folder also contains the other standalone theme/width combinations. Reviewed the rendered
PDF reference, both receipt layouts, mobile notes and print screenshots. Long unbroken labels
and 65-line receipts are exercised in the browser tests.

## Actual validation — 8 October 2026

From `frontend/`:

- `npm run typecheck` — passed.
- `E2E_DB_NAME=supreme_pr09_e2e E2E_API_PORT=8099 E2E_WEB_PORT=5199 PR09_CAPTURE=after npm run e2e -- pr09-receipts.spec.ts pr09-screenshots.spec.ts` — final run: **7 passed**, including six focused regressions and screenshot capture.
- `npm run build` — passed; Vite reports its existing bundle-size advisory (main JS ~576 kB).
- `E2E_DB_NAME=supreme_pr09_e2e E2E_API_PORT=8099 E2E_WEB_PORT=5199 PR09_CAPTURE=before npm run e2e -- pr09-screenshots.spec.ts` — 1 passed.
- `E2E_DB_NAME=supreme_pr09_e2e E2E_API_PORT=8099 E2E_WEB_PORT=5199 PR09_CAPTURE=after npm run e2e -- pr09-receipts.spec.ts pr09-screenshots.spec.ts floor-qa.spec.ts worked-trace.spec.ts discount.spec.ts voucher-prize.spec.ts pr01-pagination.spec.ts pr02-shell.spec.ts` — 26 passed, 1 pre-existing failure described below.

From repository root, after building the SPA:

```sh
PGPASSWORD=supreme /Library/PostgreSQL/18/bin/createdb -h localhost -U supreme supreme_pr09_suite_scratch
DB_URL=jdbc:postgresql://localhost:5432/supreme_pr09_suite_scratch DB_USERNAME=supreme DB_PASSWORD=supreme ./mvnw resources:copy-resources@copy-frontend test
PGPASSWORD=supreme /Library/PostgreSQL/18/bin/dropdb -h localhost -U supreme supreme_pr09_suite_scratch
```

Result: **265 tests, 0 failures, 0 errors, 0 skipped**. The scratch database was dropped.
No trading database, port 8080, deployment, or production data was used.

The broader browser run covers checkout, discounts, receipt photos and failed-upload recovery,
duplicate references, Sales pagination, mobile navigation and receipt snapshots. The existing
`voucher-prize.spec.ts:121` fails later on the dashboard because it expects a `Table use` button
removed by earlier analytics work. The same test was rerun with all four modified existing
application files restored to `origin/main` (new components were unreferenced): **same failure
at the same assertion**. Its voucher checkout, zero-charge receipt and Sales assertions pass.
The unrelated dashboard test is unchanged in this PR.

Mutation check: temporarily changed receipt dismissal from `navigate(-1)` to
`navigate('/floor')`, then ran `npm run e2e -- pr09-receipts.spec.ts --grep 'several receipts'`
with the same scratch environment. It failed on the expected return URL (`/floor` instead of
the exact Sales date/page). Restored the behavior before final validation.

No report code changed, so `npm run test:reports` was not required. Firefox, WebKit and a physical
printer were not tested. Browser checks use Chromium, including actual browser PDF generation.

Print verification: extracted text from the browser-generated modal and standalone PDFs (one page each), and a seven-page, 65-line receipt. Each has exactly one heading and footer, no notes/list/controls, and all 65 numbered lines survive in order. Inspected rendered PDF pages as well as print-media screenshots.
