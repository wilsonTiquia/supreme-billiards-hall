import { useEffect } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useSearchParams } from 'react-router-dom';
import { fetchPeriodReport } from '@/api/endpoints/reports';
import { fetchCurrentBusinessDay } from '@/api/endpoints/businessDay';
import { queryKeys } from '@/api/queryKeys';
import { messageOf } from '@/api/errors';
import type { Money, PeriodComparison, PeriodReport } from '@/api/types';
import { AdminPage } from '../AdminPage';
import { Delta, Group, Row, Tile } from '../dashboard/DashboardPage';
import { SalesByHour } from '../dashboard/SalesByHour';
import { Card } from '@/components/Card';
import { Spinner } from '@/components/Spinner';
import { formatMoney } from '@/lib/money';
import { GrossByDay } from './GrossByDay';
import {
  PRESETS,
  WEEKDAYS,
  formatMonth,
  formatRange,
  presetOf,
  presetRange,
} from './periodDates';

/**
 * The owner's month. Reads only — nothing on this page is editable and nothing is billable.
 *
 * The URL carries from/to so a report can be bookmarked, and the presets are calendar ranges
 * of the business date the SERVER says is current. The previous period is the server's
 * decision too, returned in the document; this page formats dates, it never chooses them.
 *
 * The sections are in the order the questions get asked, and the same order the brief lists
 * them: is it making money, what does it need to take, how did it trend, which nights and hours
 * carry it, what it cost to be open, which tables and products pull their weight, what was
 * given away, and how well the drawer was kept.
 */
export function ReportsPage() {
  const [params, setParams] = useSearchParams();
  const from = params.get('from') ?? '';
  const to = params.get('to') ?? '';

  const currentDay = useQuery({
    queryKey: queryKeys.businessDayCurrent,
    queryFn: fetchCurrentBusinessDay,
    staleTime: 60_000,
  });
  const current = currentDay.data?.businessDate;

  // No range in the URL means this month, once the server has said which month it is. Replaced
  // rather than pushed so Back does not land on an empty page.
  useEffect(() => {
    if (current && (!from || !to)) {
      const range = presetRange('thisMonth', current);
      setParams({ from: range.from, to: range.to }, { replace: true });
    }
  }, [current, from, to, setParams]);

  const report = useQuery({
    queryKey: queryKeys.periodReport(from, to),
    queryFn: () => fetchPeriodReport(from, to),
    enabled: Boolean(from && to),
  });

  const data = report.data;
  const selected = current && from && to ? presetOf(from, to, current) : null;

  return (
    <AdminPage
      title="Reports"
      intro="Any range of business dates against the equivalent range before it. Every figure is the server's; nothing here is recomputed."
      error={report.isError ? messageOf(report.error) : null}
    >
      <div className="print:hidden mb-6 flex flex-wrap items-end gap-3">
        <div className="flex flex-wrap gap-2" role="group" aria-label="Presets">
          {PRESETS.map((preset) => (
            <button
              key={preset.key}
              type="button"
              disabled={!current}
              onClick={() => current && setParams(presetRange(preset.key, current))}
              aria-pressed={selected === preset.key}
              className={`hit rounded-full border px-4 text-body transition ${
                selected === preset.key
                  ? 'border-green bg-green text-ink font-semibold'
                  : 'border-border bg-surface text-text hover:bg-raised'
              }`}
            >
              {preset.label}
            </button>
          ))}
        </div>
        <label className="flex flex-col gap-2">
          <span className="text-label uppercase text-text-dim">From</span>
          <input
            type="date"
            value={from}
            max={to || undefined}
            onChange={(event) => setParams({ from: event.target.value, to })}
            className="hit rounded-lg border border-border bg-raised px-3 text-body text-text"
          />
        </label>
        <label className="flex flex-col gap-2">
          <span className="text-label uppercase text-text-dim">To</span>
          <input
            type="date"
            value={to}
            min={from || undefined}
            onChange={(event) => setParams({ from, to: event.target.value })}
            className="hit rounded-lg border border-border bg-raised px-3 text-body text-text"
          />
        </label>
        <button
          type="button"
          onClick={() => window.print()}
          disabled={!data}
          className="hit rounded-lg border border-border bg-surface px-4 text-body text-text disabled:opacity-50"
        >
          Print
        </button>
        <p className="basis-full text-label text-text-dim">
          Business dates, inclusive. The week starts Monday. A trading day is a business date
          with at least one sale or a drawer count; every per-day figure divides by trading
          days, not calendar days.
        </p>
      </div>

      {!from || !to || report.isPending ? (
        <div className="py-16 text-center">
          <Spinner label="Loading the period…" />
        </div>
      ) : !data ? null : (
        <Report data={data} />
      )}
    </AdminPage>
  );
}

const COMPARISON_LABELS: Record<PeriodComparison, string> = {
  SAME_DAYS_OF_PREVIOUS_MONTH: 'the same days of the previous month',
  SAME_DAYS_OF_PREVIOUS_WEEK: 'the same days of the previous week',
  PRECEDING_DAYS: 'the same number of days immediately before',
};

function Report({ data }: { data: PeriodReport }) {
  const { headline, previousHeadline: previous, breakEven } = data;
  const period = formatRange(data.from, data.to);
  const previousPeriod = formatRange(data.previousFrom, data.previousTo);
  const against = `${previousPeriod}`;

  return (
    <div className="flex flex-col gap-10">
      {/* 1 — IS IT MAKING MONEY. */}
      <Group title={`${period} vs ${previousPeriod}`}>
        <p className="-mt-2 text-label text-text-dim">
          Compared like for like against {COMPARISON_LABELS[data.comparison]}.
        </p>
        <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-5">
          <Figure label="Gross" now={headline.gross} before={previous.gross} against={against} />
          <Figure label="Cost of goods" now={headline.costOfGoods} before={previous.costOfGoods} against={against} />
          <Figure label="Gross profit" now={headline.grossProfit} before={previous.grossProfit} against={against} />
          <Figure label="Operating expenses" now={headline.operatingExpenses} before={previous.operatingExpenses} against={against} goodWhen="down" />
          <Figure label="Net" now={headline.net} before={previous.net} against={against} emphasis />
          <Figure label="Bills" now={headline.bills} before={previous.bills} against={against} kind="count" />
          <Figure label="Trading days" now={headline.tradingDays} before={previous.tradingDays} against={against} kind="count" />
          <Figure label="Gross per trading day" now={headline.grossPerTradingDay} before={previous.grossPerTradingDay} against={against} />
          <Figure label="Net per trading day" now={headline.netPerTradingDay} before={previous.netPerTradingDay} against={against} />
          <Figure label="Gross margin" now={headline.grossMarginPercent} before={previous.grossMarginPercent} against={against} kind="percent" />
        </div>
        <p className="text-label text-text-dim">
          Gross profit is gross less cost of goods — what the dashboard calls profit. Net is gross
          profit less operating expenses. Sales include bills left unsettled on the night; voided
          lines and voided expenses are excluded.
        </p>
      </Group>

      {/* 2 — WHAT IT NEEDS TO TAKE. */}
      <Group title="Break-even">
        <Card>
          {breakEven.computable &&
          breakEven.requiredGrossPerTradingDay !== null &&
          breakEven.actualGrossPerTradingDay !== null ? (
            <>
              <p className="text-heading text-text">
                You need {formatMoney(breakEven.requiredGrossPerTradingDay)} a day to break even.
                You averaged{' '}
                <span
                  className={
                    breakEven.actualGrossPerTradingDay >= breakEven.requiredGrossPerTradingDay
                      ? 'text-green'
                      : 'text-danger'
                  }
                >
                  {formatMoney(breakEven.actualGrossPerTradingDay)}
                </span>
                .
              </p>
              <p className="mt-2 text-label text-text-dim">
                Operating expenses for the period ÷ gross margin ÷ trading days: the gross a
                trading day must take for the margin on it to cover the cost of being open.
              </p>
            </>
          ) : (
            <p className="text-body text-text-dim">
              {headline.gross === 0
                ? 'Not enough sales to compute a break-even.'
                : 'Gross margin is not positive, so no level of sales covers costs.'}
            </p>
          )}
        </Card>
      </Group>

      {/* 3 — THE TREND. */}
      <Group title="Day by day">
        <Tile title="Gross per night, with the break-even line">
          <GrossByDay
            days={data.byDay}
            breakEven={breakEven.computable ? breakEven.requiredGrossPerTradingDay : null}
          />
          <p className="mt-3 text-label text-text-dim">
            Nights below the line did not take enough to cover their share of the period&rsquo;s
            operating cost. Net per night is in the table, where a night&rsquo;s own expenses
            belong.
          </p>
        </Tile>
        <Tile title="Every night">
          <Table
            head={['Night', 'Bills', 'Gross', 'Cost of goods', 'Gross profit', 'Expenses', 'Net']}
            rows={data.byDay.map((day) => ({
              key: day.businessDate,
              dim: !day.trading,
              cells: [
                day.businessDate,
                String(day.bills),
                formatMoney(day.gross),
                formatMoney(day.costOfGoods),
                formatMoney(day.grossProfit),
                formatMoney(day.operatingExpenses),
                formatMoney(day.net),
              ],
            }))}
          />
        </Tile>
      </Group>

      {/* 4 — WHICH NIGHTS CARRY IT. */}
      <Group title="Day of week">
        <Tile title="Average per trading day">
          <Table
            head={['Day', 'Trading days', 'Avg bills', 'Avg gross', 'Avg net']}
            rows={data.byDayOfWeek.map((day) => ({
              key: String(day.isoDay),
              dim: day.tradingDays === 0,
              cells: [
                WEEKDAYS[day.isoDay - 1] ?? String(day.isoDay),
                String(day.tradingDays),
                day.avgBills === null ? '—' : day.avgBills.toFixed(1),
                formatMoney(day.avgGross),
                formatMoney(day.avgNet),
              ],
            }))}
          />
          <p className="mt-3 text-label text-text-dim">
            Counting only the days that traded. A weekday that never opened shows a dash, not a
            zero.
          </p>
        </Tile>
      </Group>

      {/* 5 — WHICH HOURS CARRY IT. */}
      <Group title="Hour of day">
        <Tile title="Bills and gross per hour, summed across the period">
          <SalesByHour hours={data.byHour} />
        </Tile>
      </Group>

      {/* 6 — WHAT IT COST TO BE OPEN. A new page when printed. */}
      <div className="print:break-before-page flex flex-col gap-10">
        <Group title="Operating expenses">
          <Tile title="By category">
            {data.expensesByCategory.length === 0 ? (
              <p className="text-body text-text-dim">Nothing paid out in either period.</p>
            ) : (
              <Table
                head={['Category', period, previousPeriod, '% of gross']}
                rows={data.expensesByCategory.map((line) => ({
                  key: line.category,
                  cells: [
                    line.category,
                    formatMoney(line.amount),
                    formatMoney(line.previousAmount),
                    percent(line.percentOfGross),
                  ],
                }))}
              />
            )}
          </Tile>
          <Tile title="Six months, by category">
            {data.expensesByMonth.rows.length === 0 ? (
              <p className="text-body text-text-dim">No expenses recorded in the last six months.</p>
            ) : (
              <Table
                head={['Category', ...data.expensesByMonth.months.map(formatMonth), 'Total']}
                rows={data.expensesByMonth.rows.map((row) => ({
                  key: row.category,
                  cells: [row.category, ...row.amounts.map((amount) => formatMoney(amount)), formatMoney(row.total)],
                }))}
              />
            )}
            <p className="mt-3 text-label text-text-dim">
              The last column runs only to {formatRange(data.to, data.to)}, so it is a partial
              month unless that is a month end. Voided expenses excluded throughout.
            </p>
          </Tile>
        </Group>

        {/* 7 — WHICH TABLES PULL THEIR WEIGHT. */}
        <Group title="Tables">
          <Tile title="Weakest first">
            <Table
              head={['Table', 'Occupied', 'Utilisation', 'Time revenue', 'Per occupied hour']}
              rows={data.tables.map((table) => ({
                key: table.tableName,
                dim: table.occupiedMinutes === 0,
                cells: [
                  table.tableName,
                  `${table.occupiedMinutes.toLocaleString('en-PH')} min`,
                  percent(table.utilisationPercent),
                  formatMoney(table.timeRevenue),
                  formatMoney(table.revenuePerOccupiedHour),
                ],
              }))}
            />
            <p className="mt-3 text-label text-text-dim">
              Occupied is wall clock, pauses included — a paused table is still nobody
              else&rsquo;s. Utilisation is against 19 hours for each trading day. Time revenue is
              what the time was charged, a moved session split between its tables by minutes. A
              premium table earning less per hour than a standard one is a pricing question.
            </p>
          </Tile>
        </Group>
      </div>

      {/* 8 — WHICH PRODUCTS PULL THEIR WEIGHT. A new page when printed. */}
      <div className="print:break-before-page flex flex-col gap-10">
        <Group title="Products">
          <Tile title="Sold, thinnest margin first">
            {data.products.length === 0 ? (
              <p className="text-body text-text-dim">Nothing sold.</p>
            ) : (
              <Table
                head={['Product', 'Qty', 'Revenue', 'Cost', 'Margin', 'Margin %']}
                rows={data.products.map((product) => ({
                  key: product.name,
                  danger: product.margin <= 0,
                  cells: [
                    product.name,
                    product.quantity.toLocaleString('en-PH'),
                    formatMoney(product.revenue),
                    formatMoney(product.cost),
                    formatMoney(product.margin),
                    percent(product.marginPercent),
                  ],
                }))}
              />
            )}
            <p className="mt-3 text-label text-text-dim">
              From the prices and costs snapshotted at the moment of sale. Anything at or below
              cost is at the top.
            </p>
          </Tile>
          <Tile title="Not sold this period, still on the shelf">
            {data.unsoldProducts.length === 0 ? (
              <p className="text-body text-text-dim">Everything in stock sold at least once.</p>
            ) : (
              <Table
                head={['Product', 'On hand', 'Avg cost', 'Capital on shelf']}
                rows={data.unsoldProducts.map((product) => ({
                  key: product.name,
                  cells: [
                    product.name,
                    product.qtyOnHand.toLocaleString('en-PH'),
                    formatMoney(product.avgCost),
                    formatMoney(product.capitalOnShelf),
                  ],
                }))}
              />
            )}
            <p className="mt-3 text-label text-text-dim">
              Valued at current average cost. Cut-the-SKU candidates.
            </p>
          </Tile>
        </Group>

        {/* 9 — WHAT WAS GIVEN AWAY. */}
        <Group title="Given away">
          <Tile title={`${formatMoney(data.givenAway.total)} · ${percent(data.givenAway.percentOfGross)} of gross`}>
            <div className="grid gap-x-8 md:grid-cols-2">
              <Row label={`Promos · ${plural(data.givenAway.promoSessions, 'session')}`} value={formatMoney(data.givenAway.promoForgone)} />
              <Row label={`Friend rates · ${plural(data.givenAway.friendSessions, 'session')}`} value={formatMoney(data.givenAway.friendForgone)} />
              <Row label={`Flat rate · ${plural(data.givenAway.flatSessions, 'session')}`} value={formatMoney(data.givenAway.flatForgone)} />
              <Row label={`Time not charged · ${plural(data.givenAway.reducedSessions, 'session')}`} value={formatMoney(data.givenAway.timeReductionForgone)} />
              <Row label={`Discounts · ${plural(data.givenAway.discountBills, 'bill')}`} value={formatMoney(data.givenAway.discountAmount)} />
              <Row label={`Vouchers · ${plural(data.givenAway.voucherCount, 'voucher')}`} value={formatMoney(data.givenAway.voucherAmount)} />
              <Row label={`Voids · ${plural(data.givenAway.voidCount, 'line')}`} value={formatMoney(data.givenAway.voidAmount)} />
              <Row label={`Comps · ${data.givenAway.compQuantity} units · estimate`} value={formatMoney(data.givenAway.compEstimatedCost)} />
            </div>
            <p className="mt-3 text-label text-text-dim">
              The dashboard&rsquo;s eight lines, summed over the period. Comps are valued at
              current average cost and are an estimate; the other seven are exact. Discounts and
              vouchers are already out of gross — they explain it rather than reduce it.
            </p>
          </Tile>
        </Group>

        {/* 10 — HOW WELL THE DRAWER WAS KEPT. */}
        <Group title="Cash discipline">
          <div className="grid items-start gap-4 2xl:grid-cols-2">
            <Tile title="The drawer">
              <Row
                label="Variance over the period"
                value={`${data.cash.varianceTotal > 0 ? '+' : ''}${formatMoney(data.cash.varianceTotal)}`}
              />
              <Row label="Nights with a variance" value={String(data.cash.nightsWithVariance)} />
              <Row label="Nights counted" value={String(data.cash.countedNights)} />
              <Row
                label="Trading days never counted"
                value={String(data.cash.uncountedTradingDays)}
                dim={data.cash.uncountedTradingDays === 0}
              />
              <p className="mt-3 text-label text-text-dim">
                Variance is counted cash less what the drawer should have held. Negative is short.
              </p>
            </Tile>
            <Tile title="Still owed">
              <Row
                label={`From this period · ${plural(data.cash.unsettled.thisPeriod.count, 'bill')}`}
                value={formatMoney(data.cash.unsettled.thisPeriod.amount)}
              />
              <Row
                label={`1–4 weeks before it · ${plural(data.cash.unsettled.oneToFourWeeksBefore.count, 'bill')}`}
                value={formatMoney(data.cash.unsettled.oneToFourWeeksBefore.amount)}
              />
              <Row
                label={`Older · ${plural(data.cash.unsettled.older.count, 'bill')}`}
                value={formatMoney(data.cash.unsettled.older.amount)}
              />
              <p className="mt-3 text-label text-text-dim">
                Unsettled bills still open right now, by the night they were played. A live figure
                — it moves when a debt is collected.
              </p>
            </Tile>
          </div>
        </Group>
      </div>

      <p className="text-label text-text-dim">
        {data.from} to {data.to} · compared against {data.previousFrom} to {data.previousTo} ·{' '}
        {headline.bills} bills over {headline.tradingDays}{' '}
        {headline.tradingDays === 1 ? 'trading day' : 'trading days'}
      </p>
    </div>
  );
}

/**
 * One headline figure with its delta. Null in, dash out: a per-day figure across no trading
 * days is undefined, not zero, and the delta is left off rather than compared against a dash.
 */
function Figure({
  label,
  now,
  before,
  against,
  kind = 'money',
  goodWhen = 'up',
  emphasis = false,
}: {
  label: string;
  now: Money | number | null;
  before: Money | number | null;
  against: string;
  kind?: 'money' | 'count' | 'percent';
  goodWhen?: 'up' | 'down';
  emphasis?: boolean;
}) {
  const shown =
    now === null
      ? '—'
      : kind === 'money'
        ? formatMoney(now)
        : kind === 'count'
          ? now.toLocaleString('en-PH')
          : `${now.toFixed(1)}%`;
  return (
    <Card className={emphasis ? 'border-green' : ''}>
      <div className="text-label uppercase text-text-dim">{label}</div>
      <div className={`tabular mt-2 text-amount ${emphasis && now !== null && now < 0 ? 'text-danger' : 'text-text'}`}>
        {shown}
      </div>
      {now === null || before === null ? (
        <p className="mt-1 text-label text-text-dim">Nothing to compare</p>
      ) : (
        <Delta
          now={now}
          before={before}
          against={against}
          none={`No trading ${against}`}
          kind={kind}
          goodWhen={goodWhen}
        />
      )}
    </Card>
  );
}

function Table({
  head,
  rows,
}: {
  head: string[];
  rows: { key: string; cells: string[]; dim?: boolean; danger?: boolean }[];
}) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-left">
        <thead>
          <tr className="border-b border-border text-label uppercase text-text-dim">
            {head.map((label, index) => (
              <th key={label} className={`py-2 ${index === 0 ? '' : 'pl-3 text-right'}`}>
                {label}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.key} className="border-b border-border">
              {row.cells.map((cell, index) => (
                <td
                  key={index}
                  className={`py-2 text-body ${index === 0 ? '' : 'tabular pl-3 text-right'} ${
                    row.danger ? 'text-danger' : row.dim ? 'text-text-dim' : 'text-text'
                  }`}
                >
                  {cell}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function percent(value: number | null): string {
  return value === null ? '—' : `${value.toFixed(1)}%`;
}

function plural(count: number, noun: string): string {
  return `${count} ${count === 1 ? noun : `${noun}s`}`;
}
