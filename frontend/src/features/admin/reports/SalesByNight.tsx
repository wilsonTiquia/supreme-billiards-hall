import { useState } from 'react';
import type { PeriodDay } from '@/api/types';
import { formatPesos } from '@/lib/money';
import { useMediaQuery } from '@/lib/useMediaQuery';
import { dayMonth, isWeekendNight, shortNight } from './periodDates';

const WIDTH = 760;
const PADDING = { top: 20, right: 8, bottom: 28, left: 44 };

/**
 * The trend: one bar per calendar night, Friday and Saturday in the stronger shade so the
 * week's rhythm shows at a glance, and the break-even sales figure drawn across the lot.
 *
 * Sales per night against break-even SALES rather than the night's own bottom line, because
 * the two have to share an axis for the line to mean anything: break-even is stated as a sales
 * figure ("you need ₱X a night"), and a night's bottom line is distorted by whichever night the
 * rent happened to fall on. Bars under the line are not coloured red — the line already says
 * which they are, and red would erase the weekend shading.
 *
 * Bars and the line are drawn from the server's figures; the only arithmetic here is pixels.
 */
export function SalesByNight({
  days,
  breakEven,
}: {
  days: PeriodDay[];
  /** Null when not computable; then there is no line. */
  breakEven: number | null;
}) {
  const [hovered, setHovered] = useState<number | null>(null);
  // A phone gets the whole month in view rather than a scrollbar that hides the last week.
  // The drawing scales down with the viewBox, so the text is drawn larger to survive it and
  // half the nights are labelled.
  const narrow = useMediaQuery('(max-width: 640px)');
  const fontScale = narrow ? 1.8 : 1;
  // Taller in proportion on a phone, where the same aspect would be a strip 100px high.
  const HEIGHT = narrow ? 400 : 240;
  const peak = Math.max(...days.map((day) => day.gross), breakEven ?? 0, 0);

  if (peak <= 0) {
    return <p className="py-8 text-center text-body text-text-dim">No sales in this period.</p>;
  }

  // Round ticks — 0, 10k, 20k, 30k — never the peak and its thirds. The top tick is the
  // axis, so the tallest bar sits just under a round number rather than exactly on a
  // meaningless one.
  const step = niceStep(peak / 4);
  const top = Math.ceil(peak / step) * step;
  const ticks = Array.from({ length: Math.round(top / step) + 1 }, (_, i) => i * step);

  const plotWidth = WIDTH - PADDING.left - PADDING.right;
  const plotHeight = HEIGHT - PADDING.top - PADDING.bottom;
  const slot = plotWidth / days.length;
  const barWidth = Math.max(slot - 4, 2);
  const y = (value: number) => PADDING.top + plotHeight - (value / top) * plotHeight;

  // Label every night when there is room, otherwise every few; past two months, only the
  // first of each month, by name.
  const labelEvery = (days.length <= 14 ? 1 : 3) * (narrow ? 2 : 1);
  const long = days.length > 62;

  const best = days.reduce((a, b) => (b.gross > a.gross ? b : a), days[0]);
  const shown = hovered === null ? best : days[hovered];

  return (
    <div>
      <div className="overflow-x-auto">
        <svg
          viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
          width="100%"
          role="img"
          aria-label="Sales per night across the period, with the break-even line"
          className="block"
          onMouseLeave={() => setHovered(null)}
        >
          {ticks.map((value) => (
            <g key={value}>
              <line
                x1={PADDING.left}
                x2={WIDTH - PADDING.right}
                y1={y(value)}
                y2={y(value)}
                className="stroke-border"
                strokeWidth="1"
              />
              <text x={PADDING.left - 8} y={y(value) + 4} textAnchor="end" className="fill-text-dim" fontSize={11 * fontScale}>
                {tickLabel(value)}
              </text>
            </g>
          ))}

          {days.map((day, index) => {
            const x = PADDING.left + index * slot + (slot - barWidth) / 2;
            const weekend = isWeekendNight(day.businessDate);
            return (
              <g key={day.businessDate} onMouseEnter={() => setHovered(index)} onClick={() => setHovered(index)}>
                <rect x={PADDING.left + index * slot} y={PADDING.top} width={slot} height={plotHeight} fill="transparent" />
                {day.gross > 0 ? (
                  <rect
                    x={x}
                    y={y(day.gross)}
                    width={barWidth}
                    height={(day.gross / top) * plotHeight}
                    rx="2"
                    className={weekend ? 'fill-chart-bar-strong' : 'fill-chart-bar'}
                    opacity={hovered === null || hovered === index ? 1 : 0.55}
                  />
                ) : null}
                {(long ? day.businessDate.endsWith('-01') : index % labelEvery === 0) ? (
                  <text
                    x={PADDING.left + index * slot + slot / 2}
                    y={HEIGHT - 10}
                    textAnchor="middle"
                    className="fill-text-dim"
                    fontSize={10 * fontScale}
                  >
                    {long ? dayMonth(day.businessDate).slice(2) : day.businessDate.slice(8).replace(/^0/, '')}
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
                className="stroke-text-dim"
                strokeWidth="1.5"
                strokeDasharray="6 4"
              />
              {/* At the left end, on a backing in the card's own colour so it stays legible
                  when the first bars run through it — which on a good month they all do. */}
              <rect
                x={PADDING.left + 2}
                y={y(breakEven) - 5 - 11 * fontScale}
                width={(`break-even ${formatPesos(breakEven)}`.length * 6.2 + 8) * fontScale}
                height={15 * fontScale}
                rx="3"
                className="fill-surface"
              />
              <text x={PADDING.left + 6} y={y(breakEven) - 5} className="fill-text-dim" fontSize={11 * fontScale}>
                break-even {formatPesos(breakEven)}
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
      <p className="tabular mt-2 text-label text-text-dim" aria-live="polite">
        {hovered === null ? 'Best night: ' : ''}
        {shortNight(shown.businessDate)} {dayMonth(shown.businessDate).split(' ')[1]} ·{' '}
        {formatPesos(shown.gross)} · {shown.bills} {shown.bills === 1 ? 'bill' : 'bills'}
      </p>
    </div>
  );
}

/** 1, 2, 2.5 or 5 times a power of ten — the nearest not below the rough step. */
export function niceStep(rough: number): number {
  const power = 10 ** Math.floor(Math.log10(rough));
  for (const factor of [1, 2, 2.5, 5, 10]) {
    if (factor * power >= rough) return factor * power;
  }
  return 10 * power;
}

export function tickLabel(value: number): string {
  if (value === 0) return '0';
  if (value >= 1000) return `${value / 1000}k`;
  return String(value);
}
