import { useState } from 'react';
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { closeSession, fetchSession, pauseSession, resumeSession } from '@/api/endpoints/sessions';
import { addBillLine, fetchBill, voidBillLine } from '@/api/endpoints/bills';
import { queryKeys } from '@/api/queryKeys';
import { messageOf } from '@/api/errors';
import type { BillLine, Product } from '@/api/types';
import { useClock } from '@/time/useClock';
import { useTicker } from '@/time/useTicker';
import { useElapsed } from '@/time/useElapsed';
import { useScreenTheme } from '@/app/useTheme';
import { BreakFlourish } from './BreakFlourish';
import { formatElapsed } from '@/lib/datetime';
import { formatHourlyRate, formatMoney, formatRate } from '@/lib/money';
import { Button } from '@/components/Button';
import { Banner } from '@/components/Banner';
import { Card } from '@/components/Card';
import { Spinner } from '@/components/Spinner';
import { Modal } from '@/components/Modal';
import { NoteThread } from '@/features/notes/NoteThread';
import { ProductGrid } from './ProductGrid';
import { BillLines } from './BillLines';
import { VoidLineModal } from './VoidLineModal';

const POLL_MS = 10_000;

export function SessionPage() {
  useScreenTheme('pos');
  const { sessionId = '' } = useParams();
  const navigate = useNavigate();
  // Set by the floor when it opened this table. Read once at mount: the flourish belongs to
  // arriving here, and should not replay because something else in this screen re-rendered.
  const location = useLocation();
  const [justStarted] = useState(
    () => (location.state as { justStarted?: boolean } | null)?.justStarted === true,
  );
  const queryClient = useQueryClient();
  const { syncTo } = useClock();

  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [voiding, setVoiding] = useState<BillLine | null>(null);
  const [voidError, setVoidError] = useState<string | null>(null);
  const [confirmingClose, setConfirmingClose] = useState(false);

  useTicker(true);

  const session = useQuery({
    queryKey: queryKeys.session(sessionId),
    queryFn: async () => {
      const result = await fetchSession(sessionId);
      syncTo(result.serverNow);
      return result;
    },
    refetchInterval: POLL_MS,
    refetchIntervalInBackground: true,
  });

  const billId = session.data?.billId;

  const bill = useQuery({
    queryKey: queryKeys.bill(billId ?? ''),
    queryFn: () => fetchBill(billId as string),
    enabled: Boolean(billId),
    refetchInterval: POLL_MS,
    refetchIntervalInBackground: true,
  });

  /* The session summary shape the timer wants. The session response carries the same figures
     under its own field names, so it is adapted here rather than duplicating the hook. */
  const timerSource = session.data
    ? {
        sessionId: session.data.id,
        billId: session.data.billId,
        poolTableName: session.data.poolTableName,
        status: session.data.status === 'PAUSED' ? ('PAUSED' as const) : ('OPEN' as const),
        customerTypeId: session.data.customerTypeId,
        customerTypeName: session.data.customerTypeName,
        openedAt: session.data.openedAt,
        billedMinutes: session.data.billedMinutes,
        billedSeconds: session.data.billedSeconds,
        ratePerMinute: session.data.rateOverridePerMinute ?? session.data.standardRatePerMinute,
        timeAmount: session.data.timeAmount,
        itemCount: session.data.itemCount,
        itemTotal: session.data.itemTotal,
        runningTotal: session.data.runningTotal,
      }
    : null;
  const elapsedMs = useElapsed(timerSource);

  function refresh() {
    void queryClient.invalidateQueries({ queryKey: queryKeys.session(sessionId) });
    if (billId) void queryClient.invalidateQueries({ queryKey: queryKeys.bill(billId) });
    void queryClient.invalidateQueries({ queryKey: queryKeys.floor });
  }

  // No optimistic updates anywhere below: every one of these moves money or stock, and a line
  // that appears then vanishes is worse than one that takes 200ms.
  const addLine = useMutation({
    mutationFn: (product: Product) =>
      addBillLine(billId as string, { productId: product.id, quantity: 1 }),
    onSuccess: (result) => {
      setError(null);
      // Selling below zero succeeds. Warn, never block — a stale count must not stop a
      // paying customer.
      setNotice(result.belowZeroStock ? result.warning : null);
      refresh();
      void queryClient.invalidateQueries({ queryKey: queryKeys.products({ activeOnly: true }) });
    },
    onError: (caught) => setError(messageOf(caught)),
  });

  const voidLine = useMutation({
    mutationFn: ({ line, reason }: { line: BillLine; reason: string }) =>
      voidBillLine(billId as string, line.id, { reason }),
    onSuccess: () => {
      setVoiding(null);
      setVoidError(null);
      refresh();
      void queryClient.invalidateQueries({ queryKey: queryKeys.products({ activeOnly: true }) });
    },
    onError: (caught) => setVoidError(messageOf(caught)),
  });

  const pause = useMutation({
    mutationFn: () => pauseSession(sessionId),
    onSuccess: refresh,
    onError: (caught) => setError(messageOf(caught)),
  });

  const resume = useMutation({
    mutationFn: () => resumeSession(sessionId),
    onSuccess: refresh,
    onError: (caught) => setError(messageOf(caught)),
  });

  const close = useMutation({
    mutationFn: () => closeSession(sessionId),
    onSuccess: (closed) => {
      refresh();
      navigate(`/checkout/${closed.billId}`);
    },
    onError: (caught) => {
      setConfirmingClose(false);
      setError(messageOf(caught));
    },
  });

  if (session.isPending) {
    return (
      <div className="flex justify-center py-16">
        <Spinner label="Loading the session…" />
      </div>
    );
  }

  if (session.isError) {
    return (
      <Card className="max-w-xl">
        <Banner tone="danger">{messageOf(session.error)}</Banner>
        <Link to="/floor" className="hit mt-4 inline-flex items-center text-body text-info underline">
          Back to the floor
        </Link>
      </Card>
    );
  }

  const live = session.data;
  const paused = live.status === 'PAUSED';
  const finished = live.status !== 'OPEN' && live.status !== 'PAUSED';
  const total = bill.data?.totalAmount ?? null;

  return (
    <div className="grid gap-6 lg:h-[calc(100dvh-6.5rem)] lg:grid-cols-[minmax(380px,32rem)_1fr]">
      {/* ── The running session ─────────────────────────────────────────────── */}
      <div className="flex min-h-0 flex-col gap-4">
        {/* The same card the floor shows, opened up. A table in play is under a lamp: the head
            is lit while the session runs, dimmer when it is paused, dark once it is finished.
            Nothing dim sits on the light — every figure lives on the solid felt below it. */}
        <div
          className={`overflow-hidden rounded-2xl ${
            finished
              ? 'bg-surface rail-free'
              : paused
                ? 'bg-raised lit-paused rail-paused'
                : 'bg-raised lit-running rail-running'
          }`}
        >
          <div className="relative flex min-h-[7.5rem] items-start justify-between gap-4 p-6">
            {/* Only when this table was opened a moment ago, and only on a live session — a
                paused or finished head is not a table coming into play. */}
            {justStarted && !paused && !finished ? <BreakFlourish /> : null}
            <div className="relative min-w-0">
              <h1 className="figure-name text-text">{live.poolTableName}</h1>
              <p className="mt-1 text-label uppercase text-text-dim">{live.customerTypeName}</p>
            </div>
            <span className="relative shrink-0 text-label uppercase text-text">
              {finished ? live.status : paused ? 'Paused' : 'Running'}
            </span>
          </div>

          <div className="bg-raised px-6 pb-6 pt-4">
            {/* The money leads. It is the server's own sum — bill.totalAmount is still 0.00 on
                a live session because the TIME lines are not written until close, so
                runningTotal is authoritative until then and the bill is after. Neither is
                added up here. */}
            <div>
              <div className="figure-amount text-amount">
                {formatMoney(finished ? total : live.runningTotal)}
              </div>
              {/* Under the money rather than beside it: this column is narrow, and the two
                  set side by side collide the moment the total passes a thousand pesos. */}
              <div className="figure-timer mt-2 text-text-dim">{formatElapsed(elapsedMs)}</div>
            </div>

            <dl className="mt-4 space-y-1 border-t border-border pt-3">
              <div className="flex justify-between gap-3">
                <dt className="text-label uppercase text-text-dim">Table time</dt>
                <dd className="tabular text-body text-text">{formatMoney(live.timeAmount)}</dd>
              </div>
              <div className="flex justify-between gap-3">
                <dt className="text-label uppercase text-text-dim">Items</dt>
                <dd className="tabular text-body text-text">{formatMoney(live.itemTotal)}</dd>
              </div>
            </dl>

            {live.rateOverridePerMinute !== null ? (
              <p className="mt-3 text-label text-amount">
                {/* Named after the customer type the session was opened on, and quoted in the
                    unit the override was set in — but only when BOTH sides were snapshotted
                    hourly, since half a comparison reads worse than a per-minute one. */}
                {live.customerTypeName ? `${live.customerTypeName} rate` : 'Rate override'} in
                effect — standard is{' '}
                {live.rateOverridePerHour !== null && live.standardRatePerHour !== null
                  ? formatHourlyRate(live.standardRatePerHour)
                  : formatRate(live.standardRatePerMinute)}
              </p>
            ) : null}

            {!finished ? (
              <div className="mt-6 flex gap-3">
                {paused ? (
                  <Button pending={resume.isPending} onClick={() => resume.mutate()}>
                    Resume
                  </Button>
                ) : (
                  <Button variant="secondary" pending={pause.isPending} onClick={() => pause.mutate()}>
                    Pause
                  </Button>
                )}
                <Button variant="secondary" onClick={() => navigate('/floor')}>
                  Floor
                </Button>
              </div>
            ) : (
              <Link
                to={`/checkout/${live.billId}`}
                className="hit mt-6 inline-flex items-center text-body text-info underline"
              >
                Go to checkout
              </Link>
            )}
          </div>
        </div>

        <Card className="flex min-h-0 flex-1 flex-col overflow-y-auto">
          <h2 className="text-heading text-text">Bill</h2>
          {bill.isPending ? (
            <div className="py-6 text-center">
              <Spinner label="Loading the bill…" />
            </div>
          ) : (
            <BillLines
              lines={bill.data?.lines ?? []}
              onVoid={(line) => {
                setVoidError(null);
                setVoiding(line);
              }}
            />
          )}
        </Card>

        {/* While people are playing is when staff know who is on the table, so the box is here
            rather than only at checkout. It stays after the close: nothing about a note depends
            on the session still running. */}
        <Card className="max-h-72 shrink-0 overflow-y-auto">
          <NoteThread source={{ kind: 'session', sessionId: live.id }} writeTo={live.id} />
        </Card>

        {/* Close sits at the bottom, away from Add item, and confirms with the amount. */}
        {!finished ? (
          <Button
            variant="danger"
            className="h-14 shrink-0 text-heading"
            onClick={() => setConfirmingClose(true)}
          >
            Close table &amp; check out
          </Button>
        ) : null}
      </div>

      {/* ── Ordering ────────────────────────────────────────────────────────── */}
      <div className="flex min-h-0 flex-col gap-4">
        {error ? <Banner tone="danger">{error}</Banner> : null}
        {notice ? <Banner tone="warning">{notice}</Banner> : null}
        {finished ? (
          <Banner tone="info">
            This session is {live.status.toLowerCase()}. Nothing more can be added to it.
          </Banner>
        ) : null}

        <Card className="flex min-h-0 flex-1 flex-col">
          <h2 className="mb-4 shrink-0 text-heading text-text">Add to the bill</h2>
          {finished ? (
            <p className="text-body text-text-dim">Ordering is closed for this session.</p>
          ) : (
            <ProductGrid
              pendingProductId={addLine.isPending ? addLine.variables?.id ?? null : null}
              onAdd={(product) => addLine.mutate(product)}
            />
          )}
        </Card>
      </div>

      {voiding ? (
        <VoidLineModal
          line={voiding}
          pending={voidLine.isPending}
          error={voidError}
          onClose={() => setVoiding(null)}
          onConfirm={(reason) => voidLine.mutate({ line: voiding, reason })}
        />
      ) : null}

      {confirmingClose ? (
        <Modal title="Close the table" onClose={() => setConfirmingClose(false)}>
          <p className="text-body text-text-dim">
            The timer stops and the time charge is written to the bill. Payment is taken on the
            next screen.
          </p>

          {/* The server's running total, not a sum taken here. */}
          <div className="tabular mt-4 text-display text-amount">
            {formatMoney(live.runningTotal)}
          </div>
          <p className="mt-1 text-label text-text-dim">
            {formatMoney(live.timeAmount)} table time + {formatMoney(live.itemTotal)} items. The
            server recomputes the final total at checkout.
          </p>
          <div className="mt-6 flex justify-end gap-3">
            <Button variant="secondary" onClick={() => setConfirmingClose(false)}>
              Keep it running
            </Button>
            <Button data-autofocus pending={close.isPending} onClick={() => close.mutate()}>
              Close &amp; check out
            </Button>
          </div>
        </Modal>
      ) : null}
    </div>
  );
}
