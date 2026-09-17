import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import ts from 'typescript';
const source = readFileSync(new URL('../src/features/admin/dashboard/morning.ts', import.meta.url), 'utf8');
const compiled = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ES2022 } }).outputText;
const { dashboardNight, shiftDay, nightHasEnded, cashStatus, validNight } = await import(`data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`);

test('default follows the server business date at 9am and 10am, not the last completed night', () => {
  const atNine = { businessDate: '2026-09-15', serverNow: '2026-09-16T09:00:00+08:00' };
  const atTen = { businessDate: '2026-09-16', serverNow: '2026-09-16T10:00:00+08:00' };
  assert.equal(dashboardNight('', atNine.businessDate), '2026-09-15');
  assert.equal(nightHasEnded('2026-09-15', atNine.serverNow), true);
  assert.equal(dashboardNight('', atTen.businessDate), '2026-09-16');
  assert.equal(nightHasEnded('2026-09-16', atTen.serverNow), false);
});

test('URL selection survives navigation; invalid and future dates fall back to the current night', () => {
  assert.equal(dashboardNight('2026-09-12', '2026-09-16'), '2026-09-12');
  for (const requested of ['2026-02-30', '2026-09-17', 'bad-date', '']) {
    assert.equal(dashboardNight(requested, '2026-09-16'), '2026-09-16');
  }
  assert.equal(dashboardNight('2026-09-12', ''), '');
  assert.equal(validNight('2026-02-30'), false);
});

test('previous and next nights cross month/year boundaries; completion changes at 5am', () => {
  assert.equal(shiftDay('2026-01-01', -1), '2025-12-31');
  assert.equal(shiftDay('2024-02-28', 1), '2024-02-29');
  assert.equal(shiftDay('2024-02-29', 1), '2024-03-01');
  assert.equal(nightHasEnded('2026-09-15', '2026-09-16T04:59:59+08:00'), false);
  assert.equal(nightHasEnded('2026-09-15', '2026-09-16T05:00:00+08:00'), true);
});

test('cash distinguishes missing, balanced, short, over, stale and unavailable counts', () => {
  assert.deepEqual(cashStatus(null, false), { text: 'Not counted yet', danger: true });
  assert.equal(cashStatus(null, true).danger, false);
  const count = { variance: 0, salesAfterClose: 0, expensesAfterClose: 0 };
  assert.equal(cashStatus(count, false).text, 'Cash balanced');
  assert.deepEqual(cashStatus({ ...count, variance: -120 }, false), { text: 'Short by ', amount: 120, danger: true });
  assert.equal(cashStatus({ ...count, variance: 120 }, false).text, 'Over by ');
  assert.match(cashStatus({ ...count, expensesAfterClose: 1 }, false).text, /recounting/);
  assert.equal(cashStatus(count, false, false, true).text, 'Cash check unavailable');
});
