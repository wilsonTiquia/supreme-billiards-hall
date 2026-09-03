import { request } from '../client';
import type { ResetPasswordRequest, StaffUser } from '../types';

/** The staff of the admin's branch. ADMIN only; an employee gets 403. */
export function listUsers(): Promise<StaffUser[]> {
  return request<StaffUser[]>('/users');
}

/**
 * ADMIN sets another user's password. The server forces that user to change it on next login,
 * so what the owner types here is always a temporary the staff member replaces themselves.
 */
export function resetUserPassword(id: string, body: ResetPasswordRequest): Promise<null> {
  return request<null>(`/users/${id}/password`, { method: 'PUT', body });
}
