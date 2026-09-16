import type { PeriodReport } from '@/api/types';

export type ReportKind = 'sales' | 'tables' | 'products' | 'expenses';
export type Grouping = 'day' | 'week' | 'month';
export type ReportCell = string | number | null;
export interface ReportDataset {
  headers: string[];
  rows: ReportCell[][];
}

/** Sum only API daily rows. Week labels are calendar labels, never a new business-day rule. */
export function salesRows(data: PeriodReport, grouping: Grouping): ReportDataset {
  const groups = new Map<string, number[]>();
  for (const day of data.byDay) {
    let key = day.businessDate;
    if (grouping === 'month') key = key.slice(0, 7);
    if (grouping === 'week') {
      const date = new Date(`${key}T12:00:00Z`);
      date.setUTCDate(date.getUTCDate() - ((date.getUTCDay() + 6) % 7));
      key = date.toISOString().slice(0, 10);
    }
    const values = [day.bills, day.gross, day.costOfGoods, day.operatingExpenses, day.net];
    const before = groups.get(key) ?? values.map(() => 0);
    groups.set(
      key,
      before.map((value, i) => value + values[i]),
    );
  }
  return {
    headers: [
      grouping === 'week'
        ? 'Week starting Monday'
        : grouping === 'month'
          ? 'Month'
          : 'Business date',
      'Bills',
      'Sales (PHP)',
      'Product cost (PHP)',
      'Expenses (PHP)',
      'After recorded costs (PHP)',
    ],
    rows: [...groups].map(([label, values]) => [
      label,
      ...values.map((value) => Math.round((value + Number.EPSILON) * 100) / 100),
    ]),
  };
}

export function reportDataset(
  data: PeriodReport,
  kind: ReportKind,
  grouping: Grouping = 'day',
): ReportDataset {
  switch (kind) {
    case 'sales':
      return salesRows(data, grouping);
    case 'tables':
      return {
        headers: [
          'Table',
          'Hours held',
          'Utilisation (%)',
          'Time sales (PHP)',
          'Sales per hour held (PHP)',
        ],
        rows: data.tables.map((row) => [
          row.tableName,
          Number((row.occupiedMinutes / 60).toFixed(2)),
          row.utilisationPercent,
          row.timeRevenue,
          row.revenuePerOccupiedHour,
        ]),
      };
    case 'products':
      return {
        headers: [
          'Product',
          'Quantity sold',
          'Sales (PHP)',
          'Cost (PHP)',
          'Margin (PHP)',
          'Margin (%)',
        ],
        rows: data.products.map((row) => [
          row.name,
          row.quantity,
          row.revenue,
          row.cost,
          row.margin,
          row.marginPercent,
        ]),
      };
    case 'expenses':
      return {
        headers: ['Category', 'Expenses (PHP)', 'Previous period (PHP)', 'Share of sales (%)'],
        rows: data.expensesByCategory.map((row) => [
          row.category,
          row.amount,
          row.previousAmount,
          row.percentOfGross,
        ]),
      };
  }
}

/** CSV names are untrusted catalog text. Prevent spreadsheet formulas and preserve quotes. */
export function csvCell(value: ReportCell): string {
  if (value === null) return '""';
  // Quantities have three decimal places in PostgreSQL. Do not round every cell as money.
  const raw = String(value);
  const safe = typeof value === 'string' && /^[\s]*[=+\-@\t\r\n]/.test(raw) ? `'${raw}` : raw;
  return `"${safe.replaceAll('"', '""')}"`;
}

export function reportCsv(dataset: ReportDataset): string {
  return (
    '\uFEFF' +
    [dataset.headers, ...dataset.rows].map((row) => row.map(csvCell).join(',')).join('\r\n')
  );
}

export function downloadReport(dataset: ReportDataset, filename: string): void {
  const url = URL.createObjectURL(
    new Blob([reportCsv(dataset)], { type: 'text/csv;charset=utf-8;' }),
  );
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  link.remove();
  // Give the browser time to consume the object URL before releasing it.
  window.setTimeout(() => URL.revokeObjectURL(url), 60_000);
}

export function rangeError(from: string, to: string): string | null {
  if (!from || !to) return 'Choose a start and end date.';
  const dates = [from, to].map((value) =>
    /^\d{4}-\d{2}-\d{2}$/.test(value) ? Date.parse(`${value}T12:00:00Z`) : NaN,
  );
  if (
    dates.some(
      (value, index) =>
        !Number.isFinite(value) || new Date(value).toISOString().slice(0, 10) !== [from, to][index],
    )
  )
    return 'Enter valid calendar dates.';
  const days = (dates[1] - dates[0]) / 86400000 + 1;
  if (days < 1) return 'The end date must be on or after the start date.';
  if (days > 366) return 'Choose a range of 366 days or fewer.';
  return null;
}
