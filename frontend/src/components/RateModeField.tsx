import { Field } from './Field';
import { formatEffectiveHourly, formatMoney, formatPreciseRate } from '@/lib/money';

/**
 * Which figure the person is typing. The owner thinks in pesos per hour and kept mistyping the
 * division; this is that division, done once, on the server.
 *
 * Nothing about billing changes. The server derives the per-minute rate by dividing by 60, and
 * that derived rate is the only figure a session is ever charged at.
 */
export type RateMode = 'minute' | 'hour';

/**
 * The request fields for a rate, in whichever unit was typed. Exactly one is ever sent — the
 * table endpoints require one of the two, and the friend rate accepts neither as "charge the
 * standard rate", which is the caller's decision to make before calling this.
 */
export function rateBody(
  mode: RateMode,
  value: string,
): { ratePerMinute: number } | { ratePerHour: number } {
  return mode === 'hour' ? { ratePerHour: Number(value) } : { ratePerMinute: Number(value) };
}

/**
 * A peso input with a per-minute / per-hour toggle. Three callers: the table form, the rate
 * form, and the friend rate on Start session — so the wording is passed in rather than guessed
 * at, and the caller owns the mode state.
 *
 * Switching mode is expected to CLEAR the box, and every caller does: 240 means two very
 * different rates in the two modes, and carrying the digits across is how a ₱240/hour table
 * becomes ₱240/minute.
 */
export function RateModeField({
  mode,
  onModeChange,
  value,
  onValueChange,
  minuteLabel,
  hourLabel,
  legend = 'Charge by',
  hint,
  placeholder,
  autoFocus,
}: {
  mode: RateMode;
  onModeChange: (mode: RateMode) => void;
  value: string;
  onValueChange: (value: string) => void;
  minuteLabel: string;
  hourLabel: string;
  legend?: string;
  hint?: string;
  placeholder?: string;
  autoFocus?: boolean;
}) {
  const typed = Number(value);
  const typedIsRate = value.trim() !== '' && Number.isFinite(typed) && typed >= 0;

  /*
   * What the server will actually store, and what that prices an hour at.
   *
   * The rounding has to happen BEFORE the multiplication or the discrepancy disappears:
   * typed / 60 * 60 is exactly what was typed, every time. The gap exists precisely because the
   * per-minute rate is rounded to four decimals first, which is what the server does with
   * HALF_UP — and Math.round is HALF_UP for the non-negative values a rate always has.
   */
  const storedPerMinute = Math.round((typed / 60) * 10000) / 10000;
  const effectivePerHour = storedPerMinute * 60;
  // Compared at the precision both are displayed to, which sidesteps picking a float epsilon.
  const reconciles = effectivePerHour.toFixed(4) === typed.toFixed(4);

  return (
    <div className="flex flex-col gap-3">
      <div className="text-label uppercase text-text-dim">{legend}</div>
      <div className="flex gap-2">
        {(
          [
            { id: 'hour', label: 'Per hour' },
            { id: 'minute', label: 'Per minute' },
          ] as const
        ).map((option) => {
          const active = mode === option.id;
          return (
            <button
              key={option.id}
              type="button"
              aria-pressed={active}
              onClick={() => onModeChange(option.id)}
              className={`hit rounded-lg border px-4 text-body font-semibold transition ${
                active
                  ? 'border-green bg-green text-ink'
                  : 'border-border bg-raised text-text-dim hover:border-text-dim hover:text-text'
              }`}
            >
              {option.label}
            </button>
          );
        })}
      </div>
      <Field
        label={mode === 'hour' ? hourLabel : minuteLabel}
        type="number"
        step={mode === 'hour' ? '0.01' : '0.0001'}
        min="0"
        inputMode="decimal"
        prefix="₱"
        value={value}
        hint={hint}
        placeholder={placeholder}
        data-autofocus={autoFocus ? true : undefined}
        onChange={(event) => onValueChange(event.target.value)}
      />
      {/*
        A preview, and labelled as one. It is the only rate figure the browser works out for
        itself; everything shown after saving comes back from the server. See
        frontend/CLAUDE.md §2 — this is rate configuration, not a bill.

        Shown ONLY when the hourly figure does not divide by 60 evenly. PHP 240/hour is
        PHP 4.0000/min and prices an hour at exactly PHP 240 — echoing that back teaches the
        reader nothing and trains them to ignore the line. PHP 500/hour does not reconcile, and
        that is worth a sentence.

        In hourly mode it speaks hourly. The four decimals on the per-minute figure are the one
        place four decimals survive, because that rounding is the whole subject of the sentence.
      */}
      {typedIsRate && mode === 'hour' && !reconciles ? (
        <p className="tabular text-label text-text-dim">
          Preview — stored as {formatPreciseRate(storedPerMinute)}, pricing an hour at{' '}
          {formatEffectiveHourly(effectivePerHour)} rather than {formatMoney(typed)}.
        </p>
      ) : null}
    </div>
  );
}
