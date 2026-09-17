import { useEffect, useId, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router-dom';
import { dashboardNight, shiftDay, nightHasEnded, validNight, cashStatus } from './morning';
import { LossesDetail, type LossKind } from './LossesDetail';
import { HourChart } from './HourChart';
import { fetchDailyReport, fetchPeriodReport } from '@/api/endpoints/reports';
import {
  fetchCashCount,
  fetchCurrentBusinessDay,
  fetchUncountedDays,
} from '@/api/endpoints/businessDay';
import { fetchUnsettledBills } from '@/api/endpoints/bills';
import { queryKeys } from '@/api/queryKeys';
import { messageOf } from '@/api/errors';
import type { CashCount, DailyReport, Losses, TimeRevenueByMode } from '@/api/types';
import { useScreenTheme } from '@/app/useTheme';
import { Banner } from '@/components/Banner';
import { AnalyticsPanel, AnalyticsLoading, RankedBars, Stat } from '../analytics/Analytics';
import { SalesByNight } from '../reports/SalesByNight';
import { presetRange } from '../reports/periodDates';
import { Disclosure } from '@/components/Disclosure';
import { formatPesos } from '@/lib/money';
import { formatHours, weekdayOf } from '@/lib/datetime';
import './dashboard.css';

/**
 * The owner's morning. Ten seconds, on a phone, without scrolling: how much was made, is that
 * better or worse than usual, is there anything to do. Charts and operational details follow
 * the headline and attention banner in equal-width rows.
 *
 * "Usual" is the same weekday last week. A Saturday against the Friday before it was a
 * comparison of two different nights, and made every Sunday read as a collapse.
 */
export function DashboardPage() {
  useScreenTheme('admin');
  const definitionsId = useId();
  const [params, setParams] = useSearchParams();
  const requestedDate = params.get('date') || '';
  const setDate = (value: string) => {
    if (!validNight(value) || value > (currentDay.data?.businessDate ?? '')) return;
    setOpenLoss(null);
    setParams((old) => { const next = new URLSearchParams(old); next.set('date', value); return next; });
  };
  const [openLoss, setOpenLoss] = useState<LossKind | null>(null);

  // Use the server clock and date label, including the 5am–10am completed-night gap.
  const currentDay = useQuery({
    queryKey: queryKeys.businessDayCurrent,
    queryFn: fetchCurrentBusinessDay,
    staleTime: 60_000,
    refetchInterval: 60_000,
  });

  const date = dashboardNight(requestedDate, currentDay.data?.businessDate ?? '');
  useEffect(() => {
    if (date && date !== requestedDate) {
      setParams((old) => { const next = new URLSearchParams(old); next.set('date', date); return next; }, { replace: true });
    }
  }, [date, requestedDate, setParams]);
  const report = useQuery({
    queryKey: queryKeys.dailyReport(date),
    queryFn: () => fetchDailyReport(date),
    enabled: Boolean(date),
    refetchInterval: 60_000,
  });

  /*
   * The things that need doing, which the daily report does not carry. They live on other
   * endpoints because they are states rather than takings, and they are gathered here so
   * the owner does not have to visit three screens to learn that nothing is outstanding.
   */
  const unsettled = useQuery({
    queryKey: queryKeys.unsettledBills,
    queryFn: fetchUnsettledBills,
    staleTime: 60_000,
    refetchInterval: 60_000,
  });

  const uncounted = useQuery({
    queryKey: queryKeys.uncountedDays,
    queryFn: fetchUncountedDays,
    staleTime: 60_000,
    refetchInterval: 60_000,
  });

  const shownDate = date;
  const trendRange = shownDate ? presetRange('thisWeek', shownDate) : null;
  const trend = useQuery({
    queryKey: queryKeys.periodReport(trendRange?.from ?? '', trendRange?.to ?? ''),
    queryFn: () => fetchPeriodReport(trendRange!.from, trendRange!.to),
    enabled: Boolean(trendRange),
    refetchInterval: 60_000,
  });
  const cashCount = useQuery({
    queryKey: queryKeys.cashCount(shownDate),
    queryFn: () => fetchCashCount(shownDate),
    enabled: Boolean(shownDate),
    staleTime: 60_000,
    refetchInterval: 60_000,
  });

  const data = report.data;
  const tonight = Boolean(currentDay.data && !nightHasEnded(shownDate, currentDay.data.serverNow));

  return (
    <div className="dashboard analytics mx-auto max-w-[112rem]">
      <div className="dashboard-heading">
        <div className="dashboard-title-row">
          <div className="flex items-center gap-2">
            <h1 className="text-[28px] font-semibold tracking-tight text-text">Dashboard</h1>
            <button type="button" popoverTarget={definitionsId} aria-label="How these numbers are worked out"
              className="hit flex w-11 items-center justify-center rounded-lg text-text-dim hover:bg-raised">
              <svg viewBox="0 0 24 24" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth="1.7" aria-hidden>
                <circle cx="12" cy="12" r="9" /><path d="M12 11v6M12 7v1" />
              </svg>
            </button>
          </div>
          {shownDate && <Link className="hit inline-flex items-center text-body text-info underline underline-offset-4"
            to={`/admin/reports?from=${shownDate}&to=${shownDate}`}>View report</Link>}
        </div>
        <div className="dashboard-date-heading">
          <div className="dashboard-date-control" role="group" aria-label="Business night">
            <button type="button" aria-label="Previous night" disabled={!shownDate} onClick={() => setDate(shiftDay(shownDate, -1))}>‹</button>
            <input type="date" aria-label="Business night date" value={shownDate} max={currentDay.data?.businessDate}
              disabled={!currentDay.data} onChange={(event) => setDate(event.currentTarget.value)} />
            <button type="button" aria-label="Next night" disabled={!shownDate || shownDate >= (currentDay.data?.businessDate ?? '')}
              onClick={() => setDate(shiftDay(shownDate, 1))}>›</button>
          </div>
          <span className="text-label text-text-dim" role="status">
            {shownDate ? `${weekdayOf(shownDate)} · ${tonight ? 'in progress since 10am' : 'complete'}` : 'Loading business night…'}
          </span>
        </div>
      </div>
      <HowWorkedOut id={definitionsId} against={data?.comparedTo ? `last ${weekdayOf(data.comparedTo)}` : 'last week'} />
      {currentDay.isError && <Banner tone="danger">The business clock is unavailable. Refresh to try again.</Banner>}
      {report.isError && <Banner tone="danger">{messageOf(report.error)}</Banner>}

      {report.isPending ? (
        <AnalyticsLoading label="Loading the night…" />
      ) : !data ? null : (
        <Night
          data={data}
          tonight={tonight}
          notCheckedOut={unsettled.data?.length ?? 0}
          uncountedNights={uncounted.data?.filter(day => day.businessDate !== shownDate).length ?? 0}
          oldestUncounted={uncounted.data?.filter(day => day.businessDate !== shownDate).map(day => day.businessDate).sort()[0]}
          cashCount={cashCount.data ?? null}
          cashLoading={cashCount.isPending}
          cashError={cashCount.isError}
          checksError={unsettled.isError || uncounted.isError}
          trend={
            <AnalyticsPanel title="Sales this week">
              {trend.data ? <SalesByNight days={trend.data.byDay} breakEven={null} showBestNight={!tonight} /> :
                <p role="status" className="analytics-note">{trend.isError ? 'The weekly trend could not be loaded.' : 'Loading weekly sales…'}</p>}
            </AnalyticsPanel>
          }
          onOpenLoss={setOpenLoss}
        />
      )}

      {openLoss && data ? (
        <LossesDetail
          kind={openLoss}
          businessDate={shownDate}
          summary={data.losses}
          onClose={() => setOpenLoss(null)}
        />
      ) : null}
    </div>
  );
}

function Night({
  data,
  tonight,
  notCheckedOut,
  uncountedNights,
  oldestUncounted,
  cashCount,
  cashLoading,
  cashError,
  checksError,
  onOpenLoss,
  trend,
}: {
  trend: React.ReactNode;
  data: DailyReport;
  tonight: boolean;
  notCheckedOut: number;
  uncountedNights: number;
  oldestUncounted?: string;
  cashCount: CashCount | null;
  cashLoading: boolean;
  cashError: boolean;
  checksError: boolean;
  onOpenLoss: (kind: LossKind) => void;
}) {
  const { totals, previousTotals: previous } = data;
  const against = data.comparedTo ? `last ${weekdayOf(data.comparedTo)}` : 'last week';
  const payments = data.paymentMix.reduce((sum, row) => sum + row.amount, 0);
  const subtotal = totals.timeRevenue + totals.itemRevenue;
  const adjustment = subtotal - totals.gross;
  const heldTables = [...data.tableUtilisation].sort((a, b) => b.occupiedMinutes - a.occupiedMinutes);
  const cash = cashStatus(cashCount, tonight, cashLoading, cashError);

  return (
    <div className="flex flex-col gap-3">
      <div className="analytics-stats">
        <Stat label="Sales" value={formatPesos(totals.gross)}
          comparison={tonight || totals.bills === 0 ? undefined : { now: totals.gross, before: previous.gross, against }}
          />
        <Stat label="Collected" value={formatPesos(payments)} />
        <Stat label="After cost of goods" value={formatPesos(totals.profit)}
          comparison={tonight || totals.bills === 0 ? undefined : { now: totals.profit, before: previous.profit, against }}
          />
        <Stat label="Closed bills" value={totals.bills.toLocaleString('en-PH')} />
      </div>
      <Attention data={data} cash={cash} notCheckedOut={notCheckedOut} uncountedNights={uncountedNights}
        oldestUncounted={oldestUncounted} checksError={checksError} />

      <div className="analytics-grid analytics-grid-equal">
        {trend}
        <AnalyticsPanel
          title="Where did the sales come from?"
        >
          {subtotal > 0 && (
            <div className="analytics-split" aria-hidden>
              <div style={{ width: `${(totals.timeRevenue / subtotal) * 100}%` }} />
              <div style={{ width: `${(totals.itemRevenue / subtotal) * 100}%` }} />
            </div>
          )}
          <div className="analytics-ledger">
            <div>
              <span>
                <i className="analytics-dot" />
                Table time
              </span>
              <strong>{formatPesos(totals.timeRevenue)}</strong>
            </div>
            <div>
              <span>
                <i className="analytics-dot analytics-dot-secondary" />
                Products
              </span>
              <strong>{formatPesos(totals.itemRevenue)}</strong>
            </div>
            {Math.abs(adjustment) >= 0.005 && (
              <div>
                <span>Bill discounts & vouchers</span>
                <strong>
                  {adjustment >= 0 ? '−' : '+'}
                  {formatPesos(Math.abs(adjustment))}
                </strong>
              </div>
            )}
            <div className="analytics-ledger-total">
              <span>Total sales</span>
              <strong>{formatPesos(totals.gross)}</strong>
            </div>
          </div>
        </AnalyticsPanel>
      </div>
      <div className="analytics-grid analytics-grid-equal">
        <AnalyticsPanel
          title="Which tables were used most?"
        >
          <RankedBars
            empty="No table time on closed bills for this day."
            rows={(heldTables.some((row) => row.occupiedMinutes > 0) ? heldTables : []).map(
              (row) => ({
                label: row.tableName,
                value: row.occupiedMinutes,
                display: formatHours(row.occupiedMinutes),
                detail: `${row.utilisationPercent.toFixed(1)}% of the 19-hour business day`,
              }),
            )}
          />
        </AnalyticsPanel>
        <AnalyticsPanel
          title="What are customers buying?"
        >
          <RankedBars
            rows={data.topItems
              .slice(0, 5)
              .map((row) => ({
                label: row.description,
                value: row.revenue,
                display: formatPesos(row.revenue),
                detail: `${row.quantity.toLocaleString('en-PH')} units sold`,
              }))}
          />
        </AnalyticsPanel>
      </div>
      <div className="analytics-grid analytics-grid-equal">
        <AnalyticsPanel
          title="When were sales closed?"
        >
          <HourChart hours={data.salesByHour} />
        </AnalyticsPanel>
        <AnalyticsPanel
          title="How did customers pay?"
          subtitle={`${formatPesos(payments)} collected on this business day`}
        >
          <RankedBars
            rows={[...data.paymentMix]
              .sort((a, b) => b.amount - a.amount)
              .map((row) => ({
                label: titleCase(row.method),
                value: row.amount,
                display: formatPesos(row.amount),
                detail: `${row.payments} ${row.payments === 1 ? 'payment' : 'payments'}${payments > 0 ? ` · ${((row.amount / payments) * 100).toFixed(1)}% of collections` : ''}`,
              }))}
          />
          {data.collectedToday.count > 0 && (
            <p className="analytics-note">
              Includes {formatPesos(data.collectedToday.amount)} collected against earlier sales.
            </p>
          )}
        </AnalyticsPanel>
      </div>
      <section className="analytics-detail-group">
        <p className="analytics-eyebrow mb-3">Behind the numbers</p>
        <Disclosure title="Table pricing" summary={formatPesos(totals.timeRevenue)}>
          <PricingModes rows={data.timeRevenueByMode} timeRevenue={totals.timeRevenue} />
        </Disclosure>
        <Disclosure title="Operating expenses" summary={formatPesos(data.expenses.total)}>
          {data.expenses.byCategory.length ? data.expenses.byCategory.map(row =>
            <Row key={row.category} label={row.category} value={formatPesos(row.amount)} />
          ) : <p className="analytics-note">No expenses recorded for this business day.</p>}
        </Disclosure>
        <Disclosure title="Given away" summary={formatPesos(givenAwayTotal(data.losses))}>
          <GivenAway losses={data.losses} onOpen={onOpenLoss} />
        </Disclosure>
        <Disclosure title="Sales by employee" summary={`${data.perEmployee.length} employees`}>
          <PerEmployee rows={data.perEmployee} />
        </Disclosure>
      </section>
    </div>
  );
}

/** One banner for the selected night's close-out and current follow-up work. */
function Attention({ data, cash, notCheckedOut, uncountedNights, oldestUncounted, checksError }: {
  data: DailyReport;
  cash: ReturnType<typeof cashStatus>;
  notCheckedOut: number;
  uncountedNights: number;
  oldestUncounted?: string;
  checksError: boolean;
}) {
  const items: { key: string; text: string; to: string; action: string }[] = [];
  if (uncountedNights > 0) items.push({ key: 'uncounted',
    text: `${uncountedNights} other ${uncountedNights === 1 ? 'night needs' : 'nights need'} a drawer count`,
    to: `/end-of-day${oldestUncounted ? `?date=${oldestUncounted}` : ''}`, action: 'Count the drawer' });
  if (notCheckedOut > 0) items.push({ key: 'checkout',
    text: `${notCheckedOut} ${notCheckedOut === 1 ? 'bill needs' : 'bills need'} checkout`,
    to: '/end-of-day', action: 'Finish checkout' });
  if (data.outstanding.count > 0) items.push({ key: 'owed',
    text: `${formatPesos(data.outstanding.amount)} owed across ${data.outstanding.count} ${data.outstanding.count === 1 ? 'bill' : 'bills'}${data.unsettledTonight.count > 0 ? ` · ${formatPesos(data.unsettledTonight.amount)} from this night` : ''}`,
    to: '/unsettled', action: 'Chase unpaid' });
  if (data.lowStock.length > 0) items.push({ key: 'stock',
    text: `Low stock: ${data.lowStock.map(line => `${line.name} (${line.qtyOnHand})`).join(', ')}`,
    to: '/admin/stock', action: 'View stock' });
  const needsAttention = cash.danger || items.length > 0 || checksError;
  return (
    <section aria-label="Night check" className={`dashboard-attention ${needsAttention ? 'dashboard-attention-needed' : ''}`}>
      <h2>Night check</h2>
      <div className="dashboard-attention-row">
        <div>
          <p className={cash.danger ? 'text-danger font-semibold' : 'font-semibold'}>
            {cash.text}{cash.amount !== undefined ? formatPesos(cash.amount) : ''}
          </p>
          <p className="mt-1 text-body text-text-dim">
            {data.totals.bills} closed {data.totals.bills === 1 ? 'bill' : 'bills'} · Paid out {formatPesos(data.expenses.total)}
            {data.expenses.byCategory.length > 0 ? ` · ${data.expenses.byCategory.map(row => row.category).join(', ')}` : ''}
          </p>
        </div>
        <Link to={`/end-of-day?date=${data.businessDate}`}>
          {cash.text === 'Cash balanced' ? 'View drawer count' : 'Count the drawer'}
        </Link>
      </div>
      {items.map(item => <div key={item.key} className="dashboard-attention-row">
        <p>{item.text}</p><Link to={item.to}>{item.action}</Link>
      </div>)}
      {checksError && <p role="status" className="mt-3 text-danger">Some unpaid-bill or close-out checks are unavailable.</p>}
    </section>
  );
}

export function Row({ label, value, dim }: { label: string; value: string; dim?: boolean }) {
  return (
    <div className="flex justify-between gap-3 py-1">
      <span className={`text-body ${dim ? 'text-text-dim' : 'text-text'}`}>{label}</span>
      <span className={`tabular text-body ${dim ? 'text-text-dim' : 'text-text'}`}>{value}</span>
    </div>
  );
}

function titleCase(word: string): string {
  return word.charAt(0) + word.slice(1).toLowerCase();
}


const MODE_LABELS: Record<TimeRevenueByMode['mode'], string> = {
  STANDARD: 'Standard rate', PROMO: 'Promo', FRIEND: 'Friend rate', FLAT: 'Flat rate',
};

function PricingModes({ rows, timeRevenue }: { rows: TimeRevenueByMode[]; timeRevenue: number }) {
  const summed = rows.reduce((total, row) => total + row.amount, 0);
  // A tolerance, not identity: both sides are the database's own sums read back through JSON.
  const reconciles = Math.abs(summed - timeRevenue) < 0.005;
  return (
    <div>
      {rows.map((row) => (
        <Row
          key={row.mode}
          label={`${MODE_LABELS[row.mode]} · ${row.sessions} ${row.sessions === 1 ? 'session' : 'sessions'}`}
          value={formatPesos(row.amount)}
          dim={row.sessions === 0}
        />
      ))}
      {reconciles ? null : (
        <p className="mt-2 text-label text-danger">
          These add up to {formatPesos(summed)}, but table time is {formatPesos(timeRevenue)}. One
          of the two is wrong — do not act on either until it is explained.
        </p>
      )}
    </div>
  );
}

/**
 * The eight figures summed for the section header. Display arithmetic on eight server figures
 * that goes nowhere — the period report ships this total, the daily report does not, and the
 * header is the only place the owner reads it.
 */
function givenAwayTotal(losses: Losses): number {
  return (
    losses.promoForgone +
    losses.friendForgone +
    losses.flatForgone +
    losses.timeReductionForgone +
    losses.discountAmount +
    losses.voucherAmount +
    losses.voidAmount +
    losses.compEstimatedCost
  );
}

function GivenAway({ losses, onOpen }: { losses: Losses; onOpen: (kind: LossKind) => void }) {
  const figures: { kind: LossKind; label: string; amount: number; count: number; noun: string }[] = [
    { kind: 'promos', label: 'Promos', amount: losses.promoForgone, count: losses.promoSessions, noun: 'session' },
    { kind: 'friendRates', label: 'Friend rates', amount: losses.friendForgone, count: losses.friendSessions, noun: 'session' },
    { kind: 'flatRates', label: 'Flat rate', amount: losses.flatForgone, count: losses.flatSessions, noun: 'session' },
    { kind: 'timeReductions', label: 'Time not charged', amount: losses.timeReductionForgone, count: losses.reducedSessions, noun: 'session' },
    { kind: 'discounts', label: 'Discounts', amount: losses.discountAmount, count: losses.discountBills, noun: 'bill' },
    { kind: 'vouchers', label: 'Vouchers', amount: losses.voucherAmount, count: losses.voucherCount, noun: 'voucher' },
    { kind: 'voids', label: 'Voids', amount: losses.voidAmount, count: losses.voidCount, noun: 'line' },
    { kind: 'comps', label: 'Comps', amount: losses.compEstimatedCost, count: losses.compQuantity, noun: 'unit' },
  ];
  return (
    <div className="grid gap-1">
      {figures.map((figure, index) => (
        <div key={figure.kind} className="w-full">
        {(index === 0 || index >= 6) && <h3 className="mb-2 mt-3 text-label text-text-dim">{index === 0 ? 'Discounts we chose' : index === 6 ? 'Mistakes' : 'Comps · estimate'}</h3>}

        <button
          key={figure.kind}
          type="button"
          onClick={() => onOpen(figure.kind)}
          className="hit flex w-full items-center justify-between gap-3 rounded-lg border border-transparent p-2 text-left transition hover:border-border hover:bg-raised"
        >
          <div
            className={`tabular text-heading underline decoration-dotted underline-offset-4 ${
              figure.amount === 0 ? 'text-text-dim' : 'text-text'
            }`}
          >
            {formatPesos(figure.amount)}
          </div>
          <div className="text-label text-text-dim">
            {figure.label} · {figure.count} {figure.count === 1 ? figure.noun : `${figure.noun}s`}
          </div>
        </button>
        </div>
      ))}
    </div>
  );
}

function PerEmployee({ rows }: { rows: DailyReport['perEmployee'] }) {
  return (
    <>
      {/* Below md each person is a block. Four columns in 340px is a scrollbar per employee
          on the screen the owner actually opens from home. */}
      <ul className="flex flex-col gap-3 md:hidden">
        {rows.map((employee) => (
          <li key={employee.username} className="rounded-lg border border-border p-3">
            <div className="flex items-baseline justify-between gap-3">
              <span className="text-body text-text">{employee.fullName}</span>
              <span className="tabular text-body text-text">{formatPesos(employee.gross)}</span>
            </div>
            <p className="mt-1 text-label text-text-dim">
              {employee.bills} {employee.bills === 1 ? 'bill' : 'bills'} ·{' '}
              {formatPesos(employee.profit)} after cost of goods
            </p>
          </li>
        ))}
      </ul>
      <div className="hidden md:block">
        <table className="w-full text-left">
          <thead>
            <tr className="border-b border-border text-label uppercase text-text-dim">
              <th className="py-2">Who</th>
              <th className="py-2 text-right">Bills</th>
              <th className="py-2 text-right">Sales</th>
              <th className="py-2 text-right">After cost of goods</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((employee) => (
              <tr key={employee.username} className="border-b border-border">
                <td className="py-2 text-body text-text">{employee.fullName}</td>
                <td className="tabular py-2 text-right text-body text-text">{employee.bills}</td>
                <td className="tabular py-2 text-right text-body text-text">{formatPesos(employee.gross)}</td>
                <td className="tabular py-2 text-right text-body text-text">{formatPesos(employee.profit)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  );
}

/** The one place a definition lives. Everything above shows a figure and nothing else. */
function HowWorkedOut({ against, id }: { against: string; id: string }) {
  return (
    <div id={id} popover="auto" role="dialog" aria-label="How these numbers are worked out" className="dashboard-definitions">
      <div className="mb-5 flex items-start justify-between gap-4">
        <h2 className="text-heading">How these numbers are worked out</h2>
        <button type="button" popoverTarget={id} popoverTargetAction="hide" aria-label="Close definitions" className="hit w-11 shrink-0 rounded-lg hover:bg-raised">×</button>
      </div>
      <dl className="grid gap-x-8 gap-y-3 text-body md:grid-cols-[max-content_1fr]">
        <Definition term="Sales this week">Monday through the selected night, including the current night if selected.</Definition>
        <Definition term="Sales">
          Every bill closed on the night, including ones left unpaid. Voided lines are left out.
        </Definition>
        <Definition term="After cost of goods">
          Sales less the recorded cost of products sold, using the cost saved with each sale.
        </Definition>
        <Definition term={`vs ${against}`}>
          The same weekday a week earlier — a Saturday is compared to a Saturday, never to a
          Friday. Under one per cent either way reads as about the same. In-progress nights have no comparison.
        </Definition>
        <Definition term="The night">
          Runs from 10am to 5am. A sale at 2am belongs to the night before.
        </Definition>
        <Definition term="Collected">Payments taken on this business night. Includes collections of older debts; excludes sales still unpaid.</Definition>
        <Definition term="Complete">The scheduled night has ended at 5am. The cash line separately shows whether it was counted.</Definition>
        <Definition term="Paid out">
          Rent, wages, water, electricity and the like recorded on the night. Not taken off the
          figures above — those are the night's trading; this is the cost of being open.
        </Definition>
        <Definition term="Given away">
          Table time and products sold below the standard price, and what was voided. Comps are
          valued at today's average cost, so that one is an estimate; the rest are exact.
        </Definition>
        <Definition term="Sales breakdown">Table time and product amounts are before bill-level discounts and vouchers, shown separately to reconcile to total sales.</Definition>
        <Definition term="Top products">Ranked by sales before bill-level discounts.</Definition>
        <Definition term="Sales by hour">Checkout time in Asia/Manila: when a bill closed, not when a table session started.</Definition>
        <Definition term="Per employee">Whoever took the payment.</Definition>
        <Definition term="Table use">
          Time the table was held, pauses included, out of the 19-hour night. Not what was
          charged — a paused table is still nobody else's.
        </Definition>
        <Definition term="Money owed">
          Every unpaid bill across all nights, as of right now.
        </Definition>
      </dl>
    </div>
  );
}

export function Definition({ term, children }: { term: string; children: React.ReactNode }) {
  return (
    <>
      <dt className="text-text">{term}</dt>
      <dd className="text-text-dim md:mb-0">{children}</dd>
    </>
  );
}
