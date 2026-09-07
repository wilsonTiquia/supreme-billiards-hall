import { useState } from 'react';
import { LossesDetail, type LossKind } from './LossesDetail';
import { NightRail } from './NightRail';
import { useQuery } from '@tanstack/react-query';
import { fetchDailyReport } from '@/api/endpoints/reports';
import {
  fetchCashCount,
  fetchCurrentBusinessDay,
  fetchUncountedDays,
} from '@/api/endpoints/businessDay';
import { fetchUnsettledBills } from '@/api/endpoints/bills';
import { Link } from 'react-router-dom';
import { queryKeys } from '@/api/queryKeys';
import { messageOf } from '@/api/errors';
import type { DailyTotals } from '@/api/types';
import { AdminPage } from '../AdminPage';
import { Card } from '@/components/Card';
import { Spinner } from '@/components/Spinner';
import { formatMoney } from '@/lib/money';

/**
 * The one place the browser does arithmetic on money, and deliberately so: a delta is a
 * comparison of two figures the server already computed, it is never sent anywhere, and the
 * API ships `previousTotals` for exactly this. Nothing billable is derived here.
 */
function Delta({ now, before }: { now: number; before: number }) {
  if (before === 0) {
    return <p className="mt-1 text-label text-text-dim">No trading the night before</p>;
  }
  const change = now - before;
  const percent = (change / before) * 100;
  const up = change > 0;
  return (
    <p className={`mt-1 text-label ${up ? 'text-green' : change < 0 ? 'text-danger' : 'text-text-dim'}`}>
      {up ? '▲' : change < 0 ? '▼' : '–'} {formatMoney(Math.abs(change))} ({percent.toFixed(1)}%)
      <span className="text-text-dim"> vs previous day</span>
    </p>
  );
}

/* Secondary to the night band above it, and sized to say so. These were Display-size when
   they were the top of the page; the hero owns that weight now. */
function Headline({
  label,
  value,
  now,
  before,
  kind = 'money',
}: {
  label: string;
  value: number;
  now: number;
  before: number;
  kind?: 'money' | 'count';
}) {
  return (
    <Card>
      <div className="text-label uppercase text-text-dim">{label}</div>
      <div className="tabular mt-2 text-amount text-text">
        {kind === 'money' ? formatMoney(value) : value}
      </div>
      <Delta now={now} before={before} />
    </Card>
  );
}

/**
 * A band of the page, with the question it answers as its heading.
 *
 * The order is the order the questions get asked: how did the night go, where did it come from,
 * what did it cost, what needs doing, who did it. An owner should be able to stop reading after
 * the first band on a good night and know exactly where to look on a bad one — which only works
 * if the bands are labelled, so nobody has to infer the grouping from adjacency.
 */
function Group({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="flex flex-col gap-4">
      <h2 className="text-label uppercase tracking-wide text-text-dim">{title}</h2>
      {children}
    </section>
  );
}

function Tile({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <Card>
      <h3 className="text-heading text-text">{title}</h3>
      <div className="mt-3">{children}</div>
    </Card>
  );
}

/**
 * The actionable band.
 *
 * Each row names the thing to do and links to where it gets done, because a count with nowhere
 * to click is a nag rather than a control. When everything is clear the band still renders —
 * one green line — so that "nothing outstanding" is a fact the owner read, not an absence they
 * have to infer from a missing section.
 */
function Attention({
  lowStock,
  unsettledCount,
  outstanding,
  uncountedCount,
  variance,
  counted,
}: {
  lowStock: { name: string; qtyOnHand: number }[];
  unsettledCount: number;
  /** Every debt still open, across all dates. A live figure, not a fact about this night. */
  outstanding: { count: number; amount: number };
  uncountedCount: number;
  variance: number | null;
  counted: boolean;
}) {
  // Only a non-zero variance is worth surfacing here; a drawer that balanced is not an action.
  const varianceOff = counted && variance !== null && variance !== 0;
  const clear =
    lowStock.length === 0 &&
    unsettledCount === 0 &&
    outstanding.count === 0 &&
    uncountedCount === 0 &&
    !varianceOff;

  if (clear) {
    return (
      <Card>
        <p className="text-body text-green">
          Nothing outstanding. Stock is fine, nobody owes anything, and the drawer balanced.
        </p>
      </Card>
    );
  }

  return (
    <div className="grid items-start gap-4 2xl:grid-cols-2">
      {varianceOff ? (
        <Tile title="Drawer variance">
          <p className="flex items-baseline justify-between gap-3">
            <span className="text-body text-text">
              {variance! < 0 ? 'The drawer was short.' : 'The drawer was over.'}
            </span>
            <span className={`tabular text-amount ${variance! < 0 ? 'text-danger' : 'text-amount'}`}>
              {variance! > 0 ? '+' : ''}
              {formatMoney(variance!)}
            </span>
          </p>
          <Link to="/end-of-day" className="hit mt-2 inline-flex items-center text-body text-info underline">
            Open the count
          </Link>
        </Tile>
      ) : null}

      {uncountedCount > 0 ? (
        <Tile title="Uncounted nights">
          <p className="text-body text-text">
            {uncountedCount} {uncountedCount === 1 ? 'night traded' : 'nights traded'} and{' '}
            {uncountedCount === 1 ? 'was' : 'were'} never counted.
          </p>
          <Link to="/end-of-day" className="hit mt-2 inline-flex items-center text-body text-info underline">
            Count {uncountedCount === 1 ? 'it' : 'them'}
          </Link>
        </Tile>
      ) : null}

      {/* Two different problems, never one row. A bill nobody checked out is a miss to be
          fixed tonight; a debt is money somebody agreed to wait for, and it is chased on a
          different screen and a different timescale. */}
      {unsettledCount > 0 ? (
        <Tile title="Not checked out">
          <p className="text-body text-text">
            {unsettledCount} {unsettledCount === 1 ? 'bill was' : 'bills were'} never checked out
            — most likely a miss.
          </p>
          <Link to="/end-of-day" className="hit mt-2 inline-flex items-center text-body text-info underline">
            See them
          </Link>
        </Tile>
      ) : null}

      {outstanding.count > 0 ? (
        <Tile title="Owed to the hall">
          <p className="flex items-baseline justify-between gap-3">
            <span className="text-body text-text">
              {outstanding.count} unpaid {outstanding.count === 1 ? 'bill' : 'bills'}, all dates.
            </span>
            <span className="tabular text-amount">{formatMoney(outstanding.amount)}</span>
          </p>
          <Link to="/unsettled" className="hit mt-2 inline-flex items-center text-body text-info underline">
            Chase them
          </Link>
        </Tile>
      ) : null}

      {lowStock.length > 0 ? (
        <Tile title="Low or negative stock">
          {lowStock.map((line) => (
            <div key={line.name} className="flex justify-between gap-3 py-1">
              <span className="text-body text-text">{line.name}</span>
              <span
                className={`tabular text-body ${line.qtyOnHand <= 0 ? 'text-danger' : 'text-text'}`}
              >
                {line.qtyOnHand}
              </span>
            </div>
          ))}
          <Link to="/admin/stock" className="hit mt-2 inline-flex items-center text-body text-info underline">
            Record a delivery
          </Link>
        </Tile>
      ) : null}
    </div>
  );
}

function Row({ label, value, dim }: { label: string; value: string; dim?: boolean }) {
  return (
    <div className="flex justify-between gap-3 py-1">
      <span className={`text-body ${dim ? 'text-text-dim' : 'text-text'}`}>{label}</span>
      <span className="tabular text-body text-text">{value}</span>
    </div>
  );
}

export function DashboardPage() {
  const [date, setDate] = useState<string>('');
  // Which loss figure the owner clicked into, if any.
  const [openLoss, setOpenLoss] = useState<LossKind | null>(null);

  // Defaults to the business day the server says is current — never one worked out here.
  const currentDay = useQuery({
    queryKey: queryKeys.businessDayCurrent,
    queryFn: fetchCurrentBusinessDay,
    staleTime: 60_000,
  });

  const report = useQuery({
    queryKey: queryKeys.dailyReport(date || undefined),
    queryFn: () => fetchDailyReport(date || undefined),
  });

  /*
   * The three things that need doing, which the daily report does not carry.
   *
   * They live on other endpoints because they are not report figures — an unsettled bill and an
   * uncounted night are states, not takings. Gathering them here is the whole point of the
   * band: the owner should not have to visit three screens to find out whether anything is
   * outstanding, because the answer is usually "no" and nobody checks three screens for a no.
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

  const shownDateForCount = date || currentDay.data?.businessDate || '';
  const cashCount = useQuery({
    queryKey: queryKeys.cashCount(shownDateForCount),
    queryFn: () => fetchCashCount(shownDateForCount),
    enabled: Boolean(shownDateForCount),
    staleTime: 60_000,
  });

  const shownDate = date || currentDay.data?.businessDate || '';
  const data = report.data;

  return (
    <AdminPage
      title="Dashboard"
      intro="One call, one night. Every figure here is the server's; nothing on this screen is recomputed."
      error={report.isError ? messageOf(report.error) : null}
    >
      <div className="mb-6 flex flex-wrap items-end gap-3">
        <label className="flex flex-col gap-2">
          <span className="text-label uppercase text-text-dim">Business day</span>
          <input
            type="date"
            value={shownDate}
            onChange={(event) => setDate(event.target.value)}
            className="hit rounded-lg border border-border bg-raised px-3 text-body text-text"
          />
        </label>
        {date ? (
          <button
            type="button"
            onClick={() => setDate('')}
            className="hit rounded-lg border border-border bg-surface px-4 text-body text-text"
          >
            Back to tonight
          </button>
        ) : null}
      </div>

      {report.isPending ? (
        <div className="py-16 text-center">
          <Spinner label="Loading the night…" />
        </div>
      ) : !data ? null : (
        <div className="flex flex-col gap-10">
          {/* 1 — HOW DID THE NIGHT GO.
              The hero stays exactly as approved, hour band included. Sales by hour belongs to
              this question as much as to the next one, and putting the same chart on screen
              twice would be worse than either placement. */}
          <Group title="How did the night go">
            <NightRail
              businessDate={shownDate}
              totals={data.totals}
              previous={data.previousTotals}
              hours={data.salesByHour}
            />
            {/* Beneath the rail rather than inside it, and only when there is something to say.
                Gross ALREADY contains this figure — the sale counted on the night it was
                played — so this is a qualification of the number above, not a second number
                beside it. Silent on a night when everyone paid, because a permanent "₱0.00
                unsettled" tile would train the eye to skip exactly the row that matters. */}
            {data.unsettledTonight.count > 0 ? (
              <div className="mt-4">
                <Tile title="Unsettled tonight">
                  <p className="flex items-baseline justify-between gap-3">
                    <span className="text-body text-text">
                      {data.unsettledTonight.count}{' '}
                      {data.unsettledTonight.count === 1 ? 'bill was' : 'bills were'} left owed.
                      Counted in gross above; not in the drawer.
                    </span>
                    <span className="tabular text-amount">
                      {formatMoney(data.unsettledTonight.amount)}
                    </span>
                  </p>
                  <Link
                    to="/unsettled"
                    className="hit mt-2 inline-flex items-center text-body text-info underline"
                  >
                    Who owes it
                  </Link>
                </Tile>
              </div>
            ) : null}
          </Group>

          {/* 2 — WHERE IT CAME FROM. */}
          <Group title="Where it came from">
            <div className="grid items-start gap-4 2xl:grid-cols-2">
              <Tile title="Table time vs products">
                <Split totals={data.totals} />
              </Tile>

              <Tile title="Payment mix">
                {data.paymentMix.map((method) => (
                  <Row
                    key={method.method}
                    label={`${method.method} (${method.payments})`}
                    value={formatMoney(method.amount)}
                  />
                ))}
              </Tile>

              {/* Money that arrived tonight against an earlier night. It is in the payment mix
                  above and in tonight's drawer, but deliberately NOT in tonight's gross: that
                  revenue was recognised on the night it was earned, and counting it again here
                  would invent a sale that never happened. */}
              {data.collectedToday.count > 0 ? (
                <Tile title="Old debts collected">
                  <p className="flex items-baseline justify-between gap-3">
                    <span className="text-body text-text">
                      {data.collectedToday.count}{' '}
                      {data.collectedToday.count === 1 ? 'debt' : 'debts'} from earlier nights.
                      In the drawer, not in tonight&rsquo;s gross.
                    </span>
                    <span className="tabular text-amount">
                      {formatMoney(data.collectedToday.amount)}
                    </span>
                  </p>
                </Tile>
              ) : null}

              <Tile title="Top items">
                {data.topItems.length === 0 ? (
                  <p className="text-body text-text-dim">Nothing sold.</p>
                ) : (
                  data.topItems.map((item) => (
                    <Row
                      key={item.description}
                      label={`${item.quantity} × ${item.description}`}
                      value={formatMoney(item.revenue)}
                    />
                  ))
                )}
              </Tile>
            </div>
          </Group>

          {/* 3 — WHAT IT COST.
              Cost of goods and the three giveaway routes in one band, because they are the same
              question: what did the night take out. Separating "cost" from "given away" would
              let someone read the first and think they had the answer. */}
          <Group title="What it cost">
            <div className="grid gap-4 sm:grid-cols-3">
              <Headline
                label="Cost of goods"
                value={data.totals.cost}
                now={data.totals.cost}
                before={data.previousTotals.cost}
              />
              {/* Beside cost of goods, never added to it: that figure is what the drinks cost,
                  this one is what the building cost, and a single "cost" number that mixed them
                  would put the rent inside the margin on a beer. */}
              <Headline
                label="Operating expenses"
                value={data.expenses.total}
                now={data.expenses.total}
                before={data.expenses.previousTotal}
              />
              <Headline
                label="Bills settled"
                kind="count"
                value={data.totals.bills}
                now={data.totals.bills}
                before={data.previousTotals.bills}
              />
            </div>

            <Tile title="What it went on">
              {data.expenses.byCategory.length === 0 ? (
                <p className="text-body text-text-dim">Nothing paid out tonight.</p>
              ) : (
                data.expenses.byCategory.map((line) => (
                  <Row
                    key={line.category}
                    label={line.category}
                    value={formatMoney(line.amount)}
                  />
                ))
              )}
            </Tile>

            <Tile title="Given away">
              <p className="mb-3 text-label text-text-dim">
                Click a figure to see who, when, and why.
              </p>
              <div className="grid gap-4 md:grid-cols-2">
                <LossFigure
                  label="Voids"
                  amount={formatMoney(data.losses.voidAmount)}
                  note={`${data.losses.voidCount} ${data.losses.voidCount === 1 ? 'line' : 'lines'} · exact`}
                  onOpen={() => setOpenLoss('voids')}
                />
                <LossFigure
                  label="Rate overrides"
                  amount={formatMoney(data.losses.forgoneRevenue)}
                  note={`${data.losses.overrideSessions} ${
                    data.losses.overrideSessions === 1 ? 'session' : 'sessions'
                  } · exact`}
                  onOpen={() => setOpenLoss('friendRates')}
                />
                <LossFigure
                  label="Flat rates"
                  amount={formatMoney(data.losses.flatForgone)}
                  note={`${data.losses.flatSessions} ${
                    data.losses.flatSessions === 1 ? 'session' : 'sessions'
                  } · exact`}
                  onOpen={() => setOpenLoss('flatRates')}
                />
                {/* The difference matters: this one is valued at today's average cost, so it
                    moves when costs move. The other two are exact figures from the ledger. */}
                <LossFigure
                  label="Comps"
                  amount={formatMoney(data.losses.compEstimatedCost)}
                  note={`${data.losses.compQuantity} units · ESTIMATE at current average cost`}
                  noteTone="estimate"
                  onOpen={() => setOpenLoss('comps')}
                />
                {/* The third giveaway route. All three on one screen, or the control is not one. */}
                <LossFigure
                  label="Time not charged"
                  amount={formatMoney(data.losses.timeReductionForgone)}
                  note={`${data.losses.reducedSessions} ${
                    data.losses.reducedSessions === 1 ? 'session' : 'sessions'
                  } · exact`}
                  onOpen={() => setOpenLoss('timeReductions')}
                />
              </div>
            </Tile>
          </Group>

          {openLoss ? (
            <LossesDetail
              kind={openLoss}
              businessDate={shownDate}
              summary={data.losses}
              onClose={() => setOpenLoss(null)}
            />
          ) : null}

          {/* 4 — WHAT NEEDS ATTENTION.
              Everything actionable in one place, and each item says what to do rather than
              only that something is wrong. When all four are clear the band says so in one
              line, because a band that is usually empty still has to be worth glancing at. */}
          <Group title="What needs attention">
            <Attention
              lowStock={data.lowStock}
              unsettledCount={unsettled.data?.length ?? 0}
              outstanding={data.outstanding}
              uncountedCount={uncounted.data?.length ?? 0}
              variance={cashCount.data ? cashCount.data.variance : null}
              counted={Boolean(cashCount.data)}
            />
          </Group>

          {/* 5 — WHO. */}
          <Group title="Who">
            <Tile title="Per employee">
              {/* Below md each person is a block. Five columns in 294px is a scrollbar per
                  employee on the screen the owner actually opens from home. */}
              <ul className="flex flex-col gap-3 md:hidden">
                {data.perEmployee.map((employee) => (
                  <li key={employee.username} className="rounded-lg border border-border p-3">
                    <div className="flex items-baseline justify-between gap-3">
                      <span className="text-body text-text">{employee.fullName}</span>
                      <span className="tabular text-body text-text">
                        {formatMoney(employee.profit)}
                      </span>
                    </div>
                    <p className="mt-1 text-label uppercase text-text-dim">
                      {employee.bills} {employee.bills === 1 ? 'bill' : 'bills'} · gross{' '}
                      {formatMoney(employee.gross)} · cost {formatMoney(employee.cost)}
                    </p>
                  </li>
                ))}
              </ul>

              <div className="hidden overflow-x-auto md:block">
                <table className="w-full min-w-[520px] text-left">
                  <thead>
                    <tr className="border-b border-border text-label uppercase text-text-dim">
                      <th className="py-2">Who</th>
                      <th className="py-2 text-right">Bills</th>
                      <th className="py-2 text-right">Gross</th>
                      <th className="py-2 text-right">Cost</th>
                      <th className="py-2 text-right">Profit</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.perEmployee.map((employee) => (
                      <tr key={employee.username} className="border-b border-border">
                        <td className="py-2 text-body text-text">
                          {employee.fullName}
                          <span className="text-text-dim"> · {employee.username}</span>
                        </td>
                        <td className="tabular py-2 text-right text-body text-text">
                          {employee.bills}
                        </td>
                        <td className="tabular py-2 text-right text-body text-text">
                          {formatMoney(employee.gross)}
                        </td>
                        <td className="tabular py-2 text-right text-body text-text-dim">
                          {formatMoney(employee.cost)}
                        </td>
                        <td className="tabular py-2 text-right text-body text-text">
                          {formatMoney(employee.profit)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <p className="mt-3 text-label text-text-dim">
                Attributed to whoever took the payment, and sums to the branch total above.
              </p>
            </Tile>

            <Tile title="Table utilisation">
              <ul>
                {/* One line per table instead of three: the share is drawn behind the row, so
                    the bar and the figure occupy the same space rather than stacking. */}
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
                        {table.billedMinutes} min ·{' '}
                        <span className="text-text">{table.utilisationPercent}%</span>
                      </span>
                    </div>
                  </li>
                ))}
              </ul>
              <p className="mt-3 text-label text-text-dim">Against a 19-hour trading day.</p>
            </Tile>
          </Group>

          <p className="text-label text-text-dim">
            {data.businessDate}
            {data.comparedTo ? ` · compared against ${data.comparedTo}` : ''} · {data.totals.bills}{' '}
            bills
          </p>
        </div>
      )}
    </AdminPage>
  );
}

function Split({ totals }: { totals: DailyTotals }) {
  const time = totals.timeRevenue;
  const items = totals.itemRevenue;
  const total = totals.gross;
  // Widths only — a proportion of a bar, not a peso figure.
  const timePercent = total > 0 ? (time / total) * 100 : 0;

  return (
    <div>
      <div className="flex h-6 overflow-hidden rounded">
        <div className="bg-chart-bar" style={{ width: `${timePercent}%` }} />
        <div className="flex-1 bg-border" />
      </div>
      <div className="mt-3 grid gap-2 md:grid-cols-2">
        <Row label="Table time" value={formatMoney(time)} />
        <Row label="Products" value={formatMoney(items)} />
      </div>
    </div>
  );
}

/** One clickable loss figure. A button, so it is reachable from the keyboard like everything else. */
function LossFigure({
  label,
  amount,
  note,
  noteTone,
  onOpen,
}: {
  label: string;
  amount: string;
  note: string;
  noteTone?: 'estimate';
  onOpen: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onOpen}
      className="rounded-lg border border-transparent p-2 text-left transition hover:border-border hover:bg-raised"
    >
      <div className="text-label uppercase text-text-dim">{label}</div>
      <div className="tabular text-amount text-danger underline decoration-dotted underline-offset-4">
        {amount}
      </div>
      <div className={`text-label ${noteTone === 'estimate' ? 'text-amount' : 'text-text-dim'}`}>
        {note}
      </div>
    </button>
  );
}
