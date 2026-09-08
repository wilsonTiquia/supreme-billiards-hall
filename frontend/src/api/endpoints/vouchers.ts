import { request } from '../client';
import type { UUID, Voucher, VoucherBatch, VoucherBatchRequest, VoucherStatus } from '../types';

/**
 * The giveaway, from the owner's side. Every route here is ADMIN, and that is a security
 * boundary rather than a layout choice: a staff member who can read a list of unredeemed codes
 * can redeem them. Spending one lives on the bill endpoints, where the counter works.
 */

/** Generates the batch in one transaction and returns the codes — the only response that ever
 *  carries live codes, so the owner can copy or print them. */
export function createVoucherBatch(body: VoucherBatchRequest): Promise<VoucherBatch> {
  return request<VoucherBatch>('/voucher-batches', { method: 'POST', body });
}

/** Each batch with its issued / redeemed / expired / outstanding counts. `codes` is null here. */
export function fetchVoucherBatches(): Promise<VoucherBatch[]> {
  return request<VoucherBatch[]>('/voucher-batches');
}

export function fetchVouchers(batchId?: UUID, status?: VoucherStatus): Promise<Voucher[]> {
  const params = new URLSearchParams();
  if (batchId) params.set('batchId', batchId);
  if (status) params.set('status', status);
  const query = params.toString();
  return request<Voucher[]>(`/vouchers${query ? `?${query}` : ''}`);
}
