import type { Ref } from 'react';
import type { Receipt } from '@/api/types';
import { Card } from '@/components/Card';
import { formatDateTime } from '@/lib/datetime';
import { groupLines } from '@/lib/billLines';
import { formatMoney } from '@/lib/money';

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

/** The stored customer-facing snapshot, shared by Sales and post-checkout receipts. */
export function ReceiptDocument({ receipt, cardRef }: { receipt: Receipt; cardRef?: Ref<HTMLDivElement> }) {
  const payload = receipt.payload;
  const lines = Array.isArray(payload.lines) ? (payload.lines as PayloadLine[]) : [];
  const issuedUnpaid = text(payload, 'status') === 'UNSETTLED';
  const groups = groupPayloadLines(lines);
  return (
      <Card ref={cardRef} className="receipt-document relative print:border-0">
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
            Receipt #{receipt.receiptNo} · {formatDateTime(receipt.issuedAt)}
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
          {/* Written into the payload only when there was one, so an ordinary chit reads
              exactly as it always has. Read through the same defensive helper as everything
              else here: a chit issued before this existed simply has no such key.

              Shown as the subtraction rather than as a lone figure — what it came to, what
              came off, and the total below — because the 54 pesos the customer was given is
              the part of the receipt they will look for. */}
          {/* The "Bill" line appears once, whichever reductions follow it. A chit can carry a
              discount, a voucher, or both, and repeating the subtotal above each one would
              turn a receipt into a worksheet. */}
          {money(payload, 'discountAmount') !== null || money(payload, 'voucherAmount') !== null ? (
            <div className="flex justify-between gap-3">
              <dt className="text-label uppercase text-text-dim">Bill</dt>
              <dd className="tabular text-body text-text-dim">
                {formatMoney(
                  (money(payload, 'subtotalTime') ?? 0) + (money(payload, 'subtotalItems') ?? 0),
                )}
              </dd>
            </div>
          ) : null}
          {money(payload, 'discountAmount') !== null ? (
            <div className="mb-2 flex justify-between gap-3">
              <dt className="text-label uppercase text-text-dim">
                Discount
                {text(payload, 'discountReason') ? (
                  <span className="block normal-case text-label text-text-dim">
                    {text(payload, 'discountReason')}
                  </span>
                ) : null}
              </dt>
              <dd className="tabular text-body text-danger">
                −{formatMoney(money(payload, 'discountAmount'))}
              </dd>
            </div>
          ) : null}
          {/* The prize, named on the document the winner takes away. The code is printed
              because it is theirs and because it is what makes the chit checkable against the
              giveaway later; the hours are printed because hours are what was advertised. */}
          {money(payload, 'voucherAmount') !== null ? (
            <div className="mb-2 flex justify-between gap-3">
              <dt className="text-label uppercase text-text-dim">
                Voucher
                <span className="block normal-case text-label text-text-dim">
                  {text(payload, 'voucherCode') ?? 'code'}
                  {text(payload, 'voucherHoursCovered')
                    ? ` · ${text(payload, 'voucherHoursCovered')} h of table time`
                    : ''}
                </span>
              </dt>
              <dd className="tabular text-body text-danger">
                −{formatMoney(money(payload, 'voucherAmount'))}
              </dd>
            </div>
          ) : null}
          <div className="flex items-baseline justify-between gap-3">
            <dt className="text-heading text-text">Total</dt>
            <dd className="figure-amount text-amount">
              {formatMoney(money(payload, 'totalAmount') ?? money(payload, 'total'))}
            </dd>
          </div>
          <div className="mt-2 flex justify-between gap-3">
            <dt className="text-label uppercase text-text-dim">Method</dt>
            {/* "Unpaid" rather than an em-dash when the chit was issued against a debt. The
                payload names its own status, so this is read from a field rather than inferred
                from a missing method key — the inference that breaks the first time the payload
                gains or loses an unrelated column. */}
            <dd className="text-body text-text">
              {/* Three states, and the third one is new: paid by some method, issued against a
                  debt, or nothing to pay at all. `noCharge` is written from the FIGURE rather
                  than from the absence of a method, so a chit reading 0.00 says why instead of
                  showing a dash the customer has to ask about. */}
              {text(payload, 'method')
                ?? (payload.noCharge === true
                  ? 'Nothing to pay'
                  : issuedUnpaid ? 'Unpaid' : '—')}
            </dd>
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

        {/*
          * What happened AFTER the document above was issued.
          *
          * A separate block, below the receipt's own rule, and never folded into the figures
          * above it. The payload is append-only in the database and is the record of the night:
          * it said "unpaid" because the bill was unpaid, and rewriting it to say otherwise
          * would make the receipt claim something that was not true when it was handed over.
          * This answers the different question the next member of staff actually has — does he
          * still owe this? — and answers it from the payment row.
          */}
        {receipt.settlement ? (
          <div className="mt-4 border-t border-border pt-4">
            <p className="text-label uppercase text-text-dim">Settled later</p>
            <div className="mt-1 flex items-baseline justify-between gap-3">
              <span className="text-body text-text">
                {receipt.settlement.method} on{' '}
                {formatDateTime(receipt.settlement.takenAt)}
                {receipt.settlement.takenByUsername
                  ? `, taken by ${receipt.settlement.takenByUsername}`
                  : ''}
              </span>
              <span className="tabular text-body text-amount">
                {formatMoney(receipt.settlement.amount)}
              </span>
            </div>
          </div>
        ) : issuedUnpaid ? (
          <p className="mt-4 border-t border-border pt-4 text-body text-amount">
            Still unpaid. This was left owed on the night and has not been collected.
          </p>
        ) : null}

        <p
          data-receipt-chrome
          className="mt-6 border-t border-border pt-4 text-center text-label uppercase text-text-dim"
        >
          Not an official receipt
        </p>
      </Card>

  );
}
