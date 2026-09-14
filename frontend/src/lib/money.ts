import type { Money } from '@/api/types';

/**
 * Formatting only. The browser never computes a peso figure — every amount displayed came
 * from the server, and this turns it into something readable. See frontend/CLAUDE.md §2.
 */
const peso = new Intl.NumberFormat('en-PH', {
  style: 'currency',
  currency: 'PHP',
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

export function formatMoney(amount: Money | null | undefined): string {
  if (amount === null || amount === undefined) return '—';
  return peso.format(amount);
}

/**
 * Whole pesos — "₱42,823", never "₱42,823.22" — for the owner's two reading pages.
 *
 * The dashboard and the period report are read for the shape of a night or a month, and
 * centavos on a five-figure sum are precision nobody can use. Rounded for the eye only: the
 * figure arrived from the server and goes nowhere. The till and the sales list keep
 * formatMoney, because those are the screens that reconcile.
 */
const wholePeso = new Intl.NumberFormat('en-PH', {
  style: 'currency',
  currency: 'PHP',
  minimumFractionDigits: 0,
  maximumFractionDigits: 0,
});

export function formatPesos(amount: Money | null | undefined): string {
  if (amount === null || amount === undefined) return '—';
  return wholePeso.format(amount);
}

/**
 * Rates are DISPLAYED at two decimals — "₱4.00 / min" — and stored at four.
 *
 * The four decimals in the data are not decoration: a ₱200/hour table is ₱3.3333/min, and
 * storing 3.33 would undercharge every session on it. This rounds for the eye only; the value
 * sent to and held by the server is untouched, and the amount charged is the server's own sum
 * over the stored rate, never this string.
 */
const rate = new Intl.NumberFormat('en-PH', {
  style: 'currency',
  currency: 'PHP',
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

export function formatRate(perMinute: number | null | undefined): string {
  if (perMinute === null || perMinute === undefined) return '—';
  return `${rate.format(perMinute)} / min`;
}

/**
 * The hourly figure an admin typed — "₱240.00 / hour". Two decimals, because it is a peso
 * amount typed into a peso box.
 *
 * This is a label for a rate, not a price for an hour of play. What an hour actually costs is
 * the server's effectiveRatePerHour, which is not always the same number.
 */
export function formatHourlyRate(perHour: number | null | undefined): string {
  if (perHour === null || perHour === undefined) return '—';
  return `${rate.format(perHour)} / hour`;
}

/**
 * A table's standing rate in both units, for the three places that present it: the floor
 * card's free branch, and the Start session header and standard-rate description.
 *
 * HOURLY LEADS AND PER-MINUTE FOLLOWS, everywhere, regardless of which unit the table was
 * configured in. The owner thinks in pesos per hour and the meter bills per minute, and a
 * screen that ordered them by configuration would put two different orderings on one modal.
 *
 * Formatting only — the caller passes `ratePerHour ?? effectiveRatePerHour` for the hourly
 * side, both of which the server computed. Nothing is multiplied by 60 here or anywhere in the
 * browser.
 *
 * `hourly` is null when there is no hourly figure at all, which happens on a table with no
 * current rate: the server returns ratePerMinute, ratePerHour and effectiveRatePerHour all
 * null together. Callers render `perMinute` alone in that case — a single "—" rather than
 * "— · —" — and having the guard here is most of the reason this function exists, since three
 * copies of it would be three chances to get it wrong.
 *
 * Two decimals via the ordinary formatters, deliberately not formatEffectiveHourly: those four
 * decimals exist to expose the ₱199.998 gap on the admin rate screen, and here they would read
 * as noise on a line whose job is orientation.
 */
export function formatRatePair(
  perMinute: number | null | undefined,
  perHour: number | null | undefined,
): { hourly: string | null; perMinute: string } {
  return {
    hourly: perHour === null || perHour === undefined ? null : formatHourlyRate(perHour),
    perMinute: formatRate(perMinute),
  };
}

/**
 * The server's effectiveRatePerHour — 60 x the stored per-minute rate — at up to four
 * decimals, because two would round ₱199.998 back to ₱200.00 and hide the very gap this
 * figure exists to show.
 */
const preciseRate = new Intl.NumberFormat('en-PH', {
  style: 'currency',
  currency: 'PHP',
  minimumFractionDigits: 2,
  maximumFractionDigits: 4,
});

export function formatEffectiveHourly(perHour: number | null | undefined): string {
  if (perHour === null || perHour === undefined) return '—';
  return preciseRate.format(perHour);
}

/**
 * A stored per-minute rate at the precision it is held at — "₱4.00", "₱3.3333". The same
 * choice the receipt line makes: a rate rounded for display is a rate the reader cannot
 * multiply back to the total.
 */
export function formatPreciseRate(perMinute: number | null | undefined): string {
  if (perMinute === null || perMinute === undefined) return '—';
  return `${preciseRate.format(perMinute)} / min`;
}

/**
 * The digits of a money figure, grouped and to two places, with no currency symbol —
 * "1,000.00". For a text input that carries its own ₱ prefix.
 *
 * Separate from formatMoney because an input's value has to be something a person can go on
 * editing, and a symbol inside the box is a character they then have to type around.
 */
const digits = new Intl.NumberFormat('en-PH', {
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

export function formatAmountDigits(amount: number): string {
  return digits.format(amount);
}

/**
 * Reads back what formatAmountDigits wrote, and anything a person plausibly types on the way
 * there: grouping commas, a trailing point, spaces, an empty box.
 *
 * Returns null when there is no number in the string yet, which is a normal state mid-typing
 * and not an error — the caller decides whether that means "leave it alone" or "nothing
 * entered".
 */
export function parseAmount(text: string): number | null {
  const cleaned = text.replace(/[,\s₱]/g, '');
  if (cleaned === '' || cleaned === '.' || cleaned === '-') return null;
  const value = Number(cleaned);
  return Number.isFinite(value) ? value : null;
}
