# Setup QA — PR5

This PR covers pages 14–16 of `docs/Tikwa QA.pdf`. All 19 pages were read for the six-PR plan.
PR4 is merged; the Floor findings on pages 17–18 remain in PR6.

The real app and throwaway backend were exercised at 400×800 and 1280×720, in light and dark.
Setup pages show the full document, including the archived section. Audit and the open rate
explanation show the viewport. All 32 images were visually reviewed, and the browser test
asserts there is no horizontal page overflow in any of the seven screens.

| Screen / state | PDF page | 400 light | 400 dark | 1280 light | 1280 dark |
|---|---|---|---|---|---|
| Audit table with before/after expanded | 14 | [View](audit-light-400.png) | [View](audit-dark-400.png) | [View](audit-light-1280.png) | [View](audit-dark-1280.png) |
| Categories: Delete / Archive / Restore | 15 | [View](categories-light-400.png) | [View](categories-dark-400.png) | [View](categories-light-1280.png) | [View](categories-dark-1280.png) |
| Pool tables: lifecycle and hourly rate | 15–16 | [View](tables-light-400.png) | [View](tables-dark-400.png) | [View](tables-light-1280.png) | [View](tables-dark-1280.png) |
| Customer types: lifecycle | 15 | [View](customer-types-light-400.png) | [View](customer-types-dark-400.png) | [View](customer-types-light-1280.png) | [View](customer-types-dark-1280.png) |
| Expense categories: lifecycle | 15 | [View](expense-categories-light-400.png) | [View](expense-categories-dark-400.png) | [View](expense-categories-light-1280.png) | [View](expense-categories-dark-1280.png) |
| Voucher batches: lifecycle | 15 | [View](vouchers-light-400.png) | [View](vouchers-dark-400.png) | [View](vouchers-light-1280.png) | [View](vouchers-dark-1280.png) |
| Staff: lifecycle and account protections | 15 | [View](staff-light-400.png) | [View](staff-dark-400.png) | [View](staff-light-1280.png) | [View](staff-dark-1280.png) |
| Per-minute rate under the info control | 16 | [View](rate-details-light-400.png) | [View](rate-details-dark-400.png) | [View](rate-details-light-1280.png) | [View](rate-details-dark-1280.png) |

## Behavior and compatibility

- Unused items have a confirmed, permanent Delete. Used items have Archive. Each setup page
  can show archived items and restore them. Historical rows remain intact.
- Lifecycle APIs are additive under `/api/v1/setup/{kind}`; legacy DELETE routes still archive.
  Reference metadata drives the button, and the server locks and checks again on deletion.
- Staff self/last-admin protections and open-table protections remain. Restored staff stay
  inactive until enabled in Edit. A restored customer type does not displace a new default.
- Voucher batches are the lifecycle unit shown by the existing Vouchers screen. Archiving
  blocks outstanding codes; existing redemptions remain unchanged. Restore makes unused,
  unexpired codes usable again. Even a redeemed-then-released code makes the batch historical.
- V21 adds voucher batch archival. V22 remembers category usage when products move away;
  it backfills existing links and audit history, conservatively keeping ambiguous reused names.
- Hourly amounts come from the server. The browser only formats them.

## Validation

From `frontend/`:

```sh
npm run typecheck
npm run build
E2E_DB_NAME=supreme_setup_qa_e2e E2E_API_PORT=8083 E2E_WEB_PORT=5176 npm run e2e -- setup-qa.spec.ts
```

Browser result: **1 passed**. This one scenario covers all four viewport/theme combinations,
all six real deletions and restores, a used customer type's archive/restore flow, hidden and
expanded rate details, and collapsed/expanded audit before/after. Fixtures use real products,
sessions, expenses and voucher redemption, not mocked API responses.

From the repository root, with an empty scratch database:

```sh
DB_URL=jdbc:postgresql://localhost:5432/supreme_setup_qa_scratch DB_USERNAME=supreme DB_PASSWORD=supreme ./mvnw resources:copy-resources@copy-frontend test '-Dtest=*Test'
```

Backend result: **245 tests, 0 failures, 0 errors**. The 29 added cases cover real deletion,
owned children, audit names after deletion, 409 refusals, archive/restore, reused names, default
restoration, open tables, self/last administrator, role/branch isolation, moved categories,
archived voucher redemption and released-code history. Persistence assertions flush and clear
before reading the stored state.

Mutation check: temporarily skipping the delete write made all six unused-item deletion cases
fail because the parent rows remained. Restoring the write returned the tests to green.
V22's backfill was also run in a rolled-back scratch transaction with an audit-only, renamed
former category and an unused control: only the formerly used category was marked.

The test databases and ports are isolated from the trading database and port 8080. No merge
or deployment is performed by this PR.
