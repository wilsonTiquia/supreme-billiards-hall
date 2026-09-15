import { useEffect, useState, type ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router-dom';
import { fetchPeriodReport } from '@/api/endpoints/reports';
import { fetchCurrentBusinessDay } from '@/api/endpoints/businessDay';
import { queryKeys } from '@/api/queryKeys';
import { messageOf } from '@/api/errors';
import type { PeriodReport } from '@/api/types';
import { AdminPage } from '../AdminPage';
import { BigFigure, ComparisonLine, comparedClause, describeChange } from '../Comparison';
import { Definition, Row } from '../dashboard/DashboardPage';
import { HourChart } from '../dashboard/HourChart';
import { Disclosure } from '@/components/Disclosure';
import { Spinner } from '@/components/Spinner';
import { formatPesos } from '@/lib/money';
import { formatHours } from '@/lib/datetime';
import { SalesByNight } from './SalesByNight';
import {
  PRESETS,
  WEEKDAYS,
  formatMonth,
  formatRange,
  isWeekendNight,
  presetOf,
  presetRange,
  shortNight,
} from './periodDates';

/**
 * The owner's month, on a laptop on the 1st. Reads only — nothing here is editable and nothing
 * is billable. The same ten-second test as the dashboard: four figures, one sentence, and
 * anything that needs doing, before anything else.
 *
 * The URL carries from/to so a report can be bookmarked, and the presets are calendar ranges
 * of the business date the SERVER says is current. The previous period is the server's
 * decision too, returned in the document; this page formats dates, it never chooses them.
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
    <AdminPage title="Reports" error={report.isError ? messageOf(report.error) : null}>
      <div className="print:hidden mb-6 flex flex-wrap items-center gap-2">
        <div className="flex flex-wrap gap-2" role="group" aria-label="Presets">
          {PRESETS.map((preset) => (
            <button
              key={preset.key}
              type="button"
              disabled={!current}
              onClick={() => current && setParams(presetRange(preset.key, current))}
              aria-pressed={selected === preset.key}
              className={`hit rounded-full border px-4 text-label transition ${
                selected === preset.key
                  ? 'border-text bg-text text-bg'
                  : 'border-border bg-surface text-text hover:bg-raised'
              }`}
            >
              {preset.label}
            </button>
          ))}
        </div>
        <div className="flex items-center gap-2">
          <input
            type="date"
            aria-label="From"
            value={from}
            max={to || undefined}
            onChange={(event) => setParams({ from: event.target.value, to })}
            className="hit rounded-lg border border-border bg-raised px-3 text-label text-text"
          />
          <span className="text-label text-text-dim">to</span>
          <input
            type="date"
            aria-label="To"
            value={to}
            min={from || undefined}
            onChange={(event) => setParams({ from, to: event.target.value })}
            className="hit rounded-lg border border-border bg-raised px-3 text-label text-text"
          />
          <button
            type="button"
            onClick={() => window.print()}
            disabled={!data}
            className="hit rounded-lg border border-border bg-surface px-4 text-label text-text disabled:opacity-50"
          >
            Print
          </button>
        </div>
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

function Report({ data }: { data: PeriodReport }) {
  const { headline, previousHeadline: previous, breakEven, cash } = data;
  const period = formatRange(data.from, data.to);
  // "vs July", not "vs July 2026", when the year is the same one: the year is on the line
  // above, and repeating it on every comparison is a word the eye has to skip eight times.
  const against = formatRange(data.previousFrom, data.previousTo).replace(
    data.previousFrom.slice(0, 4) === data.from.slice(0, 4) ? / \d{4}$/ : /$/,
    '',
  );

  const net = describeChange({ now: headline.net, before: previous.net, against });
  const clause = comparedClause(net, against);

  const owed = cash.unsettled.thisPeriod.count + cash.unsettled.oneToFourWeeksBefore.count + cash.unsettled.older.count;
  const owedAmount = cash.unsettled.thisPeriod.amount + cash.unsettled.oneToFourWeeksBefore.amount + cash.unsettled.older.amount;

  const strongestDay = data.byDayOfWeek.reduce(
    (best, day) => ((day.avgGross ?? -Infinity) > (best.avgGross ?? -Infinity) ? day : best),
    data.byDayOfWeek[0],
  );
  const belowCost = data.products.filter((product) => product.margin <= 0).length;
  const weakestTable = data.tables.find((table) => table.occupiedMinutes > 0) ?? data.tables[0];

  return (
    <div className="flex flex-col gap-8">
      {/* 1 — THE ANSWER. */}
      <section className="flex flex-col gap-6">
        <p className="text-label uppercase text-text-dim">
          {period} <span className="normal-case">vs {against}</span>
        </p>
        <div className="grid grid-cols-2 gap-x-8 gap-y-6 lg:grid-cols-4">
          <BigFigure
            label="Sales"
            value={formatPesos(headline.gross)}
            comparison={{ now: headline.gross, before: previous.gross, against }}
          />
          <BigFigure
            label="After cost of goods"
            value={formatPesos(headline.grossProfit)}
            comparison={{ now: headline.grossProfit, before: previous.grossProfit, against }}
          />
          <BigFigure
            label="Expenses"
            value={formatPesos(headline.operatingExpenses)}
            comparison={{
              now: headline.operatingExpenses,
              before: previous.operatingExpenses,
              against,
              goodWhen: 'down',
            }}
          />
          <BigFigure
            label="After all costs"
            value={formatPesos(headline.net)}
            tone={headline.net < 0 ? 'danger' : undefined}
            comparison={{ now: headline.net, before: previous.net, against }}
          />
        </div>
        <p className="max-w-prose text-body text-text">
          {period}: you {headline.net < 0 ? 'lost' : 'made'} {formatPesos(Math.abs(headline.net))} after
          all costs{clause ? `, ${clause}` : ''}.
        </p>
      </section>

      {/* 2 — ANYTHING TO DO. Only when there is. */}
      {cash.uncountedTradingDays > 0 || owed > 0 ? (
        <section
          aria-label="Needs attention"
          className="rounded-xl border border-border border-l-4 border-l-danger bg-surface px-5 py-2"
        >
          <ul className="divide-y divide-border">
            {cash.uncountedTradingDays > 0 ? (
              <li className="flex flex-wrap items-center justify-between gap-x-4 gap-y-1 py-2">
                <span className="text-body text-danger">
                  {cash.uncountedTradingDays}{' '}
                  {cash.uncountedTradingDays === 1 ? 'night was' : 'nights were'} never counted
                </span>
                <Link to="/end-of-day" className="hit inline-flex items-center text-body text-info underline">
                  Count {cash.uncountedTradingDays === 1 ? 'it' : 'them'}
                </Link>
              </li>
            ) : null}
            {owed > 0 ? (
              <li className="flex flex-wrap items-center justify-between gap-x-4 gap-y-1 py-2">
                <span className="text-body text-danger">
                  {formatPesos(owedAmount)} still owed across {owed} {owed === 1 ? 'bill' : 'bills'}
                </span>
                <Link to="/unsettled" className="hit inline-flex items-center text-body text-info underline">
                  Chase it
                </Link>
              </li>
            ) : null}
          </ul>
        </section>
      ) : null}

      {/* 3 — WHAT IT NEEDS TO TAKE, AND THE TREND. */}
      <section className="rounded-2xl border border-border bg-surface p-5 sm:p-8">
        {breakEven.computable &&
        breakEven.requiredGrossPerTradingDay !== null &&
        breakEven.actualGrossPerTradingDay !== null ? (
          <p className="mb-6 text-body text-text">
            You need {formatPesos(breakEven.requiredGrossPerTradingDay)} a night to break even. You
            averaged{' '}
            <span
              className={`tabular font-semibold ${
                breakEven.actualGrossPerTradingDay >= breakEven.requiredGrossPerTradingDay
                  ? 'text-green'
                  : 'text-danger'
              }`}
            >
              {formatPesos(breakEven.actualGrossPerTradingDay)}
            </span>
            .
          </p>
        ) : (
          <p className="mb-6 text-body text-text-dim">
            {headline.gross === 0
              ? 'Not enough sales to work out a break-even.'
              : 'Goods sold for less than they cost, so no level of sales covers costs.'}
          </p>
        )}
        <h2 className="mb-3 text-label uppercase text-text-dim">Sales per night</h2>
        <SalesByNight
          days={data.byDay}
          breakEven={breakEven.computable ? breakEven.requiredGrossPerTradingDay : null}
        />
      </section>

      {/* 4 — DETAILS. Collapsed, each with its key figure showing. */}
      <section>
        <h2 className="mb-2 text-label uppercase tracking-wide text-text-dim">Details</h2>

        <Disclosure
          title="Bills and averages"
          summary={`${headline.bills.toLocaleString('en-PH')} bills over ${headline.tradingDays} trading ${headline.tradingDays === 1 ? 'day' : 'days'}`}
        >
          <FigureRow label="Bills" value={headline.bills.toLocaleString('en-PH')}>
            <ComparisonLine now={headline.bills} before={previous.bills} against={against} kind="count" />
          </FigureRow>
          <FigureRow label="Trading days" value={String(headline.tradingDays)}>
            <ComparisonLine now={headline.tradingDays} before={previous.tradingDays} against={against} kind="count" />
          </FigureRow>
          <FigureRow label="Sales per trading day" value={formatPesos(headline.grossPerTradingDay)}>
            <ComparisonLine now={headline.grossPerTradingDay} before={previous.grossPerTradingDay} against={against} />
          </FigureRow>
          <FigureRow label="After all costs per trading day" value={formatPesos(headline.netPerTradingDay)}>
            <ComparisonLine now={headline.netPerTradingDay} before={previous.netPerTradingDay} against={against} />
          </FigureRow>
          <FigureRow
            label="Kept after cost of goods"
            value={headline.grossMarginPercent === null ? '—' : `${headline.grossMarginPercent}%`}
          >
            <p className="mt-1 text-label text-text-dim">
              {previous.grossMarginPercent === null ? 'Nothing to compare' : `${previous.grossMarginPercent}% ${against}`}
            </p>
          </FigureRow>
        </Disclosure>

        <Disclosure
          title="Day of week"
          summary={
            strongestDay?.avgGross == null
              ? 'No trading'
              : `${WEEKDAYS[strongestDay.isoDay - 1]} strongest · ${formatPesos(strongestDay.avgGross)} a night`
          }
        >
          {/* "vs break-even" rather than an average bottom line: rent and wages land on
              whichever weekday they were paid, which made Wednesdays read as losing money
              while their sales were fine. The break-even figure spreads the period's expenses
              evenly, and it is the same figure the chart draws, so the two agree. */}
          <Table
            head={['Day', 'Trading days', 'Avg bills', 'Avg sales', 'vs break-even']}
            rows={data.byDayOfWeek.map((day) => {
              const gap =
                day.avgGross === null || !breakEven.computable || breakEven.requiredGrossPerTradingDay === null
                  ? null
                  : day.avgGross - breakEven.requiredGrossPerTradingDay;
              return {
                key: String(day.isoDay),
                dim: day.tradingDays === 0,
                cells: [
                  WEEKDAYS[day.isoDay - 1] ?? String(day.isoDay),
                  String(day.tradingDays),
                  day.avgBills === null ? '—' : day.avgBills.toFixed(1),
                  formatPesos(day.avgGross),
                  gap === null ? '—' : `${gap >= 0 ? '+' : '−'}${formatPesos(Math.abs(gap))}`,
                ],
                cellTone: gap === null ? undefined : { 4: gap >= 0 ? 'good' : 'bad' },
              };
            })}
          />
        </Disclosure>

        <Disclosure title="Every night" summary={`${data.byDay.length} nights`}>
          <EveryNight days={data.byDay} />
        </Disclosure>

        <Disclosure
          title="Sales by hour"
          summary={(() => {
            const busiest = data.byHour.reduce((a, b) => (b.amount > a.amount ? b : a), data.byHour[0]);
            return busiest ? `Busiest at ${hourLabel(busiest.hour)}` : 'No sales';
          })()}
        >
          <HourChart hours={data.byHour} />
        </Disclosure>
      </section>

      {/* 5 — WHAT IT COST TO BE OPEN, and the rest. A new page when printed. */}
      <section className="-mt-6 print:break-before-page">
        <Disclosure
          title="Expenses"
          summary={
            data.expensesByCategory.length === 0
              ? 'None'
              : `${formatPesos(headline.operatingExpenses)} · ${data.expensesByCategory[0].category.toLowerCase()} ${formatPesos(data.expensesByCategory[0].amount)}`
          }
        >
          {data.expensesByCategory.length === 0 ? (
            <p className="text-body text-text-dim">Nothing paid out in either period.</p>
          ) : (
            <Table
              head={['Category', period, against, '% of sales']}
              rows={data.expensesByCategory.map((line) => ({
                key: line.category,
                cells: [line.category, formatPesos(line.amount), formatPesos(line.previousAmount), percent(line.percentOfGross)],
              }))}
              total={[
                'Total',
                formatPesos(headline.operatingExpenses),
                formatPesos(previous.operatingExpenses),
                percent(headline.gross > 0 ? (headline.operatingExpenses / headline.gross) * 100 : null),
              ]}
            />
          )}
          {data.expensesByMonth.rows.length > 0 ? (
            <div className="mt-8">
              <h3 className="mb-2 text-label uppercase text-text-dim">Six months</h3>
              <Table
                head={['Category', ...data.expensesByMonth.months.map(formatMonth), 'Total']}
                rows={data.expensesByMonth.rows.map((row) => ({
                  key: row.category,
                  cells: [row.category, ...row.amounts.map((amount) => formatPesos(amount)), formatPesos(row.total)],
                }))}
                total={[
                  'Total',
                  ...data.expensesByMonth.months.map((_, index) =>
                    formatPesos(data.expensesByMonth.rows.reduce((sum, row) => sum + row.amounts[index], 0)),
                  ),
                  formatPesos(data.expensesByMonth.rows.reduce((sum, row) => sum + row.total, 0)),
                ]}
              />
            </div>
          ) : null}
        </Disclosure>

        <Disclosure
          title="Tables"
          summary={
            weakestTable
              ? `Weakest: ${weakestTable.tableName} · ${formatPesos(weakestTable.revenuePerOccupiedHour)} an hour`
              : 'No tables'
          }
        >
          <Table
            head={['Table', 'Hours held', 'In use', 'Time sales', 'Per hour held']}
            rows={data.tables.map((table) => ({
              key: table.tableName,
              dim: table.occupiedMinutes === 0,
              marker: table === weakestTable ? 'weakest' : undefined,
              cells: [
                table.tableName,
                formatHours(table.occupiedMinutes),
                percent(table.utilisationPercent),
                formatPesos(table.timeRevenue),
                formatPesos(table.revenuePerOccupiedHour),
              ],
            }))}
          />
        </Disclosure>

        <Disclosure
          title="Products"
          summary={
            data.products.length === 0
              ? 'Nothing sold'
              : belowCost === 0
                ? 'All sold above cost'
                : `${belowCost} sold at or below cost`
          }
          tone={belowCost > 0 ? 'danger' : undefined}
        >
          {data.products.length === 0 ? (
            <p className="text-body text-text-dim">Nothing sold.</p>
          ) : (
            <Table
              head={['Product', 'Qty', 'Sales', 'Cost', 'Margin', 'Margin %']}
              rows={data.products.map((product) => ({
                key: product.name,
                danger: product.margin <= 0,
                cells: [
                  product.name,
                  product.quantity.toLocaleString('en-PH'),
                  formatPesos(product.revenue),
                  formatPesos(product.cost),
                  formatPesos(product.margin),
                  percent(product.marginPercent),
                ],
              }))}
            />
          )}
          {data.unsoldProducts.length > 0 ? (
            <div className="mt-8">
              <h3 className="mb-2 text-label uppercase text-text-dim">Not sold, still on the shelf</h3>
              <Table
                head={['Product', 'On hand', 'Avg cost', 'On the shelf']}
                rows={data.unsoldProducts.map((product) => ({
                  key: product.name,
                  cells: [
                    product.name,
                    product.qtyOnHand.toLocaleString('en-PH'),
                    formatPesos(product.avgCost),
                    formatPesos(product.capitalOnShelf),
                  ],
                }))}
              />
            </div>
          ) : null}
        </Disclosure>

        <Disclosure
          title="Given away"
          summary={`${formatPesos(data.givenAway.total)}${
            data.givenAway.percentOfGross === null ? '' : ` · ${data.givenAway.percentOfGross}% of sales`
          }`}
        >
          <div className="grid gap-x-8 md:grid-cols-2">
            <h3 className="col-span-full mt-3 text-label text-text-dim">Discounts we chose</h3>
            <Row label={`Promos · ${plural(data.givenAway.promoSessions, 'session')}`} value={formatPesos(data.givenAway.promoForgone)} />
            <Row label={`Friend rates · ${plural(data.givenAway.friendSessions, 'session')}`} value={formatPesos(data.givenAway.friendForgone)} />
            <Row label={`Flat rate · ${plural(data.givenAway.flatSessions, 'session')}`} value={formatPesos(data.givenAway.flatForgone)} />
            <Row label={`Time not charged · ${plural(data.givenAway.reducedSessions, 'session')}`} value={formatPesos(data.givenAway.timeReductionForgone)} />
            <Row label={`Discounts · ${plural(data.givenAway.discountBills, 'bill')}`} value={formatPesos(data.givenAway.discountAmount)} />
            <Row label={`Vouchers · ${plural(data.givenAway.voucherCount, 'voucher')}`} value={formatPesos(data.givenAway.voucherAmount)} />
            <h3 className="col-span-full mt-3 text-label text-text-dim">Mistakes</h3>
            <Row label={`Voids · ${plural(data.givenAway.voidCount, 'line')}`} value={formatPesos(data.givenAway.voidAmount)} />
            <h3 className="col-span-full mt-3 text-label text-text-dim">Comps · estimate</h3>
            <Row label={`Comps · ${plural(data.givenAway.compQuantity, 'unit')}`} value={formatPesos(data.givenAway.compEstimatedCost)} />
          </div>
        </Disclosure>

        <Disclosure
          title="Drawer"
          summary={`${cash.varianceTotal > 0 ? '+' : cash.varianceTotal < 0 ? '−' : ''}${formatPesos(Math.abs(cash.varianceTotal))} over ${cash.countedNights} counted ${cash.countedNights === 1 ? 'night' : 'nights'}`}
          tone={cash.varianceTotal < 0 ? 'danger' : undefined}
        >
          <Row
            label="Variance over the period"
            value={`${cash.varianceTotal > 0 ? '+' : cash.varianceTotal < 0 ? '−' : ''}${formatPesos(Math.abs(cash.varianceTotal))}`}
          />
          <Row label="Nights with a variance" value={String(cash.nightsWithVariance)} />
          <Row label="Nights counted" value={String(cash.countedNights)} />
          <Row label="Trading days never counted" value={String(cash.uncountedTradingDays)} dim={cash.uncountedTradingDays === 0} />
        </Disclosure>

        <Disclosure
          title="Still owed"
          summary={owed === 0 ? 'Nothing' : `${formatPesos(owedAmount)} · ${plural(owed, 'bill')}`}
          tone={owed > 0 ? 'danger' : undefined}
        >
          <Row label={`From this period · ${plural(cash.unsettled.thisPeriod.count, 'bill')}`} value={formatPesos(cash.unsettled.thisPeriod.amount)} />
          <Row label={`1–4 weeks before it · ${plural(cash.unsettled.oneToFourWeeksBefore.count, 'bill')}`} value={formatPesos(cash.unsettled.oneToFourWeeksBefore.amount)} />
          <Row label={`Older · ${plural(cash.unsettled.older.count, 'bill')}`} value={formatPesos(cash.unsettled.older.amount)} />
        </Disclosure>
      </section>

      <Disclosure title="How these numbers are worked out">
        <dl className="grid gap-x-8 gap-y-3 text-body md:grid-cols-[max-content_1fr]">
          <Definition term="Sales">
            Every bill closed in the period, including ones left unpaid. Voided lines are left out.
          </Definition>
          <Definition term="After cost of goods">
            Sales less what the food and drink cost to buy, at the cost on the day it was sold.
          </Definition>
          <Definition term="Expenses">
            Rent, wages, water, electricity and the like, dated by the night they were paid. Voided
            expenses are left out.
          </Definition>
          <Definition term="After all costs">After cost of goods, less expenses.</Definition>
          <Definition term={`vs ${against}`}>
            Like for like: a whole month against the whole month before, a month to date against
            the same days of the month before, a week against the same weekdays a week earlier.
            Under one per cent either way reads as about the same.
          </Definition>
          <Definition term="Trading day">
            A night with at least one sale or a drawer count. Every per-day figure divides by
            trading days, not calendar days.
          </Definition>
          <Definition term="Break-even">
            What a night must take for the margin on it to cover the period&rsquo;s expenses
            spread evenly across its trading days. The day-of-week table measures against the
            same figure.
          </Definition>
          <Definition term="Tables">
            Hours held is wall clock, pauses included. In use is against 19 hours a trading day.
            Time sales is what the time was charged, a moved session split between its tables
            by minutes. Weakest is the table earning least per hour it was held — a premium
            table earning less than a standard one is a pricing question.
          </Definition>
          <Definition term="Products">
            From the prices and costs recorded at the moment of sale. Unsold stock is valued at
            its current average cost.
          </Definition>
          <Definition term="Given away">
            The dashboard&rsquo;s eight lines summed over the period. Comps are valued at current
            average cost, so that one is an estimate; the rest are exact.
          </Definition>
          <Definition term="Drawer">
            Variance is counted cash less what the drawer should have held. Negative is short.
          </Definition>
          <Definition term="Still owed">
            Unpaid bills open right now, by the night they were played. It moves when a debt is
            collected.
          </Definition>
        </dl>
      </Disclosure>
    </div>
  );
}

/** A figure with its comparison, as a row: label left, number and comparison right. */
function FigureRow({ label, value, children }: { label: string; value: string; children: ReactNode }) {
  return (
    <div className="flex items-start justify-between gap-3 border-b border-border py-2 last:border-0">
      <span className="text-body text-text">{label}</span>
      <span className="text-right">
        <span className="tabular block text-body text-text">{value}</span>
        {children}
      </span>
    </div>
  );
}

/**
 * Every night, reduced to what the owner reads: Night · Bills · Sales · Expenses · After all
 * costs. "Show all columns" brings cost of goods and after cost of goods back for the
 * accountant. An expense names its categories so a rent night reads as rent, not as a loss.
 */
function EveryNight({ days }: { days: PeriodReport['byDay'] }) {
  const [all, setAll] = useState(false);
  const head = all
    ? ['Night', 'Bills', 'Sales', 'Cost of goods', 'After cost of goods', 'Expenses', 'After all costs']
    : ['Night', 'Bills', 'Sales', 'Expenses', 'After all costs'];
  return (
    <div>
      <div className="mb-2 flex justify-end print:hidden">
        <button
          type="button"
          onClick={() => setAll((value) => !value)}
          aria-pressed={all}
          className="hit text-label text-info underline"
        >
          {all ? 'Fewer columns' : 'Show all columns'}
        </button>
      </div>
      <Table
        head={head}
        rows={days.map((day) => {
          const expenses =
            day.operatingExpenses === 0
              ? ''
              : `${formatPesos(day.operatingExpenses)} · ${day.expenses.map((line) => line.category.toLowerCase()).join(', ')}`;
          const cells = all
            ? [
                shortNight(day.businessDate),
                String(day.bills),
                formatPesos(day.gross),
                formatPesos(day.costOfGoods),
                formatPesos(day.grossProfit),
                expenses,
                formatPesos(day.net),
              ]
            : [shortNight(day.businessDate), String(day.bills), formatPesos(day.gross), expenses, formatPesos(day.net)];
          return {
            key: day.businessDate,
            dim: !day.trading,
            shaded: isWeekendNight(day.businessDate),
            cells,
          };
        })}
      />
    </div>
  );
}

/**
 * Numbers right-aligned in tabular figures, the first column left. A `total` row is set off
 * from the body; a `marker` row carries a word beside its name; a `shaded` row is the
 * weekend, in the same shade as the chart.
 */
function Table({
  head,
  rows,
  total,
}: {
  head: string[];
  rows: {
    key: string;
    cells: string[];
    dim?: boolean;
    danger?: boolean;
    shaded?: boolean;
    marker?: string;
    cellTone?: Record<number, 'good' | 'bad'>;
  }[];
  total?: string[];
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
            <tr key={row.key} className={`border-b border-border ${row.shaded ? 'bg-surface' : ''}`}>
              {row.cells.map((cell, index) => (
                <td
                  key={index}
                  className={`py-2 text-body ${index === 0 ? '' : 'tabular pl-3 text-right'} ${
                    row.cellTone?.[index] === 'good'
                      ? 'text-green'
                      : row.cellTone?.[index] === 'bad' || row.danger
                        ? 'text-danger'
                        : row.dim
                          ? 'text-text-dim'
                          : 'text-text'
                  }`}
                >
                  {cell}
                  {index === 0 && row.marker ? (
                    <span className="ml-2 rounded-full border border-danger px-2 text-label text-danger">
                      {row.marker}
                    </span>
                  ) : null}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
        {total ? (
          <tfoot>
            <tr className="border-t-2 border-border">
              {total.map((cell, index) => (
                <td
                  key={index}
                  className={`py-2 text-body font-semibold text-text ${index === 0 ? '' : 'tabular pl-3 text-right'}`}
                >
                  {cell}
                </td>
              ))}
            </tr>
          </tfoot>
        ) : null}
      </table>
    </div>
  );
}

function hourLabel(hour: number): string {
  const twelve = hour % 12 === 0 ? 12 : hour % 12;
  return `${twelve}${hour < 12 ? 'am' : 'pm'}`;
}

function percent(value: number | null): string {
  return value === null ? '—' : `${value.toFixed(1)}%`;
}

function plural(count: number, noun: string): string {
  return `${count} ${count === 1 ? noun : `${noun}s`}`;
}
