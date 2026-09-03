import { request } from '../client';
import type { OpenSessionRequest, Session } from '../types';

/**
 * The server stamps the start time — no clock is sent. A 409 here means another tab opened
 * this table first; the database index decides, so exactly one caller can win.
 */
export function openSession(body: OpenSessionRequest): Promise<Session> {
  return request<Session>('/sessions', { method: 'POST', body });
}

export function fetchSession(id: string): Promise<Session> {
  return request<Session>(`/sessions/${id}`);
}

export function pauseSession(id: string, reason?: string): Promise<Session> {
  return request<Session>(`/sessions/${id}/pause`, { method: 'POST', body: { reason } });
}

export function resumeSession(id: string): Promise<Session> {
  return request<Session>(`/sessions/${id}/resume`, { method: 'POST' });
}

/** Ends the segments and writes the TIME lines. Does not take payment. */
/**
 * Charges less table time than was played. Taken at checkout, before payment.
 *
 * Only downwards — the server rejects anything above the actual minutes, because charging for
 * time that was not played is an overcharge rather than a discount. The reason is required and
 * the reduction appears beside comps and friend rates in the losses drill-down.
 */
export function overrideBilledMinutes(
  sessionId: string,
  body: { billedMinutes: number; reason: string },
): Promise<Session> {
  return request<Session>(`/sessions/${sessionId}/billed-minutes`, { method: 'POST', body });
}

export function closeSession(id: string): Promise<Session> {
  return request<Session>(`/sessions/${id}/close`, { method: 'POST' });
}
