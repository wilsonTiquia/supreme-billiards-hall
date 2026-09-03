import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { fetchLossesDetail } from '@/api/endpoints/reports';
import { queryKeys } from '@/api/queryKeys';
import { messageOf } from '@/api/errors';
import type { Losses, LossesDetail as LossesDetailData } from '@/api/types';
import { Modal } from '@/components/Modal';
import { Banner } from '@/components/Banner';
import { Spinner } from '@/components/Spinner';
import { formatDateTime } from '@/lib/datetime';
import { formatMoney, formatRate } from '@/lib/money';

export type LossKind = 'voids' | 'friendRates' | 'comps' | 'timeReductions';

const TITLES: Record<LossKind, string> = {
  voids: 'Voided lines',
  friendRates: 'Friend rates given',
  comps: 'Given away',
  timeReductions: 'Time not charged',
};

/** A figure that must equal its tile. When it does not, say so rather than showing both quietly. */
function SectionTotal({
  label,
  detail,
  summary,
  format,
}: {
  label: string;
  detail: number;
  summary: number;
  format: (value: number) => string;
}) {
  // Compared with a tolerance, not for identity: both sides are the database's own sums read
  // back through JSON, and a hair of float noise is not a reporting error.
  const agrees = Math.abs(detail - summary) < 0.005;
  return (
    <div>
      <div className="flex items-baseline justify-between gap-3">
        <span className="text-label uppercase text-text-dim">{label}</span>
        <span className="tabular text-amount text-danger">{format(detail)}</span>
      </div>
      {agrees ? null : (
        <p className="mt-2 text-label text-danger">
          This does not match the dashboard, which shows {format(summary)}. One of the two is
          wrong — do not act on either until it is explained.
        </p>
      )}
    </div>
  );
}

/**
 * What is behind one of the three loss figures, for one business day.
 *
 * The hall has no friend-rate floor and no supervisor role, so nothing stops a comp or an
 * override at the moment it happens. The owner reading this afterwards IS the control — which
 * is why the reason gets a column of its own on every list rather than being folded into a
 * tooltip, and why the actor and the time sit beside it.
 */
export function LossesDetail({
  kind,
  businessDate,
  summary,
  onClose,
}: {
  kind: LossKind;
  businessDate: string;
  summary: Losses;
  onClose: () => void;
}) {
  const detail = useQuery({
    queryKey: queryKeys.lossesDetail(businessDate),
    queryFn: () => fetchLossesDetail(businessDate),
  });

  return (
    <Modal title={TITLES[kind]} onClose={onClose}>
      {detail.isPending ? (
        <div className="py-8 text-center">
          <Spinner label="Loading…" />
        </div>
      ) : detail.isError ? (
        <Banner tone="danger">{messageOf(detail.error)}</Banner>
      ) : kind === 'comps' ? (
        <Comps data={detail.data} summary={summary} />
      ) : kind === 'voids' ? (
        <Voids data={detail.data} summary={summary} />
      ) : kind === 'timeReductions' ? (
        <TimeReductions data={detail.data} summary={summary} />
      ) : (
        <FriendRates data={detail.data} summary={summary} />
      )}
    </Modal>
  );
}

function Empty({ what }: { what: string }) {
  return <p className="py-6 text-center text-body text-green">No {what} on this day.</p>;
}

function Comps({ data, summary }: { data: LossesDetailData; summary: Losses }) {
  const { lines, compQuantity, compEstimatedCost } = data.comps;
  return (
    <div className="flex flex-col gap-4">
      <SectionTotal
        label={`${compQuantity} units given away`}
        detail={compEstimatedCost}
        summary={summary.compEstimatedCost}
        format={formatMoney}
      />
      <p className="text-label text-amount">
        Valued at today's average cost, so an estimate — the same basis as the tile.
      </p>
      {lines.length === 0 ? (
        <Empty what="give-aways" />
      ) : (
        <ul className="divide-y divide-border border-t border-border">
          {lines.map((line, index) => (
            <li key={index} className="py-3">
              <div className="flex items-baseline justify-between gap-3">
                <span className="text-body text-text">
                  {line.quantity} × {line.productName}
                </span>
                <span className="tabular text-body text-danger">
                  {formatMoney(line.estimatedCost)}
                </span>
              </div>
              {/* Given its own line, full width. This is the column the owner is here for. */}
              <p className="mt-1 text-body text-text">{line.reason ?? '— no reason recorded —'}</p>
              <p className="text-label uppercase text-text-dim">
                {line.actorUsername ?? 'unknown'} · {formatDateTime(line.occurredAt)}
              </p>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function Voids({ data, summary }: { data: LossesDetailData; summary: Losses }) {
  const { lines, voidCount, voidAmount } = data.voids;
  return (
    <div className="flex flex-col gap-4">
      <SectionTotal
        label={`${voidCount} ${voidCount === 1 ? 'line' : 'lines'} voided`}
        detail={voidAmount}
        summary={summary.voidAmount}
        format={formatMoney}
      />
      {lines.length === 0 ? (
        <Empty what="voids" />
      ) : (
        <ul className="divide-y divide-border border-t border-border">
          {lines.map((line, index) => (
            <li key={index} className="py-3">
              <div className="flex items-baseline justify-between gap-3">
                <span className="text-body text-text">
                  {line.quantity} × {line.description}
                </span>
                <span className="tabular text-body text-danger">
                  {formatMoney(line.lineTotal)}
                </span>
              </div>
              <p className="mt-1 text-body text-text">{line.reason ?? '— no reason recorded —'}</p>
              <p className="text-label uppercase text-text-dim">
                {line.actorUsername ?? 'unknown'} · {formatDateTime(line.voidedAt)}
                {line.receiptNo !== null ? (
                  <>
                    {' · '}
                    <Link to={`/receipt/${line.billId}`} className="text-info underline">
                      receipt #{line.receiptNo}
                    </Link>
                  </>
                ) : (
                  ' · bill still open'
                )}
              </p>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function FriendRates({ data, summary }: { data: LossesDetailData; summary: Losses }) {
  const { lines, overrideSessions, forgoneRevenue } = data.friendRates;
  return (
    <div className="flex flex-col gap-4">
      <SectionTotal
        label={`${overrideSessions} ${overrideSessions === 1 ? 'session' : 'sessions'} at a friend rate`}
        detail={forgoneRevenue}
        summary={summary.forgoneRevenue}
        format={formatMoney}
      />
      {lines.length === 0 ? (
        <Empty what="friend rates" />
      ) : (
        <ul className="divide-y divide-border border-t border-border">
          {lines.map((line, index) => (
            <li key={index} className="py-3">
              <div className="flex items-baseline justify-between gap-3">
                <span className="text-body text-text">{line.poolTableName}</span>
                <span className="tabular text-body text-danger">
                  {formatMoney(line.forgoneRevenue)}
                </span>
              </div>
              <p className="tabular mt-1 text-label text-text-dim">
                {formatRate(line.standardRatePerMinute)} standard, charged{' '}
                {formatRate(line.chargedRatePerMinute)} · {line.billedMinutes} min billed
              </p>
              <p className="mt-1 text-body text-text">{line.reason ?? '— no reason recorded —'}</p>
              <p className="text-label uppercase text-text-dim">
                {line.actorUsername ?? 'unknown'} · {formatDateTime(line.openedAt)}
              </p>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function TimeReductions({ data, summary }: { data: LossesDetailData; summary: Losses }) {
  const { lines, reducedSessions, forgoneRevenue } = data.timeReductions;
  return (
    <div className="flex flex-col gap-4">
      <SectionTotal
        label={`${reducedSessions} ${reducedSessions === 1 ? 'session' : 'sessions'} charged short`}
        detail={forgoneRevenue}
        summary={summary.timeReductionForgone}
        format={formatMoney}
      />
      {lines.length === 0 ? (
        <Empty what="reduced time" />
      ) : (
        <ul className="divide-y divide-border border-t border-border">
          {lines.map((line, index) => (
            <li key={index} className="py-3">
              <div className="flex items-baseline justify-between gap-3">
                <span className="text-body text-text">{line.poolTableName}</span>
                <span className="tabular text-body text-danger">
                  {formatMoney(line.forgoneRevenue)}
                </span>
              </div>
              <p className="tabular mt-1 text-label text-text-dim">
                {line.actualMinutes} min played, charged {line.chargedMinutes} min at{' '}
                {formatRate(line.ratePerMinute)}
              </p>
              <p className="mt-1 text-body text-text">{line.reason ?? '— no reason recorded —'}</p>
              <p className="text-label uppercase text-text-dim">
                {line.actualUsername ?? 'unknown'} · {formatDateTime(line.closedAt)}
              </p>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
