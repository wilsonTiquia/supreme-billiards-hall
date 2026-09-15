import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import ts from 'typescript';
const source = readFileSync(new URL('../src/features/admin/dashboard/morning.ts', import.meta.url), 'utf8');
const compiled = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ES2022 } }).outputText;
const { lastCompletedNight, nightHasEnded, cashStatus, validNight } = await import(`data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`);

test('morning selection follows the 5am end, including the 5am–10am business-date gap', () => {
  const night = '2026-09-15';
  assert.equal(lastCompletedNight({ businessDate: night, serverNow: '2026-09-16T04:59:59+08:00' }), '2026-09-14');
  for (const time of ['05:00:00', '09:59:59']) {
    assert.equal(lastCompletedNight({ businessDate: night, serverNow: `2026-09-16T${time}+08:00` }), night);
  }
  assert.equal(lastCompletedNight({ businessDate: '2026-09-16', serverNow: '2026-09-16T10:00:00+08:00' }), night);
  assert.equal(lastCompletedNight({ businessDate: '2026-01-01', serverNow: '2026-01-01T16:00:00+08:00' }), '2025-12-31');
  assert.equal(nightHasEnded(night, '2026-09-16T04:59:59+08:00'), false);
  assert.equal(validNight('2026-02-30'), false);
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
