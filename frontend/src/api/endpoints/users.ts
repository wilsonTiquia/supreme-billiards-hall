import { request } from '../client';
import type {
  CreateUserRequest,
  ResetPasswordRequest,
  StaffUser,
  UpdateUserRequest,
} from '../types';

/**
 * The staff this admin can manage: their branch's people, plus every global admin. A global
 * admin belongs to no branch and so is staff of every one — they appear here on purpose, and
 * without them no admin would be listed at all. ADMIN only; an employee gets 403.
 */
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

/** ADMIN adds someone who can sign in. They are forced to replace the temporary on first use. */
export function createUser(body: CreateUserRequest): Promise<StaffUser> {
  return request<StaffUser>('/users', { method: 'POST', body });
}

/** ADMIN edits a name, a role, or whether the account can sign in. */
export function updateUser(id: string, body: UpdateUserRequest): Promise<StaffUser> {
  return request<StaffUser>(`/users/${id}`, { method: 'PUT', body });
}

/**
 * ADMIN retires an account. Archived, never deleted — the audit log, bill lines and payments
 * point at this person for ever — and the username becomes free for a replacement to take.
 */
export function archiveUser(id: string): Promise<StaffUser> {
  return request<StaffUser>(`/users/${id}`, { method: 'DELETE' });
}
