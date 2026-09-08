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
import { formatHourlyRate, formatMoney, formatRate } from '@/lib/money';

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
  const before = entry.before ?? {};
  const after = entry.after ?? {};
  const keys = Array.from(new Set([...Object.keys(before), ...Object.keys(after)]));

  const rate = rateRow(before, after);
  // The rate pair is rendered as one row or not at all, so neither key reaches the generic list.
  const rest = rate ? keys.filter((key) => key !== 'ratePerMinute' && key !== 'ratePerHour') : keys;
  if (!rate && rest.length === 0) return null;

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
        {rate ? (
          <tr>
            <td className="py-1 pr-4 text-label text-text-dim">Rate</td>
            <td className="tabular py-1 pr-4 text-label text-text-dim">{rate.before}</td>
            <td className="tabular py-1 text-label text-text">{rate.after}</td>
          </tr>
        ) : null}
        {rest.map((key) => (
          <tr key={key}>
            <td className="py-1 pr-4 text-label text-text-dim">{humanise(key)}</td>
            <td className="tabular py-1 pr-4 text-label text-text-dim">{format(before[key], key)}</td>
            <td className="tabular py-1 text-label text-text">{format(after[key], key)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/**
 * The one rate row, in the unit it was set in.
 *
 * A rate reaches the log as a pair — the derived per-minute figure that bills, and the hourly
 * figure the admin typed, which is null when they typed per minute. Showing both puts the same
 * rate on screen twice in two units, and the reader has to work out that they are one number.
 *
 * Driven off the keys rather than off the action, so an action added later that snapshots a
 * rate gets this for free — the same reason `format` is key-driven.
 *
 * Each SIDE picks its own unit. A table moved from hourly to per-minute has an hourly figure on
 * the before side only, and reads "PHP 240.00 / hour -> PHP 5.00 / min" — the mode change is
 * real information. Forcing the after side into hours would mean multiplying up a figure nobody
 * typed, which is the arithmetic this codebase refuses everywhere else.
 *
 * Returns null when neither side carries a rate at all, including rows written before the
 * hourly mode existed, which carry no `ratePerHour` key whatsoever.
 */
function rateRow(
  before: Record<string, unknown>,
  after: Record<string, unknown>,
): { before: string; after: string } | null {
  const has = (side: Record<string, unknown>) =>
    typeof side.ratePerHour === 'number' || typeof side.ratePerMinute === 'number';
  if (!has(before) && !has(after)) return null;

  const side = (values: Record<string, unknown>) =>
    typeof values.ratePerHour === 'number'
      ? formatHourlyRate(values.ratePerHour)
      : typeof values.ratePerMinute === 'number'
        ? formatRate(values.ratePerMinute)
        : '—';

  return { before: side(before), after: side(after) };
}

/**
 * Whether the kind of thing still has to be said out loud beside its name.
 *
 * Three correct decisions collide here: the vocabulary calls a `pool_table`, a
 * `pool_table_rate` and a `table_session` all "Table", the feed resolves the subject to the pool
 * table's own name, and that name is usually "Table 2" — so the row read "Table Table 2".
 *
 * Suppressed here rather than by dropping those keys from the vocabulary, because a table named
 * "Corner" or "VIP Room" still needs the word and the entity label is what supplies it.
 *
 * The match has to end on a word boundary. A plain prefix test would strip the label from a
 * table named "Tablecloth", leaving "· Tablecloth" with nothing saying it is a table at all.
 */
function needsEntityLabel(entry: AuditFeedEntry): boolean {
  const label = entry.entityLabel?.trim();
  const subject = entry.subject?.trim();
  if (!label || !subject) return Boolean(label);
  if (!subject.toLowerCase().startsWith(label.toLowerCase())) return true;
  const next = subject.charAt(label.length);
  return next !== '' && /[a-z0-9]/i.test(next);
}

/** camelCase field name to something readable: `openingFloat` becomes `Opening float`. */
function humanise(key: string): string {
  const spaced = key.replace(/([A-Z])/g, ' $1').toLowerCase().trim();
  return spaced.charAt(0).toUpperCase() + spaced.slice(1);
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * Which keys are money, and in what unit. First match wins.
 *
 * Key-driven rather than action-driven, for the reason the doc comment on `Changes` gives: a
 * renderer that knew each action's shape would silently print raw numbers for whatever a later
 * action adds. A key called `ratePerHour` is a rate per hour whoever wrote it.
 *
 * The two rate patterns sit ahead of the general one because the plain money formatter would
 * turn 4 into "PHP 4.00" and drop the unit, which is the difference between a rate and a price.
 */
const MONEY_KEYS: [RegExp, (value: number) => string][] = [
  [/ratePerHour$/i, formatHourlyRate],
  [/ratePerMinute$/i, formatRate],
  [/rate|amount|price|total|float|cash/i, formatMoney],
];

function format(value: unknown, key?: string): string {
  if (value === null || value === undefined) return '—';
  if (typeof value === 'object') return JSON.stringify(value);

  // Only numbers are formatted as money. `/rate/i` also matches `rateOverrideReason`, and a
  // money formatter handed a sentence prints "PHP NaN". Everything else falls through to the
  // behaviour below, unchanged.
  if (typeof value === 'number' && key) {
    const money = MONEY_KEYS.find(([pattern]) => pattern.test(key));
    if (money) return money[1](value);
  }

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
                          {needsEntityLabel(entry) ? `${entry.entityLabel} ` : ''}
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
