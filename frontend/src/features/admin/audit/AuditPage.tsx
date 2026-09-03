import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { fetchAuditFeed, fetchAuditFilters } from '@/api/endpoints/reports';
import { queryKeys } from '@/api/queryKeys';
import { messageOf } from '@/api/errors';
import type { AuditFeedEntry } from '@/api/types';
import { AdminPage } from '../AdminPage';
import { Card } from '@/components/Card';
import { Button } from '@/components/Button';
import { Select } from '@/components/Select';
import { Spinner } from '@/components/Spinner';
import { formatDateTime } from '@/lib/datetime';

const SIZE = 25;

/**
 * `before` and `after` are free-form jsonb whose keys differ per action, so they are rendered
 * generically rather than per-action — a renderer that knew the shapes would silently omit
 * whatever a later action adds.
 *
 * The keys are camelCase field names from the server, which read acceptably as labels; what is
 * not acceptable is showing them for a row that has no diff at all, so this returns nothing
 * rather than an empty table.
 */
function Changes({ entry }: { entry: AuditFeedEntry }) {
  const keys = Array.from(
    new Set([...Object.keys(entry.before ?? {}), ...Object.keys(entry.after ?? {})]),
  );
  if (keys.length === 0) return null;

  return (
    <table className="mt-3 w-full text-left">
      <thead>
        <tr className="text-label uppercase text-text-dim">
          <th className="py-1 font-normal">Field</th>
          <th className="py-1 font-normal">Before</th>
          <th className="py-1 font-normal">After</th>
        </tr>
      </thead>
      <tbody>
        {keys.map((key) => (
          <tr key={key}>
            <td className="py-1 pr-4 text-label text-text-dim">{humanise(key)}</td>
            <td className="tabular py-1 pr-4 text-label text-text-dim">
              {format((entry.before ?? {})[key])}
            </td>
            <td className="tabular py-1 text-label text-text">
              {format((entry.after ?? {})[key])}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/** camelCase field name to something readable: `openingFloat` becomes `Opening float`. */
function humanise(key: string): string {
  const spaced = key.replace(/([A-Z])/g, ' $1').toLowerCase().trim();
  return spaced.charAt(0).toUpperCase() + spaced.slice(1);
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function format(value: unknown): string {
  if (value === null || value === undefined) return '—';
  if (typeof value === 'object') return JSON.stringify(value);
  const text = String(value);
  /*
   * Rows written before the payloads recorded names instead of ids still hold the odd UUID.
   * Printing one asks the reader to do a join in their head, so it degrades to a marker that
   * says the value exists and is not readable — which is the truth — rather than to sixteen
   * bytes of hex pretending to be information. New rows do not hit this path.
   */
  return UUID.test(text) ? '(recorded as an id)' : text;
}

/**
 * How much stock moved, and which way.
 *
 * Signed rather than described because the sign is the fact: a delivery adds, a give-away
 * takes, and a correction does either. Colouring the outflow is what makes a night of
 * give-aways visible while scrolling.
 */
function Quantity({ delta }: { delta: number }) {
  const out = delta < 0;
  return (
    <span className={`tabular text-body ${out ? 'text-danger' : 'text-text'}`}>
      {out ? '' : '+'}
      {delta}
    </span>
  );
}

export function AuditPage() {
  const [action, setAction] = useState('');
  const [actor, setActor] = useState('');
  const [page, setPage] = useState(0);

  const query = {
    action: action || undefined,
    actor: actor || undefined,
    page,
    size: SIZE,
  };

  const feed = useQuery({
    queryKey: queryKeys.auditFeed(query),
    queryFn: () => fetchAuditFeed(query),
  });

  // Built from what is actually in this branch's history, so the owner picks from a list of
  // things that happened rather than typing a constant they would have to already know.
  const filters = useQuery({
    queryKey: queryKeys.auditFilters,
    queryFn: fetchAuditFilters,
    staleTime: 5 * 60_000,
  });

  const data = feed.data;

  function reset(setter: (value: string) => void) {
    return (event: { target: { value: string } }) => {
      setter(event.target.value);
      setPage(0);
    };
  }

  return (
    <AdminPage
      title="Audit log"
      intro="Every edit, void, override, delivery, correction and give-away — newest first, with who did it and why."
      error={feed.isError ? messageOf(feed.error) : null}
    >
      <Card className="mb-4">
        <div className="grid gap-4 md:grid-cols-2">
          <Select label="What happened" value={action} onChange={reset(setAction)}>
            <option value="">Everything</option>
            {(filters.data?.actions ?? []).map((option) => (
              <option key={option.action} value={option.action}>
                {option.label}
              </option>
            ))}
          </Select>
          {/* Names, not ids. The old screen asked for a user id in a text box, which nobody has
              and nobody could get to without another query. */}
          <Select label="Who" value={actor} onChange={reset(setActor)}>
            <option value="">Everyone</option>
            {(filters.data?.actors ?? []).map((option) => (
              <option key={option.id} value={option.id}>
                {option.name}
              </option>
            ))}
          </Select>
        </div>
      </Card>

      {feed.isPending ? (
        <div className="py-16 text-center">
          <Spinner label="Loading the trail…" />
        </div>
      ) : !data ? null : data.content.length === 0 ? (
        <Card>
          <p className="py-6 text-center text-body text-text-dim">
            Nothing recorded for that filter.
          </p>
        </Card>
      ) : (
        <>
          <ul className="flex flex-col gap-3">
            {data.content.map((entry) => (
              <li key={entry.id}>
                <Card>
                  <div className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1">
                    <div className="min-w-0">
                      <span className="text-body font-semibold text-text">
                        {entry.actionLabel}
                      </span>
                      {entry.subject ? (
                        <span className="text-body text-text-dim">
                          {' · '}
                          {entry.entityLabel}{' '}
                          <span className="text-text">{entry.subject}</span>
                        </span>
                      ) : null}
                    </div>
                    <div className="shrink-0 text-label text-text-dim">
                      {entry.actorName ?? 'system'} · {formatDateTime(entry.occurredAt)}
                    </div>
                  </div>

                  {entry.quantityDelta !== null ? (
                    <p className="mt-2 text-body text-text-dim">
                      Stock <Quantity delta={entry.quantityDelta} />
                    </p>
                  ) : null}

                  {/* The reason gets room and is never truncated. On a give-away or a
                      correction it is the only thing separating a recount from someone
                      helping themselves, so it reads at body size, not as a footnote. */}
                  {entry.note ? (
                    <p className="mt-2 whitespace-pre-wrap break-words text-body text-text">
                      {entry.note}
                    </p>
                  ) : entry.quantityDelta !== null && entry.quantityDelta < 0 ? (
                    // Only stock leaving without a reason is worth flagging. A delivery is a
                    // routine inflow and needs no justification; a give-away or a correction
                    // that removes stock is exactly the row the reason exists for, and the
                    // schema makes it mandatory — so this should never fire, and says so
                    // loudly if it ever does.
                    <p className="mt-2 text-body text-danger">No reason given.</p>
                  ) : null}

                  <Changes entry={entry} />
                </Card>
              </li>
            ))}
          </ul>

          <div className="mt-4 flex items-center justify-between gap-3">
            <span className="text-label text-text-dim">
              Page {data.page + 1} of {data.totalPages} · {data.totalElements} entries
            </span>
            <div className="flex gap-2">
              <Button
                variant="secondary"
                disabled={data.page === 0}
                onClick={() => setPage((current) => Math.max(current - 1, 0))}
              >
                Newer
              </Button>
              <Button
                variant="secondary"
                disabled={data.page + 1 >= data.totalPages}
                onClick={() => setPage((current) => current + 1)}
              >
                Older
              </Button>
            </div>
          </div>
        </>
      )}
    </AdminPage>
  );
}
