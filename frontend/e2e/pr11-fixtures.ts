import type { Page } from '@playwright/test';
import type { PoolTable, StaffUser, SetupItem } from '../src/api/types';

export const tables: PoolTable[] = [
  { id: 'hourly', name: 'Table 1', tableNumber: 1, isPremium: true, isActive: true, ratePerHour: 200, ratePerMinute: 3.3333, effectiveRatePerHour: 199.998, session: null },
  { id: 'minute', name: 'Practice table', tableNumber: 2, isPremium: false, isActive: true, ratePerHour: null, ratePerMinute: 2.1234, effectiveRatePerHour: 127.404, session: null },
  { id: 'long', name: 'Tournament table reserved for the visiting league and evening practice', tableNumber: 3, isPremium: false, isActive: false, ratePerHour: 240, ratePerMinute: 4, effectiveRatePerHour: 240, session: null },
];
export const staff: StaffUser[] = [
  { id: 'owner', username: 'owner', fullName: 'Owner', role: 'ADMIN', active: true, mustChangePassword: false },
  { id: 'counter', username: 'counter', fullName: 'Front Counter', role: 'EMPLOYEE', active: true, mustChangePassword: true },
  { id: 'long', username: 'evening-supervisor', fullName: 'Evening supervisor for tournaments and visiting league players', role: 'EMPLOYEE', active: false, mustChangePassword: false },
];
export async function maintenanceFixtures(page: Page) {
  await page.route('**/api/v1/tables', route => route.fulfill({ json: { success: true, data: { tables, serverNow: new Date().toISOString() } } }));
  await page.route('**/api/v1/users', route => route.fulfill({ json: { success: true, data: staff } }));
  for (const kind of ['tables', 'staff']) {
    const data: SetupItem[] = (kind === 'tables' ? tables : staff).map(row => ({ id: row.id,
      name: 'name' in row ? row.name : row.fullName, archivedAt: null, canDelete: row.id === 'long',
      deletionReason: 'History is kept when archived.', blockedReason: row.id === 'owner' ? 'You cannot archive yourself.' : null,
    }));
    await page.route(`**/api/v1/setup/${kind}`, route => route.fulfill({ json: { success: true, data } }));
  }
}
