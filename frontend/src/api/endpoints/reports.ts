import { request } from '../client';
import type {
  AuditEntry,
  AuditFeedEntry,
  AuditFeedQuery,
  AuditFilterOptions,
  AuditQuery,
  DailyReport,
  LossesDetail,
  Paged,
} from '../types';

/**
 * The rows behind the losses tile. ADMIN, same as the dashboard.
 *
 * There is no friend-rate floor and no supervisor role, so this screen is the owner's only
 * control on comps and overrides — which is why the reason travels on every row.
 */
export function fetchLossesDetail(businessDate?: string): Promise<LossesDetail> {
  return request<LossesDetail>('/reports/losses', { query: { businessDate } });
}

/** ADMIN only. One call for the whole dashboard, so the sections cannot drift apart. */
export function fetchDailyReport(date?: string): Promise<DailyReport> {
  return request<DailyReport>('/reports/daily', { query: { date } });
}

/** ADMIN only. Newest first; `size` is capped at 200 server-side. */
export function fetchAudit(query: AuditQuery): Promise<Paged<AuditEntry>> {
  return request<Paged<AuditEntry>>('/audit', {
    query: {
      entity: query.entity,
      actor: query.actor,
      from: query.from,
      to: query.to,
      page: query.page,
      size: query.size,
    },
  });
}

/**
 * The Audit screen's own read: the same history with the stock ledger folded in and every id
 * already a name. `fetchAudit` above stays as the raw view of audit_log alone.
 */
export function fetchAuditFeed(query: AuditFeedQuery): Promise<Paged<AuditFeedEntry>> {
  return request<Paged<AuditFeedEntry>>('/audit/feed', {
    query: {
      action: query.action,
      actor: query.actor,
      entity: query.entity,
      from: query.from,
      to: query.to,
      page: query.page,
      size: query.size,
    },
  });
}

/** The actions and people actually present in the log, so the filters offer names to pick. */
export function fetchAuditFilters(): Promise<AuditFilterOptions> {
  return request<AuditFilterOptions>('/audit/filters');
}
