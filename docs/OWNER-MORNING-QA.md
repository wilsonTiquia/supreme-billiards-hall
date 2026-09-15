# Owner morning dashboard

Restores the layout from `1d57b2b` and applies the agreed morning flow:

1. Selected night and completion status; one-click current-night view.
2. Sales, after cost of goods, and payments collected.
3. A cash line, always present, linking to the selected night's count.
4. Attention band, summary sentence, and recorded payouts without subtracting them.
5. Collapsed Details, including the hour chart, top items, table use, and pricing.

The selected date is stored in `?date=`. Default selection uses the server clock: trading ends at 05:00 Manila, while the database date label changes at 10:00. “Complete” describes the scheduled night ending, not cash-count sign-off. The in-progress label uses the configured 10am business-day start, not the time of the first sale. Running nights show no comparison lines. No-sales nights do not receive percentage comparisons.

Collected is the sum of payments actually taken on the selected business date. Older-debt collections are named in the sentence when present; the API's amount left unpaid on the night is historical, distinct from live outstanding debt in Attention. Operating expenses remain stated as Paid out, without reducing the dashboard's after-COGS figure.

Cash loading, unavailable checks, and activity after close are explicit rather than displaying a false balance. Missing counts are red once the scheduled night ends.

Given away retains all eight rows and daily drill-throughs: six chosen-discount categories (including time not charged), voids under Mistakes, and Comps under an estimate heading. Reports retains its existing total and receives the same grouping headings.

## Verification

- Helper tests cover the 05:00 and 10:00 boundaries, year rollover, invalid dates, cash balance/short/over, uncounted, unavailable, and recount states.
- Browser: default date materializes in the URL; Tonight so far suppresses comparisons; September 5 survives opening Reports and returning.
- Browser at 390 × 844: figures, cash, attention, sentence, and rent payout appear before Details.
- September 5 displays sales ₱27,068, after COGS ₱18,959, collected ₱27,068, cash balanced, and rent payout ₱45,000 separately.
- Given away shows all eight buttons under three headings; Discounts opens its supporting bills.
- TypeScript and production build pass. Vite retains the existing large-bundle advisory.
