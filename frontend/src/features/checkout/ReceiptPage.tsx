import { useState } from 'react';
import { Link, useLocation, useParams } from 'react-router-dom';
import { useMutation, useQuery } from '@tanstack/react-query';
import { fetchReceipt, uploadPaymentPhoto } from '@/api/endpoints/bills';
import type { Payment } from '@/api/types';
import { queryKeys } from '@/api/queryKeys';
import { messageOf } from '@/api/errors';
import { useScreenTheme } from '@/app/useTheme';
import { formatDateTime } from '@/lib/datetime';
import { groupLines } from '@/lib/billLines';
import { formatMoney } from '@/lib/money';
import { Card } from '@/components/Card';
import { useAuth } from '@/auth/useAuth';
import { useBillMorph, type CardRect } from './useBillMorph';
import { Banner } from '@/components/Banner';
import { Spinner } from '@/components/Spinner';
import { Button } from '@/components/Button';

/** A line as it was written into the stored receipt payload. Free-form, so read defensively. */
interface PayloadLine {
  description?: string;
  quantity?: number;
  unitPrice?: number;
  lineTotal?: number;
}

/**
 * Grouping happens HERE, at render, and nowhere else. The stored jsonb payload keeps its
 * faithful line-by-line record — it is the legal document, and a receipt written last month
 * must render the same today. This only decides how those lines are laid out.
 *
 * Defensive about shape for the same reason the readers above are: an old payload may not
 * carry every field, and a receipt that fails to render is worse than one that reads plainly.
 */
function groupPayloadLines(lines: PayloadLine[]) {
  return groupLines(
    lines.map((line) => ({
      description: line.description ?? '—',
      unitPrice: line.unitPrice ?? 0,
      quantity: line.quantity ?? 1,
      lineTotal: line.lineTotal ?? 0,
    })),
  );
}

function money(payload: Record<string, unknown>, key: string): number | null {
  const value = payload[key];
  return typeof value === 'number' ? value : null;
}

function text(payload: Record<string, unknown>, key: string): string | null {
  const value = payload[key];
  return typeof value === 'string' ? value : null;
}

/**
 * The stored snapshot, rendered as it was written and never recomputed. It carries no cost,
 * so this screen is the same for both roles.
 */
/**
 * Where this receipt was opened from, so it can send you back there.
 *
 * Checking several receipts in a sitting is the normal case, not the exception: the owner
 * reconciling a night opens one, reads it, and wants the same list back on the same business
 * day and the same page. Landing on the floor every time makes that nine clicks instead of
 * three. Whoever links here says where "back" is; the floor is the honest default for a
 * receipt opened cold from a bookmark or after a refresh.
 */
interface ReceiptOrigin {
  label: string;
  to: string;
}

const FLOOR: ReceiptOrigin = { label: 'Back to the floor', to: '/floor' };

export function ReceiptPage() {
  useScreenTheme('pos');
  const { billId = '' } = useParams();
  const location = useLocation();
  const state = location.state as
    | { origin?: ReceiptOrigin; justPaid?: Payment; from?: CardRect | null }
    | null;
  const origin: ReceiptOrigin = state?.origin ?? FLOOR;

  /*
   * Set when checkout sent us here, which is the overwhelmingly common way to arrive.
   *
   * Read once at mount so a re-render cannot resurrect it, and used for the two things the
   * stored receipt payload cannot tell us: whether this payment was a replay of one another
   * till had already taken, and whether a digital payment still wants its confirmation photo.
   */
  const [justPaid] = useState<Payment | null>(() => state?.justPaid ?? null);
  // Where the bill card was standing. Read once: the morph belongs to arriving from a payment,
  // and must not replay because something else re-rendered.
  const [from] = useState<CardRect | null>(() => state?.from ?? null);
  // The flag rides on the session, so the counter reads it without an ADMIN-only request.
  const { user } = useAuth();

  // A callback ref, so the morph starts the instant the card mounts — which is after the
  // receipt has loaded, not on the page's first render.
  const [card, setCard] = useState<HTMLDivElement | null>(null);
  useBillMorph(
    card,
    from,
    // A replayed payment gets no morph: nothing became anything, another till took the money.
    user?.checkoutAnimation === true && justPaid !== null && !justPaid.replayed,
  );
  const [photoNote, setPhotoNote] = useState<string | null>(null);

  const attachPhoto = useMutation({
    mutationFn: (file: File) => uploadPaymentPhoto((justPaid as Payment).id, file),
    onSuccess: () => setPhotoNote('Photo attached.'),
    onError: (caught) => setPhotoNote(messageOf(caught)),
  });

  const receipt = useQuery({
    queryKey: queryKeys.receipt(billId),
    queryFn: () => fetchReceipt(billId),
    staleTime: Infinity,
  });

  if (receipt.isPending) {
    return (
      <div className="flex justify-center py-16">
        <Spinner label="Loading the receipt…" />
      </div>
    );
  }

  if (receipt.isError) {
    return (
      <Card className="mx-auto max-w-md">
        <Banner tone="danger">{messageOf(receipt.error)}</Banner>
        <Link
          to={origin.to}
          className="hit mt-4 inline-flex items-center text-body text-info underline"
        >
          {origin.label}
        </Link>
      </Card>
    );
  }

  const payload = receipt.data.payload;
  const lines = Array.isArray(payload.lines) ? (payload.lines as PayloadLine[]) : [];
  const groups = groupPayloadLines(lines);

  return (
    <div className="mx-auto max-w-md">
      <Card ref={setCard} className="relative print:border-0">
        {/* The tear. A receipt is a thing torn off a roll, and this is the moment the bill
            becomes one — so it draws outward from the middle of the top edge as the card
            lands. Decorative and never printed. */}
        <div
          data-receipt-tear
          aria-hidden
          className="receipt-perforation pointer-events-none absolute inset-x-0 top-1.5 h-[5px] origin-center print:hidden"
        />
        <header data-receipt-chrome className="border-b border-border pb-4 text-center">
          <div className="text-heading text-text">SUPREME BILLIARD HALL</div>
          <div className="text-label uppercase text-text-dim">Parañaque</div>
          <div className="mt-2 text-label text-text-dim">
            Receipt #{receipt.data.receiptNo} · {formatDateTime(receipt.data.issuedAt)}
          </div>
        </header>

        <ul className="my-4 divide-y divide-border">
          {groups.map((group) => (
            <li key={group.key} className="flex justify-between gap-3 py-2">
              <span className="text-body text-text">
                {group.quantity !== 1 ? `${group.quantity} × ` : ''}
                {group.description}
              </span>
              <span className="tabular text-body text-text">
                {formatMoney(group.lineTotal)}
              </span>
            </li>
          ))}
        </ul>

        <dl className="border-t border-border pt-3">
          <div className="flex items-baseline justify-between gap-3">
            <dt className="text-heading text-text">Total</dt>
            <dd className="figure-amount text-amount">
              {formatMoney(money(payload, 'totalAmount') ?? money(payload, 'total'))}
            </dd>
          </div>
          <div className="mt-2 flex justify-between gap-3">
            <dt className="text-label uppercase text-text-dim">Method</dt>
            <dd className="text-body text-text">{text(payload, 'method') ?? '—'}</dd>
          </div>
          {money(payload, 'tendered') !== null ? (
            <>
              <div className="flex justify-between gap-3">
                <dt className="text-label uppercase text-text-dim">Tendered</dt>
                <dd className="tabular text-body text-text">
                  {formatMoney(money(payload, 'tendered'))}
                </dd>
              </div>
              <div className="flex justify-between gap-3">
                <dt className="text-label uppercase text-text-dim">Change</dt>
                <dd className="tabular text-body text-text">
                  {formatMoney(money(payload, 'changeGiven'))}
                </dd>
              </div>
            </>
          ) : null}
          {text(payload, 'referenceNo') ? (
            <div className="flex justify-between gap-3">
              <dt className="text-label uppercase text-text-dim">Reference</dt>
              <dd className="text-body text-text">{text(payload, 'referenceNo')}</dd>
            </div>
          ) : null}
        </dl>

        {/* Only meaningful on the way in from checkout, and only for a payment that was
            already taken elsewhere. Silence on a normal payment is correct. */}
        {justPaid?.replayed ? (
          <p className="mt-4 text-label text-amount print:hidden">
            Already recorded — another till took this payment first. The money is collected.
          </p>
        ) : null}
        {justPaid?.duplicateReferenceOverridden ? (
          <p className="mt-2 text-label text-amount print:hidden">
            Recorded against a reference already used, on your say-so.
          </p>
        ) : null}

        <p
          data-receipt-chrome
          className="mt-6 border-t border-border pt-4 text-center text-label uppercase text-text-dim"
        >
          Not an official receipt
        </p>
      </Card>

      {/* Digital payments only, and only straight after taking one — the confirmation photo is
          evidence attached at the moment of payment, not something to go back and add. It sits
          below the receipt so it never delays anyone: the common path is the button row. */}
      {justPaid && justPaid.method !== 'CASH' ? (
        <Card className="mt-4 print:hidden">
          <label className="text-label uppercase text-text-dim" htmlFor="payment-photo">
            Attach a photo of the confirmation (optional)
          </label>
          <input
            id="payment-photo"
            type="file"
            accept="image/*"
            disabled={attachPhoto.isPending || photoNote === 'Photo attached.'}
            onChange={(event) => {
              const file = event.target.files?.[0];
              if (file) attachPhoto.mutate(file);
            }}
            className="mt-2 block w-full text-body text-text-dim"
          />
          {photoNote ? <p className="mt-2 text-label text-text-dim">{photoNote}</p> : null}
        </Card>
      ) : null}

      {/*
        One press for the common path.
        
        Going back to the floor is what happens ninety-nine times out of a hundred, so it is the
        primary and it is first. Print is a real need but a rare one, and it reads as the
        secondary it is. The third button that used to sit here — a second route to the floor —
        was a choice between equals dressed up as a convenience.
      */}
      <div data-receipt-chrome className="mt-4 flex justify-center gap-3 print:hidden">
        <Link to={origin.to} className="inline-flex">
          <Button>{origin.label}</Button>
        </Link>
        <Button variant="secondary" onClick={() => window.print()}>
          Print
        </Button>
      </div>
    </div>
  );
}
