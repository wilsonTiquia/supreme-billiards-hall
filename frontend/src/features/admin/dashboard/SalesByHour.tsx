import type { HourlySales } from '@/api/types';
import { formatMoney } from '@/lib/money';

/**
 * The business day runs 10:00 → 05:00, so the axis is 10…23 then 00…04 — nineteen hours in
 * clock order, not numeric order. Sorting these as plain integers would put the 02:00 sales at
 * the left-hand end of the night, which is exactly backwards.
 */
const HOURS = [...Array.from({ length: 14 }, (_, i) => i + 10), 0, 1, 2, 3, 4];

const WIDTH = 760;
const HEIGHT = 220;
const PADDING = { top: 16, right: 8, bottom: 28, left: 56 };

function label(hour: number): string {
  const suffix = hour < 12 ? 'AM' : 'PM';
  const twelve = hour % 12 === 0 ? 12 : hour % 12;
  return `${twelve}${suffix}`;
}

export function SalesByHour({ hours }: { hours: HourlySales[] }) {
  const byHour = new Map(hours.map((entry) => [entry.hour, entry]));
  const peak = Math.max(...HOURS.map((h) => byHour.get(h)?.amount ?? 0), 0);

  const plotWidth = WIDTH - PADDING.left - PADDING.right;
  const plotHeight = HEIGHT - PADDING.top - PADDING.bottom;
  const slot = plotWidth / HOURS.length;
  const barWidth = Math.max(slot - 6, 4);

  if (peak <= 0) {
    return <p className="py-8 text-center text-body text-text-dim">No sales recorded yet.</p>;
  }

  // Three gridlines, at the peak and its thirds. Rounded only for the axis label; the bars
  // themselves are drawn from the exact figures.
  const gridlines = [peak, peak * (2 / 3), peak / 3, 0];

  return (
    <div className="overflow-x-auto">
      <svg
        viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
        width="100%"
        role="img"
        aria-label="Sales by hour across the business day"
        className="min-w-[640px]"
      >
        {gridlines.map((value, index) => {
          const y = PADDING.top + plotHeight - (value / peak) * plotHeight;
          return (
            <g key={index}>
              <line
                x1={PADDING.left}
                x2={WIDTH - PADDING.right}
                y1={y}
                y2={y}
                className="stroke-border"
                strokeWidth="1"
              />
              <text
                x={PADDING.left - 8}
                y={y + 4}
                textAnchor="end"
                className="fill-text-dim"
                fontSize="11"
              >
                {Math.round(value).toLocaleString('en-PH')}
              </text>
            </g>
          );
        })}

        {HOURS.map((hour, index) => {
          const entry = byHour.get(hour);
          const amount = entry?.amount ?? 0;
          const barHeight = (amount / peak) * plotHeight;
          const x = PADDING.left + index * slot + (slot - barWidth) / 2;
          const y = PADDING.top + plotHeight - barHeight;

          return (
            <g key={hour}>
              {amount > 0 ? (
                /* fill-chart-bar, never fill-gold: gold on this white card is 1.68:1. The
                   token resolves to the darkened green here and to gold on the dark theme. */
                <rect
                  x={x}
                  y={y}
                  width={barWidth}
                  height={barHeight}
                  rx="2"
                  className="fill-chart-bar"
                >
                  <title>{`${label(hour)} — ${formatMoney(amount)} from ${entry?.bills ?? 0} bills`}</title>
                </rect>
              ) : null}
              <text
                x={PADDING.left + index * slot + slot / 2}
                y={HEIGHT - 10}
                textAnchor="middle"
                className="fill-text-dim"
                fontSize="10"
              >
                {index % 2 === 0 ? label(hour) : ''}
              </text>
            </g>
          );
        })}

        <line
          x1={PADDING.left}
          x2={WIDTH - PADDING.right}
          y1={PADDING.top + plotHeight}
          y2={PADDING.top + plotHeight}
          className="stroke-border"
          strokeWidth="1"
        />
      </svg>
    </div>
  );
}
