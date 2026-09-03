import { request } from '../client';
import type { Settings, SettingsRequest } from '../types';

/**
 * Branch configuration the owner changes from a screen. ADMIN only.
 *
 * Deliberately not a generic key/value surface: branch_setting also holds keys the till reasons
 * about, and those are not a text box.
 */
export function fetchSettings(): Promise<Settings> {
  return request<Settings>('/settings');
}

/** Writes an audit row with both figures. Nights already counted keep their own recorded float. */
export function updateSettings(body: SettingsRequest): Promise<Settings> {
  return request<Settings>('/settings', { method: 'PUT', body });
}
