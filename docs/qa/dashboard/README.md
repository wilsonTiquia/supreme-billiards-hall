# Dashboard QA — PR 2

Implements pages 5–7 of [Tikwa QA](../../Tikwa%20QA.pdf), from merged shell PR #3.

## Findings addressed

- **Page 5:** one date control with previous/next arrows; remove the dashboard's global business-day bar and the two night-selection buttons. Fresh visits use the server business date. Existing `?date=` selections remain shareable and survive browser Back.
- **Page 6:** fill-only KPI tiles, four across desktop and two by two on phones. Cash status, closed bills and payouts share the Night check banner with unpaid bills, uncounted nights and low stock. Each actionable row has a destination. All three chart rows use **50/50** columns: a stable reading order and more space for labels than the previous changing ratios.
- **Page 7:** definitions live in a scrollable native popover opened by the heading's info icon; close button and Escape dismiss it. View report is a text link. Attention comes before the chart section headings and body content.

## Behavior checks

- Server-clock unit cases cover 9am (yesterday, complete), 10am (today, in progress), the 5am completion boundary, invalid/future URL dates, month/year/leap-day navigation and cash states. Mutation check: restoring yesterday as the default makes two tests fail.
- Saturday 12 September: ₱1,080 sales/collected, ₱330 after cost of goods, 50% above Saturday 5 September; balanced cash.
- Tuesday 15 September: no sales, zero bills, balanced cash; empty charts.
- Wednesday 16 September at about 3:25am on the 17th: in progress, ₱1,890 sales versus ₱1,350 collected, ₱828 after cost of goods. No comparison despite the previous Wednesday having sales. ₱45,000 rent is stated, not subtracted. The weekly chart also suppresses its “Best night” ranking while in progress.
- Resumed at about 2pm on the 17th: the selected 16th correctly became complete and uncounted cash turned red. A fresh dashboard visit selected the 17th, in progress. The light phone in-progress capture shows this later, empty night.
- Previous/next arrows update the URL and report. View report carries the selected date in both from/to; browser Back restores it. Chase unpaid opens `/unsettled`. Count the drawer opens `/end-of-day?date=` for the selected night; View drawer count opens the historical count.
- Low stock and unpaid balances are current across all nights, including when viewing a historical night.

## Validation

- `npm --prefix frontend run typecheck` — passed.
- `npm --prefix frontend run build` — passed.
- `node --test frontend/tests/*.test.mjs` — 6 passed, 1 skipped (the opt-in real-API export test). The four dashboard tests passed.
- `DB_URL=jdbc:postgresql://localhost:5432/supreme_dashboard_qa_suite_20260917 ./mvnw resources:copy-resources@copy-frontend test` — **203 tests, 0 failures, 0 errors, 0 skipped**. Scratch database created and dropped for this run.
- Browser data used the separate `supreme_dashboard_20260917_e2e` database through ports 8181/5174. Product quantities were created through deliveries/sales, not direct stock writes. No production data was changed.
- **Browser QA limitation:** the embedded browser crashed when opening the native date calendar. Calendar-popup selection could not be verified there; date-field automation also did not commit a change. The normal native date input remains in place. URL selection and both arrow controls were verified. Please include a native-calendar selection check in the human review browser.
- No backend implementation changes or dependencies. The shared SalesByNight component adds an optional ranking flag defaulting to the existing Reports behavior. Reports QA remains PR 3.

## Screenshots

Before: designer PDF pages 5–7. Full-page captures below; viewport captures show the first screen at the requested sizes. Data timestamps differ because QA continued across the business-day boundary.

### Saturday complete

- [Light, 400px](saturday-light-400.png)
- [Light, 1280px](saturday-light-1280.png)
- [Dark, 400px](saturday-dark-400.png)
- [Dark, 1280px](saturday-dark-1280.png)

### Closed Tuesday

- [Light, 400px](closed-tuesday-light-400.png)
- [Light, 1280px](closed-tuesday-light-1280.png)
- [Dark, 400px](closed-tuesday-dark-400.png)
- [Dark, 1280px](closed-tuesday-dark-1280.png)

### In progress

- [Light, 400px](in-progress-light-400.png)
- [Light, 1280px](in-progress-light-1280.png)
- [Dark, 400px](in-progress-dark-400.png)
- [Dark, 1280px](in-progress-dark-1280.png)

### Definitions popover

- [Light, 400px](definitions-light-400.png)
- [Light, 1280px](definitions-light-1280.png)
- [Dark, 400px](definitions-dark-400.png)
- [Dark, 1280px](definitions-dark-1280.png)

### First screen

- [Light, 400px](dashboard-light-400-viewport.png)
- [Light, 1280px](dashboard-light-1280-viewport.png)
- [Dark, 400px](dashboard-dark-400-viewport.png)
- [Dark, 1280px](dashboard-dark-1280-viewport.png)

[Completed but uncounted, light phone](completed-uncounted-light-400.png)
