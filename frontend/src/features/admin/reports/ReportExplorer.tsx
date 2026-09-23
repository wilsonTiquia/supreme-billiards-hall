import { Link, useNavigate } from 'react-router-dom';
import type { PeriodReport } from '@/api/types';
import { formatMoney } from '@/lib/money';
import { AnalyticsPanel, EmptyState } from '../analytics/Analytics';
import type { Grouping, ReportCell, ReportKind } from './reportData';
import { reportView, PAGE_SIZE, type ExplorerView } from './reportView';

const REPORTS: { key: ReportKind; label: string }[] = [
  { key: 'sales', label: 'Sales' },
  { key: 'tables', label: 'Tables' },
  { key: 'products', label: 'Products' },
  { key: 'expenses', label: 'Expenses' },
];
const NOTES: Record<ReportKind, string> = {
  sales:
    'Closed bills, including unpaid sales. Grouped totals include only dates inside the selected range. Open a date to view settled receipts; bills left unpaid appear under Unsettled.',
  tables:
    'Time sales before bill-level discounts. Hours held include pauses; moved sessions are split between tables by minutes.',
  products:
    'Product sales and margins use recorded sale prices and costs, before bill-level discounts and vouchers.',
  expenses: 'Recorded operating expenses by category. Voided expenses are excluded.',
};

function display(value: ReportCell, header: string): string {
  if (value === null) return '—';
  if (typeof value === 'string') return value;
  if (header.includes('(PHP)')) return formatMoney(value);
  return `${value.toLocaleString('en-PH', { maximumFractionDigits: header === 'Quantity sold' ? 3 : 2 })}${header.includes('(%)') ? '%' : ''}`;
}

export function ReportExplorer({ data, view, onViewChange }: {
  data: PeriodReport; view: ExplorerView; onViewChange: (view: ExplorerView) => void;
}) {
  const navigate = useNavigate();
  const { kind, grouping, search, sort } = view;
  const update = (change: Partial<ExplorerView>) => onViewChange({ ...view, page: 1, ...change });
  const belowCost = data.products.filter((product) => product.margin <= 0).length;
  const weakestTable = data.tables.find((table) => table.occupiedMinutes > 0);
  const dataset = reportView(data, view);
  const { rows, visibleRows, page, pages, searchable } = dataset;
  const daily = kind === 'sales' && grouping === 'day';
  return (
    <AnalyticsPanel
      title="Explore the numbers"
      subtitle="Choose a report, narrow the rows, and export what you see."
      className="print:hidden"
    >
      <div className="analytics-tabs" role="group" aria-label="Report type">
        {REPORTS.map((report) => (
          <button
            type="button"
            key={report.key}
            aria-pressed={kind === report.key}
            onClick={() => {
              update({ kind: report.key, search: '', sort: 'source' });
            }}
          >
            {report.label}
          </button>
        ))}
      </div>
      <div className="analytics-toolbar">
        <div className="analytics-actions">
          {kind === 'sales' ? (
            <label className="analytics-note">
              Group by{' '}
              <select
                aria-label="Group sales by"
                value={grouping}
                onChange={(event) => update({ grouping: event.target.value as Grouping })}
              >
                <option value="day">Day</option>
                <option value="week">Week</option>
                <option value="month">Month</option>
              </select>
            </label>
          ) : null}
          {searchable && (
            <input
              type="search"
              aria-label={`Filter ${kind}`}
              placeholder={`Search ${kind === 'expenses' ? 'categories' : kind}…`}
              value={search}
              onChange={(event) => update({ search: event.target.value })}
            />
          )}
          <select
            aria-label="Sort report"
            value={sort}
            onChange={(event) => update({ sort: event.target.value as ExplorerView['sort'] })}
          >
            <option value="source">Default order</option>
            <option value="amount">Highest amount</option>
            <option value="name">Name / date</option>
          </select>
        </div>
      </div>
      <p className="analytics-note">
        {NOTES[kind]}
      </p>
      {kind === 'products' && belowCost > 0 && (
        <p className="mt-2 text-sm text-danger">
          {belowCost} {belowCost === 1 ? 'product sold' : 'products sold'} at or below cost in this
          period. These rows are highlighted.
        </p>
      )}
      {kind === 'tables' && weakestTable && (
        <p className="analytics-note">
          Lowest sales per hour held: {weakestTable.tableName} ·{' '}
          {formatMoney(weakestTable.revenuePerOccupiedHour)}. Hours held include pauses.
        </p>
      )}
      {!rows.length ? (
        <EmptyState title={search ? 'No matching rows' : 'No records in this period'}>
          {search
            ? 'Try another search or clear the filter.'
            : 'Select another date range to investigate earlier activity.'}
        </EmptyState>
      ) : (
        <div className="analytics-table-wrap">
          <table className="analytics-table">
            <caption className="sr-only">
              {kind} report from {data.from} to {data.to}
            </caption>
            <thead>
              <tr>
                {dataset.headers.map((header) => (
                  <th scope="col" key={header}>
                    {header.replace(' (PHP)', '')}
                  </th>
                ))}
                {daily && <th scope="col"><span className="sr-only">View sales</span></th>}
              </tr>
            </thead>
            <tbody>
              {visibleRows.map((row, i) => (
                <tr
                  key={`${row[0]}-${i}`}
                  className={daily ? 'reports-night-row' : kind === 'products' && Number(row[4]) <= 0 ? 'text-danger' : undefined}
                  onClick={daily ? (event) => {
                    if (!(event.target as Element).closest('a')) navigate(`/admin/sales?date=${row[0]}`);
                  } : undefined}
                >
                  {row.map((cell, index) => (
                    <td key={index}>
                      {display(cell, dataset.headers[index])}
                    </td>
                  ))}
                  {daily && <td><Link to={`/admin/sales?date=${row[0]}`} aria-label={`View sales for ${row[0]}`} className="reports-row-link">›</Link></td>}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <div className="reports-pagination">
        <p className="analytics-note" role="status">
          {rows.length ? `${(page - 1) * PAGE_SIZE + 1}–${Math.min(page * PAGE_SIZE, rows.length)} of ${rows.length}` : '0'} rows
          {rows.length !== dataset.total ? ` (${dataset.total} before filtering)` : ''} · All amounts in PHP
        </p>
        {pages > 1 && <nav aria-label="Report pages">
          <button type="button" className="analytics-button" disabled={page === 1} onClick={() => update({ page: page - 1 })}>Previous</button>
          <span>Page {page} of {pages}</span>
          <button type="button" className="analytics-button" disabled={page === pages} onClick={() => update({ page: page + 1 })}>Next</button>
        </nav>}
      </div>
    </AnalyticsPanel>
  );
}
