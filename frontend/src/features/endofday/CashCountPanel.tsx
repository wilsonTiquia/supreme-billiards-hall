import { useState, type FormEvent } from 'react';
import type { CashCount } from '@/api/types';
import { formatDateTime } from '@/lib/datetime';
import { Card } from '@/components/Card';
import { Button } from '@/components/Button';
import { Field } from '@/components/Field';
import { Banner } from '@/components/Banner';
import { formatAmountDigits, formatMoney, parseAmount } from '@/lib/money';

/**
 * The drawer count.
 *
 * The counted total is the only figure the counter produces. The takings stay hidden until
 * after they commit — a counter who can see the target first is not really counting — and the
 * float is shown because it is not a secret: they put it in the drawer themselves at open.
 *
 * It is prefilled with the branch standard so the usual night is one action, counting. The
 * override exists because occasionally the float genuinely differed, and a control that cannot
 * describe an unusual night gets worked around instead of used.
 */
export function CashCountPanel({
  count,
  standardFloat,
  paidFromDrawer,
  pending,
  error,
  canCorrect,
  correcting,
  correctError,
  onSubmit,
  onCorrect,
  onRecount,
  recounting,
}: {
  count: CashCount | null;
  standardFloat: number;
  /**
   * Paid out of the drawer tonight, before it has been counted.
   *
   * Shown for the same reason the float is: it is not a secret — the counter handed the money
   * over themselves — and it is the difference between a drawer that reads ₱850 short and one
   * that reads right. It does NOT reveal the expected total, which needs the takings, and those
   * stay hidden until after the count is committed.
   */
  paidFromDrawer: number;
  pending: boolean;
  error: string | null;
  canCorrect: boolean;
  correcting: boolean;
  correctError: string | null;
  onSubmit: (countedCash: number, openingFloat: number | undefined, note?: string) => void;
  onCorrect: (countedCash: number, openingFloat: number | undefined, note?: string) => void;
  onRecount: (countedCash: number, note?: string) => void;
  recounting: boolean;
}) {
  const [counted, setCounted] = useState('');
  /*
   * Held as a string, and seeded when the override is OPENED rather than at mount.
   *
   * standardFloat arrives with the business day, which can land after this panel first renders
   * — seeding from it in useState would capture whatever it was at that instant, usually the 0
   * fallback, and never correct itself. Filling it at the moment the field appears reads the
   * prop as it stands then, and needs no effect to keep it honest.
   */
  const [float_, setFloat] = useState('');
  const [floatEditable, setFloatEditable] = useState(false);
  const [note, setNote] = useState('');
  const [showCorrection, setShowCorrection] = useState(false);
  const [correctedTo, setCorrectedTo] = useState('');
  const [correctedFloat, setCorrectedFloat] = useState('');
  const [correctionNote, setCorrectionNote] = useState('');
  const [showRecount, setShowRecount] = useState(false);
  const [recountedTo, setRecountedTo] = useState('');
  const [recountNote, setRecountNote] = useState('');

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    if (counted.trim() === '') return;
    // Sent only when it was actually changed. Omitting it lets the server apply the standard,
    // which keeps "what counts as an override" a server decision rather than a client claim.
    const typedFloat = parseAmount(float_);
    const overriddenFloat = floatEditable && typedFloat !== null ? typedFloat : undefined;
    onSubmit(Number(counted), overriddenFloat, note.trim() === '' ? undefined : note.trim());
  }

  if (count) {
    const short = count.variance < 0;
    const over = count.variance > 0;
    // The night runs to 05:00, so a sale can land on a day already signed off. When that has
    // happened the variance below is measured against a total that has since moved, and
    // showing it as a clean balance would be a lie the screen tells with a straight face.
    const stale =
      count.closedAt !== null && (count.salesAfterClose > 0 || count.expensesAfterClose > 0);

    return (
      <Card>
        <h2 className="text-heading text-text">Drawer counted</h2>
        {/* The arithmetic, not just its answer. A variance nobody can check is a number people
            learn to nod at; float + takings shown beside the total means "why 1,050" is
            answerable on the screen, months later, without asking anyone. */}
        <dl className="mt-4 space-y-1">
          <div className="flex justify-between gap-3">
            <dt className="text-label uppercase text-text-dim">
              Float at open
              {count.floatOverridden ? (
                <span className="ml-2 rounded border border-gold px-1.5 py-0.5 text-label uppercase text-text">
                  Not the usual
                </span>
              ) : null}
            </dt>
            <dd className="tabular text-body text-text-dim">{formatMoney(count.openingFloat)}</dd>
          </div>
          <div className="flex justify-between gap-3">
            <dt className="text-label uppercase text-text-dim">Cash takings</dt>
            <dd className="tabular text-body text-text-dim">{formatMoney(count.cashSales)}</dd>
          </div>
          {/* Only when there was one. A "less expenses ₱0.00" line every night is a row people
              stop reading, and then miss on the night it says 850. */}
          {count.cashExpenses > 0 ? (
            <div className="flex justify-between gap-3">
              <dt className="text-label uppercase text-text-dim">Less paid from the drawer</dt>
              <dd className="tabular text-body text-text-dim">
                −{formatMoney(count.cashExpenses)}
              </dd>
            </div>
          ) : null}
          <div className="flex justify-between gap-3 border-t border-border pt-2">
            <dt className="text-label uppercase text-text-dim">Expected</dt>
            <dd className="tabular text-body text-text">{formatMoney(count.expectedCash)}</dd>
          </div>
          <div className="flex justify-between gap-3">
            <dt className="text-label uppercase text-text-dim">Counted</dt>
            <dd className="tabular text-body text-text">{formatMoney(count.countedCash)}</dd>
          </div>
          <div className="flex items-baseline justify-between gap-3 border-t border-border pt-2">
            <dt className="text-label uppercase text-text-dim">Variance</dt>
            {/* Short is money missing and reads as danger; over is still a discrepancy worth
                explaining, so it warns rather than passing silently. */}
            <dd
              className={`figure-amount ${short ? 'text-danger' : over ? 'text-amount' : 'text-green'}`}
            >
              {over ? '+' : ''}
              {formatMoney(count.variance)}
            </dd>
          </div>
        </dl>
        {stale ? (
          <div className="mt-4">
            <Banner tone="warning">
              <div>
                <strong>This count no longer describes the night.</strong> The day was closed{' '}
                {formatDateTime(count.closedAt as string)}, and{' '}
                {count.salesAfterClose > 0 ? (
                  <>
                    {count.salesAfterClose}{' '}
                    {count.salesAfterClose === 1 ? 'sale' : 'sales'} worth{' '}
                    {formatMoney(count.amountAfterClose)} have been recorded since —{' '}
                    {formatMoney(count.cashAfterClose)} of it cash.{' '}
                  </>
                ) : null}
                {/* A payout after the close moves the drawer exactly as a late sale does, and
                    without this it would be the one change to the night nothing announced. */}
                {count.expensesAfterClose > 0 ? (
                  <>
                    {count.expensesAfterClose}{' '}
                    {count.expensesAfterClose === 1 ? 'expense' : 'expenses'} worth{' '}
                    {formatMoney(count.cashExpensesAfterClose)} have been paid out of the drawer
                    since.{' '}
                  </>
                ) : null}
                The expected figure above was frozen when you counted, so the variance is
                measured against a total that has moved. Count the drawer again and close the day
                a second time.
              </div>
            </Banner>
            {showRecount ? (
              <form
                className="mt-4 flex flex-col gap-4"
                onSubmit={(event) => {
                  event.preventDefault();
                  if (recountedTo.trim() === '') return;
                  onRecount(Number(recountedTo), recountNote.trim() || undefined);
                }}
              >
                <Field
                  label="Cash counted now"
                  type="number"
                  step="0.01"
                  min="0"
                  inputMode="decimal"
                  value={recountedTo}
                  data-autofocus
                  hint="The first count and its close stay in the audit log."
                  onChange={(event) => setRecountedTo(event.target.value)}
                />
                <Field
                  label="Why"
                  value={recountNote}
                  placeholder="What was sold after closing"
                  onChange={(event) => setRecountNote(event.target.value)}
                />
                <div className="flex justify-end gap-3">
                  <Button type="button" variant="secondary" onClick={() => setShowRecount(false)}>
                    Cancel
                  </Button>
                  <Button type="submit" pending={recounting} disabled={recountedTo.trim() === ''}>
                    Save the new count
                  </Button>
                </div>
              </form>
            ) : (
              <Button variant="danger" className="mt-4" onClick={() => setShowRecount(true)}>
                Count the drawer again
              </Button>
            )}
          </div>
        ) : null}

        <p className="mt-3 text-body text-text-dim">
          {short
            ? 'The drawer is short. Note what happened before closing.'
            : over
              ? 'The drawer is over. Worth a note — it usually means a payment was mis-keyed.'
              : 'The drawer balances exactly.'}
        </p>
        {count.note ? <p className="mt-2 text-label text-text-dim">Note: {count.note}</p> : null}

        {count.closedAt ? (
          <p className="mt-4 border-t border-border pt-3 text-label uppercase text-text-dim">
            Day closed {formatDateTime(count.closedAt)}
            {count.closedByUsername ? ` by ${count.closedByUsername}` : ''}
          </p>
        ) : null}

        {/* Owner-only, and gone once the night is signed off. The original figure is not
            overwritten anywhere that matters — the correction writes both values to the
            audit log. */}
        {canCorrect && !count.closedAt ? (
          showCorrection ? (
            <form
              className="mt-4 flex flex-col gap-4 border-t border-border pt-4"
              onSubmit={(event) => {
                event.preventDefault();
                if (correctedTo.trim() === '') return;
                onCorrect(
                  Number(correctedTo),
                  correctedFloat.trim() === '' ? undefined : Number(correctedFloat),
                  correctionNote.trim() || undefined,
                );
              }}
            >
              <Field
                label="Corrected count"
                type="number"
                step="0.01"
                min="0"
                inputMode="decimal"
                value={correctedTo}
                data-autofocus
                hint="The original figure stays in the audit log."
                onChange={(event) => setCorrectedTo(event.target.value)}
              />
              {/* "We ran 500 last night, not 1,000" is exactly as likely a mistake as a
                  mistyped total, so it is correctable here too. Blank leaves it alone. */}
              <Field
                label="Corrected float"
                type="number"
                step="0.01"
                min="0"
                inputMode="decimal"
                value={correctedFloat}
                placeholder={String(count.openingFloat)}
                hint="Leave blank to keep the recorded float."
                onChange={(event) => setCorrectedFloat(event.target.value)}
              />
              <Field
                label="Why"
                value={correctionNote}
                placeholder="What was wrong with the first figure"
                onChange={(event) => setCorrectionNote(event.target.value)}
              />
              {correctError ? <Banner tone="danger">{correctError}</Banner> : null}
              <div className="flex justify-end gap-3">
                <Button type="button" variant="secondary" onClick={() => setShowCorrection(false)}>
                  Cancel
                </Button>
                <Button type="submit" pending={correcting} disabled={correctedTo.trim() === ''}>
                  Save correction
                </Button>
              </div>
            </form>
          ) : (
            <Button
              variant="secondary"
              className="mt-4"
              onClick={() => setShowCorrection(true)}
            >
              Correct the count
            </Button>
          )
        ) : null}
      </Card>
    );
  }

  return (
    <Card>
      <h2 className="text-heading text-text">Count the drawer</h2>
      <p className="mt-1 text-body text-text-dim">
        Count everything in the drawer, float included, and enter the total. The expected figure
        and the variance are shown once it is recorded — the count is deliberately blind.
      </p>

      <form onSubmit={handleSubmit} className="mt-4 flex flex-col gap-6">
          <Field
            label="Cash counted"
            type="number"
            step="0.01"
            min="0"
            inputMode="decimal"
            value={counted}
            placeholder="0.00"
            onChange={(event) => setCounted(event.target.value)}
          />

          {/* The float, already filled in. Locked by default so the ordinary night is one
              action; the link is there for the night it genuinely differed rather than making
              everyone retype a constant. */}
          <div>
            {/* The heading belongs to the read-only state only. Once the field opens it carries
                its own label, and showing both stacks two headings on one control. */}
            {!floatEditable ? (
              <div className="flex flex-wrap items-baseline justify-between gap-3">
                <span className="text-label uppercase text-text-dim">
                  Change float (cash in the drawer before opening)
                </span>
                <button
                  type="button"
                  className="hit text-label text-info underline"
                  onClick={() => {
                    setFloat(formatAmountDigits(standardFloat));
                    setFloatEditable(true);
                  }}
                >
                  Tonight’s float was different
                </button>
              </div>
            ) : null}
            {!floatEditable ? (
              <p className="tabular mt-1 text-body text-text">{formatMoney(standardFloat)}</p>
            ) : (
              <div>
                <Field
                  label="Change float (cash in the drawer before opening)"
                  type="text"
                  inputMode="decimal"
                  autoComplete="off"
                  prefix="₱"
                  value={float_}
                  data-autofocus
                  hint="Recorded on the count and in the audit log. Say why in the note."
                  onChange={(event) => setFloat(event.target.value)}
                  onBlur={() => {
                    const amount = parseAmount(float_);
                    setFloat(amount === null ? '' : formatAmountDigits(amount));
                  }}
                />
                <button
                  type="button"
                  className="hit mt-1 text-label text-text-dim underline"
                  onClick={() => {
                    setFloatEditable(false);
                    setFloat('');
                  }}
                >
                  Use the usual {formatMoney(standardFloat)}
                </button>
              </div>
            )}
          </div>
          {/* Beside the float, and on the same footing: both are things the counter already
              knows because they did them, and neither gives away the takings. Without this the
              drawer simply reads short by whatever was paid out. */}
          {paidFromDrawer > 0 ? (
            <div className="flex items-baseline justify-between gap-3">
              <span className="text-label uppercase text-text-dim">
                Paid out of the drawer tonight
              </span>
              <span className="tabular text-body text-text">{formatMoney(paidFromDrawer)}</span>
            </div>
          ) : null}
          <Field
            label="Note (optional)"
            value={note}
            placeholder="Anything that explains a difference"
            onChange={(event) => setNote(event.target.value)}
          />
          {error ? <Banner tone="danger">{error}</Banner> : null}
        <Button type="submit" pending={pending} disabled={counted.trim() === ''}>
          Record the count
        </Button>
      </form>
    </Card>
  );
}
