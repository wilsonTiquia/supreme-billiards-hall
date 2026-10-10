import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { fetchUnpaidBills } from '@/api/endpoints/bills';
import { queryKeys } from '@/api/queryKeys';
import { messageOf } from '@/api/errors';
import { useScreenTheme } from '@/app/useTheme';
import { formatMoney } from '@/lib/money';
import { formatBusinessDate } from '@/lib/datetime';
import { Card } from '@/components/Card';
import { Field } from '@/components/Field';
import { Banner } from '@/components/Banner';
import { ButtonLink } from '@/components/Button';
import { Spinner } from '@/components/Spinner';
import type { UnpaidBill } from '@/api/types';
import { DebtSummary } from './DebtSummary';

/**
 * Who owes the hall money.
 *
 * Every bill here is a DECISION: a regular played, a member of staff put a name against it, and
 * the sale was recorded without the money. That is a different thing from the floor's "not
 * checked out" strip, which is a MISTAKE nobody has fixed yet, and the two lists are kept apart
 * on purpose — collapsing them would teach the counter to ignore both.
 *
 * Not scoped to tonight. One of these can sit for a month, and a list that reset at the date
 * roll would mean the money was simply never collected.
 *
 * Reachable by both roles: collecting a debt is counter work, and nothing here is a cost or a
 * profit figure.
 */
export function UnsettledPage() {
  useScreenTheme('pos');
  const [nameFilter, setNameFilter] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');

  const unpaid = useQuery({
    queryKey: queryKeys.unpaidBills,
    queryFn: fetchUnpaidBills,
    refetchInterval: 60_000,
  });

  const bills = useMemo(() => unpaid.data ?? [], [unpaid.data]);

  /* Filtering happens here rather than on the server: the whole list is what the hall is owed,
     and it is a page of rows, not a table of thousands. Matching on the note text is the point
     — "has Jun paid yet" is the question this screen is opened to answer. */
  const shown = useMemo(() => {
    const needle = nameFilter.trim().toLowerCase();
    return bills.filter((bill) => {
      if (needle !== '' && !(bill.latestNote?.body ?? '').toLowerCase().includes(needle)) {
        return false;
      }
      if (from !== '' && bill.businessDate < from) return false;
      if (to !== '' && bill.businessDate > to) return false;
      return true;
    });
  }, [bills, nameFilter, from, to]);

  // First-seen date order and each group's row order follow the server's newest-first list.
  // Group by the stored played date, never by unsettledAt or the browser's calendar.
  const groups = useMemo(() => {
    const byDate = new Map<string, UnpaidBill[]>();
    for (const bill of shown) {
      const group = byDate.get(bill.businessDate) ?? [];
      group.push(bill);
      byDate.set(bill.businessDate, group);
    }
    return [...byDate];
  }, [shown]);

  /* Summed from the very rows listed beneath it, so the headline and the list cannot disagree.
     This is a display total over amounts the server computed, not money calculated in the
     browser: every figure added here came from the API already priced. */
  const outstanding = shown.reduce((sum, bill) => sum + bill.totalAmount, 0);
  const filtered = shown.length !== bills.length;

  if (unpaid.isPending) {
    return (
      <div className="flex justify-center py-16">
        <Spinner label="Loading what is owed…" />
      </div>
    );
  }

  if (unpaid.isError) {
    return (
      <Card className="max-w-xl">
        <Banner tone="danger">{messageOf(unpaid.error)}</Banner>
      </Card>
    );
  }

  return (
    <div className="mx-auto flex max-w-5xl flex-col gap-6">
      <Card>
        <p className="text-label uppercase text-text-dim">
          {filtered ? 'Outstanding — matching these filters' : 'Outstanding'}
        </p>
        <p className="tabular mt-1 text-display text-amount">{formatMoney(outstanding)}</p>
        <p className="mt-2 text-body text-text-dim">
          {shown.length === 0
            ? (bills.length === 0 ? 'Nothing is owed.' : 'No unpaid bill matches those filters.')
            : `${shown.length} ${shown.length === 1 ? 'bill' : 'bills'} waiting to be collected.`}
        </p>
      </Card>

      {bills.length > 0 ? (
        <Card>
          <div className="grid gap-4 sm:grid-cols-3">
            <Field
              label="Who"
              value={nameFilter}
              placeholder="Search the notes"
              onChange={(event) => setNameFilter(event.target.value)}
            />
            <Field
              label="Played from"
              type="date"
              value={from}
              onChange={(event) => setFrom(event.target.value)}
            />
            <Field
              label="Played to"
              type="date"
              value={to}
              onChange={(event) => setTo(event.target.value)}
            />
          </div>
        </Card>
      ) : null}

      <Card>
        <h2 className="text-heading text-text">Unpaid</h2>
        {shown.length === 0 ? (
          <p className="mt-3 text-body text-text-dim">
            {bills.length === 0
              ? 'No bills have been left unpaid.'
              : 'No unpaid bill matches those filters.'}
          </p>
        ) : (
          <div className="mt-4 space-y-6">
            {groups.map(([date, group]) => (
              <section key={date} aria-labelledby={`played-${date}`}>
                <h3 id={`played-${date}`} className="rounded-lg bg-raised px-3 py-2 text-label font-semibold text-text-dim">
                  <time dateTime={date}>{formatBusinessDate(date)}</time>
                </h3>
                <ul className="divide-y divide-border">
                  {group.map((bill) => (
                    <li key={bill.id} className="flex flex-wrap items-center justify-between gap-4 py-4">
                      <div className="min-w-0 flex-1 basis-64">
                        <DebtSummary bill={bill} />
                        <p className="mt-1 flex flex-wrap items-center gap-x-2 text-label text-text-dim [overflow-wrap:anywhere]">
                          <span>{bill.tableNames.length > 0 ? bill.tableNames.join(', ') : 'No table'}</span>
                          <span aria-hidden="true">·</span>
                          <Link to={`/receipt/${bill.id}`} className="hit inline-flex items-center text-info underline">
                            Receipt #{bill.receiptNo}
                          </Link>
                        </p>
                      </div>
                      <div className="ml-auto flex items-center gap-4">
                        <span className="tabular text-amount">{formatMoney(bill.totalAmount)}</span>
                        <ButtonLink to={`/checkout/${bill.id}`}>Settle</ButtonLink>
                      </div>
                    </li>
                  ))}
                </ul>
              </section>
            ))}
          </div>
        )}
      </Card>
    </div>
  );
}
