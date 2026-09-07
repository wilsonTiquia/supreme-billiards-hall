import { Field } from './Field';

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
  // >= 0, not > 0: a zero friend rate is a comped game and deserves its preview like any other.
  const preview = value.trim() !== '' && Number.isFinite(typed) && typed >= 0;

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
      {/* A preview, and labelled as one. It is the only rate figure the browser works out for
          itself; everything shown after saving comes back from the server. See
          frontend/CLAUDE.md §2 — this is rate configuration, not a bill. */}
      {preview && mode === 'hour' ? (
        <p className="tabular text-label text-text-dim">
          Preview — ₱{(typed / 60).toFixed(4)} / min, once saved.
        </p>
      ) : null}
    </div>
  );
}
