import { request } from '../client';
import { requestBlob } from '../client';
import type {
  AddBillLineRequest,
  AddBillLineResult,
  Bill,
  BillLine,
  BillSummary,
  Paged,
  CheckoutPreview,
  Payment,
  PaymentPhoto,
  PaymentRequest,
  Receipt,
  UnsettledBill,
  VoidBillLineRequest,
} from '../types';

/**
 * One business day's settled sales, newest first. ADMIN — the owner browsing the night's
 * takings to find a receipt. Carries no cost or profit.
 */
export function fetchSettledBills(
  businessDate: string,
  page = 0,
  size = 50,
): Promise<Paged<BillSummary>> {
  return request<Paged<BillSummary>>('/bills', { query: { businessDate, page, size } });
}

/** Open bills on the current business day with nothing running — the floor header strip. */
export function fetchUnsettledBills(): Promise<UnsettledBill[]> {
  return request<UnsettledBill[]>('/bills/unsettled');
}

export function fetchBill(id: string): Promise<Bill> {
  return request<Bill>(`/bills/${id}`);
}

/** No price is sent: the server snapshots name, selling price and cost at the moment of sale. */
export function addBillLine(billId: string, body: AddBillLineRequest): Promise<AddBillLineResult> {
  return request<AddBillLineResult>(`/bills/${billId}/lines`, { method: 'POST', body });
}

/** The reason is required and non-blank, or the server returns 400. */
export function voidBillLine(
  billId: string,
  lineId: string,
  body: VoidBillLineRequest,
): Promise<BillLine> {
  return request<BillLine>(`/bills/${billId}/lines/${lineId}/void`, { method: 'POST', body });
}

/** A preview and nothing else: reading this writes nothing. */
export function fetchCheckout(billId: string): Promise<CheckoutPreview> {
  return request<CheckoutPreview>(`/bills/${billId}/checkout`);
}

/**
 * The request object is built per method by the caller, with the irrelevant keys absent
 * rather than blank. The server rejects on `!= null`, not on blank, so an empty-string
 * referenceNo on a cash sale is a 409 that reads like a server bug.
 */
export function payBill(billId: string, body: PaymentRequest): Promise<Payment> {
  return request<Payment>(`/bills/${billId}/payment`, { method: 'POST', body });
}

export function fetchReceipt(billId: string): Promise<Receipt> {
  return request<Receipt>(`/bills/${billId}/receipt`);
}

/** multipart/form-data, field name `file`. One photo per payment; a second is a 409. */
export function uploadPaymentPhoto(paymentId: string, file: File): Promise<PaymentPhoto> {
  const form = new FormData();
  form.append('file', file);
  return request<PaymentPhoto>(`/payments/${paymentId}/photo`, { method: 'POST', body: form });
}

/** ADMIN only, and raw bytes rather than the envelope. */
export function fetchPaymentPhoto(paymentId: string): Promise<Blob> {
  return requestBlob(`/payments/${paymentId}/photo`);
}
