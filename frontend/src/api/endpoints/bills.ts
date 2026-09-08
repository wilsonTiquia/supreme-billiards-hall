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
  LeaveUnpaidRequest,
  Receipt,
  SessionNote,
  UnpaidBill,
  UnsettledBill,
  VoidBillLineRequest,
  VoucherRedemption,
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

/**
 * OPEN bills with nothing running on them — the floor header strip. These are MISTAKES: the
 * session closed and nobody took payment, so the table reads free and nothing points at the
 * bill any more. Not the same list as `fetchUnpaidBills`, and they must not be merged.
 */
export function fetchUnsettledBills(): Promise<UnsettledBill[]> {
  return request<UnsettledBill[]>('/bills/unsettled');
}

/**
 * Debts: bills deliberately left unpaid, newest first, across every business date. Not scoped
 * to tonight — one of these can legitimately sit for a month, and a list that reset at the date
 * roll would mean the money was never collected.
 */
export function fetchUnpaidBills(): Promise<UnpaidBill[]> {
  return request<UnpaidBill[]>('/bills/unpaid');
}

/**
 * Records the sale without the money. Runs the same finalisation a checkout runs — totals
 * freeze, a receipt number is allocated — but takes no payment.
 *
 * 409 `SESSION_NOTE_REQUIRED` when the session carries no staff note and none is supplied:
 * the repair is to make the note field required, not to show the message.
 */
export function leaveBillUnpaid(billId: string, body: LeaveUnpaidRequest): Promise<UnpaidBill> {
  return request<UnpaidBill>(`/bills/${billId}/leave-unpaid`, { method: 'POST', body });
}

export function fetchBill(id: string): Promise<Bill> {
  return request<Bill>(`/bills/${id}`);
}

/** No price is sent: the server snapshots name, selling price and cost at the moment of sale. */
export function addBillLine(billId: string, body: AddBillLineRequest): Promise<AddBillLineResult> {
  return request<AddBillLineResult>(`/bills/${billId}/lines`, { method: 'POST', body });
}

/**
 * Knocks money off the whole bill — food and drink included, unlike a session's time reduction.
 * Both can apply to one bill.
 *
 * `chargeAmount` is what is being CHARGED, not what is coming off: the server computes the
 * discount from it, and returns the bill with the authoritative figures. 400 without a reason
 * or on a charge below 0.01; 409 on a charge above the subtotal, on a charge equal to it
 * (a bill of 0.00 could never be settled), or on a bill that is no longer OPEN.
 */
export function discountBill(
  billId: string,
  body: { chargeAmount: number; reason: string },
): Promise<Bill> {
  return request<Bill>(`/bills/${billId}/discount`, { method: 'POST', body });
}

/** Puts the bill back to its full amount. Audited like the discount itself. */
export function clearBillDiscount(billId: string): Promise<Bill> {
  return request<Bill>(`/bills/${billId}/discount`, { method: 'DELETE' });
}

/**
 * Spends a giveaway voucher against this bill.
 *
 * The code is sent as typed — the server normalises case, spaces and dashes, so "sb 7k4-m2q"
 * and "SB7K4M2Q" both work. Every refusal comes back as its own 409 message; there is no code
 * to branch on and none is needed, because each message says a different thing to the cashier.
 */
export function redeemVoucher(billId: string, code: string): Promise<VoucherRedemption> {
  return request<VoucherRedemption>(`/bills/${billId}/voucher`, {
    method: 'POST',
    body: { code },
  });
}

/** Puts the code back, unredeemed, for one entered against the wrong bill. Audited like the
 *  redemption itself. */
export function releaseVoucher(billId: string): Promise<VoucherRedemption> {
  return request<VoucherRedemption>(`/bills/${billId}/voucher`, { method: 'DELETE' });
}

/**
 * Finishes a bill that comes to nothing — a voucher covering all of it, or a comped rate.
 *
 * Not a payment of zero: `payment_amount_chk` refuses one and there is no row that could
 * record it. The bill closes, takes a receipt number and lands on the night's report like any
 * other sale; it simply had nothing to collect. The server refuses any bill with a figure on
 * it, so this can only ever complete on a total the operator can already see is 0.00.
 */
export function settleWithoutPayment(billId: string): Promise<Receipt> {
  return request<Receipt>(`/bills/${billId}/no-charge`, { method: 'POST' });
}

/** The reason is required and non-blank, or the server returns 400. */
export function voidBillLine(
  billId: string,
  lineId: string,
  body: VoidBillLineRequest,
): Promise<BillLine> {
  return request<BillLine>(`/bills/${billId}/lines/${lineId}/void`, { method: 'POST', body });
}

/**
 * Every note on every session this bill carried, oldest first, including the settlement note.
 * Nothing is dropped when the debt is collected — a year of these is what answers who keeps
 * playing on credit.
 */
export function fetchBillNotes(billId: string): Promise<SessionNote[]> {
  return request<SessionNote[]>(`/bills/${billId}/notes`);
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
