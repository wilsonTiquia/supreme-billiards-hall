import type { Bill } from '@/api/types';
import { splitBillLines } from '@/lib/billLines';
import { formatMoney } from '@/lib/money';
import { formatMinutes } from '@/lib/datetime';

/**
 * The receipt-style read of the bill. Identical items read as one line; voided lines stay,
 * struck through, individually and with their reason, excluded from the totals — because what
 * was rung up is part of what happened at that table.
 */
export function BillSummary({ bill }: { bill: Bill }) {
  const { timeLines, itemGroups, voidedItems } = splitBillLines(bill.lines);

  return (
    <div>
      {timeLines.length > 0 ? (
        <section className="mb-4">
          <h3 className="text-label uppercase text-text-dim">Table time</h3>
          <ul className="mt-2 divide-y divide-border">
            {timeLines.map((line) => (
              <li key={line.id} className="flex justify-between gap-3 py-2">
                {/* The rate is already in the description; minutes are never re-multiplied. */}
                <span className="text-body text-text">{line.description}</span>
                <span className="tabular text-body text-text">{formatMoney(line.lineTotal)}</span>
              </li>
            ))}
          </ul>
        </section>
      ) : null}

      {itemGroups.length > 0 ? (
        <section className="mb-4">
          <h3 className="text-label uppercase text-text-dim">Items</h3>
          <ul className="mt-2 divide-y divide-border">
            {itemGroups.map((group) => (
              <li key={group.key} className="flex justify-between gap-3 py-2">
                <span className="text-body text-text">
                  {group.quantity} × {group.description}
                </span>
                <span className="tabular text-body text-text">
                  {formatMoney(group.lineTotal)}
                </span>
              </li>
            ))}
          </ul>
        </section>
      ) : null}

      {/* Never folded into a quantity: the operator is reading this out to a customer, and a
          void is the part of the bill most likely to be asked about. */}
      {voidedItems.length > 0 ? (
        <section className="mb-4">
          <h3 className="text-label uppercase text-text-dim">Voided</h3>
          <ul className="mt-2 divide-y divide-border">
            {voidedItems.map((line) => (
              <li key={line.id} className="flex justify-between gap-3 py-2">
                <span className="opacity-60">
                  <span className="text-body text-text line-through">
                    {line.quantity} × {line.description}
                  </span>
                  <span className="block text-label text-danger">
                    Voided — {line.voidReason}
                  </span>
                </span>
                <span className="tabular text-body text-text-dim line-through">
                  {formatMoney(line.lineTotal)}
                </span>
              </li>
            ))}
          </ul>
        </section>
      ) : null}

      <dl className="border-t border-border pt-3">
        <div className="flex justify-between gap-3">
          <dt className="text-label uppercase text-text-dim">Table time</dt>
          <dd className="tabular text-body text-text">{formatMoney(bill.subtotalTime)}</dd>
        </div>
        <div className="flex justify-between gap-3">
          <dt className="text-label uppercase text-text-dim">Items</dt>
          <dd className="tabular text-body text-text">{formatMoney(bill.subtotalItems)}</dd>
        </div>
        {/* Above the total and below the subtotals, which is where the subtraction actually
            happens — the operator reads the two figures out, then the discount, then what is
            owed. The reason sits under it for the same reason a void's does: this is the line
            the customer is most likely to ask about. */}
        {bill.discountAmount > 0 ? (
          <div className="mt-1 flex justify-between gap-3">
            <dt className="text-label uppercase text-text-dim">
              Discount
              {bill.discountReason ? (
                <span className="block normal-case text-label text-text-dim">
                  {bill.discountReason}
                </span>
              ) : null}
            </dt>
            <dd className="tabular text-body text-danger">
              −{formatMoney(bill.discountAmount)}
            </dd>
          </div>
        ) : null}
        {/* Beneath the discount and above the total, in the order the subtraction happens.
            The minutes are stated as well as the pesos, because a voucher is a quantity of
            TIME and "2 hours" is what the customer won — the pesos are only what those hours
            were worth on this table. */}
        {bill.voucherAmount > 0 ? (
          <div className="mt-1 flex justify-between gap-3">
            <dt className="text-label uppercase text-text-dim">
              Voucher
              <span className="block normal-case text-label text-text-dim">
                {bill.voucherCode}
                {bill.voucherMinutesCovered != null
                  ? ` · ${formatMinutes(bill.voucherMinutesCovered)} covered`
                  : ''}
              </span>
            </dt>
            <dd className="tabular text-body text-danger">
              −{formatMoney(bill.voucherAmount)}
            </dd>
          </div>
        ) : null}
        <div className="mt-3 flex items-baseline justify-between gap-3 border-t border-border pt-3">
          <dt className="text-heading text-text">Total</dt>
          <dd className="figure-amount text-amount">{formatMoney(bill.totalAmount)}</dd>
        </div>
      </dl>
    </div>
  );
}
