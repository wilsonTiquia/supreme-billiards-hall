import type { DailyTotals, HourlySales } from '@/api/types';
import { formatMoney } from '@/lib/money';
import { formatBusinessDate } from '@/lib/datetime';

/**
 * The night, drawn as a rail.
 *
 * A pool table's rails carry inlaid diamond sights at fixed intervals — they are the table's
 * own measuring system, the thing players aim off. A business night is also a measured span,
 * 10:00 to 05:00, so the hours are marked the way the table marks distance: diamonds on a
 * rail, with the night's takings standing on it.
 *
 * That is the one liberty taken on this screen, and it is taken here because the borrowing is
 * structural rather than decorative — a sight marks a position, which is exactly what an hour
 * tick does. Everything around it stays plain.
 *
 * The job is still five seconds: gross and profit are the largest type on the page and are
 * read first; the rail underneath answers "and what shape was the night" without a second
 * glance. Both figures are the server's, printed, never recomputed here.
 */

const HOURS = [...Array.from({ length: 14 }, (_, i) => i + 10), 0, 1, 2, 3, 4];

const W = 1000;
const H = 124;
const RAIL_Y = 104;

function hourLabel(hour: number): string {
  const twelve = hour % 12 === 0 ? 12 : hour % 12;
  return `${twelve}${hour < 12 ? 'a' : 'p'}`;
}

function delta(now: number, before: number): string | null {
  if (before <= 0) return null;
  const change = Math.round(((now - before) / before) * 100);
  return `${change >= 0 ? '+' : ''}${change}% on last night`;
}

export function NightRail({
  businessDate,
  totals,
  previous,
  hours,
}: {
  businessDate: string;
  totals: DailyTotals;
  previous: DailyTotals;
  hours: HourlySales[];
}) {
  const byHour = new Map(hours.map((entry) => [entry.hour, entry]));
  const peak = Math.max(...HOURS.map((h) => byHour.get(h)?.amount ?? 0), 0);
  const slot = W / HOURS.length;
  const barWidth = Math.max(slot - 10, 4);

  const busiest = HOURS.reduce(
    (best, h) => ((byHour.get(h)?.amount ?? 0) > (byHour.get(best)?.amount ?? 0) ? h : best),
    HOURS[0],
  );
  const grossDelta = delta(totals.gross, previous.gross);
  const profitDelta = delta(totals.profit, previous.profit);
  // Margin is a ratio of two server figures, not a peso amount derived here.
  const margin = totals.gross > 0 ? Math.round((totals.profit / totals.gross) * 100) : null;

  return (
    <section className="rounded-2xl border border-border bg-surface p-8">
      {/* The report can arrive before the business day does — it is fetched with no date and
          the server picks tonight. An empty string here used to reach formatBusinessDate and
          throw "Invalid time value", taking the whole dashboard down to the error boundary. */}
      {businessDate ? (
        <p className="text-label uppercase text-text-dim">{formatBusinessDate(businessDate)}</p>
      ) : null}

      <div className="mt-6 flex flex-wrap items-end gap-x-16 gap-y-6">
        <div>
          <div className="figure-amount text-text">{formatMoney(totals.gross)}</div>
          <div className="mt-2 text-label uppercase text-text-dim">
            Gross{grossDelta ? ` · ${grossDelta}` : ''}
          </div>
        </div>
        <div>
          <div className="figure-amount text-green">{formatMoney(totals.profit)}</div>
          <div className="mt-2 text-label uppercase text-text-dim">
            Profit{profitDelta ? ` · ${profitDelta}` : ''}
          </div>
        </div>
        {margin !== null ? (
          <div>
            <div className="figure-timer text-text">{margin}%</div>
            <div className="mt-2 text-label uppercase text-text-dim">Margin</div>
          </div>
        ) : null}
      </div>

      {peak <= 0 ? (
        <p className="mt-8 border-t border-border pt-6 text-body text-text-dim">
          Nothing sold on this night yet.
        </p>
      ) : (
        <div className="mt-8">
          <svg
            viewBox={`0 0 ${W} ${H}`}
            width="100%"
            role="img"
            aria-label={`Takings by hour from 10am to 4am. Busiest hour ${hourLabel(busiest)}.`}
            className="block"
          >
            {HOURS.map((hour, index) => {
              const amount = byHour.get(hour)?.amount ?? 0;
              const height = Math.round((amount / peak) * (RAIL_Y - 14));
              const x = index * slot + (slot - barWidth) / 2;
              const cx = index * slot + slot / 2;
              return (
                <g key={hour}>
                  {height > 0 ? (
                    <rect
                      x={x}
                      y={RAIL_Y - height}
                      width={barWidth}
                      height={height}
                      rx="3"
                      className={hour === busiest ? 'fill-brand' : 'fill-chart-bar'}
                      // 0.78, not 0.55: a bar carries meaning, so it needs 3:1 against the
                      // card. At 0.55 the quiet hours measured 2.07:1 in the light theme and
                      // were decoration pretending to be data.
                      opacity={hour === busiest ? 1 : 0.78}
                    />
                  ) : null}
                  {/* The sights. Every third hour — a rail marks positions, it does not
                      fence every inch, and at this width more would run together. */}
                  {index % 3 === 0 ? (
                    <path
                      d={`M ${cx} ${RAIL_Y + 9} l 6 7 l -6 7 l -6 -7 Z`}
                      className="fill-chart-bar"
                      opacity="0.7"
                    />
                  ) : null}
                </g>
              );
            })}
            <rect x="0" y={RAIL_Y} width={W} height="3" rx="1.5" className="fill-chart-bar" />
          </svg>

          {/*
            Hour labels sit outside the SVG so they keep their real size at any width.

            min-w-0 is what stops them forcing the page sideways: nineteen flex items each with
            the intrinsic width of "10A" add up to more than a phone, and flex-1 cannot shrink a
            child below its content unless it is allowed to. Every third hour is marked on a
            monitor; every sixth on a phone, where nineteen sights in 340px is a smudge rather
            than a scale. The hidden ones keep their space so the marks stay on their hours.
          */}
          <div className="flex" aria-hidden>
            {HOURS.map((hour, index) => (
              <span
                key={hour}
                className={`min-w-0 flex-1 overflow-hidden text-center text-label uppercase text-text-dim ${
                  index % 6 === 0 ? '' : index % 3 === 0 ? 'invisible sm:visible' : 'invisible'
                }`}
              >
                {hourLabel(hour)}
              </span>
            ))}
          </div>

          <p className="mt-3 text-label uppercase text-text-dim">
            Busiest at {hourLabel(busiest)} · {formatMoney(byHour.get(busiest)?.amount ?? 0)}
          </p>
        </div>
      )}
    </section>
  );
}
