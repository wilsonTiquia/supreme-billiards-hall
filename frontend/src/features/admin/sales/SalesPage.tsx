import { Link, useSearchParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { fetchSettledBills } from '@/api/endpoints/bills';
import { fetchCurrentBusinessDay } from '@/api/endpoints/businessDay';
import { queryKeys } from '@/api/queryKeys';
import type { BillSummary } from '@/api/types';
import { messageOf } from '@/api/errors';
import { AdminPage } from '../AdminPage';
import { Card } from '@/components/Card';
import { Button } from '@/components/Button';
import { Field } from '@/components/Field';
import { Spinner } from '@/components/Spinner';
import { formatBusinessDate, formatTime } from '@/lib/datetime';
import { formatMoney } from '@/lib/money';

const METHOD_LABEL = { CASH: 'Cash', GCASH: 'GCash', MAYA: 'Maya' } as const;

/** No method means no payment row: the bill came to nothing and was closed without one. */
function methodLabel(method: BillSummary['method']): string {
  return method ? METHOD_LABEL[method] : 'Nothing to pay';
}

/*
 * The last sentence is the one that earns its place.
 *
 * The dashboard's gross counts bills left unpaid, and this list cannot show them: they have no
 * payment and no settlement, which is the whole reason they are on a different screen. So the
 * two disagree by design on any night that had one — and nothing said so, which left the owner
 * adding up receipts by hand against a gross that would not match and no way to tell which of
 * the two screens was lying. Neither figure changes; the screen just admits the gap.
 */
const INTRO =
  "Every settled sale for one business day, newest first. Open one to see the receipt exactly " +
  'as it was stored. A bill left unpaid counts in the night’s gross on the dashboard but has ' +
  'no payment, so it is not listed here — on a night with one, this list will not add up to gross.';

/**
 * A night's takings, receipt by receipt. The owner's way to answer "what was receipt 47?"
 * without a database client.
 *
 * No cost and no profit here even though the screen is admin-only: this is for finding a
 * receipt, and margin belongs on the dashboard. Clicking a row opens the stored snapshot.
 */
export function SalesPage() {
  /* Day and page live in the URL, so a receipt can link straight back to this exact list and
     a reload does not throw the owner back to tonight halfway through a reconciliation. */
  const [params, setParams] = useSearchParams();
  const date = params.get('date');
  const page = Number(params.get('page') ?? 0);
  const setDate = (next: string) => setParams({ date: next, page: '0' });
  const setPage = (next: number) =>
    setParams({ ...(date ? { date } : {}), page: String(next) });

  // Defaults to the current business day, which is the server's — at 02:00 the night still
  // belongs to the day before, and this screen must not disagree with the rest of the app.
  const today = useQuery({
    queryKey: queryKeys.businessDayCurrent,
    queryFn: fetchCurrentBusinessDay,
    staleTime: 60_000,
  });

  const businessDate = date ?? today.data?.businessDate ?? null;

  const bills = useQuery({
    queryKey: queryKeys.settledBills(businessDate ?? '', page),
    queryFn: () => fetchSettledBills(businessDate as string, page),
    enabled: Boolean(businessDate),
  });

  const rows = bills.data?.content ?? [];

  return (
    <AdminPage
      title="Sales"
      intro={INTRO}
      error={bills.isError ? messageOf(bills.error) : null}
    >
      <Card className="mb-4">
        <div className="flex flex-wrap items-end gap-4">
          <Field
            label="Business day"
            type="date"
            value={businessDate ?? ''}
            className="max-w-[16rem]"
            onChange={(event) => setDate(event.target.value)}
          />
          {businessDate ? (
            <p className="pb-3 text-body text-text-dim">{formatBusinessDate(businessDate)}</p>
          ) : null}
        </div>
      </Card>

      <Card className="overflow-x-auto">
        {!businessDate || bills.isPending ? (
          <div className="py-10 text-center">
            <Spinner label="Loading sales…" />
          </div>
        ) : rows.length === 0 ? (
          <p className="py-10 text-center text-body text-text-dim">
            Nothing was settled on this business day.
          </p>
        ) : (
          <>
            {/*
              Below md each sale is a block instead of a row.
              
              A seven-column table in 294px is a horizontal scrollbar for every single receipt,
              and the owner reads this from home on a phone. Same data, same order, same link —
              laid out down the screen rather than across it. The table returns at md, where
              there is width for it and the columns are the faster read.
            */}
            <ul className="flex flex-col gap-3 md:hidden">
              {rows.map((bill) => (
                <li key={bill.id} className="rounded-lg border border-border p-4">
                  <div className="flex items-baseline justify-between gap-3">
                    <span className="tabular text-heading text-text">#{bill.receiptNo}</span>
                    <span className="tabular text-amount text-amount">
                      {formatMoney(bill.totalAmount)}
                    </span>
                  </div>
                  <p className="mt-1 text-body text-text-dim">
                    {formatTime(bill.closedAt)} · {bill.quickSale ? 'Quick sale' : 'Table'} ·{' '}
                    {methodLabel(bill.method)}
                  </p>
                  <p className="text-body text-text-dim">
                    Taken by {bill.takenByUsername ?? '—'}
                  </p>
                  <Link
                    to={`/receipt/${bill.id}`}
                    state={{
                      origin: {
                        label: 'Back to sales',
                        to: `/admin/sales?date=${businessDate ?? ''}&page=${page}`,
                      },
                    }}
                    className="mt-3 inline-flex"
                  >
                    <Button variant="secondary">Receipt</Button>
                  </Link>
                </li>
              ))}
            </ul>

            {/* Capped: a receipts table stretched to 1600px puts the number and its total a
                hand's width apart. The card keeps the page's width; the table keeps a measure. */}
            <table className="hidden w-full min-w-[720px] max-w-5xl text-left md:table">
            <thead>
              <tr className="border-b border-border text-label uppercase text-text-dim">
                <th className="py-2">Receipt</th>
                <th className="py-2">Time</th>
                <th className="py-2">Kind</th>
                <th className="py-2">Method</th>
                <th className="py-2">Taken by</th>
                <th className="py-2 text-right">Total</th>
                <th className="py-2" />
              </tr>
            </thead>
            <tbody>
              {rows.map((bill) => (
                <tr key={bill.id} className="border-b border-border transition hover:bg-raised">
                  <td className="tabular py-4 pr-4 text-heading text-text">#{bill.receiptNo}</td>
                  <td className="tabular py-4 pr-4 text-body text-text">{formatTime(bill.closedAt)}</td>
                  <td className="py-4 pr-4 text-body text-text-dim">
                    {bill.quickSale ? 'Quick sale' : 'Table'}
                  </td>
                  <td className="py-4 pr-4 text-body text-text-dim">{methodLabel(bill.method)}</td>
                  <td className="py-4 pr-4 text-body text-text-dim">{bill.takenByUsername ?? '—'}</td>
                  <td className="tabular py-4 pl-4 text-right text-amount text-amount">
                    {formatMoney(bill.totalAmount)}
                  </td>
                  <td className="py-4 text-right">
                    {/* Carries the day and the page with it, so returning lands on the list
                        you left rather than on the floor. Checking several receipts in a
                        sitting is the normal case. */}
                    <Link
                      to={`/receipt/${bill.id}`}
                      state={{
                        origin: {
                          label: 'Back to sales',
                          to: `/admin/sales?date=${businessDate ?? ''}&page=${page}`,
                        },
                      }}
                      className="inline-flex"
                    >
                      <Button variant="secondary">Receipt</Button>
                    </Link>
                  </td>
                </tr>
              ))}
            </tbody>
            </table>
          </>
        )}

        {bills.data && bills.data.totalPages > 1 ? (
          <div className="mt-4 flex items-center justify-between gap-3 border-t border-border pt-4">
            <Button
              variant="secondary"
              disabled={page === 0}
              onClick={() => setPage(page - 1)}
            >
              Newer
            </Button>
            <span className="text-label uppercase text-text-dim">
              Page {bills.data.page + 1} of {bills.data.totalPages} · {bills.data.totalElements}{' '}
              sales
            </span>
            <Button
              variant="secondary"
              disabled={page >= bills.data.totalPages - 1}
              onClick={() => setPage(page + 1)}
            >
              Older
            </Button>
          </div>
        ) : null}
      </Card>
    </AdminPage>
  );
}
