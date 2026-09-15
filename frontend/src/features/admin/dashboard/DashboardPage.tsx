import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router-dom';
import { lastCompletedNight, nightHasEnded, validNight, cashStatus } from './morning';
import { LossesDetail, type LossKind } from './LossesDetail';
import { HourChart } from './HourChart';
import { fetchDailyReport } from '@/api/endpoints/reports';
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
import { BigFigure, comparedClause, describeChange } from '../Comparison';
import { Disclosure } from '@/components/Disclosure';
import { Spinner } from '@/components/Spinner';
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
  const cashCount = useQuery({
    queryKey: queryKeys.cashCount(shownDate),
    queryFn: () => fetchCashCount(shownDate),
    enabled: Boolean(shownDate),
    staleTime: 60_000,
  });

  const data = report.data;
  const tonight = Boolean(currentDay.data && !nightHasEnded(shownDate, currentDay.data.serverNow));

  return (
    <AdminPage title="Dashboard" error={report.isError ? messageOf(report.error) : null}>
      <div className="mb-4 flex flex-wrap items-center justify-between gap-2">
        <h2 className="text-heading text-text">
          {shownDate ? `${new Intl.DateTimeFormat('en-GB', { weekday: 'long', day: 'numeric', month: 'short', timeZone: 'UTC' }).format(new Date(`${shownDate}T12:00:00Z`))} · ${tonight ? 'in progress since 10am' : 'complete'}` : 'Loading business night…'}
        </h2>
        <div className="flex flex-wrap items-center gap-2">
          <button type="button" onClick={() => setDate(tonight ? latestCompleted : currentDay.data!.businessDate)}
            disabled={!currentDay.data || (!tonight && nightHasEnded(currentDay.data.businessDate, currentDay.data.serverNow))}
            className="hit rounded-lg border border-border px-3 text-label">
            {tonight ? 'Last completed night' : 'Tonight so far'}
          </button>
          <input type="date" aria-label="Business day" value={shownDate} max={currentDay.data?.businessDate}
            onInput={(event) => setDate(event.currentTarget.value)}
            className="hit rounded-lg border border-border bg-raised px-3 text-label text-text" />
          <Link className="hit inline-flex items-center text-label underline" to={`/admin/reports?from=${shownDate}&to=${shownDate}`}>View report</Link>
        </div>
      </div>
      {currentDay.isError && <p role="alert">The business clock is unavailable. Refresh to try again.</p>}

      {report.isPending ? (
        <div className="py-16 text-center">
          <Spinner label="Loading the night…" />
        </div>
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
}: {
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
      {/* 1 — THE ANSWER. */}
      <section className="flex flex-col gap-5">
        {/* The answer first and full width; the two beneath share a row on a phone and all
            three share one from sm up. */}
        <div className="grid grid-cols-2 gap-x-6 gap-y-5 sm:flex sm:items-end sm:gap-x-12">
          <div className="col-span-2">
            <BigFigure
              label="Sales"
              size="hero"
              value={formatPesos(totals.gross)}
              comparison={tonight || totals.bills === 0 ? undefined : { now: totals.gross, before: previous.gross, against }}
            />
          </div>
          <BigFigure
            label="After cost of goods"
            value={formatPesos(totals.profit)}
            comparison={tonight || totals.bills === 0 ? undefined : { now: totals.profit, before: previous.profit, against }}
          />
          <BigFigure
            label="Collected"
            value={formatPesos(payments)}
          />
        </div>
      </section>
      <Link to={`/end-of-day?date=${data.businessDate}`} className={`text-body ${cash.danger ? 'text-danger' : 'text-text'} underline decoration-dotted`}>
        {cash.text}{cash.amount !== undefined ? formatPesos(cash.amount) : ''}
      </Link>
      {attention}
      {checksError && <p role="status" className="text-danger">Some unpaid-bill or close-out checks are unavailable.</p>}
      <p className="max-w-prose text-body text-text">
        {totals.bills} closed {totals.bills === 1 ? 'bill' : 'bills'}; {formatPesos(data.unsettledTonight.amount)} left unpaid on this night
        {data.collectedToday.amount > 0 ? `; collections include ${formatPesos(data.collectedToday.amount)} from older bills` : ''}
        {!tonight && totals.bills > 0 && clause ? `; sales ${clause}` : ''}.
      </p>
      <p className="text-body text-text">
        Paid out {formatPesos(data.expenses.total)}{data.expenses.byCategory.length ? ` · ${data.expenses.byCategory.map(row => row.category).join(', ')}` : ''}
      </p>

      {/* 2 — DETAILS. Collapsed, each with its key figure showing. */}
      <section>
        <h2 className="mb-2 text-label uppercase tracking-wide text-text-dim">Details</h2>
        <Disclosure title="Sales by hour"><HourChart hours={data.salesByHour} /></Disclosure>
        <Disclosure
          title="Table time vs products"
          summary={`${formatPesos(totals.timeRevenue)} · ${formatPesos(totals.itemRevenue)}`}
        >
          <Split time={totals.timeRevenue} items={totals.itemRevenue} total={totals.gross} />
        </Disclosure>

        <Disclosure
          title="How table time was priced"
          summary={`${formatPesos(data.timeRevenueByMode.find((row) => row.mode === 'STANDARD')?.amount ?? 0)} standard`}
        >
          <PricingModes rows={data.timeRevenueByMode} timeRevenue={totals.timeRevenue} />
        </Disclosure>

        <Disclosure
          title="Payments"
          summary={
            data.paymentMix.length === 0
              ? 'None'
              : `${formatPesos(Math.max(...data.paymentMix.map((m) => m.amount)))} ${
                  data.paymentMix.reduce((best, m) => (m.amount > best.amount ? m : best)).method.toLowerCase()
                }`
          }
        >
          {data.paymentMix.map((method) => (
            <Row
              key={method.method}
              label={`${titleCase(method.method)} · ${method.payments} ${method.payments === 1 ? 'payment' : 'payments'}`}
              value={formatPesos(method.amount)}
            />
          ))}
          {/* Money that arrived tonight against an earlier night. In the drawer, not in
              tonight's sales — that revenue was recognised on the night it was earned. */}
          {data.collectedToday.count > 0 ? (
            <Row
              label={`Old debts collected · ${data.collectedToday.count} ${data.collectedToday.count === 1 ? 'bill' : 'bills'}`}
              value={formatPesos(data.collectedToday.amount)}
              dim
            />
          ) : null}
        </Disclosure>

        <Disclosure
          title="Top items"
          summary={
            data.topItems.length === 0
              ? 'Nothing sold'
              : `${data.topItems[0].description} × ${data.topItems[0].quantity}`
          }
        >
          {data.topItems.map((item) => (
            <Row
              key={item.description}
              label={`${item.quantity} × ${item.description}`}
              value={formatPesos(item.revenue)}
            />
          ))}
        </Disclosure>

        <Disclosure
          title="Paid out"
          summary={data.expenses.total === 0 ? 'Nothing' : formatPesos(data.expenses.total)}
        >
          {data.expenses.byCategory.length === 0 ? (
            <p className="text-body text-text-dim">Nothing paid out on this night.</p>
          ) : (
            data.expenses.byCategory.map((line) => (
              <Row key={line.category} label={line.category} value={formatPesos(line.amount)} />
            ))
          )}
        </Disclosure>

        <Disclosure title="Given away" summary={formatPesos(givenAwayTotal(data.losses))}>
          <GivenAway losses={data.losses} onOpen={onOpenLoss} />
        </Disclosure>

        <Disclosure
          title="Per employee"
          summary={
            data.perEmployee.length === 0
              ? 'Nobody'
              : data.perEmployee.length === 1
                ? `${data.perEmployee[0].fullName} · ${formatPesos(data.perEmployee[0].gross)}`
                : `${data.perEmployee.length} people`
          }
        >
          <PerEmployee rows={data.perEmployee} />
        </Disclosure>

        <Disclosure
          title="Table use"
          summary={(() => {
            const busiest = data.tableUtilisation.reduce(
              (best, t) => (t.utilisationPercent > best.utilisationPercent ? t : best),
              data.tableUtilisation[0],
            );
            return busiest ? `${busiest.tableName} · ${Math.round(busiest.utilisationPercent)}%` : 'No tables';
          })()}
        >
          <ul>
            {data.tableUtilisation.map((table) => (
              <li key={table.tableName} className="relative overflow-hidden rounded-md">
                <div
                  className="absolute inset-y-0 left-0 bg-chart-bar/20"
                  style={{ width: `${Math.min(table.utilisationPercent, 100)}%` }}
                  aria-hidden
                />
                <div className="relative flex items-baseline justify-between gap-3 px-3 py-2">
                  <span className="text-body text-text">{table.tableName}</span>
                  <span className="tabular text-body text-text-dim">
                    {formatHours(table.occupiedMinutes)} ·{' '}
                    <span className="text-text">{Math.round(table.utilisationPercent)}%</span>
                  </span>
                </div>
              </li>
            ))}
          </ul>
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

function Split({ time, items, total }: { time: number; items: number; total: number }) {
  // Widths only — a proportion of a bar, not a peso figure.
  const timePercent = total > 0 ? (time / total) * 100 : 0;
  return (
    <div>
      <div className="flex h-4 overflow-hidden rounded">
        <div className="bg-chart-bar" style={{ width: `${timePercent}%` }} />
        <div className="flex-1 bg-border" />
      </div>
      <div className="mt-3 grid gap-x-8 md:grid-cols-2">
        <Row label="Table time" value={formatPesos(time)} />
        <Row label="Products" value={formatPesos(items)} />
      </div>
    </div>
  );
}

const MODE_LABELS: Record<TimeRevenueByMode['mode'], string> = {
  STANDARD: 'Standard rate',
  PROMO: 'Promo',
  FRIEND: 'Friend rate',
  FLAT: 'Flat rate',
};

/**
 * Table revenue by how it was priced — and a check that it still adds up. The four amounts
 * are the whole of `totals.timeRevenue` broken apart, so they must sum back to it. Said on
 * screen only when they do not: two figures that disagree about the same money are worse
 * than one figure alone.
 */
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
