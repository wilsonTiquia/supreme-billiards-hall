import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router-dom';
import { lastCompletedNight, nightHasEnded, validNight, cashStatus } from './morning';
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
import { AdminPage } from '../AdminPage';
import { comparedClause, describeChange } from '../Comparison';
import { AnalyticsPanel, AnalyticsLoading, RankedBars, Stat } from '../analytics/Analytics';
import { SalesByNight } from '../reports/SalesByNight';
import { presetRange } from '../reports/periodDates';
import { Disclosure } from '@/components/Disclosure';
import { formatPesos } from '@/lib/money';
import { formatHours, weekdayOf } from '@/lib/datetime';

/**
 * The owner's morning. Ten seconds, on a phone, without scrolling: how much was made, is that
 * better or worse than usual, is there anything to do. Everything else is a detail and lives
 * below, collapsed, with its one key figure showing.
 *
 * "Usual" is the same weekday last week. A Saturday against the Friday before it was a
 * comparison of two different nights, and made every Sunday read as a collapse.
 */
export function DashboardPage() {
  const [params, setParams] = useSearchParams();
  const requestedDate = params.get('date') || '';
  const setDate = (value: string) => {
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

  const latestCompleted = currentDay.data ? lastCompletedNight(currentDay.data) : '';
  const date = validNight(requestedDate) && requestedDate <= (currentDay.data?.businessDate || '')
    ? requestedDate : latestCompleted;
  useEffect(() => {
    if (date && date !== requestedDate) {
      setParams((old) => { const next = new URLSearchParams(old); next.set('date', date); return next; }, { replace: true });
    }
  }, [date, requestedDate, setParams]);
  const report = useQuery({
    queryKey: queryKeys.dailyReport(date),
    queryFn: () => fetchDailyReport(date),
    enabled: Boolean(date),
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
  });

  const uncounted = useQuery({
    queryKey: queryKeys.uncountedDays,
    queryFn: fetchUncountedDays,
    staleTime: 60_000,
  });

  const shownDate = date || currentDay.data?.businessDate || '';
  const trendRange = shownDate ? presetRange('thisWeek', shownDate) : null;
  const trend = useQuery({
    queryKey: queryKeys.periodReport(trendRange?.from ?? '', trendRange?.to ?? ''),
    queryFn: () => fetchPeriodReport(trendRange!.from, trendRange!.to),
    enabled: Boolean(trendRange),
  });
  const cashCount = useQuery({
    queryKey: queryKeys.cashCount(shownDate),
    queryFn: () => fetchCashCount(shownDate),
    enabled: Boolean(shownDate),
    staleTime: 60_000,
  });

  const data = report.data;
  const tonight = Boolean(currentDay.data && !nightHasEnded(shownDate, currentDay.data.serverNow));

  return (
    <AdminPage title="Dashboard" intro="Your hall, at a glance." error={report.isError ? messageOf(report.error) : null}>
      <div className="analytics">
      <div className="analytics-toolbar">
        <div>
        <p className="analytics-eyebrow">Owner overview · Business day</p>
        <h2 className="text-heading text-text">
          {shownDate ? `${new Intl.DateTimeFormat('en-GB', { weekday: 'long', day: 'numeric', month: 'short', timeZone: 'UTC' }).format(new Date(`${shownDate}T12:00:00Z`))} · ${tonight ? 'in progress since 10am' : 'complete'}` : 'Loading business night…'}
        </h2>
        </div>
        <div className="analytics-actions">
          <button type="button" onClick={() => setDate(tonight ? latestCompleted : currentDay.data!.businessDate)}
            disabled={!currentDay.data || (!tonight && nightHasEnded(currentDay.data.businessDate, currentDay.data.serverNow))}
            className="analytics-button">
            {tonight ? 'Last completed night' : 'Tonight so far'}
          </button>
          <input type="date" aria-label="Business day" value={shownDate} max={currentDay.data?.businessDate}
            onInput={(event) => setDate(event.currentTarget.value)}
            className="analytics-button" />
          <Link className="analytics-button analytics-button-primary" to={`/admin/reports?from=${shownDate}&to=${shownDate}`}>View report</Link>
        </div>
      </div>
      {currentDay.isError && <p role="alert">The business clock is unavailable. Refresh to try again.</p>}

      {report.isPending ? (
        <AnalyticsLoading label="Loading the night…" />
      ) : !data ? null : (
        <Night
          data={data}
          tonight={tonight}
          notCheckedOut={unsettled.data?.length ?? 0}
          uncountedNights={uncounted.data?.length ?? 0}
          cashCount={cashCount.data ?? null}
          cashLoading={cashCount.isPending}
          cashError={cashCount.isError}
          checksError={unsettled.isError || uncounted.isError}
          trend={
            <AnalyticsPanel title="How are sales changing?" subtitle="This week through the selected day · Monday start">
              {trend.data ? <SalesByNight days={trend.data.byDay} breakEven={null} /> :
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
    </AdminPage>
  );
}

function Night({
  data,
  tonight,
  notCheckedOut,
  uncountedNights,
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

  const attention = (
    <Attention
      lowStock={data.lowStock}
      notCheckedOut={notCheckedOut}
      uncountedNights={uncountedNights}
      outstanding={data.outstanding}
      unsettledTonight={data.unsettledTonight}
    />
  );

  const clause = comparedClause(
    describeChange({ now: totals.gross, before: previous.gross, against }),
    against,
  );

  return (
    <div className="flex flex-col gap-3">
      <div className="analytics-stats">
        <Stat primary label="Sales" value={formatPesos(totals.gross)}
          comparison={tonight || totals.bills === 0 ? undefined : { now: totals.gross, before: previous.gross, against }}
          note="Closed bills, including sales left unpaid" />
        <Stat label="Collected" value={formatPesos(payments)} note="Payments actually taken on this night" />
        <Stat label="After cost of goods" value={formatPesos(totals.profit)}
          comparison={tonight || totals.bills === 0 ? undefined : { now: totals.profit, before: previous.profit, against }}
          note="Sales − recorded product costs" />
        <Stat label="Closed bills" value={totals.bills.toLocaleString('en-PH')} note="Table bills and quick sales" />
      </div>
      {attention}
      <Link to={`/end-of-day?date=${data.businessDate}`} className={`text-body ${cash.danger ? 'text-danger' : 'text-text'} underline decoration-dotted`}>
        {cash.text}{cash.amount !== undefined ? formatPesos(cash.amount) : ''}
      </Link>
      {checksError && <p role="status" className="text-danger">Some unpaid-bill or close-out checks are unavailable.</p>}
      <p className="max-w-prose text-body text-text">
        {totals.bills} closed {totals.bills === 1 ? 'bill' : 'bills'}; {formatPesos(data.unsettledTonight.amount)} left unpaid on this night
        {data.collectedToday.amount > 0 ? `; collections include ${formatPesos(data.collectedToday.amount)} from older bills` : ''}
        {!tonight && totals.bills > 0 && clause ? `; sales ${clause}` : ''}.
      </p>
      <p className="text-body text-text">
        Paid out {formatPesos(data.expenses.total)}{data.expenses.byCategory.length ? ` · ${data.expenses.byCategory.map(row => row.category).join(', ')}` : ''}
      </p>

      <div className="analytics-grid">
        {trend}
        <AnalyticsPanel
          title="Where did the sales come from?"
          subtitle="Table time and products, reconciled to total sales"
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
          <p className="analytics-note">
            Table and product amounts are before bill-level discounts and vouchers.
          </p>
        </AnalyticsPanel>
      </div>
      <div className="analytics-grid analytics-grid-equal">
        <AnalyticsPanel
          title="Which tables were used most?"
          subtitle="Hours held on closed bills · includes pauses"
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
          subtitle="Top products by sales · before bill-level discounts"
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
      <div className="analytics-grid">
        <AnalyticsPanel
          title="When were sales closed?"
          subtitle="Sales by checkout hour · Asia/Manila"
        >
          <HourChart hours={data.salesByHour} />
          <p className="analytics-note">
            Checkout time shows when a bill closed, not when a table session started.
          </p>
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
          <p className="analytics-note">
            Collections can include older debts and exclude bills left unpaid.
          </p>
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
      <HowWorkedOut against={against} />
    </div>
  );
}

/**
 * The actionable band. Each row names the thing to do and links to where it gets done. When
 * there is nothing, the band does not render — an empty band trains the eye to skip the
 * place that matters on the night it is not empty.
 */
function Attention({
  lowStock,
  notCheckedOut,
  uncountedNights,
  outstanding,
  unsettledTonight,
}: {
  lowStock: { name: string; qtyOnHand: number }[];
  notCheckedOut: number;
  uncountedNights: number;
  /** Every debt still open, across all dates. A live figure, not a fact about this night. */
  outstanding: { count: number; amount: number };
  unsettledTonight: { count: number; amount: number };
}) {
  const items: { key: string; text: string; to: string; action: string }[] = [];

  if (uncountedNights > 0) {
    items.push({
      key: 'uncounted',
      text: `${uncountedNights} ${uncountedNights === 1 ? 'night was' : 'nights were'} never counted`,
      to: '/end-of-day',
      action: uncountedNights === 1 ? 'Count it' : 'Count them',
    });
  }
  if (notCheckedOut > 0) {
    items.push({
      key: 'not-checked-out',
      text: `${notCheckedOut} ${notCheckedOut === 1 ? 'bill was' : 'bills were'} never checked out`,
      to: '/end-of-day',
      action: 'See them',
    });
  }
  if (outstanding.count > 0) {
    items.push({
      key: 'owed',
      text: `${formatPesos(outstanding.amount)} owed across ${outstanding.count} ${outstanding.count === 1 ? 'bill' : 'bills'}${
        unsettledTonight.count > 0 ? `, ${formatPesos(unsettledTonight.amount)} of it from this night` : ''
      }`,
      to: '/unsettled',
      action: 'Chase it',
    });
  }
  if (lowStock.length > 0) {
    items.push({
      key: 'stock',
      text: `Low stock: ${lowStock.map((line) => `${line.name} (${line.qtyOnHand})`).join(', ')}`,
      to: '/admin/stock',
      action: 'View stock',
    });
  }

  if (items.length === 0) return null;

  return (
    <section
      aria-label="Needs attention"
      className="rounded-xl border border-border border-l-4 border-l-danger bg-surface px-5 py-2"
    >
      <ul className="divide-y divide-border">
        {items.map((item) => (
          <li key={item.key} className="flex items-center justify-between gap-3 py-1">
            <span className="min-w-0 flex-1 text-body text-danger">{item.text}</span>
            <Link to={item.to} className="hit inline-flex shrink-0 items-center text-body text-info underline">
              {item.action}
            </Link>
          </li>
        ))}
      </ul>
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
function HowWorkedOut({ against }: { against: string }) {
  return (
    <Disclosure title="How these numbers are worked out">
      <dl className="grid gap-x-8 gap-y-3 text-body md:grid-cols-[max-content_1fr]">
        <Definition term="Sales">
          Every bill closed on the night, including ones left unpaid. Voided lines are left out.
        </Definition>
        <Definition term="After cost of goods">
          Sales less the recorded cost of products sold, using the cost saved with each sale.
        </Definition>
        <Definition term={`vs ${against}`}>
          The same weekday a week earlier — a Saturday is compared to a Saturday, never to a
          Friday. Under one per cent either way reads as about the same.
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
        <Definition term="Per employee">Whoever took the payment.</Definition>
        <Definition term="Table use">
          Time the table was held, pauses included, out of the 19-hour night. Not what was
          charged — a paused table is still nobody else's.
        </Definition>
        <Definition term="Money owed">
          Every unpaid bill across all nights, as of right now.
        </Definition>
      </dl>
    </Disclosure>
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
