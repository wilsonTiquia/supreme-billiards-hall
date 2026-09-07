import type { AuditFeedQuery, AuditQuery, BusinessDate, ProductQuery, UUID } from './types';

/**
 * Every cache key in one place. Keys are hierarchical so a prefix can invalidate a whole
 * family — invalidating ['bills'] catches every bill, its checkout preview and its receipt.
 */
export const queryKeys = {
  me: ['auth', 'me'] as const,
  time: ['time'] as const,

  floor: ['floor'] as const,

  categories: ['categories'] as const,
  customerTypes: ['customerTypes'] as const,

  products: (query?: ProductQuery) => ['products', query ?? {}] as const,
  productMovements: (id: UUID) => ['products', id, 'movements'] as const,

  sessions: ['sessions'] as const,
  session: (id: UUID) => ['sessions', id] as const,
  sessionNotes: (id: UUID) => ['sessions', id, 'notes'] as const,

  bills: ['bills'] as const,
  unsettledBills: ['bills', 'unsettled'] as const,
  settledBills: (date: BusinessDate, page: number) =>
    ['bills', 'settled', date, page] as const,
  bill: (id: UUID) => ['bills', id] as const,
  billNotes: (id: UUID) => ['bills', id, 'notes'] as const,
  checkout: (id: UUID) => ['bills', id, 'checkout'] as const,
  receipt: (id: UUID) => ['bills', id, 'receipt'] as const,

  quickSaleQuote: (lines: unknown) => ['quickSale', 'quote', lines] as const,

  businessDayCurrent: ['businessDay', 'current'] as const,
  uncountedDays: ['businessDay', 'uncounted'] as const,
  businessDayOpenSessions: (date: BusinessDate) => ['businessDay', date, 'openSessions'] as const,

  expenseCategories: ['expenseCategories'] as const,
  expenses: (date?: BusinessDate) => ['expenses', date ?? 'current'] as const,

  lowStock: ['stock', 'low'] as const,
  tables: ['tables'] as const,
  cashCount: (date: BusinessDate) => ['businessDay', date, 'cashCount'] as const,

  dailyReport: (date?: BusinessDate) => ['reports', 'daily', date ?? 'current'] as const,
  lossesDetail: (date?: BusinessDate) => ['reports', 'losses', date ?? 'current'] as const,
  audit: (query: AuditQuery) => ['audit', query] as const,
  auditFeed: (query: AuditFeedQuery) => ['audit', 'feed', query] as const,
  auditFilters: ['audit', 'filters'] as const,
  settings: ['settings'] as const,
  users: ['users'] as const,
} as const;
