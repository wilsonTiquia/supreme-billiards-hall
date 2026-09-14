import type { BusinessDate } from '@/api/types';

/**
 * Calendar arithmetic for the preset chips, on business dates the server supplied.
 *
 * Nothing here touches a clock: "this month" starts from the business date the server says is
 * current, never from the browser's idea of today, so a laptop set to the wrong day cannot move
 * the report. Which nights belong to which dates is the database's decision; this only picks
 * ranges of the labels it returned. All dates are handled as midday UTC so that no timezone can
 * slip a day.
 */
function parse(date: BusinessDate): Date {
  return new Date(`${date}T12:00:00Z`);
}

function iso(date: Date): BusinessDate {
  return date.toISOString().slice(0, 10);
}

function shift(date: BusinessDate, days: number): BusinessDate {
  const d = parse(date);
  d.setUTCDate(d.getUTCDate() + days);
  return iso(d);
}

/** Monday of the week the date falls in. The week starts Monday, and the page says so. */
function mondayOf(date: BusinessDate): BusinessDate {
  const day = parse(date).getUTCDay(); // 0 Sunday … 6 Saturday
  const back = day === 0 ? 6 : day - 1;
  return shift(date, -back);
}

function firstOfMonth(date: BusinessDate): BusinessDate {
  return `${date.slice(0, 7)}-01`;
}

function lastOfMonth(date: BusinessDate): BusinessDate {
  const d = parse(firstOfMonth(date));
  d.setUTCMonth(d.getUTCMonth() + 1);
  d.setUTCDate(0);
  return iso(d);
}

export type Preset = 'thisWeek' | 'lastWeek' | 'thisMonth' | 'lastMonth';

export const PRESETS: { key: Preset; label: string }[] = [
  { key: 'thisWeek', label: 'This week' },
  { key: 'lastWeek', label: 'Last week' },
  { key: 'thisMonth', label: 'This month' },
  { key: 'lastMonth', label: 'Last month' },
];

export function presetRange(
  preset: Preset,
  current: BusinessDate,
): { from: BusinessDate; to: BusinessDate } {
  switch (preset) {
    case 'thisWeek':
      return { from: mondayOf(current), to: current };
    case 'lastWeek': {
      const monday = shift(mondayOf(current), -7);
      return { from: monday, to: shift(monday, 6) };
    }
    case 'thisMonth':
      return { from: firstOfMonth(current), to: current };
    case 'lastMonth': {
      const previous = shift(firstOfMonth(current), -1);
      return { from: firstOfMonth(previous), to: lastOfMonth(previous) };
    }
  }
}

/** Which preset a from/to pair is, if any, so the chip can show as selected. */
export function presetOf(from: BusinessDate, to: BusinessDate, current: BusinessDate): Preset | null {
  for (const { key } of PRESETS) {
    const range = presetRange(key, current);
    if (range.from === from && range.to === to) return key;
  }
  return null;
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

// Day before month, always — "31 Aug", never "Aug 31". Built by hand because Intl's en-PH
// short form puts the month first, and the label has to read as a date range at a glance.
function dayMonth(date: BusinessDate): string {
  const d = parse(date);
  return `${d.getUTCDate()} ${MONTHS[d.getUTCMonth()]}`;
}

function dayMonthYear(date: BusinessDate): string {
  return `${dayMonth(date)} ${date.slice(0, 4)}`;
}

const MONTH_NAMES = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August',
  'September', 'October', 'November', 'December'];

/**
 * "1–14 Sep", "28 Jul – 3 Aug", "20 Dec 2025 – 4 Jan 2026" — and a complete month is its name,
 * "September", because "1–30 Sep vs 1–31 Aug" reads as a mismatch when it is the whole of both.
 */
export function formatRange(from: BusinessDate, to: BusinessDate): string {
  if (from === to) return dayMonthYear(from);
  if (from.endsWith('-01') && to === lastOfMonth(from)) {
    return `${MONTH_NAMES[Number(from.slice(5, 7)) - 1]} ${from.slice(0, 4)}`;
  }
  if (from.slice(0, 4) !== to.slice(0, 4)) {
    return `${dayMonthYear(from)} – ${dayMonthYear(to)}`;
  }
  if (from.slice(0, 7) === to.slice(0, 7)) {
    return `${parse(from).getUTCDate()}–${dayMonth(to)}`;
  }
  return `${dayMonth(from)} – ${dayMonth(to)}`;
}

/** "Apr 26" from "2026-04". */
export function formatMonth(yearMonth: string): string {
  return `${MONTHS[Number(yearMonth.slice(5, 7)) - 1]} ${yearMonth.slice(2, 4)}`;
}

export const WEEKDAYS = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'];
