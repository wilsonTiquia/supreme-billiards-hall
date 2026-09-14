import { useState } from 'react';
import type { HourlySales } from '@/api/types';
import { formatPesos } from '@/lib/money';

/**
 * Sales by hour, drawn as a rail.
 *
 * A pool table's rails carry inlaid diamond sights at fixed intervals — the table's own
 * measuring system. A business night is also a measured span, 10:00 to 05:00, so the hours
 * are marked the way the table marks distance: diamonds on a rail, with the takings standing
 * on it. That is the one liberty taken on these pages, and it is structural rather than
 * decorative — a sight marks a position, which is exactly what an hour tick does.
 *
 * The axis is 10…23 then 00…04, nineteen hours in clock order, not numeric order: sorted as
 * integers the 02:00 sales would sit at the left-hand end of the night, which is backwards.
 * Every figure is the server's; the only arithmetic here is pixels.
 */
const HOURS = [...Array.from({ length: 14 }, (_, i) => i + 10), 0, 1, 2, 3, 4];

const W = 1000;
const H = 124;
const RAIL_Y = 104;

function hourLabel(hour: number): string {
  const twelve = hour % 12 === 0 ? 12 : hour % 12;
  return `${twelve}${hour < 12 ? 'am' : 'pm'}`;
}

export function HourChart({ hours }: { hours: HourlySales[] }) {
  const [hovered, setHovered] = useState<number | null>(null);
  const byHour = new Map(hours.map((entry) => [entry.hour, entry]));
  const peak = Math.max(...HOURS.map((h) => byHour.get(h)?.amount ?? 0), 0);
  const slot = W / HOURS.length;
  const barWidth = Math.max(slot - 10, 4);

  const busiest = HOURS.reduce(
    (best, h) => ((byHour.get(h)?.amount ?? 0) > (byHour.get(best)?.amount ?? 0) ? h : best),
    HOURS[0],
  );

  if (peak <= 0) {
    return <p className="text-body text-text-dim">Nothing sold yet.</p>;
  }

  // The caption is the hover readout: the hour under the pointer, or the busiest one when
  // nothing is. A readout that is always on screen works on a phone, where nothing hovers.
  const shown = hovered ?? busiest;
  const shownEntry = byHour.get(shown);

  return (
    <div>
      <svg
        viewBox={`0 0 ${W} ${H}`}
        width="100%"
        role="img"
        aria-label={`Sales by hour from 10am to 4am. Busiest hour ${hourLabel(busiest)}.`}
        className="block"
        onMouseLeave={() => setHovered(null)}
      >
        {HOURS.map((hour, index) => {
          const amount = byHour.get(hour)?.amount ?? 0;
          const height = Math.round((amount / peak) * (RAIL_Y - 14));
          const x = index * slot + (slot - barWidth) / 2;
          const cx = index * slot + slot / 2;
          return (
            <g key={hour} onMouseEnter={() => setHovered(hour)} onClick={() => setHovered(hour)}>
              {/* The whole column is the hit area, so a thin bar is as easy to point at as a
                  tall one. */}
              <rect x={index * slot} y="0" width={slot} height={RAIL_Y} fill="transparent" />
              {height > 0 ? (
                <rect
                  x={x}
                  y={RAIL_Y - height}
                  width={barWidth}
                  height={height}
                  rx="3"
                  className={hour === shown ? 'fill-chart-bar-strong' : 'fill-chart-bar'}
                />
              ) : null}
              {/* The sights. Every third hour — a rail marks positions, it does not fence
                  every inch, and at this width more would run together. */}
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

      {/* Hour labels sit outside the SVG so they keep their real size at any width. min-w-0
          is what stops them forcing the page sideways: nineteen flex items each with the
          intrinsic width of "10am" add up to more than a phone. Every third hour is marked
          on a monitor; every sixth on a phone. The hidden ones keep their space so the marks
          stay on their hours, and the visible ones may overflow their slot, which is why the
          slot does not clip. */}
      <div className="flex" aria-hidden>
        {HOURS.map((hour, index) => (
          <span
            key={hour}
            className={`min-w-0 flex-1 overflow-visible whitespace-nowrap text-center text-label text-text-dim ${
              index % 6 === 0 ? '' : index % 3 === 0 ? 'invisible sm:visible' : 'invisible'
            }`}
          >
            {hourLabel(hour)}
          </span>
        ))}
      </div>

      <p className="tabular mt-2 text-label text-text-dim" aria-live="polite">
        {hovered === null ? 'Busiest at ' : ''}
        {hourLabel(shown)} · {formatPesos(shownEntry?.amount ?? 0)}
        {shownEntry ? ` · ${shownEntry.bills} ${shownEntry.bills === 1 ? 'bill' : 'bills'}` : ''}
      </p>
    </div>
  );
}
