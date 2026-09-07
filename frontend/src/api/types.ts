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
export type BillStatus = 'OPEN' | 'CLOSED' | 'VOIDED' | 'MERGED';
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
  ratePerMinute: Rate;
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
  /** Only accepted when the customer type has allowsRateOverride; otherwise 409. */
  rateOverridePerMinute?: Rate;
  rateOverrideReason?: string;
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
  /** `openingFloat + cashSales` — what the drawer should have held. */
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
}

/** ADMIN only. Corrects a mistyped count; the original survives in the audit log. */
export interface CashCountUpdateRequest {
  countedCash: Money;
  /** Null leaves the recorded float alone. ADMIN only, like the rest of this request. */
  openingFloat?: Money;
  note?: string;
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
  overrideSessions: number;
  /** Exact. */
  forgoneRevenue: Money;
  compQuantity: Quantity;
  /** An estimate, valued at current average cost. Label it as such. */
  compEstimatedCost: Money;
  /** Table time played but not charged — the third giveaway route. */
  reducedSessions: number;
  timeReductionForgone: Money;
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

export interface RateOverrideLossLine {
  poolTableName: string;
  standardRatePerMinute: Rate;
  chargedRatePerMinute: Rate;
  billedMinutes: number;
  forgoneRevenue: Money;
  actorUsername: string | null;
  reason: string | null;
  openedAt: IsoInstant;
}

export interface LossesDetail {
  businessDate: BusinessDate;
  comps: { compQuantity: Quantity; compEstimatedCost: Money; lines: CompLossLine[] };
  voids: { voidCount: number; voidAmount: Money; lines: VoidLossLine[] };
  friendRates: {
    overrideSessions: number;
    forgoneRevenue: Money;
    lines: RateOverrideLossLine[];
  };
  timeReductions: {
    reducedSessions: number;
    forgoneRevenue: Money;
    lines: TimeReductionLossLine[];
  };
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
  tableUtilisation: TableUtilisation[];
  topItems: TopItem[];
  paymentMix: PaymentMix[];
  losses: Losses;
  lowStock: ReportLowStock[];
  /** Sums exactly to totals; attributed to whoever took the payment. */
  perEmployee: EmployeeSales[];
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
  /** The same thing in words: "Friend rate given". Display this. */
  actionLabel: string;
  /** What kind of thing changed: "Product", "Table", "Drawer count". */
  entityLabel: string;
  /** Which one: the product's name, the table's name, the date of the count. */
  subject: string | null;
  actorName: string | null;
  note: string | null;
  /** Stock rows only. Negative took stock out, positive put it in. */
  quantityDelta: Quantity | null;
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
