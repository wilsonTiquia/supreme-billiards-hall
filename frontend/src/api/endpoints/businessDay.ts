import { request } from '../client';
import type {
  BusinessDayStatus,
  CashCount,
  CashCountRequest,
  CashCountUpdateRequest,
  UncountedDay,
} from '../types';

export function fetchCurrentBusinessDay(): Promise<BusinessDayStatus> {
  return request<BusinessDayStatus>('/business-day/current');
}

export function fetchOpenSessions(date: string): Promise<BusinessDayStatus> {
  return request<BusinessDayStatus>(`/business-day/${date}/open-sessions`);
}

/**
 * Earlier nights that traded and were never counted. The 05:00 job closes leftover sessions
 * but not the day, so such a night stays open and says nothing about itself.
 *
 * Deliberately carries no cash figure: that would preview the expected total and defeat the
 * blind count.
 */
export function fetchUncountedDays(): Promise<UncountedDay[]> {
  return request<UncountedDay[]>('/business-day/uncounted');
}

/** The recorded count, or null when the day has not been counted yet. */
export function fetchCashCount(date: string): Promise<CashCount | null> {
  return request<CashCount | null>(`/business-day/${date}/cash-count`);
}

/**
 * `expectedCash` is computed and frozen server-side and is never sent. It is also not
 * readable beforehand — there is no GET — so the count is blind, which is the right control:
 * a counter who can see the target first is not really counting.
 */
export function recordCashCount(date: string, body: CashCountRequest): Promise<CashCount> {
  return request<CashCount>(`/business-day/${date}/cash-count`, { method: 'POST', body });
}

/**
 * ADMIN only. An employee gets 403, and it is refused once the day is closed — the original
 * figure is never destroyed, it is written to the audit log.
 */
export function correctCashCount(date: string, body: CashCountUpdateRequest): Promise<CashCount> {
  return request<CashCount>(`/business-day/${date}/cash-count`, { method: 'PUT', body });
}

/**
 * Counts the drawer again after the day was closed and then traded on.
 *
 * Not admin-only: the shift that closed up and then served a straggler has to be able to
 * finish the night. The original count goes to the audit log and the day reopens, so the
 * ordinary close runs a second time and records who signed it off.
 */
export function recountAfterClose(date: string, body: CashCountRequest): Promise<CashCount> {
  return request<CashCount>(`/business-day/${date}/recount`, { method: 'POST', body });
}

/**
 * Refuses with a 409 while any session is open, if the drawer has not been counted, or if the
 * day has already been closed.
 */
export function closeBusinessDay(date: string): Promise<BusinessDayStatus> {
  return request<BusinessDayStatus>(`/business-day/${date}/close`, { method: 'POST' });
}
