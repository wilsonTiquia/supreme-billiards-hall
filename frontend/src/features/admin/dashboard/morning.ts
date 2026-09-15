import type { BusinessDayStatus, CashCount } from '@/api/types';

export function validNight(date: string): boolean {
  return /^\d{4}-\d{2}-\d{2}$/.test(date) && !Number.isNaN(Date.parse(`${date}T00:00:00Z`)) &&
    new Date(`${date}T00:00:00Z`).toISOString().slice(0, 10) === date;
}

function shiftDay(date: string, days: number): string {
  const shifted = new Date(`${date}T00:00:00Z`);
  shifted.setUTCDate(shifted.getUTCDate() + days);
  return shifted.toISOString().slice(0, 10);
}

// The database's business-date label changes at 10am, but trading ends at 5am.
// Between 5am and 10am the current label already describes a completed night.
export function nightHasEnded(date: string, serverNow: string): boolean {
  return validNight(date) && Date.parse(serverNow) >= Date.parse(`${shiftDay(date, 1)}T05:00:00+08:00`);
}

export function lastCompletedNight(day: Pick<BusinessDayStatus, 'businessDate' | 'serverNow'>): string {
  return nightHasEnded(day.businessDate, day.serverNow) ? day.businessDate : shiftDay(day.businessDate, -1);
}

export function cashStatus(count: CashCount | null, running: boolean, loading = false, error = false): {
  text: string; amount?: number; danger: boolean;
} {
  if (error) return { text: 'Cash check unavailable', danger: true };
  if (loading) return { text: 'Checking cash…', danger: false };
  if (!count) return { text: 'Not counted yet', danger: !running };
  if (count.salesAfterClose > 0 || count.expensesAfterClose > 0) {
    return { text: 'Cash needs recounting · activity after close', danger: true };
  }
  if (count.variance === 0) return { text: 'Cash balanced', danger: false };
  return { text: count.variance < 0 ? 'Short by ' : 'Over by ', amount: Math.abs(count.variance), danger: true };
}
