import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  clearBillDiscount,
  discountBill,
  fetchCheckout,
  payBill,
  redeemVoucher,
  releaseVoucher,
} from '@/api/endpoints/bills';
import { overrideBilledMinutes } from '@/api/endpoints/sessions';
import { Field } from '@/components/Field';
import { queryKeys } from '@/api/queryKeys';
import { ErrorCode, isApiError, messageOf } from '@/api/errors';
import type { PaymentRequest, VoucherRedemption } from '@/api/types';
import { newIdempotencyKey } from '@/lib/idempotency';
import { useScreenTheme } from '@/app/useTheme';
import { formatMoney } from '@/lib/money';
import { formatBusinessDate, formatMinutes } from '@/lib/datetime';
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
  const [discounting, setDiscounting] = useState(false);
  const [chargeAmount, setChargeAmount] = useState('');
  const [discountReason, setDiscountReason] = useState('');
  const [discountError, setDiscountError] = useState<string | null>(null);
  const [redeeming, setRedeeming] = useState(false);
  const [voucherCode, setVoucherCode] = useState('');
  const [voucherError, setVoucherError] = useState<string | null>(null);
  /* What the server said the redemption did, held only until the operator moves on. This is
     where the forfeited minutes get said out loud — the customer with a two-hour code who
     played ninety minutes has thirty minutes taken off them, and the cashier is the one who
     has to tell them so. It is not on the bill anywhere afterwards, by design: it is news, not
     state. */
  const [redemption, setRedemption] = useState<VoucherRedemption | null>(null);
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

  /* Knocking money off the whole bill — the beer as well as the table, which is what makes it
     a different control from "charge less time" beside it rather than a variant of it. Both
     can apply to one bill; the server keeps them apart and the dashboard reports them apart.

     The operator types what they are CHARGING, because "make it 600" is the sentence the
     counter actually produced. The server does the subtraction. */
  const discount = useMutation({
    mutationFn: ({ charge, reason }: { charge: number; reason: string }) =>
      discountBill(billId, { chargeAmount: charge, reason }),
    onSuccess: () => {
      setDiscountError(null);
      setDiscounting(false);
      setChargeAmount('');
      setDiscountReason('');
      void queryClient.invalidateQueries({ queryKey: queryKeys.checkout(billId) });
    },
    onError: (caught) => setDiscountError(messageOf(caught)),
  });

  const removeDiscount = useMutation({
    mutationFn: () => clearBillDiscount(billId),
    onSuccess: () => {
      setDiscountError(null);
      void queryClient.invalidateQueries({ queryKey: queryKeys.checkout(billId) });
    },
    onError: (caught) => setDiscountError(messageOf(caught)),
  });

  /* Free table time, won as a prize and produced at the counter when it is time to pay.
     A third control beside the two above, and it reaches something neither of them does: a
     measured quantity of TIME, covered at the rate that time was actually billed at.

     Every refusal renders the server's own message. There are six of them and they say
     different things — expired, already used, wrong pricing, wrong status, no time on the bill,
     no such code — and "invalid code" for all six is useless to a cashier holding up a queue. */
  const applyVoucher = useMutation({
    mutationFn: (code: string) => redeemVoucher(billId, code),
    onSuccess: (result) => {
      setVoucherError(null);
      setRedeeming(false);
      setVoucherCode('');
      setRedemption(result);
      void queryClient.invalidateQueries({ queryKey: queryKeys.checkout(billId) });
    },
    onError: (caught) => setVoucherError(messageOf(caught)),
  });

  const removeVoucher = useMutation({
    mutationFn: () => releaseVoucher(billId),
    onSuccess: () => {
      setVoucherError(null);
      setRedemption(null);
      void queryClient.invalidateQueries({ queryKey: queryKeys.checkout(billId) });
    },
    onError: (caught) => setVoucherError(messageOf(caught)),
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

  /* The bill BEFORE any discount but AFTER any voucher — what the discount is taken off, and
     the ceiling on what can be charged. All three figures come from the server; adding and
     subtracting them is not pricing, it is restating what the server already split apart. The
     stored discount and the total shown after saving are the server's own, never these.

     The voucher is subtracted because the counter types the FINAL charge, whatever else is on
     the bill. A 654 bill carrying a 480 voucher is a 174 bill as far as "make it 150" goes,
     and the server bounds it exactly this way. */
  const subtotal = bill.subtotalTime + bill.subtotalItems - bill.voucherAmount;
  const typed = chargeAmount.trim() === '' ? null : Number(chargeAmount);
  const preview =
    typed === null || Number.isNaN(typed) || typed < 0 || typed > subtotal
      ? null
      : subtotal - typed;

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

        {/* Knocking money off the whole bill, beside the control that charges less time. Two
            controls and not one because they do different things: that one reaches table time,
            this one reaches everything on the bill. A bill can carry both. */}
        {!blocked ? (
          <div className="mt-6 border-t border-border pt-4">
            {discounting ? (
              <form
                className="flex flex-col gap-4"
                onSubmit={(event) => {
                  event.preventDefault();
                  if (chargeAmount.trim() === '' || discountReason.trim() === '') return;
                  discount.mutate({
                    charge: Number(chargeAmount),
                    reason: discountReason.trim(),
                  });
                }}
              >
                <Field
                  label="Amount to charge"
                  type="number"
                  min="0"
                  step="0.01"
                  max={subtotal}
                  inputMode="decimal"
                  value={chargeAmount}
                  data-autofocus
                  hint={`This bill comes to ${formatMoney(subtotal)}. Type what you are actually charging — the discount is worked out from it.`}
                  onChange={(event) => setChargeAmount(event.target.value)}
                />
                {/* A PREVIEW, and labelled as one. The figure that gets stored, and the total
                    shown once this is saved, are the server's — this only spares the operator
                    doing the subtraction in their head while the customer waits. */}
                {preview !== null ? (
                  <p className="text-label text-text-dim">
                    Preview — that takes off{' '}
                    <strong className="text-danger">{formatMoney(preview)}</strong>. The saved
                    figure comes back from the server.
                  </p>
                ) : null}
                <Field
                  label="Why"
                  value={discountReason}
                  placeholder="Who agreed it, and what for"
                  onChange={(event) => setDiscountReason(event.target.value)}
                />
                {/* Said once, plainly, because it is the thing that surprises people: the
                    discount is pesos, not a percentage, and it does not follow the bill up. */}
                <p className="text-label text-text-dim">
                  A fixed amount. Add something to the bill afterwards and the total goes up —
                  the discount stays where you put it.
                </p>
                {discountError ? <Banner tone="danger">{discountError}</Banner> : null}
                <div className="flex justify-end gap-3">
                  <Button type="button" variant="secondary" onClick={() => setDiscounting(false)}>
                    Cancel
                  </Button>
                  <Button
                    type="submit"
                    pending={discount.isPending}
                    disabled={chargeAmount.trim() === '' || discountReason.trim() === ''}
                  >
                    Charge {chargeAmount === '' ? '—' : formatMoney(Number(chargeAmount))}
                  </Button>
                </div>
              </form>
            ) : (
              <div className="flex items-center justify-between gap-3">
                <span className="text-label uppercase text-text-dim">
                  {bill.discountAmount > 0
                    ? `${formatMoney(bill.discountAmount)} off · ${bill.discountByUsername ?? 'unknown'}`
                    : 'Whole bill'}
                </span>
                <div className="flex gap-3">
                  {bill.discountAmount > 0 ? (
                    <Button
                      variant="secondary"
                      pending={removeDiscount.isPending}
                      onClick={() => {
                        setDiscountError(null);
                        removeDiscount.mutate();
                      }}
                    >
                      Remove discount
                    </Button>
                  ) : null}
                  <Button
                    variant="secondary"
                    onClick={() => {
                      setDiscountError(null);
                      setChargeAmount(String(bill.totalAmount));
                      setDiscountReason('');
                      setDiscounting(true);
                    }}
                  >
                    Adjust total
                  </Button>
                </div>
              </div>
            )}
            {!discounting && discountError ? (
              <Banner tone="danger">{discountError}</Banner>
            ) : null}
          </div>
        ) : null}

        {/* Free table time won as a prize, produced at the counter when it is time to pay.
            A third control, beside the two above, because it does a third thing: it covers a
            measured quantity of TIME at the rate that time was billed at, and reaches nothing
            else on the bill. */}
        {!blocked ? (
          <div className="mt-6 border-t border-border pt-4">
            {redeeming ? (
              <form
                className="flex flex-col gap-4"
                onSubmit={(event) => {
                  event.preventDefault();
                  if (voucherCode.trim() === '') return;
                  applyVoucher.mutate(voucherCode.trim());
                }}
              >
                <Field
                  label="Voucher code"
                  value={voucherCode}
                  data-autofocus
                  autoComplete="off"
                  spellCheck={false}
                  placeholder="SB-7K4-M2Q"
                  className="uppercase tracking-widest"
                  hint="Read it off the customer's screen. Spaces and dashes do not matter, and neither does case."
                  /* Uppercased as typed, because that is what the customer is looking at. The
                     server normalises anyway — this is so the two agree on screen while the
                     cashier checks character by character in a dark room. */
                  onChange={(event) => setVoucherCode(event.target.value.toUpperCase())}
                />
                {voucherError ? <Banner tone="danger">{voucherError}</Banner> : null}
                <div className="flex justify-end gap-3">
                  <Button
                    type="button"
                    variant="secondary"
                    onClick={() => {
                      setRedeeming(false);
                      setVoucherError(null);
                    }}
                  >
                    Cancel
                  </Button>
                  <Button
                    type="submit"
                    pending={applyVoucher.isPending}
                    disabled={voucherCode.trim() === ''}
                  >
                    Apply voucher
                  </Button>
                </div>
              </form>
            ) : (
              <div className="flex items-center justify-between gap-3">
                <span className="text-label uppercase text-text-dim">
                  {bill.voucherAmount > 0
                    ? `${formatMoney(bill.voucherAmount)} covered · ${bill.voucherCode ?? 'voucher'}`
                    : 'Voucher'}
                </span>
                <div className="flex gap-3">
                  {bill.voucherAmount > 0 ? (
                    <Button
                      variant="secondary"
                      pending={removeVoucher.isPending}
                      onClick={() => {
                        setVoucherError(null);
                        removeVoucher.mutate();
                      }}
                    >
                      Remove voucher
                    </Button>
                  ) : (
                    <Button
                      variant="secondary"
                      onClick={() => {
                        setVoucherError(null);
                        setVoucherCode('');
                        setRedeeming(true);
                      }}
                    >
                      Apply voucher
                    </Button>
                  )}
                </div>
              </div>
            )}

            {/* THE THING THE CASHIER HAS TO SAY OUT LOUD, at the moment it becomes true.
                Unused minutes are forfeited — no change, no residual balance, the code is
                spent — and the customer finding that out later, from a receipt, is how an
                argument starts at the counter of a hall this size. Every figure here is the
                server's; the browser computes none of it. */}
            {!redeeming && redemption ? (
              <Banner tone={redemption.minutesForfeited > 0 ? 'warning' : 'info'}>
                {redemption.code} covered {formatMinutes(redemption.minutesCovered)} of table time
                — {formatMoney(redemption.voucherAmount)} off.
                {redemption.minutesForfeited > 0
                  ? ` The remaining ${redemption.minutesForfeited} min of the voucher are forfeited: there is no change and no balance left on it. Tell the customer the code is now spent.`
                  : ' The code is now spent.'}
              </Banner>
            ) : null}

            {!redeeming && voucherError ? (
              <Banner tone="danger">{voucherError}</Banner>
            ) : null}
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
            <p className="text-label uppercase text-text-dim">Not paying tonight?</p>
            <p className="mt-1 text-body text-text-dim">
              Record who owes it and collect later.
            </p>
            {/* Styled as a button, not a link. §4 is about WHERE a destructive control sits,
                not about denying it affordance — and this is not destructive anyway: it is a
                different way of finishing the sale, which is why it is secondary rather than
                danger. A legitimate action the counter takes deliberately should look like one.

                type="button" is explicit because Button spreads ...rest without defaulting it,
                so a bare one is type="submit". Harmless today — nothing here is inside a form —
                and a trap the day somebody wraps this column in one. */}
            <Button
              type="button"
              variant="secondary"
              className="mt-3 w-full"
              onClick={() => setLeavingUnpaid(true)}
            >
              Leave unpaid
            </Button>
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
