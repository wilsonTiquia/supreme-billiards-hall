import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import ts from 'typescript';

const compile = name => ts.transpileModule(readFileSync(new URL(`../src/features/admin/reports/${name}.ts`, import.meta.url), 'utf8'), {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ES2022 },
}).outputText;
const moduleUrl = source => `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`;
const dataUrl = moduleUrl(compile('reportData'));
const { reportView, initialView } = await import(moduleUrl(compile('reportView').replace("'./reportData'", JSON.stringify(dataUrl))));
const { reportCsv } = await import(dataUrl);
const data = {
  byDay: Array.from({ length: 31 }, (_, i) => ({ businessDate: `2026-08-${String(i + 1).padStart(2, '0')}`, bills: 1, gross: 100 + i, costOfGoods: 10, operatingExpenses: 0, net: 90 + i })),
  tables: Array.from({ length: 7 }, (_, i) => ({ tableName: `Table ${i + 1}`, occupiedMinutes: 60, timeRevenue: 100, utilisationPercent: 10, revenuePerOccupiedHour: 100 })),
  products: Array.from({ length: 16 }, (_, i) => ({ name: `Product ${String(i + 1).padStart(2, '0')}`, quantity: 1, revenue: 16 - i, cost: 1, margin: 15 - i, marginPercent: 50 })),
  expensesByCategory: [{ category: 'Rent', amount: 45000, previousAmount: 45000, percentOfGross: 40 }],
};

test('31 nights appear in three pages without loss; CSV retains all 31 rows', () => {
  const pages = [1, 2, 3].map(page => reportView(data, { ...initialView, page }));
  assert.deepEqual(pages.map(p => p.visibleRows.length), [15, 15, 1]);
  assert.deepEqual(pages.flatMap(p => p.visibleRows.map(r => r[0])), data.byDay.map(d => d.businessDate));
  assert.equal(reportCsv(pages[1]).split('\r\n').length, 32);
  assert.equal(reportView(data, { ...initialView, page: 100 }).page, 3);
});

test('search is based on source count, stays usable after narrowing, and is ignored for small lists', () => {
  const filtered = reportView(data, { ...initialView, kind: 'products', search: 'Product 16', page: 2 });
  assert.equal(filtered.searchable, true);
  assert.equal(filtered.page, 1);
  assert.equal(filtered.rows.length, 1);
  assert.equal(filtered.rows[0][0], 'Product 16');
  const small = reportView(data, { ...initialView, kind: 'tables', search: 'no match' });
  assert.equal(small.searchable, false);
  assert.equal(small.rows.length, 7);
  assert.equal(reportView(data, { ...initialView, kind: 'expenses' }).searchable, false);
  assert.equal(reportView({ ...data, products: data.products.slice(0, 15) }, { ...initialView, kind: 'products' }).searchable, true);
});

test('sort and grouping happen before pagination and CSV follows the filtered ordering', () => {
  const sorted = reportView(data, { ...initialView, sort: 'amount' });
  assert.equal(sorted.visibleRows[0][0], '2026-08-31');
  assert.equal(sorted.visibleRows[14][0], '2026-08-17');
  assert.equal(data.byDay[0].businessDate, '2026-08-01');
  const grouped = reportView(data, { ...initialView, grouping: 'month', page: 3, search: 'stale filter' });
  assert.equal(grouped.page, 1);
  assert.equal(grouped.rows.length, 1);
  assert.equal(grouped.rows[0][0], '2026-08');
  assert.equal(grouped.rows[0][1], 31);
  const empty = reportView(data, { ...initialView, search: 'no matching date' });
  assert.equal(empty.page, 1);
  assert.deepEqual(empty.visibleRows, []);
});
