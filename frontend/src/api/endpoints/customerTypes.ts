import { request } from '../client';
import type { CustomerType, CustomerTypeRequest, UUID } from '../types';

export function fetchCustomerTypes(): Promise<CustomerType[]> {
  return request<CustomerType[]>('/customer-types');
}

export function createCustomerType(body: CustomerTypeRequest): Promise<CustomerType> {
  return request<CustomerType>('/customer-types', { method: 'POST', body });
}

export function updateCustomerType(id: UUID, body: CustomerTypeRequest): Promise<CustomerType> {
  return request<CustomerType>(`/customer-types/${id}`, { method: 'PUT', body });
}

export function archiveCustomerType(id: UUID): Promise<null> {
  return request<null>(`/customer-types/${id}`, { method: 'DELETE' });
}
