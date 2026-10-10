import type { UnpaidBill } from '@/api/types';

/** The API stores a single staff note, not separate customer and promise-to-pay fields. */
export function DebtSummary({ bill }: { bill: UnpaidBill }) {
  const age = bill.daysOutstanding === 0
    ? 'Tonight'
    : `${bill.daysOutstanding} ${bill.daysOutstanding === 1 ? 'day' : 'days'}`;

  return (
    <div className="flex min-w-0 flex-wrap items-baseline gap-x-3 gap-y-1">
      <span className="min-w-0 break-words text-body font-semibold text-text [overflow-wrap:anywhere]">
        {bill.latestNote?.body ?? 'No name recorded'}
      </span>
      <span
        aria-label={`${bill.daysOutstanding} ${bill.daysOutstanding === 1 ? 'day' : 'days'} outstanding`}
        className={`shrink-0 rounded-full border px-2 py-0.5 text-label ${bill.daysOutstanding >= 14
          ? 'border-danger/40 bg-danger/10 text-danger'
          : 'border-border bg-raised text-text-dim'}`}
      >
        {age}
      </span>
    </div>
  );
}
