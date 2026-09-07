import type { PoolTable } from '@/api/types';
import { useElapsed } from '@/time/useElapsed';
import { formatMoney, formatRate } from '@/lib/money';
import { formatElapsed } from '@/lib/datetime';
import { RackMark } from './RackMark';
import { PixelTable } from './PixelTable';

/**
 * One table, read from a standing position across a dim room.
 *
 * The table NUMBER leads, because that is what staff shout across the room — "three wants a
 * beer" — and it was body text before. Everything else hangs off it: the status pill top
 * right, the money as the typographic subject on a running card, and on a free one a racked
 * triangle behind the rate, because an empty table is a racked table waiting.
 *
 * The card is still the table and the head is still under a lamp: lit when in play, dimmer
 * when paused, dark when free. Status separates on luminance before it separates on hue,
 * because that is the order peripheral vision resolves them in; the word only confirms it.
 */

/** "Table 5 (Premium)" is a number and a tier wearing one label. Split them for display. */
function readName(name: string): { number: string; tag: string | null } {
  const tagged = name.match(/^(.+?)\s*\(([^()]+)\)$/);
  const bare = tagged ? tagged[1] : name;
  const numbered = bare.match(/(\d+)\s*$/);
  return { number: numbered ? numbered[1].padStart(2, '0') : bare, tag: tagged ? tagged[2] : null };
}

export function TableCard({
  table,
  onStart,
  onOpen,
}: {
  table: PoolTable;
  onStart: (table: PoolTable) => void;
  onOpen: (table: PoolTable) => void;
}) {
  const session = table.session;
  const elapsedMs = useElapsed(session);
  const paused = session?.status === 'PAUSED';
  const { number, tag } = readName(table.name);

  const skin = !session
    ? 'bg-surface rail-free'
    : paused
      ? 'bg-raised lit-paused rail-paused'
      : 'bg-raised lit-running rail-running';

  return (
    <button
      type="button"
      onClick={() => (session ? onOpen(table) : onStart(table))}
      className={`group relative flex min-h-[19rem] w-full flex-col overflow-hidden rounded-2xl text-left transition hover:brightness-110 ${skin}`}
    >
      {/* Racked and waiting, or broken and in play — the illustration IS the state.
          Both sit behind everything and both are told to lose: the running total is what
          this card is for. */}
      {!session ? null : (
        <div
          className="pointer-events-none absolute right-5 top-16 opacity-[0.30]"
          aria-hidden
        >
          <PixelTable />
        </div>
      )}

      <div className="relative flex flex-1 items-start justify-between gap-4 p-6">
        {/* Centred in the head, which on a free card is the empty space, and raised to 0.55.
            At 0.13 it read as a smudge; these are the unused tables and the rack is the only
            character they have.

            Raising it is safe because the mark is STROKES, not a fill — measured from its own
            geometry it covers 2.8% of the head, so the head's mean luminance goes 0.0108 ->
            0.0135, still 0.43x a paused card and 0.30x a running one. The free/paused/running
            ordering that makes the floor readable sideways-on is untouched, and the Start
            button and the rate stay far the brightest things here. */}
        {!session ? (
          <div
            className="pointer-events-none absolute inset-0 flex items-center justify-center opacity-[0.55]"
            aria-hidden
          >
            <RackMark premium={tag !== null} />
          </div>
        ) : null}

        <div className="relative min-w-0">
          {/* What staff say out loud, at the size that implies. */}
          <div className="figure-table-number tabular text-text">{number}</div>
          <div className="text-label uppercase text-text">Table</div>
          {tag ? (
            <span className="mt-3 inline-flex rounded-md border border-border px-2 py-0.5 text-label uppercase text-text-dim">
              {tag}
            </span>
          ) : null}
        </div>

        {/* Opaque, with a --text label rather than the state colour. Both measured: --green
            on the lit head is 2.53:1 and fails AA on its own, and the pill sits near the
            illustration where a ball highlight would drag --text to 3.65:1. Its own solid
            ground fixes both at 13.64:1 whatever ends up behind it. The border still carries
            the state colour, and the rail and the light were always the real cue. */}
        <span
          className={`relative shrink-0 rounded-lg border bg-raised px-3 py-1 text-label uppercase text-text ${
            !session ? 'border-border' : paused ? 'border-gold' : 'border-green'
          }`}
        >
          {!session ? 'Free' : paused ? 'Paused' : 'Running'}
        </span>
      </div>

      {/* The felt. Its own solid ground, so no figure ever sits on the gradient. */}
      <div className="relative bg-raised px-6 pb-6 pt-4">
        {session ? (
          <>
            <div className="flex items-baseline justify-between gap-3">
              <span className="figure-amount text-amount">
                {formatMoney(session.runningTotal)}
              </span>
              <span className="figure-timer text-text-dim">{formatElapsed(elapsedMs)}</span>
            </div>

            {/* What is on the bill so far. The money alone cannot say whether that is one
                round or six, and the counter is usually being asked exactly that. */}
            <div className="mt-4 flex items-baseline justify-between gap-4 border-t border-border pt-3 text-label uppercase text-text-dim">
              <span className="tabular">
                {session.itemCount > 0
                  ? `${session.itemCount} on the bill · ${formatMoney(session.itemTotal)}`
                  : 'Nothing on the bill yet'}
              </span>
              {/*
                The SESSION's pricing, not the table's. On a flat session the table is still
                configured at its own rate — a flat fee never touches the table — so showing
                table.ratePerMinute here would read "₱4.00 / min" while the session is actually
                on a fixed fee. That is plausible enough that nobody would ever question it,
                which is worse than a figure that looks broken.
              */}
              <span className="tabular shrink-0 normal-case">
                {session.flatAmount !== null
                  ? `Flat ${formatMoney(session.flatAmount)}`
                  : formatRate(table.ratePerMinute)}
              </span>
            </div>
          </>
        ) : (
          <>
            <div className="tabular text-heading text-text">{formatRate(table.ratePerMinute)}</div>
            <div className="hit mt-4 flex min-h-[3.5rem] w-full items-center justify-center rounded-xl bg-green text-heading font-semibold text-ink transition group-hover:brightness-110">
              Start session
            </div>
          </>
        )}
      </div>
    </button>
  );
}
