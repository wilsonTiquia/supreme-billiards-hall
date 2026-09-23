# Stock QA — PR4

Read all 19 pages of `docs/Tikwa QA.pdf`. This PR covers the Products and Stock findings on
pages 11–14; the Audit finding on page 14 remains in PR5.

Screenshots use the real app and scratch backend with seeded products. Each state is captured
at 400×800 and 1280×720 in both themes. Page screenshots show the full document; dialogs and
toasts show the viewport. The long-delivery capture shows line 8 inside its own scroll region;
the rest of Stock remains reachable by normal page scrolling.

| State | PDF page | 400 light | 400 dark | 1280 light | 1280 dark |
|---|---|---|---|---|---|
| Products, including Add stock on each row | 12 | [View](products-light-400.png) | [View](products-dark-400.png) | [View](products-light-1280.png) | [View](products-dark-1280.png) |
| New product with paired opening quantity and unit cost | 11–12 | [View](opening-stock-light-400.png) | [View](opening-stock-dark-400.png) | [View](opening-stock-light-1280.png) | [View](opening-stock-dark-1280.png) |
| Delivery opened from the product row, prefilled | 12 | [View](add-stock-light-400.png) | [View](add-stock-dark-400.png) | [View](add-stock-light-1280.png) | [View](add-stock-dark-1280.png) |
| Edit product, without opening stock controls | 11–12 | [View](edit-product-light-400.png) | [View](edit-product-dark-400.png) | [View](edit-product-light-1280.png) | [View](edit-product-dark-1280.png) |
| Stock page | 13 | [View](stock-light-400.png) | [View](stock-dark-400.png) | [View](stock-light-1280.png) | [View](stock-dark-1280.png) |
| Eight delivery lines scrolled to the last line | 13–14 | [View](stock-long-delivery-light-400.png) | [View](stock-long-delivery-dark-400.png) | [View](stock-long-delivery-light-1280.png) | [View](stock-long-delivery-dark-1280.png) |
| Give-away success toast | 14 | [View](stock-toast-light-400.png) | [View](stock-toast-dark-400.png) | [View](stock-toast-light-1280.png) | [View](stock-toast-dark-1280.png) |

## Reproduce

From `frontend/`:

```sh
E2E_DB_NAME=supreme_stock_qa_e2e E2E_API_PORT=8083 E2E_WEB_PORT=5176 npm run e2e -- stock-qa.spec.ts
```

The existing Playwright guards and `scripts/e2e-backend.sh` create and own the throwaway
instance. This never targets the trading database or port 8080. The test logs in, rejects a
half-filled opening pair, creates 12 units at ₱62.50, receives another 12 at ₱67.50 from the
product row, and reads back 24 on hand / ₱65.00 average cost plus both delivery movements. It
also checks that Edit cannot re-enter opening stock, that both scroll areas work without
horizontal page overflow, and that a give-away produces a dismissible toast.
