import { useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  closeBusinessDay,
  correctCashCount,
  fetchCashCount,
  fetchCurrentBusinessDay,
  fetchOpenSessions,
  fetchUncountedDays,
  recordCashCount,
  recountAfterClose,
} from '@/api/endpoints/businessDay';
import { fetchUnsettledBills } from '@/api/endpoints/bills';
import { fetchExpenses } from '@/api/endpoints/expenses';
import { queryKeys } from '@/api/queryKeys';
import { isApiError, messageOf } from '@/api/errors';
import type { CashCount } from '@/api/types';
import { useScreenTheme } from '@/app/useTheme';
import { useAuth } from '@/auth/useAuth';
import { formatBusinessDate, formatElapsed } from '@/lib/datetime';
import { formatMoney } from '@/lib/money';
import { Card } from '@/components/Card';
import { Button } from '@/components/Button';
import { Banner } from '@/components/Banner';
import { Spinner } from '@/components/Spinner';
import { CashCountPanel } from './CashCountPanel';

export function EndOfDayPage() {
  useScreenTheme('pos');
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { user } = useAuth();

  const [count, setCount] = useState<CashCount | null>(null);
  const [countError, setCountError] = useState<string | null>(null);
  const [closeBlocker, setCloseBlocker] = useState<string | null>(null);
  const [correctError, setCorrectError] = useState<string | null>(null);
  const [closed, setClosed] = useState(false);

  /* Normally this screen is tonight. `?date=` opens an earlier night instead, which is what the
     uncounted-days notice links to — otherwise the notice could name a night with no way to go
     and count it. Every mutation below already takes the date, so nothing else changes. */
  const [params] = useSearchParams();
  const viewing = params.get('date');

  const day = useQuery({
    queryKey: viewing
      ? queryKeys.businessDayOpenSessions(viewing)
      : queryKeys.businessDayCurrent,
    queryFn: () => (viewing ? fetchOpenSessions(viewing) : fetchCurrentBusinessDay()),
    refetchInterval: 15_000,
    refetchIntervalInBackground: true,
  });

  // Earlier nights nobody counted. Informational, exactly like the unsettled strip: it must
  // never stop staff closing tonight.
  const uncounted = useQuery({
    queryKey: queryKeys.uncountedDays,
    queryFn: fetchUncountedDays,
    refetchInterval: 60_000,
  });

  // Informational only. A genuinely abandoned bill must not trap staff at 5am, so this never
  // blocks the close — it just makes sure nobody closes the night unaware of what is uncollected.
  const unsettled = useQuery({
    queryKey: queryKeys.unsettledBills,
    queryFn: fetchUnsettledBills,
    refetchInterval: 15_000,
    refetchIntervalInBackground: true,
  });

  const businessDate = day.data?.businessDate;

  // Read the count rather than only remembering one taken in this tab: the shift that counted
  // the drawer is often not the shift that closes the day.
  const recorded = useQuery({
    queryKey: queryKeys.cashCount(businessDate ?? ''),
    queryFn: () => fetchCashCount(businessDate as string),
    enabled: Boolean(businessDate),
  });

  /* What has already been paid out of the drawer tonight.
   *
   * Read from the expense list rather than the count, because before the drawer is counted
   * there is no frozen figure to read — and this is exactly when the counter needs it. Adding
   * server values for display is allowed; deriving a peso figure from a rate is not.
   */
  const expenses = useQuery({
    queryKey: queryKeys.expenses(businessDate),
    queryFn: () => fetchExpenses(businessDate),
    enabled: Boolean(businessDate),
  });
  const paidFromDrawer = (expenses.data ?? [])
    .filter((expense) => !expense.voided && expense.paidFromDrawer)
    .reduce((sum, expense) => sum + expense.amount, 0);

  const cashCount = useMutation({
    mutationFn: ({
      countedCash,
      openingFloat,
      note,
    }: { countedCash: number; openingFloat?: number; note?: string }) =>
      recordCashCount(businessDate as string, { countedCash, openingFloat, note }),
    onSuccess: (saved) => {
      setCountError(null);
      setCount(saved);
      setCloseBlocker(null);
    },
    onError: (caught) => {
      // Counting twice is a 409, and it means someone already did it — so refetch and show
      // theirs rather than reporting a failure the operator cannot act on.
      if (isApiError(caught) && caught.status === 409) {
        setCountError(null);
        void recorded.refetch();
        return;
      }
      setCountError(messageOf(caught));
    },
  });

  const correct = useMutation({
    mutationFn: ({
      countedCash,
      openingFloat,
      note,
    }: { countedCash: number; openingFloat?: number; note?: string }) =>
      correctCashCount(businessDate as string, { countedCash, openingFloat, note }),
    onSuccess: (saved) => {
      setCorrectError(null);
      setCount(saved);
      void recorded.refetch();
    },
    onError: (caught) => setCorrectError(messageOf(caught)),
  });

  /* Counting again after the day was closed and then traded on. Reopens the day, so the
     ordinary close below runs a second time and records who signed it off. */
  const recount = useMutation({
    mutationFn: ({ countedCash, note }: { countedCash: number; note?: string }) =>
      recountAfterClose(businessDate as string, { countedCash, note }),
    onSuccess: (saved) => {
      setCountError(null);
      setCount(saved);
      setClosed(false);
      void recorded.refetch();
      void queryClient.invalidateQueries({ queryKey: queryKeys.businessDayCurrent });
    },
    onError: (caught) => setCountError(messageOf(caught)),
  });

  const close = useMutation({
    mutationFn: () => closeBusinessDay(businessDate as string),
    onSuccess: () => {
      setCloseBlocker(null);
      setClosed(true);
      void queryClient.invalidateQueries({ queryKey: queryKeys.businessDayCurrent });
      void queryClient.invalidateQueries({ queryKey: queryKeys.uncountedDays });
    },
    // Both preconditions live on the server. Whatever it says is missing is shown as guidance,
    // not as a crash — 409 here is an expected state, not an exception.
    onError: (caught) => setCloseBlocker(messageOf(caught)),
  });

  if (day.isPending) {
    return (
      <div className="flex justify-center py-16">
        <Spinner label="Loading the business day…" />
      </div>
    );
  }

  if (day.isError || !day.data) {
    return (
      <Card className="max-w-xl">
        <Banner tone="danger">{messageOf(day.error)}</Banner>
      </Card>
    );
  }

  const openSessions = day.data.openSessions;
  const uncollected = unsettled.data ?? [];
  // The night being looked at is never listed as one still to do.
  const outstanding = (uncounted.data ?? []).filter(
    (night) => night.businessDate !== day.data?.businessDate,
  );

  // Whether this shift entered the count or an earlier one did, the closer should see what
  // the day was closed against.
  const settledCount = count ?? recorded.data ?? null;

  if (closed) {
    return (
      <Card className="mx-auto max-w-lg">
        <h1 className="text-heading text-text">
          {formatBusinessDate(day.data.businessDate)} is closed
        </h1>
        <p className="mt-3 text-body text-text-dim">
          The drawer count is recorded against the day and the close is in the audit log.
        </p>
        {settledCount ? (
          <p className="mt-3 text-body text-text-dim">
            Counted {formatMoney(settledCount.countedCash)} against{' '}
            {formatMoney(settledCount.expectedCash)} expected — variance{' '}
            <span className={settledCount.variance < 0 ? 'text-danger' : 'text-text'}>
              {settledCount.variance > 0 ? '+' : ''}
              {formatMoney(settledCount.variance)}
            </span>
            .
          </p>
        ) : null}
        <Button className="mt-6" onClick={() => navigate('/floor')}>
          Back to the floor
        </Button>
      </Card>
    );
  }

  return (
    <div className="mx-auto grid max-w-6xl gap-6 lg:grid-cols-2">
      {outstanding.length > 0 ? (
        <div className="lg:col-span-2">
          <Banner tone="warning">
            <div>
              <strong>
                {outstanding.length === 1
                  ? 'An earlier night was never counted.'
                  : `${outstanding.length} earlier nights were never counted.`}
              </strong>{' '}
              The drawer was never reconciled against those takings, and nothing else says so.
              Counting tonight is unaffected.
              <ul className="mt-2 flex flex-wrap gap-x-4 gap-y-1">
                {outstanding.map((night) => (
                  <li key={night.businessDate}>
                    <Link
                      to={`/end-of-day?date=${night.businessDate}`}
                      className="hit inline-flex items-center rounded-lg px-2 text-info underline"
                    >
                      {formatBusinessDate(night.businessDate)}
                    </Link>{' '}
                    <span className="text-text-dim">
                      ({night.bills} {night.bills === 1 ? 'bill' : 'bills'})
                    </span>
                  </li>
                ))}
              </ul>
            </div>
          </Banner>
        </div>
      ) : null}

      <div className="flex flex-col gap-4">
        <Card>
          <h1 className="text-heading text-text">End of day</h1>
          <p className="mt-1 text-body text-text-dim">
            {formatBusinessDate(day.data.businessDate)}
          </p>
          {viewing ? (
            <p className="mt-3 text-label uppercase text-amount">
              An earlier night — not tonight.{' '}
              {/* Inline in a sentence, so it needs the hit class to reach 44px; measured at
                  15px without it. */}
              <Link
                to="/end-of-day"
                className="hit inline-flex items-center text-info underline"
              >
                Back to tonight
              </Link>
            </p>
          ) : null}
          <p className="mt-3 text-label text-text-dim">
            The business day runs 10:00 to 05:00, so a sale at 02:00 still belongs to the night
            before. The date above is the server's.
          </p>
        </Card>

        {/* ── Open sessions: the hard block, with a way to act on it ─────────── */}
        <Card>
          <h2 className="text-heading text-text">Tables still running</h2>
          {openSessions.length === 0 ? (
            <p className="mt-3 text-body text-green">
              Nothing is running. The floor is clear.
            </p>
          ) : (
            <>
              <p className="mt-1 text-body text-text-dim">
                The day cannot close while a table is running. Close each one, then come back.
              </p>
              <ul className="mt-4 divide-y divide-border">
                {openSessions.map((session) => (
                  <li key={session.sessionId} className="flex items-center justify-between gap-3 py-3">
                    <div>
                      <div className="text-body text-text">{session.poolTableName}</div>
                      <div className="tabular text-label text-text-dim">
                        {formatElapsed(session.billedSeconds * 1000)} ·{' '}
                        {formatMoney(session.runningTotal)} · {session.customerTypeName}
                      </div>
                    </div>
                    <Link to={`/sessions/${session.sessionId}`}>
                      <Button variant="secondary">Go and close it</Button>
                    </Link>
                  </li>
                ))}
              </ul>
            </>
          )}
        </Card>

        {/* ── Unsettled bills: information, never a block ────────────────────── */}
        <Card>
          <h2 className="text-heading text-text">Uncollected</h2>
          {uncollected.length === 0 ? (
            <p className="mt-3 text-body text-text-dim">Every bill tonight has been settled.</p>
          ) : (
            <>
              <p className="mt-1 text-body text-text-dim">
                These bills were never paid. This does not stop the close — it is here so the
                night is not shut with money quietly left on the table.
              </p>
              <ul className="mt-4 divide-y divide-border">
                {uncollected.map((bill) => (
                  <li key={bill.id} className="flex items-center justify-between gap-3 py-3">
                    <div>
                      <div className="text-body text-text">
                        {bill.tableNames.length > 0 ? bill.tableNames.join(', ') : 'Quick sale'}
                      </div>
                      <div className="text-label text-text-dim">
                        {bill.customerTypeName}
                        {bill.businessDate !== day.data.businessDate ? (
                          <span className="text-danger">
                            {' '}· carried over from {formatBusinessDate(bill.businessDate)}
                          </span>
                        ) : null}
                      </div>
                    </div>
                    <div className="flex items-center gap-3">
                      <span className="tabular text-body text-amount">
                        {formatMoney(bill.totalAmount)}
                      </span>
                      <Link to={`/checkout/${bill.id}`}>
                        <Button variant="secondary">Collect</Button>
                      </Link>
                    </div>
                  </li>
                ))}
              </ul>
            </>
          )}
        </Card>
      </div>

      <div className="flex flex-col gap-4">
        <CashCountPanel
          count={settledCount}
          standardFloat={day.data?.standardCashFloat ?? 0}
          paidFromDrawer={paidFromDrawer}
          pending={cashCount.isPending}
          error={countError}
          canCorrect={user?.role === 'ADMIN'}
          correcting={correct.isPending}
          correctError={correctError}
          onSubmit={(countedCash, openingFloat, note) =>
            cashCount.mutate({ countedCash, openingFloat, note })
          }
          onCorrect={(countedCash, openingFloat, note) =>
            correct.mutate({ countedCash, openingFloat, note })
          }
          onRecount={(countedCash, note) => recount.mutate({ countedCash, note })}
          recounting={recount.isPending}
        />

        <Card>
          <h2 className="text-heading text-text">Close the day</h2>
          {settledCount?.closedAt &&
          (settledCount.salesAfterClose > 0 || settledCount.expensesAfterClose > 0) ? (
            <p className="mt-3 text-body text-danger">
              Closed, then traded on. Recount the drawer above, then close it again.
            </p>
          ) : settledCount?.closedAt ? (
            <p className="mt-3 text-body text-green">
              This day is already closed. Nothing further to do.
            </p>
          ) : null}
          <p className="mt-1 text-body text-text-dim">
            Both conditions are enforced by the server: no table running, and the drawer
            counted.
          </p>

          {closeBlocker ? (
            <div className="mt-4">
              <Banner tone="warning">{closeBlocker}</Banner>
            </div>
          ) : null}

          {settledCount?.closedAt &&
          settledCount.salesAfterClose === 0 &&
          settledCount.expensesAfterClose === 0 ? null : (
            <Button
              variant="danger"
              className="mt-6 h-14 w-full text-heading"
              pending={close.isPending}
              onClick={() => close.mutate()}
            >
              Close {formatBusinessDate(day.data.businessDate)}
            </Button>
          )}
        </Card>
      </div>
    </div>
  );
}
