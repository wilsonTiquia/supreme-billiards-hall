import { request } from '../client';
import type { Payment, QuickSaleQuote, QuickSaleLineRequest, QuickSaleRequest } from '../types';

/**
 * Prices the lines without selling them.
 *
 * This exists so the browser never sums line totals — frontend/CLAUDE.md §2. The amount the
 * quick sale then sends must equal the bill total exactly, and both figures come from here,
 * so there is no arithmetic on this side of the wire to get wrong.
 */
export function quoteQuickSale(lines: QuickSaleLineRequest[]): Promise<QuickSaleQuote> {
  return request<QuickSaleQuote>('/quick-sales/quote', { method: 'POST', body: { lines } });
}

/** Creates the bill, moves the stock and takes the payment in one transaction. */
export function recordQuickSale(body: QuickSaleRequest): Promise<Payment> {
  return request<Payment>('/quick-sales', { method: 'POST', body });
}
