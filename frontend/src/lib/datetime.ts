import type { BusinessDate, IsoInstant } from '@/api/types';

/**
 * All instants arrive as ISO-8601 UTC and are rendered in Asia/Manila. The venue machine's
 * own timezone is never consulted — a laptop set to the wrong zone must not change what the
 * screen says about when a sale happened.
 */
const MANILA = 'Asia/Manila';

const timeOfDay = new Intl.DateTimeFormat('en-PH', {
  timeZone: MANILA,
  hour: '2-digit',
  minute: '2-digit',
  hour12: true,
});

const dateAndTime = new Intl.DateTimeFormat('en-PH', {
  timeZone: MANILA,
  day: 'numeric',
  month: 'short',
  hour: '2-digit',
  minute: '2-digit',
  hour12: true,
});

const longDate = new Intl.DateTimeFormat('en-PH', {
  timeZone: MANILA,
  weekday: 'long',
  day: 'numeric',
  month: 'long',
  year: 'numeric',
});

export function formatTime(instant: IsoInstant): string {
  return timeOfDay.format(new Date(instant));
}

export function formatDateTime(instant: IsoInstant): string {
  return dateAndTime.format(new Date(instant));
}

/**
 * A business date is a plain calendar label from the server, not an instant. It is parsed as
 * midday UTC so that rendering it can never slip a day across the timezone offset.
 */
export function formatBusinessDate(date: BusinessDate): string {
  return longDate.format(new Date(`${date}T12:00:00Z`));
}

/** Elapsed milliseconds as H:MM:SS, for the live table timers in Phase B. */
export function formatElapsed(milliseconds: number): string {
  const total = Math.max(0, Math.floor(milliseconds / 1000));
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const seconds = total % 60;
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${hours}:${pad(minutes)}:${pad(seconds)}`;
}
