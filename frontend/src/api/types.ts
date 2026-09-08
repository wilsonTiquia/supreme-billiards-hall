/**
 * Every API shape, in one place, derived from docs/API-CONTRACT.md and cross-checked against
 * the DTO classes. Section headings mirror the contract's own numbering so the two can be
 * diffed by eye. Do not redeclare any of these in a feature folder.
 */

/* ── Primitive aliases ───────────────────────────────────────────────────────────────
   Money and rates arrive as JSON numbers (2 and 4 decimal places respectively). These
   aliases carry the intent; the rule that the browser never does arithmetic on them is in
   frontend/CLAUDE.md §2, not in the type system. */
export type UUID = string;
/** ISO-8601 UTC instant, e.g. "2026-08-31T12:37:26.610539Z". Render in Asia/Manila. */
export type IsoInstant = string;
/** Business date, "YYYY-MM-DD". Computed by the database; never derived in the client. */
export type BusinessDate = string;
export type Money = number;
export type Rate = number;
export type Quantity = number;

/* ── Enums (§2, §4, §5, §6, §7, §8, §10) ─────────────────────────────────────────── */
export type Role = 'EMPLOYEE' | 'ADMIN';
export type SessionStatus = 'OPEN' | 'PAUSED' | 'CLOSED' | 'AUTO_CLOSED' | 'VOIDED';
/** The floor view only ever shows a live session, so it narrows to these two. */
export type LiveSessionStatus = Extract<SessionStatus, 'OPEN' | 'PAUSED'>;
export type SessionCloseKind = 'MANUAL' | 'AUTO_END_OF_DAY';
/**
 * UNSETTLED is a finished sale that nobody has paid for yet — the regular who settles next
 * month. It is NOT the same as an OPEN bill nobody checked out: that is a mistake the floor
 * strip exists to catch, this is a decision with a name against it.
 */
export type BillStatus = 'OPEN' | 'CLOSED' | 'UNSETTLED' | 'VOIDED' | 'MERGED';
export type BillLineKind = 'TIME' | 'PRODUCT';
export type PaymentMethod = 'CASH' | 'GCASH' | 'MAYA';
export type StockReason = 'SALE' | 'SALE_VOID' | 'DELIVERY' | 'CORRECTION' | 'STAFF_COMP';
/**
 * `SYSTEM` notes are written by the server at an event — currently settlement. The API never
 * accepts a kind from the client, so a staff member cannot forge one by typing the sentence.
 * Mark the two apart on screen: that distinction is the whole reason the field exists.
 */
export type SessionNoteKind = 'STAFF' | 'SYSTEM';

/* ── §2 Auth ─────────────────────────────────────────────────────────────────────── */
export interface LoginRequest {
  username: string;
  password: string;
}

export interface CurrentUser {
  id: UUID;
  username: string;
  fullName: string;
  role: Role;
  /** Null for a global admin with no branch selected; every other call then 409s. */
  branchId: UUID | null;
  branchName: string | null;
  /**
   * Whether the checkout transition should play, carried on the session so the counter can
   * read it without being an ADMIN. Flipping it in Admin → Settings reaches every till on its
   * next sign-in or refresh — no restart, no deploy.
   */
  checkoutAnimation: boolean;
  /**
   * When true the user must change their password before anything else works: the server gates
   * every other call with 403 PASSWORD_CHANGE_REQUIRED. Set by the go-live default-password
   * path and by an admin reset — a handed-over password is always temporary. The SPA routes to
   * the forced-change screen when this is set.
   */
  mustChangePassword: boolean;
}

export interface SelectBranchRequest {
  branchId: UUID;
}

export interface ChangePasswordRequest {
  currentPassword: string;
  newPassword: string;
}

/** ADMIN sets another user's password. No current password — the admin is the authority. */
export interface ResetPasswordRequest {
  newPassword: string;
}

/** A row in Admin → Staff. No password material ever crosses the wire. */
export interface StaffUser {
  id: UUID;
  username: string;
  fullName: string;
  role: Role;
  active: boolean;
  /** Still owes a password change — a handed-over temporary, or a go-live default. */
  mustChangePassword: boolean;
}

/* ── §3 Time ─────────────────────────────────────────────────────────────────────── */
export interface ServerTime {
  serverNow: IsoInstant;
}

/* ── §4 Catalog ──────────────────────────────────────────────────────────────────── */
export interface Category {
  id: UUID;
  name: string;
  sortOrder: number | null;
}

export interface CategoryRequest {
  name: string;
  sortOrder?: number;
}

/** What an EMPLOYEE receives. `avgCost` is absent from the JSON, not null. */
export interface Product {
  id: UUID;
  name: string;
  categoryId: UUID | null;
  sellingPrice: Money;
  qtyOnHand: Quantity;
  isActive: boolean;
  /**
   * Null for a live product. Set means archived: it is off the POS grid, cannot be added to a
   * bill, and appears in the admin catalogue only when `includeArchived` is asked for.
   */
  archivedAt: IsoInstant | null;
  /**
   * Null when the product has no picture, which is the normal case — every screen renders
   * without one. When present it is also the cache-buster for the image URL, so a replaced
   * image is seen at once and an unchanged one is served from the browser cache.
   */
  imageSha256: string | null;
}

/** What an ADMIN receives: the same, plus cost. Employee screens must type against Product. */
export interface ProductAdmin extends Product {
  avgCost: Money;
}

export interface ProductRequest {
  name: string;
  categoryId?: UUID;
  sellingPrice: Money;
  isActive?: boolean;
}

export interface ProductImage {
  productId: UUID;
  imageSha256: string;
  imageBytes: number;
}

export interface ProductQuery {
  categoryId?: UUID;
  q?: string;
  /** Defaults to true server-side. */
  activeOnly?: boolean;
  /**
   * Admin catalogue only. The server ignores it for an employee, so an archived product can
   * never reach the counter even if this were sent from there.
   */
  includeArchived?: boolean;
}

export interface StockMovement {
  id: UUID;
  productId: UUID;
  productName: string;
  reason: StockReason;
  quantityDelta: Quantity;
  qtyAfter: Quantity;
  unitCost: Money | null;
  billLineId: UUID | null;
  deliveryId: UUID | null;
  note: string | null;
  occurredAt: IsoInstant;
  businessDate: BusinessDate;
}

export interface CustomerType {
  id: UUID;
  name: string;
  /** Enables the friend-rate field on the start-session modal. */
  allowsRateOverride: boolean;
  isDefault: boolean;
  sortOrder: number | null;
}

export interface CustomerTypeRequest {
  name: string;
  allowsRateOverride?: boolean;
  isDefault?: boolean;
  sortOrder?: number;
}

/* ── §5 Floor view and tables ────────────────────────────────────────────────────── */

/**
 * Which kind of rate override a session carries.
 *
 * A PRICING MODE, not a kind of customer. `PROMO` is happy hour — an event, so it is ungated
 * and runs on any customer type including Regular; `FRIEND` is a favour and is offered only
 * where `customerType.allowsRateOverride`. Both bill through the same rate fields; this only
 * says which it was, so the report can tell a promo from a favour.
 */
export type RateOverrideKind = 'FRIEND' | 'PROMO';

/** The occupied-table summary. Note `sessionId`, not `id` — it differs from Session. */
export interface TableSessionSummary {
  sessionId: UUID;
  billId: UUID;
  /** Which table this session is on — the end-of-day list has no other way to say. */
  poolTableName: string;
  status: LiveSessionStatus;
  customerTypeId: UUID;
  customerTypeName: string;
  openedAt: IsoInstant;
  /** Elapsed less pauses, floored. The figure the customer is actually charged. */
  billedMinutes: number;
  /**
   * The same elapsed, exact to the second and not floored. Nothing is billed on it — it is
   * what the local counter anchors on, so the display agrees with the server continuously
   * instead of trailing it by up to 59 seconds.
   */
  billedSeconds: number;
  /** Zero on a flat session: the segments are not priced, the session is. Read `flatAmount`. */
  ratePerMinute: Rate;
  /**
   * The fixed charge, when this session was opened on tournament pricing. Null on a metered
   * session. The floor card leads with this rather than the table's configured rate, which is
   * still whatever it always was and would read as a plausible per-minute figure.
   */
  flatAmount: Money | null;
  /**
   * Which kind of override, when the session carries one. Null otherwise. The card names an
   * override after the customer type, which is right for a favour and wrong for a promo —
   * happy hour runs on any type, so "Regular rate" would be plausible and untrue.
   */
  rateOverrideKind: RateOverrideKind | null;
  timeAmount: Money;
  /** Units of product on the bill so far — one round or six, which the value alone cannot say. */
  itemCount: Quantity;
  itemTotal: Money;
  /**
   * `timeAmount + itemTotal`, added server-side. The bill carries no total until checkout
   * writes the TIME lines, so this is the only trustworthy "so far" figure on a live table.
   * Display it; never sum the two components here.
   */
  runningTotal: Money;
}

export interface PoolTable {
  id: UUID;
  name: string;
  tableNumber: number | null;
  isActive: boolean;
  /** The rate that bills. The two below are for the rate screen and charge nothing. */
  ratePerMinute: Rate;
  /**
   * What the admin typed, when they configured this table hourly. Null on a table configured
   * per minute — do not fill that in by multiplying, it is a number they never typed.
   */
  ratePerHour: Money | null;
  /**
   * 60 x ratePerMinute, from the server. What an hour is actually priced at, as against what
   * was typed: PHP 200/hour stores 3.3333/min, whose effective hourly rate is 199.998. Shown
   * whenever it differs from ratePerHour, because the admin is entitled to know.
   */
  effectiveRatePerHour: Money | null;
  /** Null when the table is free. */
  session: TableSessionSummary | null;
}

/** GET /tables — an object, not an array. Carries the clock so the timer needs no extra call. */
export interface FloorView {
  tables: PoolTable[];
  serverNow: IsoInstant;
}

/**
 * Exactly one of ratePerMinute and ratePerHour. Neither or both is a 400 — the rate is
 * required, and which of the two you send is the input mode, not an option to combine.
 */
export interface PoolTableRequest {
  name: string;
  tableNumber?: number;
  ratePerMinute?: Rate;
  ratePerHour?: Money;
  isActive?: boolean;
}

/** Exactly one of the two rates, as with PoolTableRequest. */
export interface PoolTableRateRequest {
  ratePerMinute?: Rate;
  ratePerHour?: Money;
  effectiveFrom?: IsoInstant;
}

/* ── §6 Sessions ─────────────────────────────────────────────────────────────────── */
export interface OpenSessionRequest {
  tableId: UUID;
  customerTypeId: UUID;
  /**
   * The friend rate, in whichever unit was typed. AT MOST one of the two — both in one request
   * is a 400, and neither means charge the table's standard rate, which is the usual case.
   * Only accepted when the customer type has allowsRateOverride; otherwise 409.
   *
   * Zero is legitimate: a comped game.
   */
  rateOverridePerMinute?: Rate;
  rateOverridePerHour?: Money;
  rateOverrideReason?: string;
  /**
   * Which kind of override the rate above is. `PROMO` is ungated and works on any customer
   * type, and REQUIRES `rateOverrideReason` — an unlabelled promo is unmeasurable, which is the
   * whole point of the feature. `FRIEND` keeps its `allowsRateOverride` gate and its optional
   * reason. Omitted alongside a rate means `FRIEND`; sent without a rate is a 400.
   */
  rateOverrideKind?: RateOverrideKind;
  /**
   * Tournament pricing: a fixed charge for the whole session however long it runs. Mutually
   * exclusive with either friend-rate field — both in one request is a 400 — and `flatRateReason`
   * is required alongside it. Zero is legitimate. Not gated on the customer type.
   */
  flatAmount?: Money;
  flatRateReason?: string;
}

export interface SessionSegment {
  id: UUID;
  poolTableId: UUID;
  poolTableName: string;
  seq: number;
  /** The rate this segment was billed at, snapshotted. */
  ratePerMinute: Rate;
  startedAt: IsoInstant;
  endedAt: IsoInstant | null;
  billedMinutes: number;
  amount: Money;
}

export interface SessionPause {
  id: UUID;
  pausedAt: IsoInstant;
  resumedAt: IsoInstant | null;
  reason: string | null;
}

/** Returned by all five session routes. */
export interface Session {
  id: UUID;
  billId: UUID;
  poolTableId: UUID;
  poolTableName: string;
  customerTypeId: UUID;
  customerTypeName: string;
  status: SessionStatus;
  openedAt: IsoInstant;
  closedAt: IsoInstant | null;
  closeKind: SessionCloseKind | null;
  standardRatePerMinute: Rate;
  rateOverridePerMinute: Rate | null;
  /**
   * The same pair as typed, when either was entered hourly. Both null on a per-minute table
   * with a per-minute friend rate. Display only — the money comes from the per-minute pair.
   */
  standardRatePerHour: Money | null;
  rateOverridePerHour: Money | null;
  /** Which kind of override is in effect, for the banner's label. Null when there is none. */
  rateOverrideKind: RateOverrideKind | null;
  /**
   * The fixed charge, when the session was opened on tournament pricing. Null on a metered
   * session. When set, `timeAmount` equals it from the first second and never moves — the timer
   * keeps running, the charge does not.
   */
  flatAmount: Money | null;
  flatRateReason: string | null;
  billedMinutes: number;
  /** Exact billable elapsed in seconds, unfloored. For the counter only; money uses minutes. */
  billedSeconds: number;
  timeAmount: Money;
  itemCount: Quantity;
  itemTotal: Money;
  /** `timeAmount + itemTotal`, computed server-side. */
  runningTotal: Money;
  segments: SessionSegment[];
  pauses: SessionPause[];
  serverNow: IsoInstant;
}

export interface PauseSessionRequest {
  reason?: string;
}

/**
 * Who was on the table. Append-only: there is no edit and no delete, here or on the server,
 * and there will not be one — a note about who owes money that an employee can quietly remove
 * defeats the point of writing it. A wrong note is corrected by a later note.
 */
export interface SessionNote {
  id: UUID;
  sessionId: UUID;
  kind: SessionNoteKind;
  /** Free text. Rendered by React and so escaped; never `dangerouslySetInnerHTML`. */
  body: string;
  authorId: UUID;
  authorUsername: string;
  createdAt: IsoInstant;
}

/** Only the words. The author and the kind are decided server-side. */
export interface AddSessionNoteRequest {
  /** Required, non-blank, at most 280 characters, or 400. */
  body: string;
}

/* ── §7 Bills, orders and voids ──────────────────────────────────────────────────── */

/** What an EMPLOYEE receives: no unitCost, no lineCost. */
export interface BillLine {
  id: UUID;
  lineKind: BillLineKind;
  seq: number;
  productId: UUID | null;
  sessionId: UUID | null;
  /** Snapshotted at the moment of sale. Never re-read from the product. */
  description: string;
  /** On a TIME line this is the whole charge for the segment, not a per-minute figure. */
  unitPrice: Money;
  quantity: Quantity;
  billedMinutes: number | null;
  lineTotal: Money;
  /** Set on a voided line, which is retained and excluded from every total. */
  voidedAt: IsoInstant | null;
  voidReason: string | null;
}

export interface BillLineAdmin extends BillLine {
  unitCost: Money;
  lineCost: Money;
}

export interface Bill {
  id: UUID;
  status: BillStatus;
  customerTypeId: UUID;
  customerTypeName: string;
  openedAt: IsoInstant;
  closedAt: IsoInstant | null;
  businessDate: BusinessDate;
  /** The optimistic lock. Echo it back on checkout as billVersion. */
  version: number;
  subtotalTime: Money;
  subtotalItems: Money;
  /**
   * Money knocked off the whole bill at the counter — food and drink included, unlike a
   * session's time reduction. A bill can carry both.
   *
   * A FIXED amount, never a rate: add a line afterwards and the total goes up while this stays
   * exactly where it was put. Zero, never null, when none was given.
   */
  discountAmount: Money;
  discountReason: string | null;
  discountByUsername: string | null;
  discountAt: IsoInstant | null;
  /**
   * Free table time given away as a prize and redeemed at the counter.
   *
   * The second reduction, and independent of the discount: this one covers a measured quantity
   * of TIME at the rate that time was billed at, and reaches nothing else. `voucherMinutes` is
   * what the code was worth and `voucherMinutesCovered` is what it reached — the difference is
   * what the customer forfeited, because unused minutes are not refunded.
   *
   * `voucherCode` is the display form, SB-7K4-M2Q. Zero and nulls when no voucher was used.
   */
  voucherAmount: Money;
  voucherCode: string | null;
  voucherMinutes: number | null;
  voucherMinutesCovered: number | null;
  /**
   * subtotalTime + subtotalItems - discountAmount - voucherAmount. What is actually being
   * charged. Both reductions are fixed amounts; adding a line raises this and leaves them.
   */
  totalAmount: Money;
  lines: BillLine[];
  sessions: TableSessionSummary[];
}

export interface BillAdmin extends Bill {
  lines: BillLineAdmin[];
  totalCost: Money;
  grossProfit: Money;
}

export interface AddBillLineRequest {
  productId: UUID;
  quantity: Quantity;
}

export interface AddBillLineResult {
  line: BillLine;
  /** True when the sale drove stock negative. The sale still succeeded — warn, never block. */
  belowZeroStock: boolean;
  qtyOnHand: Quantity;
  warning: string | null;
}

/**
 * A bill left unpaid with no live session. Once its last session closes the table reads free,
 * so nothing on the floor points at it any more — this is the only route back to it.
 * Carries no cost field, so one shape serves both roles.
 */
export interface UnsettledBill {
  id: UUID;
  openedAt: IsoInstant;
  /** When the last session on it closed; null if it never had one. */
  sessionEndedAt: IsoInstant | null;
  businessDate: BusinessDate;
  customerTypeName: string;
  tableNames: string[];
  /** Computed from live lines — the bill's own total is only finalised at checkout. */
  totalAmount: Money;
  /**
   * The most recent note on this bill, so the card can name who owes the money rather than
   * showing three identical "Table 1" rows. Null until someone writes one; the whole thread
   * is at `GET /bills/{id}/notes`.
   */
  latestNote: SessionNote | null;
}

/**
 * A debt: a bill deliberately left unpaid, finalised and awaiting collection.
 *
 * Deliberately a different shape from `UnsettledBill`, and the two lists must never be merged.
 * `totalAmount` here is the FROZEN figure — finalisation has already run, and this is the exact
 * amount that must be tendered to settle. On an `UnsettledBill` it is computed from live lines,
 * because that bill has not been finalised at all.
 *
 * No cost or profit, so one shape serves both roles.
 */
export interface UnpaidBill {
  id: UUID;
  /** Allocated when the bill was left unpaid, from the same counter a paid checkout draws on. */
  receiptNo: number;
  /** The night it was PLAYED, not the night it will be collected. */
  businessDate: BusinessDate;
  unsettledAt: IsoInstant;
  unsettledByUsername: string | null;
  /** Whole business days since the sale. Computed server-side; the browser never dates money. */
  daysOutstanding: number;
  tableNames: string[];
  /** Frozen at leave-unpaid. The exact amount that must be tendered. */
  totalAmount: Money;
  /** Who owes it. The same shape the floor strip renders. */
  latestNote: SessionNote | null;
}

/** Recording the sale without the money. */
export interface LeaveUnpaidRequest {
  /**
   * Who owes it. Required only when the session carries no staff note yet — the server decides,
   * and answers 409 `SESSION_NOTE_REQUIRED` when it is missing and needed.
   */
  note?: string;
  /** Stale version → 409, exactly as at checkout. */
  billVersion: number;
}

export interface VoidBillLineRequest {
  /** Required and non-blank, or 400. */
  reason: string;
}

/* ── §8 Checkout and payment ─────────────────────────────────────────────────────── */
export interface CheckoutPreview {
  bill: Bill;
  canCheckout: boolean;
  /** Explains a false canCheckout, e.g. an open session. Guidance, not an error. */
  blockers: string[];
}

/**
 * Built per method rather than as one object with blanks: the server rejects on
 * `!= null`, not on blank, so an empty-string referenceNo on a cash sale is a 409 that
 * reads like a server bug. See API-CONTRACT §8.
 */
export interface CashPaymentRequest {
  method: 'CASH';
  amount: Money;
  tendered: Money;
  idempotencyKey: string;
  billVersion: number;
  referenceNo?: never;
}

export interface DigitalPaymentRequest {
  method: 'GCASH' | 'MAYA';
  amount: Money;
  referenceNo: string;
  idempotencyKey: string;
  billVersion: number;
  /** Set true to proceed past DUPLICATE_PAYMENT_REFERENCE, reusing the same key. */
  duplicateOverride?: boolean;
  tendered?: never;
}

export type PaymentRequest = CashPaymentRequest | DigitalPaymentRequest;

export interface Payment {
  id: UUID;
  billId: UUID;
  method: PaymentMethod;
  amount: Money;
  tendered: Money | null;
  /** Computed by the server. Never calculated in the browser. */
  changeGiven: Money | null;
  referenceNo: string | null;
  receiptNo: number;
  takenAt: IsoInstant;
  businessDate: BusinessDate;
  duplicateReferenceOverridden: boolean;
  /** True when this is a replay of an earlier request with the same key. Money moved once. */
  replayed: boolean;
}

export interface Receipt {
  id: UUID;
  billId: UUID;
  receiptNo: number;
  issuedAt: IsoInstant;
  /** The stored customer-facing snapshot, free-form and never re-rendered. Carries no cost. */
  payload: Record<string, unknown>;
  /**
   * How a debt was eventually collected — null on a bill paid at the counter.
   *
   * Deliberately NOT merged into `payload`. That is the frozen document handed over on the
   * night: it is append-only in the database, and it said "unpaid" because the bill was unpaid.
   * This answers the different question the next member of staff is actually asking — does he
   * still owe this? Render them as two blocks, never as one.
   */
  settlement: ReceiptSettlement | null;
}

export interface ReceiptSettlement {
  method: PaymentMethod;
  amount: Money;
  takenAt: IsoInstant;
  takenByUsername: string | null;
}

export interface QuickSaleRequest {
  customerTypeId: UUID;
  lines: AddBillLineRequest[];
  /** billVersion is required by validation but ignored; send 0. */
  payment: PaymentRequest;
}

export interface PaymentPhoto {
  paymentId: UUID;
  photoSha256: string;
  photoBytes: number;
}

/* ── §8 Quick sale ───────────────────────────────────────────────────────────────── */

export interface QuickSaleLineRequest {
  productId: UUID;
  quantity: Quantity;
}

/** Priced by the server. No cost fields — the counter reads this. */
export interface QuickSaleQuoteLine {
  productId: UUID;
  description: string;
  unitPrice: Money;
  quantity: Quantity;
  lineTotal: Money;
}

export interface QuickSaleQuote {
  lines: QuickSaleQuoteLine[];
  total: Money;
}

export interface QuickSaleRequest {
  customerTypeId: UUID;
  lines: QuickSaleLineRequest[];
  payment: PaymentRequest;
}

/* ── §9 Business day ─────────────────────────────────────────────────────────────── */
/** A night that traded and was never counted. Carries no cash figure — see the DTO comment. */
export interface UncountedDay {
  businessDate: BusinessDate;
  bills: number;
}

export interface BusinessDayStatus {
  businessDate: BusinessDate;
  canClose: boolean;
  openSessions: TableSessionSummary[];
  serverNow: IsoInstant;
  /**
   * The branch's standard change float, so the close-out can default to it. Zero means the hall
   * keeps no float and the drawer is expected to hold takings alone.
   */
  standardCashFloat: Money;
}

export interface CashCountRequest {
  countedCash: Money;
  /**
   * Omit for the branch standard, which is the answer on almost every night. Send it only when
   * the drawer genuinely started with something else — the server compares it to the standard
   * and records the difference itself.
   */
  openingFloat?: Money;
  note?: string;
}

export interface CashCount {
  /**
   * Trading recorded on this business day AFTER it was closed. The night runs to 05:00, so a
   * sale at 03:30 joins a day signed off at 03:00 — and `expectedCash`, frozen at the moment
   * of counting, stops describing the drawer. When `salesAfterClose > 0` the variance is
   * measured against a total that has since moved, and no screen may show it without saying so.
   */
  salesAfterClose: number;
  amountAfterClose: Money;
  cashAfterClose: Money;
  id: UUID;
  businessDate: BusinessDate;
  /** The change float this night actually ran, frozen on the row. */
  openingFloat: Money;
  /** That day's cash takings. Computed and frozen server-side. Never sent. */
  cashSales: Money;
  /**
   * Cash paid out of the till that night — a water delivery, a bag of ice. Computed and frozen
   * server-side like `cashSales`, and shown beside it so a counter can see why the expected
   * figure dropped instead of assuming the drawer is short.
   */
  cashExpenses: Money;
  /** `openingFloat + cashSales - cashExpenses` — what the drawer should have held. */
  expectedCash: Money;
  /** True when this night's float differed from the branch standard. */
  floatOverridden: boolean;
  countedCash: Money;
  /** Negative is a shortfall. */
  variance: Money;
  countedAt: IsoInstant;
  note: string | null;
  /** Null while the drawer is counted but the night is still open. */
  closedAt: IsoInstant | null;
  closedByUsername: string | null;
  /**
   * Cash paid out on this business day AFTER it was closed — the payout half of the same
   * staleness `salesAfterClose` describes. A night closed at 03:00 that then pays the water man
   * at 03:30 has a frozen `expectedCash` that no longer matches the drawer.
   */
  expensesAfterClose: number;
  cashExpensesAfterClose: Money;
}

/** ADMIN only. Corrects a mistyped count; the original survives in the audit log. */
export interface CashCountUpdateRequest {
  countedCash: Money;
  /** Null leaves the recorded float alone. ADMIN only, like the rest of this request. */
  openingFloat?: Money;
  note?: string;
}

/* ── §9 Expenses ─────────────────────────────────────────────────────────────────────
   What the hall SPENDS, as opposed to what the goods cost. Not gated by role: an expense
   carries no unit cost, no margin and no profit, so one type serves both — the same reasoning
   as UnsettledBill. */

export interface ExpenseCategory {
  id: UUID;
  name: string;
  sortOrder: number;
}

export interface ExpenseCategoryRequest {
  name: string;
  sortOrder?: number;
}

export interface Expense {
  id: UUID;
  expenseCategoryId: UUID;
  categoryName: string | null;
  amount: Money;
  note: string | null;
  /** True when the cash physically left the till, which is what moves the drawer arithmetic. */
  paidFromDrawer: boolean;
  incurredAt: IsoInstant;
  /** Computed by the database, so an expense at 02:00 lands on the night still running. */
  businessDate: BusinessDate;
  recordedByUsername: string | null;
  /** Voided rows come back with the live ones and stay on screen struck through. */
  voided: boolean;
  voidedAt: IsoInstant | null;
  voidedByUsername: string | null;
  voidReason: string | null;
}

export interface ExpenseRequest {
  expenseCategoryId: UUID;
  amount: Money;
  note?: string;
  paidFromDrawer: boolean;
}

export interface VoidExpenseRequest {
  reason: string;
}

/* ── §10 Stock ───────────────────────────────────────────────────────────────────── */
export interface StockDeliveryLineRequest {
  productId: UUID;
  quantity: Quantity;
  unitCost: Money;
}

export interface StockDeliveryRequest {
  supplierName?: string;
  reference?: string;
  note?: string;
  lines: StockDeliveryLineRequest[];
}

export interface StockDelivery {
  id: UUID;
  supplierName: string | null;
  reference: string | null;
  note: string | null;
  receivedAt: IsoInstant;
  businessDate: BusinessDate;
}

export interface StockCorrectionRequest {
  productId: UUID;
  newQuantity: Quantity;
  /** Required. */
  note: string;
}

export interface StockCompLineRequest {
  productId: UUID;
  quantity: Quantity;
}

/**
 * Several products given away at once — a round for a table, a tray for the staff. One reason
 * covers the batch and is written onto every movement. All or nothing server-side.
 */
export interface StockCompBatchRequest {
  lines: StockCompLineRequest[];
  /** Required. The schema will not accept a STAFF_COMP without one. */
  note: string;
}

export interface StockCompRequest {
  productId: UUID;
  quantity: Quantity;
  /** Required. */
  note: string;
}

export interface LowStockLine {
  productId: UUID;
  name: string;
  qtyOnHand: Quantity;
  threshold: Quantity;
}

/** One settled sale in the owner's receipts list. No cost or profit — see the DTO comment. */
export interface BillSummary {
  id: UUID;
  receiptNo: number;
  closedAt: IsoInstant;
  totalAmount: Money;
  method: PaymentMethod;
  takenByUsername: string | null;
  /** A table sale carried a session; a quick sale never did. */
  quickSale: boolean;
}

/* ── Settings (ADMIN) ─────────────────────────────────────────────────────────────── */
export interface Settings {
  /** Zero means the hall keeps no float and the drawer is expected to hold takings alone. */
  standardCashFloat: Money;
  /** Whether the checkout transition plays. Off is a perfectly good answer on a busy floor. */
  checkoutAnimation: boolean;
}

export interface SettingsRequest {
  standardCashFloat: Money;
  checkoutAnimation: boolean;
}

/* ── §11 Reports and audit (ADMIN) ───────────────────────────────────────────────── */
export interface DailyTotals {
  bills: number;
  gross: Money;
  cost: Money;
  profit: Money;
  timeRevenue: Money;
  itemRevenue: Money;
}

export interface TimeRevenueByMode {
  mode: 'STANDARD' | 'PROMO' | 'FRIEND' | 'FLAT';
  /** Beside the money because they answer different halves: PHP 3,000 across twenty sessions
      is not the same night as PHP 3,000 across two. */
  sessions: number;
  amount: Money;
}

export interface HourlySales {
  /** Asia/Manila hour, 10 through 4 across the business day. */
  hour: number;
  bills: number;
  amount: Money;
}

export interface TableUtilisation {
  tableName: string;
  billedMinutes: number;
  /** Against a 19-hour day. */
  utilisationPercent: number;
}

export interface TopItem {
  description: string;
  quantity: Quantity;
  revenue: Money;
}

export interface PaymentMix {
  method: PaymentMethod;
  payments: number;
  amount: Money;
}

export interface TimeReductionLossLine {
  poolTableName: string;
  actualMinutes: number;
  chargedMinutes: number;
  ratePerMinute: Rate;
  forgoneRevenue: Money;
  actualUsername: string | null;
  reason: string | null;
  closedAt: IsoInstant;
}

export interface Losses {
  voidCount: number;
  /** Exact. */
  voidAmount: Money;
  /**
   * The two kinds of rate override, apart. Same arithmetic — (standard - charged) x minutes —
   * and different facts: a promo is a decision about the night, a friend rate a decision about
   * one person. Both exact.
   *
   * These were one pair called `overrideSessions` / `forgoneRevenue` until promos existed.
   * Renamed rather than quietly narrowed: "override" at the top level reads as ALL overrides.
   */
  promoSessions: number;
  promoForgone: Money;
  friendSessions: number;
  friendForgone: Money;
  compQuantity: Quantity;
  /** An estimate, valued at current average cost. Label it as such. */
  compEstimatedCost: Money;
  /** Table time played but not charged — the third giveaway route. */
  reducedSessions: number;
  timeReductionForgone: Money;
  /**
   * Tournament pricing. `flatForgone` is the metered figure less the flat fee, clamped at zero
   * per session, so a fee above the meter contributes nothing rather than cancelling a real loss.
   */
  flatSessions: number;
  flatForgone: Money;
  /**
   * Money knocked off whole bills — the one giveaway here that is not about table time.
   *
   * Beside `timeReductionForgone`, never folded into it: a bill can carry both, and they cannot
   * double-count because a reduction rewrites the TIME lines before the discount is computed.
   *
   * This is also what explains gross. `totals.gross` reports the DISCOUNTED figure, because
   * gross has to reconcile to the drawer.
   */
  discountBills: number;
  discountAmount: Money;
  /**
   * Free table time redeemed against a bill. The third bill-level giveaway, overlapping
   * neither of the others, and — like the discount — a figure that EXPLAINS gross rather than
   * one subtracted from it. A winner who played three hours on a two-hour voucher put 240 in
   * the drawer; gross says 240, and this says where the other 480 went.
   */
  voucherCount: number;
  voucherAmount: Money;
}

/* ── Losses drill-down (ADMIN) ─────────────────────────────────────────────────────
   Each section repeats its tile's own figure, summed from the very rows listed with it. If a
   section total and its tile disagree, that is a bug and the screen says so. */

export interface CompLossLine {
  productName: string;
  quantity: Quantity;
  reason: string | null;
  actorUsername: string | null;
  occurredAt: IsoInstant;
  /** At today's average cost, so an estimate — same basis as the tile. */
  estimatedCost: Money;
}

export interface VoidLossLine {
  description: string;
  quantity: Quantity;
  lineTotal: Money;
  reason: string | null;
  actorUsername: string | null;
  voidedAt: IsoInstant;
  billId: UUID;
  /** Null while the bill is still open. */
  receiptNo: number | null;
}

export interface DiscountLossLine {
  billId: UUID;
  /** Always present: a discount is taken on an OPEN bill, and every bill here has been settled. */
  receiptNo: number;
  subtotal: Money;
  discountAmount: Money;
  chargedAmount: Money;
  reason: string | null;
  actorUsername: string | null;
  discountAt: IsoInstant;
}

export interface VoucherLossLine {
  billId: UUID;
  receiptNo: number;
  /** Display form, SB-7K4-M2Q. */
  code: string;
  /** What the giveaway was — the only thing telling one batch's redemptions from another's. */
  batchNote: string | null;
  /** What the code was worth, what it reached, and what the customer lost. */
  voucherMinutes: number;
  minutesCovered: number;
  minutesForfeited: number;
  voucherAmount: Money;
  poolTableName: string | null;
  actorUsername: string | null;
  redeemedAt: IsoInstant;
}

export interface FlatRateLossLine {
  poolTableName: string;
  billedMinutes: number;
  standardRatePerMinute: Rate;
  /** What the meter would have charged for those minutes. */
  meteredRevenue: Money;
  flatAmount: Money;
  /** Metered less flat, clamped at zero on this row. */
  forgoneRevenue: Money;
  actorUsername: string | null;
  reason: string | null;
  openedAt: IsoInstant;
}

export interface RateOverrideLossLine {
  poolTableName: string;
  standardRatePerMinute: Rate;
  chargedRatePerMinute: Rate;
  /** Both snapshotted at open, when the friend rate was entered hourly. Display only. */
  standardRatePerHour: Money | null;
  chargedRatePerHour: Money | null;
  billedMinutes: number;
  forgoneRevenue: Money;
  actorUsername: string | null;
  reason: string | null;
  openedAt: IsoInstant;
  /** Which section this row belongs in, on the row so a line reads on its own. */
  rateOverrideKind: RateOverrideKind | null;
}

export interface LossesDetail {
  businessDate: BusinessDate;
  comps: { compQuantity: Quantity; compEstimatedCost: Money; lines: CompLossLine[] };
  voids: { voidCount: number; voidAmount: Money; lines: VoidLossLine[] };
  /**
   * The same shape twice, told apart by kind. Both keep the scoped names an override section
   * has always had: inside `promos`, `forgoneRevenue` can only mean the promos' own. It is the
   * tile figures on `Losses` that had to say which they meant.
   */
  promos: {
    overrideSessions: number;
    forgoneRevenue: Money;
    lines: RateOverrideLossLine[];
  };
  friendRates: {
    overrideSessions: number;
    forgoneRevenue: Money;
    lines: RateOverrideLossLine[];
  };
  flatRates: {
    flatSessions: number;
    flatForgone: Money;
    lines: FlatRateLossLine[];
  };
  timeReductions: {
    reducedSessions: number;
    forgoneRevenue: Money;
    lines: TimeReductionLossLine[];
  };
  discounts: {
    discountBills: number;
    discountAmount: Money;
    lines: DiscountLossLine[];
  };
  vouchers: {
    voucherCount: number;
    voucherAmount: Money;
    lines: VoucherLossLine[];
  };
}

/* ── Vouchers ──────────────────────────────────────────────────────────────────────
   Free table time, generated in batches by the owner and spent at the counter. The code list is
   ADMIN only: whoever can read an unredeemed code can redeem it. */

export type VoucherStatus = 'OUTSTANDING' | 'REDEEMED' | 'EXPIRED';

export interface Voucher {
  id: UUID;
  batchId: UUID;
  /** Display form, SB-7K4-M2Q. The stored form has no separators and is never sent. */
  code: string;
  minutes: number;
  expiresOn: BusinessDate;
  /**
   * Resolved against the current BUSINESS date, not stored: a code expiring tonight is
   * outstanding until the night ends at 05:00, which is when the customer is still playing.
   */
  status: VoucherStatus;
  redeemedAt: IsoInstant | null;
  redeemedByUsername: string | null;
  redeemedBillId: UUID | null;
  redeemedReceiptNo: number | null;
}

export interface VoucherBatch {
  id: UUID;
  /** Minutes is what is stored; `hoursLabel` is how the hall says it — "2 hours", "90 min". */
  minutes: number;
  hoursLabel: string;
  quantity: number;
  expiresOn: BusinessDate;
  note: string | null;
  createdByUsername: string | null;
  createdAt: IsoInstant;
  /** Exclusive, and they sum to `issued`. `outstanding` is what could still walk in. */
  issued: number;
  redeemed: number;
  expired: number;
  outstanding: number;
  /** Only on the response to CREATING a batch, so the owner can print them. Null on the list. */
  codes: Voucher[] | null;
}

export interface VoucherBatchRequest {
  /** HOURS — the server converts to minutes and refuses a fractional minute. */
  hours: number;
  quantity: number;
  expiresOn: BusinessDate;
  note?: string;
}

/**
 * What just happened at the counter, with the fact the cashier has to say out loud:
 * `minutesForfeited`. No change, no residual balance, the code is spent.
 */
export interface VoucherRedemption {
  bill: Bill;
  code: string;
  voucherMinutes: number;
  minutesCovered: number;
  minutesForfeited: number;
  voucherAmount: Money;
}

export interface ReportLowStock {
  name: string;
  qtyOnHand: Quantity;
}

export interface EmployeeSales {
  username: string;
  fullName: string;
  bills: number;
  gross: Money;
  cost: Money;
  profit: Money;
}

export interface DailyReport {
  businessDate: BusinessDate;
  comparedTo: BusinessDate | null;
  totals: DailyTotals;
  /** Zeros when there is no previous day. */
  previousTotals: DailyTotals;
  salesByHour: HourlySales[];
  /**
   * How the night's table time was priced. Always four rows, in the order STANDARD, PROMO,
   * FRIEND, FLAT, zeros included.
   *
   * THE AMOUNTS SUM EXACTLY TO `totals.timeRevenue`. The dashboard says so on screen when they
   * do not, rather than showing two figures that disagree about the same money.
   */
  timeRevenueByMode: TimeRevenueByMode[];
  tableUtilisation: TableUtilisation[];
  topItems: TopItem[];
  paymentMix: PaymentMix[];
  losses: Losses;
  /** Operating cost. Deliberately NOT part of `totals.cost`, which is cost of goods. */
  expenses: ReportExpenses;
  lowStock: ReportLowStock[];
  /** Sums exactly to totals; attributed to whoever took the payment. */
  perEmployee: EmployeeSales[];
  /**
   * How much of `totals.gross` was left unpaid on this night.
   *
   * `totals.gross` ALREADY INCLUDES this: the sale counts on the night it was played. This says
   * how much of it is still a promise, so the night and the drawer read as two facts rather
   * than one confusing one. A historical figure — collecting the debt later does not change it.
   */
  unsettledTonight: BillCountAndAmount;
  /**
   * Money that arrived on this business date against an EARLIER night's sale. NOT in
   * `totals.gross` — that revenue was recognised when it was earned, and counting it twice
   * would invent a sale. It is here because it IS in tonight's drawer.
   */
  collectedToday: BillCountAndAmount;
  /**
   * Every debt still open, across all dates, for the Attention band. The one live figure in
   * this report: it answers "right now", so it moves even on a report for an old night.
   */
  outstanding: BillCountAndAmount;
}

export interface BillCountAndAmount {
  count: number;
  amount: Money;
}

/**
 * What it cost to be open — water, electricity, rent, supplies.
 *
 * Kept apart from `totals.cost` on purpose: that is cost of goods, snapshotted at the moment of
 * sale, and adding the rent to it would put the rent inside the margin on a beer. Voided
 * expenses are excluded, exactly as voided lines are excluded from revenue.
 */
export interface ReportExpenses {
  total: Money;
  /** The same figure for the previous business day, so the tile can carry a delta. */
  previousTotal: Money;
  byCategory: ExpenseCategoryTotal[];
}

export interface ExpenseCategoryTotal {
  category: string;
  amount: Money;
}

export interface AuditEntry {
  id: UUID;
  action: string;
  entityTable: string;
  entityId: UUID;
  actorId: UUID | null;
  actorUsername: string | null;
  /** Free-form; keys differ per action. Render generically. */
  before: Record<string, unknown> | null;
  after: Record<string, unknown> | null;
  note: string | null;
  occurredAt: IsoInstant;
  businessDate: BusinessDate;
}

/**
 * One row of the audit feed, already readable: audit_log and the stock ledger as one list, with
 * every id resolved server-side. Nothing here is a UUID the screen has to render.
 */
export interface AuditFeedEntry {
  id: UUID;
  /** `AUDIT` or `STOCK`. For grouping and icons only — never shown as text. */
  source: 'AUDIT' | 'STOCK';
  /** The stored constant. Sent back as a filter; not for display. */
  action: string;
  /**
   * The same thing in words: "Rate overridden". Display this. On a rate override the server
   * names it after the customer type instead — "Happy Hour rate".
   */
  actionLabel: string;
  /** What kind of thing changed: "Product", "Table", "Drawer count". */
  entityLabel: string;
  /** Which one: the product's name, the table's name, the date of the count. */
  subject: string | null;
  actorName: string | null;
  note: string | null;
  /** Stock rows only. Negative took stock out, positive put it in. */
  quantityDelta: Quantity | null;
  /**
   * The customer type a session row was opened on, joined at read time. Null on every other
   * kind of row, and on a session opened without one.
   */
  customerTypeName: string | null;
  /** Free-form; keys differ per action. Render generically. Null on stock rows. */
  before: Record<string, unknown> | null;
  after: Record<string, unknown> | null;
  occurredAt: IsoInstant;
  businessDate: BusinessDate;
}

/** What the filters offer, drawn from what is actually in this branch's history. */
export interface AuditFilterOptions {
  actions: { action: string; label: string }[];
  actors: { id: UUID; name: string }[];
}

export interface AuditFeedQuery {
  action?: string;
  actor?: UUID;
  entity?: string;
  from?: BusinessDate;
  to?: BusinessDate;
  page?: number;
  /** Capped at 200 server-side; default 50. */
  size?: number;
}

export interface AuditQuery {
  entity?: string;
  actor?: UUID;
  from?: BusinessDate;
  to?: BusinessDate;
  page?: number;
  /** Capped at 200 server-side; default 50. */
  size?: number;
}

export interface Paged<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}
