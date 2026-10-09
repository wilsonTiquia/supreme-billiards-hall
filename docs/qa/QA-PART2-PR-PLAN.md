# QA Part 2: implementation PR briefs

Prepared 30 September 2026 from all 25 pages of `/Users/wilsontiqua/Downloads/qapart2.pdf`, including the screenshots. Checked against local commit `2bedbd7` on `feat/nightwatch`. These are proposed PRs, not existing GitHub PR numbers. Recheck current main before implementing: this assessment is of the local checkout, not a live-site test.

The PDF supplies requested product changes and visual references. It does not authorize this planning session to implement, publish, or deploy them. This deliverable is a handoff plan only.

## How to hand one PR to a fresh Astra session

Paste the following, replacing `XX` with the selected number:

```text
Implement PR-XX from /Users/wilsontiqua/SupremeBilliards/docs/qa/QA-PART2-PR-PLAN.md.
Read the shared implementation instructions and the entire selected brief first.
Use /Users/wilsontiqua/Downloads/qapart2.pdf as the visual reference for the cited pages.
Implement only this PR, after verifying that its listed dependencies have landed.
If a listed business decision is unresolved, ask the specific question before implementing
that dependent behavior; continue any independent work within this PR.
Validate the changes, create a reviewable pull request, and report its URL, screenshots,
and actual test results. Do not merge or deploy.
```

The plan contains enough written acceptance criteria to preserve the requirements if the PDF moves. For another computer/session, attach the PDF and this file and replace the local paths.

**PR-13 decision update, 9 October 2026:** The owner approved visibility-only Archive plus separate permanent cancellation, with legacy archived batches migrated as cancelled. See decision 2 and the PR-13 brief below. Other PR briefs remain the original planning reference.

## Shared implementation instructions

- Read applicable repository guidance, `frontend/CLAUDE.md`, relevant portions of `docs/API-CONTRACT.md`, and existing implementations. Some repository prose describes older code; verify claims against current source and migrations.
- Start from updated main in an isolated checkout when necessary. Use a separate branch and one PR per brief. Do not include unrelated local changes; the planning checkout had an untracked `src/main/java/com/supremebilliardshall/billiards_hall_system/dto/TestDTO.java`.
- Match the existing React/TypeScript, Tailwind, CSS-variable, and hand-authored SVG approach. Reuse components and tokens. Avoid adding a component or chart library for this work.
- Apply icons, status pills, and color deliberately. Preserve readable text labels, contrast in both themes, keyboard focus, 44px interaction targets, and touch alternatives for hover interactions. Do not rely on color alone.
- Preserve server-authoritative amounts, billable time, business dates, receipt snapshots, branch isolation, and API role restrictions. Cosmetic changes must not change accounting, stock, redemption, or permission rules.
- Test representative screens at roughly 1280px and 400px widths, in light and dark themes. Check loading, empty, error, long-label, and large-data states where relevant. Capture before/after screenshots for changed screens.
- Run `npm run typecheck` and `npm run build` from `frontend/`. For report changes run `npm run test:reports`; use relevant existing Playwright checks and add focused regression coverage for changed interactions or data behavior. Do not write assertion-only tests for padding or markup details.
- Follow the repository's scratch-database test workflow and PR template. Run required backend checks on an isolated PostgreSQL scratch database; never use trading data or point mutating E2E fixtures at production. Report exact commands/results and anything not run. New migrations must be additive; never edit applied migrations.
- Update relevant API documentation when contracts change. Use `.github/pull_request_template.md`; deliver the PR for human review without merging or deploying.

## Recommended order and dependencies

Numbers are identifiers; the dependencies determine merge order. Complete PR-01 and PR-02 first, then the analytics sequence. The remaining screen work can be taken one PR at a time. Resolve any remaining PR-07 decisions before assigning dependent implementation. PR-13 archive/cancellation policy was approved on 9 October 2026.

| PR | Title | Depends on | PDF pages |
|---|---|---|---|
| 01 | Shared actions, icons, and pagination | None | 1, 4–5, 10, 17, 19, 25 |
| 02 | Single-column sidebar and floor-only sticky business-day header | 01 | 1–4, 8–9, 12, 15, 17–18, 20 |
| 03 | Analytics tooltips, sales donut, table tiles, and product summaries | 01 | 6–10 |
| 04 | Dashboard night-check hierarchy and definitions popup | 01, 03 | 4–6 |
| 05 | Reports toolbar, financial summary, and detail sections | 01, 03 | 8–12 |
| 06 | Categorized product catalog and optional image on creation | 01 | 12–14 |
| 07 | Single-product stock entry and explicit purchase-cost policy | 06; cost decision | 12–13 |
| 08 | Segmented stock workspace with low-stock sidebar | 01 | 15 |
| 09 | Sales receipt modal and reusable receipt layout | 01, 02 | 15–17 |
| 10 | Expandable audit rows | 01 | 17 |
| 11 | Table/staff action menus and rate help | 01 | 18–19 |
| 12 | Voucher batch cards and readable code list | 01 | 18–19 |
| 13 | Voucher batch archive behavior | 12; approved archive/cancellation policy | 18 |
| 14 | Session and quick-sale navigation and product density | 01, 02 | 20–22 |
| 15 | Expense layout and centered settings | 01 | 19–20, 22–23 |
| 16 | Unsettled-bill hierarchy and end-of-day banners | 01, 02 | 23–25 |

Avoid assigning PR-03/04/05 against the same unmerged files. PR-06/07 and PR-12/13 should also land sequentially. PR-08 keeps ownership of the bulk delivery form; PR-07 owns the single-product shortcut.

## Decisions and source discrepancies

1. **Purchase cost, PR-07:** The PDF assumes cost is editable in Edit product. Current `ProductRequest` has no purchase-cost field; the exposed `avgCost` is a moving weighted average derived from stock deliveries. Recommended design, subject to owner approval: add a separate default purchase cost for future deliveries, editable by admins. Changing it must not overwrite inventory average cost or historical sale costs. Decide initialization/backfill and behavior when it is unset. Do not silently use average cost or zero as an actual supplier price.
2. **Voucher archive, PR-13 — approved 9 October 2026:** Archive is reversible and visibility-only, with Show archived and Restore. Add a separate explicit Cancel action, confirmed and audited, that permanently disables unused codes. Restore changes visibility only; it never reverses cancellation, expiry, or redemption. Current main already has `archived_at` and setup archive/restore endpoints, and blocks redemption of archived batches. An additive migration must preserve that block by marking every previously archived batch as both archived and cancelled; never automatically reactivate codes. Reuse existing archive support. Serialize cancellation and redemption on the batch lock, preserve past redemptions/receipts, and expose clear cancelled counts/status.
3. **Premium tables, PR-03:** The screenshot labels some table names “Premium,” but `PoolTable`, `TableUtilisation`, and `PeriodTable` have no explicit premium flag; the report rows also lack stable table IDs. Verify the intended source of truth before assigning a star. Prefer explicit metadata over guesses from rate or name text. If new metadata is needed, confirm its default/backfill for existing tables and include a narrowly scoped API/migration change, or split that addition into a prerequisite PR.
4. **Report arithmetic, PR-05:** The literal wording “total sales - after product costs - operating expenses” would subtract the wrong value. Preserve the existing accounting: sales minus product costs equals gross profit; gross profit minus operating expenses equals net. Present this clearly with server-provided fields.
5. **Business-day header, PR-02:** Repeated “remove this” screenshots refer to the global Business day/date strip, not individual page titles or date controls. The later Floor note makes an exception. Proposed scope: show it only on the full `/floor` overview, where it is sticky; remove it from other routes while preserving necessary date controls within each page.
6. **Already present:** Both dashboard/report top-product summaries already use `.slice(0, 5)`; table Edit already changes rates; existing-product image upload already exists; Sales already supplies a “Back to sales” receipt origin. Finish the requested visual/interaction changes without rebuilding these behaviors.
7. **Reference shorthand:** Reports item 4 says “jude the break-even”; interpreted as “hide the break-even [label] while hovering a bar.” Keep the reference line/value available otherwise. Audit says to match dashboard pagination, but the reusable paginated example is in Reports; standardize the existing page controls rather than invent dashboard pagination.

## PR-01 — Shared actions, icons, and pagination

**Outcome:** Later screen PRs can use consistent primary, secondary, tertiary, overflow-menu, and pagination controls.

**Implement:**

- Extend the existing button styling with a tertiary treatment and a matching router-link treatment. Navigation remains a link; actions remain buttons. Avoid nested interactive controls.
- Establish a small reusable SVG icon set for navigation, chevrons, overflow, back, refresh, and status. Reuse existing icons where suitable.
- Add an accessible `…` action-menu component with labeled trigger, keyboard operation, Escape/outside dismissal, and focus restoration. Menu items preserve existing permission/disabled states and confirmation flows.
- Extract one pagination component and adopt it on existing paginated surfaces: Reports, Audit, and Sales. Use “Previous”/“Next,” consistent spacing, page/count text, and boundary/pending states. Do not add pagination to unpaginated screens just for consistency.
- Keep feature-specific menu adoption and CTA hierarchy in their owning PRs below.

**Start in:** `frontend/src/components/Button.tsx`, `ThemeIcon.tsx`, `frontend/src/styles/`, `frontend/src/features/admin/reports/ReportExplorer.tsx`, `ReportsPage.tsx`, `audit/AuditPage.tsx`, `sales/SalesPage.tsx`.

**Accept when:** All existing paginators share the same component without changing filters, sorting, page indexing, or result sets. Menu and icon-only controls work with keyboard and touch. Screen readers receive meaningful labels.

## PR-02 — Single-column sidebar and floor-only sticky business-day header

**Outcome:** Navigation remains recognizable when collapsed and does not compete with content.

**Implement:**

- Make destination lists one column in both expanded and collapsed navigation. Expanded links show icon and label; collapsed links show icons with accessible labels/tooltips instead of abbreviations such as Db/Rp.
- Move the theme control beside the user/account area at the bottom. Remove the repeated branch/hall name there; retain the user's name and role.
- Preserve the Floor/Admin workspace switch and role restrictions. Use compact spacing, scrolling, or collapsible Look up/Set up groups to fit short screens without shrinking targets below 44px.
- Restrict the global business-day strip to the full Floor overview and make it sticky with correct stacking. Remove it from admin pages and other counter routes. Preserve the underlying business-date queries and page-specific controls.

**Start in:** `frontend/src/app/AppShell.tsx`, `Sidebar.tsx`, relevant shared CSS.

**Accept when:** Every allowed route is reachable at narrow widths and a short desktop height; mobile drawer focus/Escape behavior still works; the theme/account area stays usable; the floor date strip remains visible while scrolling without covering controls.

## PR-03 — Analytics tooltips, sales donut, table tiles, and product summaries

**Outcome:** Dashboard and Reports communicate exact chart values at the point of interaction and use balanced summaries.

**Implement:**

- Give `SalesByNight` and `HourChart` anchored value tooltips on hover, keyboard focus, and touch selection. Include the corresponding night/hour, amount, and available bill count. Keep the best/busiest caption stable at the bottom rather than replacing it with hover data.
- In Reports, suppress the break-even label while a selected bar's tooltip would overlap it; restore it afterward. Do not remove the break-even reference from the report.
- Replace the dashboard sales-source stacked bar with an SVG pie/donut on the left and the numeric legend on the right. Preserve separate bill-discount/voucher adjustments and reconciliation to total sales; never show a negative adjustment as a pie slice.
- Replace dashboard/report table summary rankings with balanced tiles. Retain their different metrics: dashboard utilization, report revenue/occupied-hour context. Add premium stars and a `★ Premium` legend only from confirmed metadata (see decision 3).
- Restyle top-product summaries as two-line entries: product and sales amount prominent, quantity sold secondary; remove 01–05 numbering. Preserve the existing top-five limit and ordering.
- Center the hourly chart in its available area and anchor its busiest note at the bottom. Balance paired table/product panels without large artificial blank areas.

**Start in:** `features/admin/analytics/Analytics.tsx` and `analytics.css`, `dashboard/HourChart.tsx`, `DashboardPage.tsx`, `dashboard.css`, `reports/SalesByNight.tsx`, `ReportsPage.tsx`, `reports.css`, `api/types.ts` if metadata is needed.

**Accept when:** Zero sales, a single nonzero source/bar, discounts, long product names, and dense dates render accurately without clipped tooltips. Dashboard and Reports share treatments while retaining their own measures. Printed reports still show figures without requiring hover.

## PR-04 — Dashboard night-check hierarchy and definitions popup

**Outcome:** The owner can distinguish urgent follow-up from secondary information immediately.

**Implement:**

- Restyle Night check using severity icons with colored backgrounds, stronger headings, and proper secondary CTAs. Put lower-priority items in an expandable “Other checks” section with smaller neutral icons/text and tertiary actions.
- Derive severity from existing actionable conditions; document the mapping. Do not invent monetary thresholds or hide a condition that blocks closing the night.
- Remove the redundant View report link beside the dashboard title; keep sidebar access.
- Change the definitions side popup into a wide popup aligned near its info trigger, with a full-screen overlay, close control, Escape/outside dismissal, and focus handling. Clamp to small viewports and allow internal scrolling.

**Start in:** `frontend/src/features/admin/dashboard/DashboardPage.tsx` (`Attention`, `HowWorkedOut`) and `dashboard.css`; reuse modal primitives where suitable.

**Accept when:** A clear night, multiple urgent conditions, lower-priority checks, and a checks-fetch failure remain distinguishable. CTA destinations and selected business night are preserved. The definitions popup is readable without horizontal scrolling.

## PR-05 — Reports toolbar, financial summary, and detail sections

**Outcome:** Reports read as one coherent financial summary with well-structured supporting details.

**Implement:**

- Align presets, date range, Export, and Refresh in one desktop row with matched control heights. Display `[date] to [date]`, hiding visual From/To labels while preserving accessible labels. Use icon-only Refresh at tighter widths; wrap cleanly on phones rather than overflowing.
- Combine the headline sentence, current/prior range subtext, and financial figures into one container. Remove separate fill/borders from intermediate figures. Put comparison percentage and label on one row and remove repeated prior-range text from each figure.
- Use financially correct labels and operators: Sales − Product costs = After product costs; then − Operating expenses = After recorded costs. Preserve server values and zero-prior-period handling. Do not literally subtract gross profit from sales (decision 4).
- Use the chart/table/product treatments from PR-03 and pagination from PR-01.
- Restyle expandable detail headers as leading icon, stacked title/subtext, and emphasized trailing chevron. Preserve their print-expanded behavior.
- Convert Bills and averages and Drawer to tiles. Keep Given away's related categories together, moving Comps into the second desktop column with the other relevant items. Render Still owed as `Age | Bills | Amount`, preserving existing age buckets and totals.

**Start in:** `frontend/src/features/admin/reports/ReportsPage.tsx`, `reports.css`, `ReportExplorer.tsx`, `frontend/src/components/Disclosure.tsx`, `features/admin/Comparison.tsx`.

**Accept when:** Automatic date application, invalid-range feedback, URL state, filtering, export, and full-report printing remain intact. Negative net, empty periods, zero prior values, and long ranges remain readable and numerically unchanged.

## PR-06 — Categorized product catalog and optional image on creation

**Outcome:** Products are easier to scan and can receive an optional picture during creation.

**Implement:**

- Group the catalog visibly by category, with an explicit uncategorized group. Preserve current search and archived filters; do not lose inactive/archived status or cost/margin visibility rules.
- Move Edit/Archive into the shared overflow menu. Keep Add stock easy to find and retain Restore for archived products with its existing behavior.
- Add a compact optional image field to New product, with preview, replace/remove-before-save, accepted-format/size feedback, and pending/error handling. Reuse the existing product-image endpoint after creation supplies an ID.
- Handle partial success explicitly: if product creation succeeds and image upload fails, retain the created product and provide an upload retry against its ID. Never create another product merely to retry its image.

**Start in:** `frontend/src/features/admin/catalog/ProductsPage.tsx`, `frontend/src/components/ProductImage.tsx`, `frontend/src/api/endpoints/products.ts`.

**Accept when:** Create without image, create with image, upload failure/retry, category filtering, archive/restore, and edit-existing-image flows work. Product creation and optional opening stock are not duplicated by image retries.

## PR-07 — Single-product stock entry and explicit purchase-cost policy

**Decision required:** Resolve purchase-cost source and edit behavior described in decision 1. This is not purely a visual change.

**Outcome:** Add stock on a product is a focused single-product form with a reliable cost source.

**Implement after the decision:**

- Lock the form to the selected product. Replace Line 1/Add a line/product selection with a `− [quantity] +` control supporting direct entry and existing fractional quantity precision.
- Show the approved per-unit cost read-only in this shortcut. Make its edit path in Edit product real if that policy is selected; define unset-cost behavior rather than assuming zero.
- If adding a default purchase cost, use an additive migration, admin-only request/response handling and validation. Keep it separate from weighted average cost. Resolve the shortcut's authoritative cost on the server or validate it under the agreed contract, so a stale client cannot silently choose a different price.
- Continue recording stock through the ledger/service transaction. Updating default cost must not revalue existing stock or historical bills. Bulk delivery in Stock retains explicit actual unit costs.

**Start in:** `ProductsPage.tsx`, `stock/DeliveryForm.tsx`, product/stock API types and controllers/services, relevant Flyway migrations. Prefer a dedicated single-product mode/form over stripping bulk capabilities from `DeliveryForm`.

**Accept when:** Repeated clicks/pending states do not submit duplicates through this UI; zero/negative/invalid quantities are rejected; the selected product cannot change; saved stock and average cost agree across a fresh request. Backend tests cover cost edits, delivery valuation, employee field omission, and immutable historical sale costs.

## PR-08 — Segmented stock workspace with low-stock sidebar

**Outcome:** Stock shows one task at a time beside a persistent low-stock overview.

**Implement:**

- Build a roughly 70/30 desktop layout: task form on the left, Low or negative stock on the right. Stack on mobile.
- Use Delivery / Count / Giveaway segmented controls to show the corresponding existing form. Count retains the correction service's current meaning; do not change stock semantics based on the shorter tab label.
- Keep bulk Delivery's multiple products and explicit unit-cost entry. Preserve or clearly manage drafts when switching tabs; a switch alone must not submit or silently lose input.
- Keep pending/error/success messages associated with the relevant task. Successful operations refresh quantities and the low-stock list.

**Start in:** `frontend/src/features/admin/stock/StockPage.tsx`, `DeliveryForm.tsx`.

**Accept when:** Each tab performs its original transaction correctly, tab switching is keyboard-accessible, long deliveries remain scrollable, and low-stock updates after every operation. No off-screen submit button on small screens.

## PR-09 — Sales receipt modal and reusable receipt layout

**Outcome:** Opening a receipt from Sales keeps the owner in the Sales workflow.

**Implement:**

- Open receipts from Sales in a wide dialog over the list. Preserve selected date, filters, and page on close; support Escape, focus restoration, loading/error states, and a meaningful browser Back behavior.
- Extract a reusable receipt presentation from `ReceiptPage` so modal and standalone receipt routes share snapshot rendering.
- Use a roughly 70/30 layout for receipt and notes. Put Back/Close upper left and Print upper right. Stack on phones. Print only the receipt, excluding modal overlay, controls, notes, and the background Sales list.
- Preserve standalone post-checkout receipt behavior, payment-photo upload, notes, and existing origin links. The current origin state helps the return link but still switches to the counter shell; fix the modal/context issue.
- Make Sales discoverable from the Floor workspace for admins, using a clear permitted navigation entry or shortcut. Do not expose the admin sales list to employees.

**Start in:** `frontend/src/features/admin/sales/SalesPage.tsx`, `features/checkout/ReceiptPage.tsx`, `features/notes/NoteThread.tsx`, `app/router.tsx`, relevant `app/AppShell.tsx` navigation.

**Accept when:** An owner can inspect several receipts and return to the exact list state; cold receipt URLs and post-payment receipts still work; print output contains the receipt once and no background UI. Receipt data is rendered from its stored payload, not reconstructed from current catalog prices.

## PR-10 — Expandable audit rows

**Outcome:** Audit details expand naturally from the row.

**Implement:**

- Replace the leading disclosure treatment with a clear trailing chevron. Clicking the row toggles its details; provide a proper keyboard-operable expansion control with `aria-expanded` and a connected detail region.
- Preserve event labels, actor, time, entity context, and before/after data. Links or controls inside expanded details must not accidentally toggle the row.
- Use the shared Previous/Next pagination from PR-01 and retain filters/order/page counts. The global date strip is removed by PR-02, not duplicated here.

**Start in:** `frontend/src/features/admin/audit/AuditPage.tsx`.

**Accept when:** Multiple event types and long payloads expand/collapse without misaligned table columns. Keyboard operation and pagination work without losing filter state or confusing which event is expanded.

## PR-11 — Table/staff action menus and rate help

**Outcome:** Table and staff maintenance share predictable action menus and readable help.

**Implement:**

- Move Edit/Archive into overflow menus for Tables and Staff. Preserve staff Reset password and all current self/last-admin/archive restrictions; decide placement by frequency without hiding it accidentally.
- Remove the redundant Change rate CTA from Tables because Edit already opens new rate periods correctly. Remove now-unused dedicated form code where safe; do not alter the rate history service.
- Show rate-detail help on pointer hover and keyboard focus, with a touch/click fallback. Keep all precise server-supplied rate information accessible.
- Remove visible references to internal files such as HELP.md from staff-facing copy. Describe only actions available in the product or direct users to their administrator; do not invent an admin-password-reset feature.

**Start in:** `frontend/src/features/admin/catalog/TablesPage.tsx`, `features/admin/staff/StaffPage.tsx`.

**Accept when:** Hourly and per-minute rates survive edit/read-back correctly, old sessions keep their rates, staff actions retain existing restrictions, and user-visible copy no longer exposes internal-document instructions.

## PR-12 — Voucher batch cards and readable code list

**Outcome:** Voucher batches and redemption status are readable without interpreting a dense grid.

**Implement:**

- Lead each batch with its dynamic coupon count and hours as subtext. Show the giveaway note as a subheading with “Created by [person] on [date/time]” below it.
- Place Outstanding / Redeemed / Expired counts in small tiles, followed by an expiry pill. Put Codes beside the header area. Archive is supplied by PR-13; do not add a dead action in this PR.
- Change both generated and inspected code views to a single-column full list. Show a read-only checkbox/check indicator for redeemed codes, plus “Redeemed on receipt #[number]” using existing response fields.
- Distinguish outstanding and expired states in text as well as color. The checkbox represents status; clicking it must never redeem or unredeem a voucher.
- Preserve copying/printing codes and scrolling for large batches. Link receipts only where routing and permissions permit.

**Start in:** `frontend/src/features/admin/vouchers/VouchersPage.tsx`, existing `Voucher`/`VoucherBatch` API types.

**Accept when:** New, partially redeemed, fully redeemed, and expired batches render correctly; long codes do not wrap ambiguously; existing receipt numbers appear; employees cannot fetch the admin code list.

## PR-13 — Voucher batch archive behavior

**Decision approved (9 October 2026):** Visibility-only Archive, Show archived, and Restore; separate irreversible Cancel with confirmation and audit. Previously archived batches must migrate as archived and cancelled, without reactivating codes. Restore affects visibility only.

**Outcome:** The overflow menu distinguishes hiding a batch from disabling unused codes.

**Implement:**

- Reuse the existing branch-scoped, admin-only setup archive/restore endpoints, storage, and audit history. Add minimum additive cancellation schema/API/service support and durable read-back.
- Add Archive to every active batch's overflow menu, independently of deletion eligibility. Provide Show archived with batch details, Codes, Restore, and Cancel where applicable.
- Archive hides the batch without affecting redemption eligibility, original expiry, receipts, or history. Restore never clears cancellation or changes expiry/redemption.
- Cancel permanently blocks unused codes, including a code later released from an open bill; preserve existing redemptions and receipts. Explain this in confirmation copy and audit the actor and before/after state. No uncancel operation.
- Preserve the existing redemption block for all previously archived batches with an additive backfill of cancellation state and an explicit migration audit record. Do not change applied migrations or automatically reactivate codes.
- Serialize cancellation with redemption using the batch lock. Redemption committed first remains valid; cancellation committed first blocks redemption. Counts/status must distinguish cancelled unused codes from outstanding codes, while retaining redeemed/expired history.

**Start in:** `VouchersPage.tsx`, `api/endpoints/vouchers.ts`, existing setup lifecycle, backend voucher controller/service/repository/entity/DTO slices and new migration.

**Accept when:** Archive/restore/cancel state survives transaction boundaries; branch/role restrictions hold; previously archived batches remain blocked after migration and Restore; newly archived valid codes remain redeemable; cancellation survives Restore and voucher release; expired/redeemed codes stay expired/redeemed; receipts and audit history survive; concurrent cancellation/redemption follows the stated ordering. Cover confirmations, failure/pending states, Show archived, and Codes with responsive light/dark screenshots.

## PR-14 — Session and quick-sale navigation and product density

**Outcome:** Counter staff can return to the floor clearly and find Pause quickly.

**Implement:**

- Add a clear Back to floor button at the upper right of the active session view reached after Start. Replace the ambiguous inline Floor control as appropriate without losing navigation.
- Use the same back-button pattern in Quick sale instead of its blue text link.
- Reduce product image dimensions by approximately 15% in the session product grid, preserving labels, prices, search/category controls, and usable hit targets. Check the shared Quick sale grid for consistent effects.
- Make Pause a primary CTA. Preserve pending states and the paused/resume state transition; keep checkout/close actions distinguishable.

**Start in:** `frontend/src/features/session/SessionPage.tsx`, `ProductGrid.tsx`, `features/quicksale/QuickSalePage.tsx`, `components/ProductImage.tsx` if shared sizing requires it.

**Accept when:** Start → session → pause/resume → back and Quick sale → back work with keyboard and pointer. Image changes affect presentation only; timers, billed amounts, basket updates, and server reconciliation remain intact.

## PR-15 — Expense layout and centered settings

**Outcome:** Expense entry sits beside tonight's entries; settings have a balanced centered form.

**Implement:**

- Use a two-column Expenses layout with entry form left and Tonight right, stacking on mobile.
- Replace the What for category dropdown with labeled selectable pills using categories returned by the API. Keep a clear selected state, keyboard operation, and graceful wrapping for longer/more categories.
- Center the Settings form within its available page width using a sensible maximum width. Preserve its heading, help, validation, and save state.

**Start in:** `frontend/src/features/expenses/ExpensesPage.tsx`, `features/admin/settings/SettingsPage.tsx`.

**Accept when:** Categories are not hardcoded, expense save/void and tonight totals retain current behavior, and settings saves remain visible and usable at both target widths.

## PR-16 — Unsettled-bill hierarchy and end-of-day banners

**Outcome:** Outstanding debts and earlier uncounted nights can be scanned without dense repeated text.

**Implement:**

- Group the Unsettled list by the bill's existing played/business date. Preserve current filtering/order behavior as far as compatible with date grouping; avoid browser-calendar reinterpretation of overnight bills.
- Use two-line debt summaries: name with days-outstanding pill; note and table underneath. Remove repeated per-row played-date text and “Left by counter” from the compact list. Keep amount and collection/receipt access visible.
- Respect the stored note model: do not guess/split a free-text name and promise-to-pay note in a way that loses information. Inspect the API fields before deciding the exact arrangement.
- Replace the large earlier-nights list on End of day with one expandable banner using the actual count. Expanded items use a consistent responsive grid of date tags with bill-count pills and chevrons; omit weekday names. Each still selects the correct night.
- Turn the opening-float adjustment area into a clearly headed banner with a secondary “Different” action. Preserve the existing float edit flow.
- Show debt age next to names in End of day without repeating “outstanding,” with shared severity styling. Reuse the existing 14-day danger threshold unless an explicit business decision changes it; retain age text for accessibility.
- Use a tertiary button/link for Go to the unpaid list, preserving its route and dynamic label/count behavior.

**Start in:** `frontend/src/features/unsettled/UnsettledPage.tsx`, `features/endofday/EndOfDayPage.tsx`, `CashCountPanel.tsx` if the float UI lives there; reuse narrow shared debt-summary styling where helpful.

**Accept when:** Today, older debts, empty/filter-empty results, long notes, and many uncounted nights remain clear. Counts are dynamic, dates route correctly, and collection, cash-count/recount, close guards, and server-provided debt ages remain unchanged.

## Source coverage checklist

| PDF section / numbered notes | Owning PR(s) |
|---|---|
| General icons, colors, tags/pills (p.1) | 01 establishes patterns; each screen adopts them |
| Sidebar 1–4 (pp.1–4) | 02 |
| Dashboard 1–4 (pp.4–6) | 01, 04 |
| Dashboard 5–8 (pp.6–8) | 03 |
| Reports 1, recurring strip removal (pp.8–9) | 02 |
| Reports 2–3 (p.9) | 05 |
| Reports 4–5 (pp.9–10) | 03 |
| Reports 6, pagination spacing (p.10) | 01 |
| Reports 7–11 (pp.10–12) | 05 |
| Products 1 (p.12) | 02 |
| Products 2–3, 5 (pp.12–14) | 06 |
| Products 4a–b (pp.12–13) | 07; cost decision required |
| Stock 1–2 (p.15) | 02, 08 |
| Sales 1–2 (pp.15–17) | 09 |
| Audit 1–3 (p.17) | 02, 10, 01 respectively |
| Tables 1–4 (p.18) | 02, 11 |
| Vouchers 1–2 (pp.18–19) | 12; Archive in 13 after decision |
| Staff 1–2 (p.19) | 11 |
| Settings 1 (pp.19–20) | 15 |
| Floor sticky strip (p.20) | 02 |
| Floor/session 1–3 (pp.20–21) | 14 |
| Quick Sale 1 (pp.21–22) | 14 |
| Expenses 1 (pp.22–23) | 15 |
| Unsettled 1 (p.23) | 16 |
| End of day 1–4 (pp.24–25) | 16 |

Planning validation: extracted text from all 25 pages, visually inspected their rendered screenshots, checked relevant current UI/API code, and mapped every numbered note above. No application code was changed and no application tests were run for this planning-only deliverable.
