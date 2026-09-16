import { useState } from 'react';
import { Link } from 'react-router-dom';
import type { PeriodReport } from '@/api/types';
import { formatMoney } from '@/lib/money';
import { AnalyticsPanel, EmptyState } from '../analytics/Analytics';
import {
  downloadReport,
  reportDataset,
  type Grouping,
  type ReportCell,
  type ReportKind,
} from './reportData';

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

export function ReportExplorer({ data }: { data: PeriodReport }) {
  const [kind, setKind] = useState<ReportKind>('sales');
  const [grouping, setGrouping] = useState<Grouping>('day');
  const [search, setSearch] = useState('');
  const [sort, setSort] = useState('source');
  const belowCost = data.products.filter((product) => product.margin <= 0).length;
  const weakestTable = data.tables.find((table) => table.occupiedMinutes > 0);
  const dataset = reportDataset(data, kind, grouping);
  const rows = dataset.rows.filter((row) =>
    String(row[0]).toLowerCase().includes(search.trim().toLowerCase()),
  );
  if (sort !== 'source') {
    const index = kind === 'sales' || kind === 'products' ? 2 : kind === 'tables' ? 3 : 1;
    rows.sort((a, b) =>
      sort === 'name'
        ? String(a[0]).localeCompare(String(b[0]))
        : Number(b[index]) - Number(a[index]),
    );
  }
  const download = () =>
    downloadReport(
      { headers: dataset.headers, rows },
      `supreme-${kind}-${data.from}-to-${data.to}.csv`,
    );
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
              setKind(report.key);
              setSearch('');
              setSort('source');
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
                onChange={(event) => setGrouping(event.target.value as Grouping)}
              >
                <option value="day">Day</option>
                <option value="week">Week</option>
                <option value="month">Month</option>
              </select>
            </label>
          ) : (
            <input
              type="search"
              aria-label={`Filter ${kind}`}
              placeholder={`Search ${kind === 'expenses' ? 'categories' : kind}…`}
              value={search}
              onChange={(event) => setSearch(event.target.value)}
            />
          )}
          <select
            aria-label="Sort report"
            value={sort}
            onChange={(event) => setSort(event.target.value)}
          >
            <option value="source">Default order</option>
            <option value="amount">Highest amount</option>
            <option value="name">Name / date</option>
          </select>
        </div>
        <button className="analytics-button" onClick={download} disabled={!rows.length}>
          ↓ Export {kind} CSV
        </button>
      </div>
      <p className="analytics-note">
        {NOTES[kind]}{' '}
        {kind === 'sales' &&
          grouping === 'day' &&
          'Open a date to view settled receipts; bills left unpaid appear under Unsettled.'}
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
              </tr>
            </thead>
            <tbody>
              {rows.map((row, i) => (
                <tr
                  key={`${row[0]}-${i}`}
                  className={kind === 'products' && Number(row[4]) <= 0 ? 'text-danger' : undefined}
                >
                  {row.map((cell, index) => (
                    <td key={index}>
                      {kind === 'sales' && grouping === 'day' && index === 0 ? (
                        <Link
                          className="text-info underline underline-offset-4"
                          to={`/admin/sales?date=${cell}`}
                        >
                          {String(cell)}
                        </Link>
                      ) : (
                        display(cell, dataset.headers[index])
                      )}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <p className="analytics-note">
        {rows.length} of {dataset.rows.length} rows · All amounts in PHP · {data.from} to {data.to}
      </p>
    </AnalyticsPanel>
  );
}
