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
