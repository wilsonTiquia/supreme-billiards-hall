import { request } from '../client';
import type { Expense, ExpenseRequest, UUID, VoidExpenseRequest } from '../types';

/**
 * The day's expenses, voided ones included and flagged, newest first.
 *
 * Available to EMPLOYEE as well as ADMIN, which is deliberate rather than an oversight: an
 * operating expense is not product cost and not profit, and the counter has to be able to see
 * the ₱850 they just typed in order to spot a mistake. Omitting the date means tonight.
 */
export function fetchExpenses(businessDate?: string): Promise<Expense[]> {
  return request<Expense[]>('/expenses', { query: { businessDate } });
}

/** Any authenticated user. Refused with a 409 once the day is closed. */
export function recordExpense(body: ExpenseRequest): Promise<Expense> {
  return request<Expense>('/expenses', { method: 'POST', body });
}

/**
 * The reason is mandatory — the server rejects a blank one, and so does the database. The row
 * is retained and excluded from every total, never deleted, exactly like a voided bill line.
 */
export function voidExpense(id: UUID, body: VoidExpenseRequest): Promise<Expense> {
  return request<Expense>(`/expenses/${id}/void`, { method: 'POST', body });
}
