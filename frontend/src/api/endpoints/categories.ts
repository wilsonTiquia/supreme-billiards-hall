import { request } from '../client';
import type { Category, CategoryRequest, UUID } from '../types';

export function fetchCategories(): Promise<Category[]> {
  return request<Category[]>('/categories');
}

export function createCategory(body: CategoryRequest): Promise<Category> {
  return request<Category>('/categories', { method: 'POST', body });
}

export function updateCategory(id: UUID, body: CategoryRequest): Promise<Category> {
  return request<Category>(`/categories/${id}`, { method: 'PUT', body });
}

/** Archives, and the archived name becomes reusable. */
export function archiveCategory(id: UUID): Promise<null> {
  return request<null>(`/categories/${id}`, { method: 'DELETE' });
}
