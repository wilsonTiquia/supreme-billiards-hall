import type { PeriodReport } from '@/api/types';
import { reportDataset, type Grouping, type ReportKind } from './reportData';

export interface ExplorerView {
  kind: ReportKind;
  grouping: Grouping;
  search: string;
  sort: 'source' | 'amount' | 'name';
  page: number;
}
export const initialView: ExplorerView = { kind: 'sales', grouping: 'day', search: '', sort: 'source', page: 1 };
export const PAGE_SIZE = 15;

/** CSV uses all filtered rows; pagination only limits the on-screen slice. */
export function reportView(data: PeriodReport, view: ExplorerView) {
  const source = reportDataset(data, view.kind, view.grouping);
  const searchable = source.rows.length >= 15;
  const search = searchable ? view.search.trim().toLowerCase() : '';
  const rows = source.rows.filter(row => String(row[0]).toLowerCase().includes(search));
  if (view.sort !== 'source') {
    const amountColumn = view.kind === 'sales' || view.kind === 'products' ? 2 : view.kind === 'tables' ? 3 : 1;
    rows.sort((a, b) => view.sort === 'name'
      ? String(a[0]).localeCompare(String(b[0]))
      : Number(b[amountColumn]) - Number(a[amountColumn]));
  }
  const pages = Math.max(1, Math.ceil(rows.length / PAGE_SIZE));
  const page = Math.min(Math.max(1, view.page), pages);
  return { headers: source.headers, rows, total: source.rows.length, searchable, pages, page,
    visibleRows: rows.slice((page - 1) * PAGE_SIZE, page * PAGE_SIZE) };
}
