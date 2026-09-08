import { useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { fetchCurrentBusinessDay } from '@/api/endpoints/businessDay';
import { queryKeys } from '@/api/queryKeys';
import type { UnsettledBill } from '@/api/types';
import { formatMoney } from '@/lib/money';
import { formatBusinessDate, formatTime } from '@/lib/datetime';

/**
 * Bills whose session has closed but which nobody checked out. The table reads free again, so
 * without this strip there is nothing anywhere pointing at them — and that is a lost sale,
 * not a tidiness problem.
 *
 * These are MISTAKES, and the wording says so. A bill deliberately left unpaid — the regular
 * who settles next month — is a different thing with a name against it, and it lives on
 * /unsettled. Showing the two under one heading would teach staff that "unpaid" sometimes
 * means "someone forgot" and sometimes means "that is fine", which is the end of this strip
 * being worth looking at.
 *
 * It is not scoped to tonight. Closing a day checks for open sessions, not unpaid bills, so
 * one of these survives the close; if the list only showed today's, it would disappear at the
 * date roll and the money would never be collected. An older bill is marked as such rather
 * than quietly sitting among tonight's.
 */
export function UnsettledStrip({ bills }: { bills: UnsettledBill[] }) {
  const navigate = useNavigate();
  const day = useQuery({
    queryKey: queryKeys.businessDayCurrent,
    queryFn: fetchCurrentBusinessDay,
    staleTime: 60_000,
  });

  if (bills.length === 0) return null;
  const today = day.data?.businessDate;
  const older = bills.filter((bill) => today && bill.businessDate !== today).length;

  return (
    <div className="mb-6 rounded-xl border border-gold bg-surface p-4">
      <div className="mb-3 flex flex-wrap items-baseline justify-between gap-4">
        <h2 className="text-label uppercase text-amount">
          Not checked out — {bills.length} {bills.length === 1 ? 'bill' : 'bills'} nobody closed
        </h2>
        <span className="text-label text-text-dim">
          {older > 0
            ? `${older} carried over from an earlier night`
            : 'Session closed, payment never taken'}
        </span>
      </div>

      <ul className="flex flex-wrap gap-3">
        {bills.map((bill) => {
          const carriedOver = Boolean(today) && bill.businessDate !== today;
          return (
            <li key={bill.id}>
              <button
                type="button"
                onClick={() => navigate(`/checkout/${bill.id}`)}
                className={`hit flex flex-col items-start rounded-lg border bg-raised px-4 py-2 text-left hover:border-gold ${
                  carriedOver ? 'border-danger' : 'border-border'
                }`}
              >
                <span className="text-body text-text">
                  {bill.tableNames.length > 0 ? bill.tableNames.join(', ') : 'Quick sale'}
                </span>
                <span className="text-label text-text-dim">
                  {bill.customerTypeName}
                  {bill.sessionEndedAt ? ` · closed ${formatTime(bill.sessionEndedAt)}` : ''}
                </span>
                {/* Every row carries its date; an older one says so in danger colour, because
                    a bill from three nights ago is a different problem from tonight's. */}
                <span className={`text-label ${carriedOver ? 'text-danger' : 'text-text-dim'}`}>
                  {carriedOver ? `From ${formatBusinessDate(bill.businessDate)}` : 'Tonight'}
                </span>
                <span className="tabular mt-1 text-body text-amount">
                  {formatMoney(bill.totalAmount)}
                </span>
                {/* Who owes it. Three unpaid bills on Table 1 read identically without this,
                    which tells the owner nothing the next morning. The most recent note only —
                    the whole thread is on the bill. Free text from staff, rendered as text. */}
                {bill.latestNote ? (
                  <span className="mt-1 max-w-[16rem] truncate text-label text-text">
                    {bill.latestNote.body}
                  </span>
                ) : null}
              </button>
            </li>
          );
        })}
      </ul>
    </div>
  );
}
