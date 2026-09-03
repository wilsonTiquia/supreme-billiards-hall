import { request } from '../client';
import type { FloorView, PoolTable, PoolTableRateRequest, PoolTableRequest, UUID } from '../types';

/** The floor view: every table and its live session, plus the server clock, in one call. */
export function fetchFloor(): Promise<FloorView> {
  return request<FloorView>('/tables');
}

export function createTable(body: PoolTableRequest): Promise<PoolTable> {
  return request<PoolTable>('/tables', { method: 'POST', body });
}

export function updateTable(id: UUID, body: PoolTableRequest): Promise<PoolTable> {
  return request<PoolTable>(`/tables/${id}`, { method: 'PUT', body });
}

/**
 * Closes the current rate period and opens a new one. History is never mutated: last month's
 * bills keep the rate they were charged at. A backdated overlap is a 409.
 */
export function changeTableRate(id: UUID, body: PoolTableRateRequest): Promise<PoolTable> {
  return request<PoolTable>(`/tables/${id}/rate`, { method: 'PUT', body });
}

/** Archives. Rejected with 409 while a session is open on the table. */
export function archiveTable(id: UUID): Promise<null> {
  return request<null>(`/tables/${id}`, { method: 'DELETE' });
}
