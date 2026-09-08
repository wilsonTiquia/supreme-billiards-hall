import { request } from '../client';
import type { ExpenseCategory, ExpenseCategoryRequest, UUID } from '../types';

/** Readable by anyone authenticated — the counter needs the dropdown to record an expense. */
export function fetchExpenseCategories(): Promise<ExpenseCategory[]> {
  return request<ExpenseCategory[]>('/expense-categories');
}

/** ADMIN only, like the rest of the writes below. An employee gets 403. */
export function createExpenseCategory(body: ExpenseCategoryRequest): Promise<ExpenseCategory> {
  return request<ExpenseCategory>('/expense-categories', { method: 'POST', body });
}

export function updateExpenseCategory(
  id: UUID,
  body: ExpenseCategoryRequest,
): Promise<ExpenseCategory> {
  return request<ExpenseCategory>(`/expense-categories/${id}`, { method: 'PUT', body });
}

/** Archives rather than deletes — recorded expenses still reference it. */
export function archiveExpenseCategory(id: UUID): Promise<null> {
  return request<null>(`/expense-categories/${id}`, { method: 'DELETE' });
}
