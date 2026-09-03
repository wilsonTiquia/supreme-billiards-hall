import { request } from '../client';
import { isApiError } from '../errors';
import type { CurrentUser, LoginRequest, SelectBranchRequest } from '../types';

export function login(body: LoginRequest): Promise<CurrentUser> {
  // skipAuthRedirect: a 401 here is a bad password, a form error — not a dead session.
  // Without this the app would try to redirect to the page it is already on.
  return request<CurrentUser>('/auth/login', { method: 'POST', body, skipAuthRedirect: true });
}

/**
 * The boot-time probe.
 *
 * Only a 401 means "nobody is logged in yet", which is the ordinary cold start and resolves
 * to null. Every other failure — above all an unreachable backend — is rethrown, because
 * "the server is down" and "you are signed out" are different things and must not look the
 * same on screen. Swallowing the difference would show a login form the operator cannot
 * possibly use.
 */
export async function fetchMe(): Promise<CurrentUser | null> {
  try {
    return await request<CurrentUser>('/auth/me', { skipAuthRedirect: true });
  } catch (error) {
    if (isApiError(error) && error.status === 401) return null;
    throw error;
  }
}

export function logout(): Promise<null> {
  return request<null>('/auth/logout', { method: 'POST' });
}

export function selectBranch(body: SelectBranchRequest): Promise<CurrentUser> {
  return request<CurrentUser>('/auth/branch', { method: 'PUT', body });
}
