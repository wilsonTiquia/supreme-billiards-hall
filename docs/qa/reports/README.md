# Reports QA — PR 3

Scope: Reports findings on pages 8–11 of [Tikwa QA](../../Tikwa%20QA.pdf). Based on main after PR 2 and README PR #5. Stock findings on page 11 belong to PR 4.

## Changes and design choice

- **Page 8:** 28px page title, 20px section titles, 13px toolbar buttons. Two native date inputs form one labeled range control. Valid edits apply immediately; invalid, incomplete, reversed or over-366-day ranges retain the last valid report and show an error. Presets also restore invalid draft edits.
- **Pages 8–9 — range decision:** native inputs avoid a dependency and preserve keyboard entry and the platform calendar. The grouped control fits at 400px and 1280px. Mobile presets use a balanced 2×2 grid.
- **Page 9:** one Export menu. PDF prints the full report. CSV exports every filtered row of the active explorer tab in the chosen grouping/order, independent of pagination. The menu names the tab and row count.
- **Pages 9–10:** every-night sales rows paginate at 15. Dates are plain text; clicking anywhere on a row opens that night's sales. A chevron link provides keyboard access and standard link behavior. Other long explorer lists use the same pagination.
- **Page 10:** search appears only when the unfiltered dataset has at least 15 rows, consistently across Sales, Tables, Products and Expenses. A filter that narrows results keeps its search field available. Stale search text never hides a small list.
- **Pages 10–11:** each financial detail block is a rounded card with its own title row and summary. The existing expansion and full-print behavior are preserved.

## Validation

- `npm --prefix frontend run typecheck` and `npm --prefix frontend run build` — passed.
- `REAL_REPORT_JSON=/tmp/reports-qa-api.json node --test frontend/tests/*.test.mjs` — **10 passed, 0 failed, 0 skipped**. Includes actual API daily/weekly/monthly CSV totals reconciled to the headline.
- New report-view tests cover 31 nights split 15/15/1 without loss, full CSV despite pagination, search threshold at 15, filtering to one/zero rows, stale filters on small lists, sorting before pagination, grouping, and page clamping.
- `DB_URL=jdbc:postgresql://localhost:5432/supreme_reports_qa_suite_20260923 ./mvnw resources:copy-resources@copy-frontend test` — **203 tests, 0 failures, 0 errors, 0 skipped**. Separate scratch database created and dropped. No backend source changed.
- Browser QA used the isolated `supreme_reports_qa_20260923_e2e` database at ports 8182/5175. Fixtures: 31 August nights, 16 products sold, seven tables, three expense categories. Products, deliveries, sales and expenses were created through the API; only historical fixture timestamps were backdated. Stock moved only through its ledger.
- Verified automatic range application in the URL, invalid-range feedback, preset recovery, 15/15/1 pages, whole-row navigation, no search for seven tables/three expense categories, and product filtering from 16 to two rows. The export menu correctly identified the two filtered product rows.
- CSV action was exercised. CSV content and totals were verified through production export helpers against the real scratch API response.
- Popover open/Escape dismissal, card expansion and both themes checked at 400×860 and 1280×720. Final browser console check had no errors.
- **Print limitation:** PDF invokes browser print, but the embedded browser did not show a print preview. Verified the print-only DOM retains all 31 nights, three expense categories, seven tables and 16 products, and that print CSS expands details and hides controls. Actual paper pagination/PDF appearance still needs a normal-browser print-preview check during review.

## Screenshots

Before: designer PDF pages 8–11. These are viewport captures of each modified part of Reports; wide tables retain horizontal scrolling on phones.

| Area | Light 400px | Dark 400px | Light 1280px | Dark 1280px |
| --- | --- | --- | --- | --- |
| Heading and date range | [View](heading-light-400.png) | [View](heading-dark-400.png) | [View](heading-light-1280.png) | [View](heading-dark-1280.png) |
| Export menu | [View](export-light-400.png) | [View](export-dark-400.png) | [View](export-light-1280.png) | [View](export-dark-1280.png) |
| Sales tab | [View](sales-light-400.png) | [View](sales-dark-400.png) | [View](sales-light-1280.png) | [View](sales-dark-1280.png) |
| Tables tab | [View](tables-light-400.png) | [View](tables-dark-400.png) | [View](tables-light-1280.png) | [View](tables-dark-1280.png) |
| Products tab | [View](products-light-400.png) | [View](products-dark-400.png) | [View](products-light-1280.png) | [View](products-dark-1280.png) |
| Expenses tab | [View](expenses-light-400.png) | [View](expenses-dark-400.png) | [View](expenses-light-1280.png) | [View](expenses-dark-1280.png) |
| Financial cards | [View](details-light-400.png) | [View](details-dark-400.png) | [View](details-light-1280.png) | [View](details-dark-1280.png) |

[Second page on phone](pagination-light-400.png)
