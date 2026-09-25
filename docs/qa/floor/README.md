# PR6 — floor and payment QA

Closes the accepted findings on pages 17–18 of `docs/Tikwa QA.pdf`. All 19 pages and the original six-PR plan were reviewed; this change only implements PR6.

## Layout decision and taps

Use two equal columns at 900px and wider: products on the left, live session and bill on the right. The product pane stays in view while the bill can grow. Below 900px, “Add items” opens the existing dismissible modal and focuses product search. A successful add closes it and returns focus to “Add items”; errors keep it open.

Starting from the floor with Table 3 already running and San Miguel visible in the catalogue:

| Layout | Action sequence | Taps to add one beer |
| --- | --- | --- |
| Chosen desktop layout | Table 3 → San Miguel Pale Pilsen | 2 |
| Chosen mobile layout | Table 3 → Add items → San Miguel Pale Pilsen | 3 |
| Alternative: popup at every width | Table 3 → Add items → San Miguel Pale Pilsen | 3 at both widths |

Desktop search also accepts typing and Enter. Keeping the products visible saves a tap on each visit and lets staff read the bill while ordering. The bill no longer gets squeezed into a small scroll area under the timer. No new dependency.

## Payment behavior

GCash/Maya accept a selected confirmation photo beside the reference field. Cash never receives a photo. The existing endpoint requires a payment ID, so the selected file uploads after successful payment. Photo failure is reported separately: payment remains recorded, and the receipt’s Add photo fallback retries only the upload. Duplicate-reference confirmation preserves the file and reuses the payment key. Quick Sale uses the same form and passes its payment context to the receipt. The receipt keeps the successful upload state on reload.

## Screenshot matrix

42 captures from the real local scratch app, not mocked pages. Viewports are 400×800 and 1280×720; page captures include scrolling content. The mobile popup uses a viewport capture; it does not exist at desktop width because products are inline there. Product images use the app’s normal initials fallback.

The floor includes a deliberately large ₱1,234,567.89 total and a paused 123:59:00 timer. The other populated table demonstrates a normal live order. Browser assertions also cover 320, 640, 767, 768, 899, 900, 1024, 1280 and 1536px, plus enlarged text.

| Screen/state | 400 light | 400 dark | 1280 light | 1280 dark |
| --- | --- | --- | --- | --- |
| Floor | [View](floor-light-400.png) | [View](floor-dark-400.png) | [View](floor-light-1280.png) | [View](floor-dark-1280.png) |
| Session — bill and products | [View](session-light-400.png) | [View](session-dark-400.png) | [View](session-light-1280.png) | [View](session-dark-1280.png) |
| GCash payment | [View](payment-gcash-light-400.png) | [View](payment-gcash-dark-400.png) | [View](payment-gcash-light-1280.png) | [View](payment-gcash-dark-1280.png) |
| Maya payment | [View](payment-maya-light-400.png) | [View](payment-maya-dark-400.png) | [View](payment-maya-light-1280.png) | [View](payment-maya-dark-1280.png) |
| Receipt — photo attached | [View](receipt-light-400.png) | [View](receipt-dark-400.png) | [View](receipt-light-1280.png) | [View](receipt-dark-1280.png) |
| Receipt — Add photo fallback | [View](receipt-fallback-light-400.png) | [View](receipt-fallback-dark-400.png) | [View](receipt-fallback-light-1280.png) | [View](receipt-fallback-dark-1280.png) |
| End of day | [View](end-of-day-light-400.png) | [View](end-of-day-dark-400.png) | [View](end-of-day-light-1280.png) | [View](end-of-day-dark-1280.png) |
| End of day — different float | [View](float-override-light-400.png) | [View](float-override-dark-400.png) | [View](float-override-light-1280.png) | [View](float-override-dark-1280.png) |
| Quick Sale — shared payment form | [View](quick-sale-light-400.png) | [View](quick-sale-dark-400.png) | [View](quick-sale-light-1280.png) | [View](quick-sale-dark-1280.png) |
| Quick Sale — payment recorded, photo failed | [View](quick-sale-paid-light-400.png) | [View](quick-sale-paid-dark-400.png) | [View](quick-sale-paid-light-1280.png) | [View](quick-sale-paid-dark-1280.png) |
| Session product popup | [View](session-products-light-400.png) | [View](session-products-dark-400.png) | Inline products above | Inline products above |

## Reproduce

From `frontend/`:

```sh
E2E_DB_NAME=supreme_floor_qa_e2e E2E_API_PORT=8086 E2E_WEB_PORT=5179 npm run e2e -- floor-qa.spec.ts
```

The existing Playwright guards start and rebuild only the marked scratch database. The spec uses real API fixture setup, adds items through the UI, checks the persisted line count, and verifies uploaded bytes through the admin photo endpoint. It covers upload failure, receipt-only retry, method switches/removal, duplicate-reference retry, and reload after attaching a photo.

Mutation checks: restoring the old TableCard fails on the clipped `123:59:00` timer; bypassing upload while claiming success fails the photo-failure scenario. Both mutations were reverted before the final run and captures.

## Validation results

- `cd frontend && npm run typecheck && npm run build` — passed. Vite reports its existing >500 kB bundle warning.
- PR6 browser spec above — **3 passed**, including real photo byte verification and retry checks.
- `E2E_DB_NAME=supreme_floor_regression_e2e E2E_API_PORT=8086 E2E_WEB_PORT=5179 npm run e2e -- worked-trace.spec.ts discount.spec.ts voucher-prize.spec.ts employee-sees-no-cost.spec.ts` — **3 passed, 1 failed**. The voucher test completes redemption and the receipt, then expects a removed dashboard `Table use` button at line 121. Running `voucher-prize.spec.ts` with every tracked frontend source file restored to base commit `34deb54` reproduced the identical failure. The unrelated dashboard/test was left unchanged for PR6.
- `DB_URL=jdbc:postgresql://localhost:5432/supreme_floor_qa_scratch DB_USERNAME=supreme DB_PASSWORD=supreme ./mvnw -B resources:copy-resources@copy-frontend test` — **245 tests, 0 failures, 0 errors, 0 skipped**. No backend code changed; the suite was still run as required by the repository instructions.
- `git diff --check` — passed. The remaining browser specs were not run; no merge or deployment was performed.
