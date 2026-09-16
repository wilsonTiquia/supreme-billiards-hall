import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import ts from 'typescript';

// Test the production helpers without a second frontend test runner or copied implementation.
const source = readFileSync(
  new URL('../src/features/admin/reports/reportData.ts', import.meta.url),
  'utf8',
);
const compiled = ts.transpileModule(source, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ES2022 },
}).outputText;
const { csvCell, reportCsv, reportDataset, rangeError } = await import(
  `data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`
);

test('date ranges reject invalid calendar dates, reversed dates and more than 366 days', () => {
  assert.equal(rangeError('2024-01-01', '2024-12-31'), null);
  assert.ok(rangeError('2024-01-01', '2025-01-01'));
  assert.ok(rangeError('2026-02-30', '2026-03-01'));
  assert.ok(rangeError('2026-09-15', '2026-09-01'));
  assert.ok(rangeError('', '2026-09-15'));
  assert.ok(rangeError('bad-date', '2026-09-15'));
  assert.equal(rangeError('2026-09-15', '2026-09-15'), null);
});

test('CSV preserves commas, quotes and line breaks, and neutralizes catalog formulas', () => {
  assert.equal(csvCell('A, "B"\nC'), '"A, ""B""\nC"');
  for (const text of ['=1+1', '+1', '-1', '@SUM(A1)', '  =1', '\tformula']) {
    assert.ok(csvCell(text).startsWith('"\''));
  }
  assert.equal(csvCell(-1), '"-1"');
  assert.equal(csvCell(0.125), '"0.125"');
  assert.equal(csvCell(null), '""');
  assert.ok(reportCsv({ headers: ['Name'], rows: [] }).startsWith('\uFEFF'));
});

// Optional integration check against a real API response. Never seed or write POS records.
const realReport = process.env.REAL_REPORT_JSON;
test(
  'real API daily, weekly and monthly exports reconcile to the same headline',
  { skip: !realReport },
  () => {
    const data = JSON.parse(readFileSync(realReport, 'utf8'));
    for (const grouping of ['day', 'week', 'month']) {
      const dataset = reportDataset(data, 'sales', grouping);
      const totals = dataset.rows.reduce(
        (result, row) => result.map((sum, i) => sum + row[i + 1]),
        [0, 0, 0, 0, 0],
      );
      const expected = [
        data.headline.bills,
        data.headline.gross,
        data.headline.costOfGoods,
        data.headline.operatingExpenses,
        data.headline.net,
      ];
      totals.forEach((total, i) =>
        assert.ok(Math.abs(total - expected[i]) < 0.005, `${grouping} column ${i} must reconcile`),
      );
      assert.ok(reportCsv(dataset).includes(dataset.headers[0]));
    }
    for (const kind of ['tables', 'products', 'expenses']) {
      const dataset = reportDataset(data, kind);
      assert.ok(dataset.rows.every((row) => row.length === dataset.headers.length));
    }
  },
);
