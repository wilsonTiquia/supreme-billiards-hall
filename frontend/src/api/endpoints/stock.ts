import { request } from '../client';
import type {
  LowStockLine,
  StockCompBatchRequest,
  StockCompRequest,
  StockCorrectionRequest,
  StockDelivery,
  StockDeliveryRequest,
  StockMovement,
} from '../types';

/** Recomputes each product's moving weighted average cost. */
/**
 * A basket given away in one go. One transaction server-side: a basket of four never
 * half-commits. Writes STAFF_COMP movements — no bill, no payment, no receipt, no revenue.
 */
export function recordCompBatch(body: StockCompBatchRequest): Promise<StockMovement[]> {
  return request<StockMovement[]>('/stock/comps/batch', { method: 'POST', body });
}

export function recordDelivery(body: StockDeliveryRequest): Promise<StockDelivery> {
  return request<StockDelivery>('/stock/deliveries', { method: 'POST', body });
}

/**
 * Records the *delta* to reach `newQuantity`. Correcting to the quantity already held is a
 * 409 — that is not a failure, it means there was nothing to correct.
 */
export function recordCorrection(body: StockCorrectionRequest): Promise<StockMovement> {
  return request<StockMovement>('/stock/corrections', { method: 'POST', body });
}

/** A counter action, not an admin one. `note` is required. */
export function recordComp(body: StockCompRequest): Promise<StockMovement> {
  return request<StockMovement>('/stock/comps', { method: 'POST', body });
}

export function fetchLowStock(): Promise<LowStockLine[]> {
  return request<LowStockLine[]>('/stock/low');
}
