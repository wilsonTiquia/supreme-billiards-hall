import type { PeriodDay } from '@/api/types';
import { formatMoney } from '@/lib/money';

const WIDTH = 760;
const HEIGHT = 240;
const PADDING = { top: 16, right: 8, bottom: 28, left: 56 };

/**
 * The trend line: one bar per calendar night in the period, with the break-even gross drawn
 * across it. Nights below the line did not take enough to cover their share of the period's
 * operating cost.
 *
 * Gross per night against the break-even GROSS, rather than net per night, because the two
 * have to share an axis for the line to mean anything: break-even is stated as a gross figure
 * ("you need ₱X a day"), and net per night is distorted by whichever night the rent happened to
 * be paid on. Net per night is in the table beneath, where a night's own expenses belong.
 *
 * Bars and the line are drawn from the server's figures; the only arithmetic here is pixels.
 */
export function GrossByDay({
  days,
  breakEven,
}: {
  days: PeriodDay[];
  /** Null when not computable; then there is no line. */
  breakEven: number | null;
}) {
  const peak = Math.max(...days.map((day) => day.gross), breakEven ?? 0, 0);

  if (peak <= 0) {
    return <p className="py-8 text-center text-body text-text-dim">No sales in this period.</p>;
  }

  const plotWidth = WIDTH - PADDING.left - PADDING.right;
  const plotHeight = HEIGHT - PADDING.top - PADDING.bottom;
  const slot = plotWidth / days.length;
  const barWidth = Math.max(slot - 4, 2);
  const y = (value: number) => PADDING.top + plotHeight - (value / peak) * plotHeight;

  // Label every night when there is room, otherwise every few, always the first and last.
  const labelEvery = days.length <= 14 ? 1 : days.length <= 31 ? 3 : 7;
  const gridlines = [peak, peak * (2 / 3), peak / 3, 0];

  return (
    <div className="overflow-x-auto">
      <svg
        viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
        width="100%"
        role="img"
        aria-label="Gross per night across the period, with the break-even line"
        className="min-w-[640px]"
      >
        {gridlines.map((value, index) => (
          <g key={index}>
            <line
              x1={PADDING.left}
              x2={WIDTH - PADDING.right}
              y1={y(value)}
              y2={y(value)}
              className="stroke-border"
              strokeWidth="1"
            />
            <text
              x={PADDING.left - 8}
              y={y(value) + 4}
              textAnchor="end"
              className="fill-text-dim"
              fontSize="11"
            >
              {Math.round(value).toLocaleString('en-PH')}
            </text>
          </g>
        ))}

        {days.map((day, index) => {
          const x = PADDING.left + index * slot + (slot - barWidth) / 2;
          const barHeight = (day.gross / peak) * plotHeight;
          const below = breakEven !== null && day.trading && day.gross < breakEven;
          return (
            <g key={day.businessDate}>
              {day.gross > 0 ? (
                <rect
                  x={x}
                  y={y(day.gross)}
                  width={barWidth}
                  height={barHeight}
                  rx="2"
                  className={below ? 'fill-danger' : 'fill-chart-bar'}
                >
                  <title>{`${day.businessDate} — ${formatMoney(day.gross)} gross, ${formatMoney(day.net)} net, ${day.bills} bills`}</title>
                </rect>
              ) : null}
              {index % labelEvery === 0 || index === days.length - 1 ? (
                <text
                  x={PADDING.left + index * slot + slot / 2}
                  y={HEIGHT - 10}
                  textAnchor="middle"
                  className="fill-text-dim"
                  fontSize="10"
                >
                  {day.businessDate.slice(8)}
                </text>
              ) : null}
            </g>
          );
        })}

        {breakEven !== null ? (
          <g>
            <line
              x1={PADDING.left}
              x2={WIDTH - PADDING.right}
              y1={y(breakEven)}
              y2={y(breakEven)}
              className="stroke-danger"
              strokeWidth="1.5"
              strokeDasharray="6 4"
            />
            <text
              x={WIDTH - PADDING.right}
              y={y(breakEven) - 5}
              textAnchor="end"
              className="fill-danger"
              fontSize="11"
            >
              break-even {formatMoney(breakEven)}
            </text>
          </g>
        ) : null}

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
