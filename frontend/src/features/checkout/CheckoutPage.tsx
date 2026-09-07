import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { fetchCheckout, payBill } from '@/api/endpoints/bills';
import { overrideBilledMinutes } from '@/api/endpoints/sessions';
import { Field } from '@/components/Field';
import { queryKeys } from '@/api/queryKeys';
import { ErrorCode, isApiError, messageOf } from '@/api/errors';
import type { PaymentRequest } from '@/api/types';
import { newIdempotencyKey } from '@/lib/idempotency';
import { useScreenTheme } from '@/app/useTheme';
import { formatMoney } from '@/lib/money';
import { formatBusinessDate } from '@/lib/datetime';
import { Card } from '@/components/Card';
import { Button } from '@/components/Button';
import { Banner } from '@/components/Banner';
import { Spinner } from '@/components/Spinner';
import { NoteThread } from '@/features/notes/NoteThread';
import { BillSummary } from './BillSummary';
import { LeaveUnpaidModal } from './LeaveUnpaidModal';
import { PaymentForm } from './PaymentForm';

/** What went wrong, and what the operator can do about it. */
type Recovery =
  | { kind: 'none' }
  | { kind: 'alreadyPaid'; message: string }
  | { kind: 'duplicateReference'; message: string }
  | { kind: 'retotalled'; message: string }
  | { kind: 'plain'; message: string };

export function CheckoutPage() {
  useScreenTheme('pos');
  const { billId = '' } = useParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const [recovery, setRecovery] = useState<Recovery>({ kind: 'none' });
  const [reducing, setReducing] = useState<string | null>(null);
  const [reduceTo, setReduceTo] = useState('');
  const [reduceReason, setReduceReason] = useState('');
  const [reduceError, setReduceError] = useState<string | null>(null);
  const [leavingUnpaid, setLeavingUnpaid] = useState(false);

  const checkout = useQuery({
    queryKey: queryKeys.checkout(billId),
    queryFn: () => fetchCheckout(billId),
    // Not polled: the operator is reading a total to a customer, and having it change under
    // them mid-sentence is worse than being one refresh stale. The server rejects a stale
    // amount anyway, which is the guard that actually matters.
    staleTime: Infinity,
  });

  const bill = checkout.data?.bill;
  const total = bill?.totalAmount ?? 0;

  /* One key per attempt, reused on every retry of that attempt — including "Record anyway",
     where reuse is what keeps the button safe against a double-click. A changed total is a
     genuinely different charge, so that starts a new attempt and a new key. */
  /* Measured at the instant of payment and handed to the receipt, which starts its life at
     this geometry and travels to its own. The bill is the thing that becomes the receipt, so
     the receipt has to know where the bill was standing. */
  const billCard = useRef<HTMLDivElement>(null);

  const idempotencyKey = useRef(newIdempotencyKey());
  const keyedFor = useRef<number | null>(null);
  useEffect(() => {
    if (!bill) return;
    if (keyedFor.current === null) {
      keyedFor.current = bill.totalAmount;
      return;
    }
    if (keyedFor.current !== bill.totalAmount) {
      idempotencyKey.current = newIdempotencyKey();
      keyedFor.current = bill.totalAmount;
    }
  }, [bill]);

  const pay = useMutation({
    mutationFn: (body: PaymentRequest) => payBill(billId, body),
    onSuccess: (payment) => {
      setRecovery({ kind: 'none' });
      void queryClient.invalidateQueries({ queryKey: queryKeys.floor });
      void queryClient.invalidateQueries({ queryKey: queryKeys.unsettledBills });
      // A settled debt leaves the unpaid list, so that one has to be dropped too.
      void queryClient.invalidateQueries({ queryKey: queryKeys.unpaidBills });
      /*
       * Straight to the receipt. There is no confirmation panel any more.
       *
       * Taking the money and showing what was paid are one action, not two, and a screen that
       * asked "would you like to see the receipt?" was asking a question with one answer. The
       * payment rides in history state so the receipt can show what only the payment response
       * knows — the change to hand back, whether this was a replay — with no second request.
       *
       * `replace`, so Back does not return to a checkout for a bill that is now settled.
       */
      const rect = billCard.current?.getBoundingClientRect();
      navigate(`/receipt/${billId}`, {
        replace: true,
        state: {
          justPaid: payment,
          from: rect ? { x: rect.x, width: rect.width } : null,
        },
      });
    },
    onError: async (caught) => {
      const message = messageOf(caught);

      /* Every failure here is classified from refetched *state*, not from the message text.
         Two of the four cases arrive as a plain 409 with no code, and string-matching a
         server sentence is exactly the kind of thing that silently stops working. */
      const fresh = await queryClient
        .fetchQuery({ queryKey: queryKeys.checkout(billId), queryFn: () => fetchCheckout(billId) })
        .catch(() => null);

      // Another tab settled it first — the realistic two-tab race. Not an error to the
      // operator: the money is collected, they just need the receipt.
      if (fresh && fresh.bill.status !== 'OPEN') {
        setRecovery({ kind: 'alreadyPaid', message });
        return;
      }

      if (isApiError(caught) && caught.code === ErrorCode.DuplicatePaymentReference) {
        setRecovery({ kind: 'duplicateReference', message });
        return;
      }

      // Built even though it cannot fire today: bill.version only moves at settle, and the
      // already-CLOSED check runs first. It becomes live the moment the backend bumps the
      // version on a line change.
      if (isApiError(caught) && caught.code === ErrorCode.StaleBillVersion) {
        setRecovery({ kind: 'retotalled', message });
        return;
      }

      // Anything else, including the amount-mismatch 409. The form re-reads the refetched
      // total, so the old amount can never be resent.
      setRecovery(
        fresh && fresh.bill.totalAmount !== total
          ? { kind: 'retotalled', message }
          : { kind: 'plain', message },
      );
    },
  });

  /* Charging less table time than was played — the friend who gets an hour off. Taken here,
     before payment, because this is the last moment the bill can still change and the first
     moment the operator knows the final figure. Only downwards; the server rejects the rest. */
  const reduceTime = useMutation({
    mutationFn: ({ sessionId, billedMinutes, reason }: { sessionId: string; billedMinutes: number; reason: string }) =>
      overrideBilledMinutes(sessionId, { billedMinutes, reason }),
    onSuccess: () => {
      setReduceError(null);
      setReducing(null);
      setReduceTo('');
      setReduceReason('');
      void queryClient.invalidateQueries({ queryKey: queryKeys.checkout(billId) });
    },
    onError: (caught) => setReduceError(messageOf(caught)),
  });

  if (checkout.isPending) {
    return (
      <div className="flex justify-center py-16">
        <Spinner label="Loading the bill…" />
      </div>
    );
  }

  if (checkout.isError || !bill) {
    return (
      <Card className="max-w-xl">
        <Banner tone="danger">{messageOf(checkout.error)}</Banner>
        <Link to="/floor" className="hit mt-4 inline-flex items-center text-body text-info underline">
          Back to the floor
        </Link>
      </Card>
    );
  }

  /* ── Settled ─────────────────────────────────────────────────────────────── */
  /* ── Taking payment ──────────────────────────────────────────────────────── */
  const blocked = !checkout.data.canCheckout;

  return (
    <div className="mx-auto grid max-w-6xl gap-6 lg:grid-cols-[1fr_minmax(24rem,32rem)]">
      <Card ref={billCard}>
        <h1 className="mb-4 text-heading text-text">Bill</h1>
        <BillSummary bill={bill} />

        {/* Time is reduced here and nowhere else: this is the last point the bill can change
            and the first point the operator knows what it came to. */}
        {bill.sessions.length > 0 && !blocked ? (
          <div className="mt-6 border-t border-border pt-4">
            {bill.sessions.map((session) => (
              <div key={session.sessionId} className="py-1">
                {reducing === session.sessionId ? (
                  <form
                    className="flex flex-col gap-4"
                    onSubmit={(event) => {
                      event.preventDefault();
                      if (reduceTo.trim() === '' || reduceReason.trim() === '') return;
                      reduceTime.mutate({
                        sessionId: session.sessionId,
                        billedMinutes: Number(reduceTo),
                        reason: reduceReason.trim(),
                      });
                    }}
                  >
                    <Field
                      label={`Minutes to charge on ${session.poolTableName}`}
                      type="number"
                      min="0"
                      max={session.billedMinutes}
                      inputMode="numeric"
                      value={reduceTo}
                      data-autofocus
                      hint={`Played ${session.billedMinutes} min. It can only go down — charging for time that was not played is an overcharge.`}
                      onChange={(event) => setReduceTo(event.target.value)}
                    />
                    <Field
                      label="Why"
                      value={reduceReason}
                      placeholder="Who authorised the reduction"
                      onChange={(event) => setReduceReason(event.target.value)}
                    />
                    {reduceError ? <Banner tone="danger">{reduceError}</Banner> : null}
                    <div className="flex justify-end gap-3">
                      <Button type="button" variant="secondary" onClick={() => setReducing(null)}>
                        Cancel
                      </Button>
                      <Button
                        type="submit"
                        pending={reduceTime.isPending}
                        disabled={reduceTo.trim() === '' || reduceReason.trim() === ''}
                      >
                        Charge {reduceTo || '—'} min
                      </Button>
                    </div>
                  </form>
                ) : (
                  <div className="flex items-center justify-between gap-3">
                    <span className="text-label uppercase text-text-dim">
                      {session.poolTableName} · {session.billedMinutes} min played
                    </span>
                    <Button
                      variant="secondary"
                      onClick={() => {
                        setReduceError(null);
                        setReduceTo(String(session.billedMinutes));
                        setReduceReason('');
                        setReducing(session.sessionId);
                      }}
                    >
                      Charge less time
                    </Button>
                  </div>
                )}
              </div>
            ))}
          </div>
        ) : null}

        {/* The names travel with the bill. Arriving from the unpaid strip, this is where staff
            put one on that they forgot during the rush; after payment it stays, and settlement
            adds its own line saying who collected. Writes go against the bill's most recent
            session — a quick sale has none, and never had a debt to attribute. */}
        <div className="mt-6 border-t border-border pt-4">
          <NoteThread
            source={{ kind: 'bill', billId }}
            writeTo={bill.sessions.at(-1)?.sessionId}
            title="Notes"
          />
        </div>
      </Card>

      <div className="flex flex-col gap-4">
        {recovery.kind === 'alreadyPaid' ? (
          <Banner
            tone="info"
            actions={
              <Button onClick={() => navigate(`/receipt/${billId}`)}>Show receipt</Button>
            }
          >
            This bill was already settled — another till got there first. The money is
            collected; nothing more to take.
          </Banner>
        ) : null}

        {recovery.kind === 'retotalled' ? (
          <Banner tone="warning">
            The bill changed while you were on this screen. It is now{' '}
            <strong>{formatMoney(total)}</strong> — confirm the new amount with the customer
            before taking payment.
          </Banner>
        ) : null}

        {recovery.kind === 'duplicateReference' ? (
          <Banner tone="warning">
            {recovery.message} Check it is not a double entry, then use “Record anyway”.
          </Banner>
        ) : null}

        {recovery.kind === 'plain' ? <Banner tone="danger">{recovery.message}</Banner> : null}

        {blocked ? (
          <Banner tone="warning">{checkout.data.blockers.join(' ')}</Banner>
        ) : null}

        {bill.status === 'UNSETTLED' ? (
          <Banner tone="info">
            This is a debt from {formatBusinessDate(bill.businessDate)}. The amount is fixed at{' '}
            <strong>{formatMoney(total)}</strong> and must be collected in full — part payment is
            not recorded.
          </Banner>
        ) : null}

        <Card>
          <h2 className="mb-4 text-heading text-text">
            {bill.status === 'UNSETTLED' ? 'Collect this debt' : 'Take payment'}
          </h2>
          {blocked || recovery.kind === 'alreadyPaid' ? (
            <p className="text-body text-text-dim">
              {recovery.kind === 'alreadyPaid'
                ? 'This bill is settled.'
                : 'Sort the above out first — the server will refuse the payment until then.'}
            </p>
          ) : (
            <PaymentForm
              total={total}
              billVersion={bill.version}
              idempotencyKey={idempotencyKey.current}
              duplicateOverride={recovery.kind === 'duplicateReference'}
              pending={pay.isPending}
              onSubmit={(body) => pay.mutate(body)}
            />
          )}
        </Card>

        <Link to="/floor" className="hit inline-flex items-center text-body text-info underline">
          Back to the floor
        </Link>

        {/* Deliberately down here, below the way out, and never beside Take payment.
            Completing a sale without collecting the money is the one action on this screen
            that cannot be undone by taking the payment again, and a control for it sitting
            next to the one pressed forty times a night is a mis-click waiting to happen.
            Only offered on a bill that could actually be paid: a blocked one has a running
            session, and a debt has to be for a finished game. */}
        {!blocked && bill.status === 'OPEN' && recovery.kind !== 'alreadyPaid' ? (
          <div className="mt-2 border-t border-border pt-4">
            <button
              type="button"
              onClick={() => setLeavingUnpaid(true)}
              className="hit inline-flex items-center text-body text-text-dim underline hover:text-text"
            >
              Leave unpaid — they will settle later
            </button>
          </div>
        ) : null}
      </div>

      {leavingUnpaid ? (
        <LeaveUnpaidModal
          billId={billId}
          billVersion={bill.version}
          amount={total}
          onClose={() => setLeavingUnpaid(false)}
          onDone={() => {
            setLeavingUnpaid(false);
            // Straight to the debt list, so the operator sees the bill land where it now
            // lives rather than being left on a checkout that no longer takes payment.
            navigate('/unsettled', { replace: true });
          }}
        />
      ) : null}
    </div>
  );
}
