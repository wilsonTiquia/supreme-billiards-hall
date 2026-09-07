import { useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { fetchExpenses, recordExpense, voidExpense } from '@/api/endpoints/expenses';
import { fetchExpenseCategories } from '@/api/endpoints/expenseCategories';
import { queryKeys } from '@/api/queryKeys';
import { messageOf } from '@/api/errors';
import type { Expense } from '@/api/types';
import { useScreenTheme } from '@/app/useTheme';
import { formatMoney, formatAmountDigits, parseAmount } from '@/lib/money';
import { formatTime } from '@/lib/datetime';
import { Card } from '@/components/Card';
import { Button } from '@/components/Button';
import { Field } from '@/components/Field';
import { Select } from '@/components/Select';
import { Banner } from '@/components/Banner';
import { Spinner } from '@/components/Spinner';
import { VoidExpenseModal } from './VoidExpenseModal';

/**
 * What the hall spent tonight.
 *
 * A counter screen, not an admin one — the person who hands ₱850 to the water man is the person
 * who records it, and asking them to fetch the owner would mean it goes on a scrap of paper
 * instead. Nothing here is cost of goods or profit, so there is no role gate on the figures.
 *
 * The running total is the server's own values added for display only; no peso figure on this
 * screen was computed in the browser from a rate or a quantity.
 */
export function ExpensesPage() {
  useScreenTheme('pos');
  const queryClient = useQueryClient();

  const [voiding, setVoiding] = useState<Expense | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [voidError, setVoidError] = useState<string | null>(null);

  // No date: tonight, as the database reckons it. The list is the night still running, which is
  // the only one the counter can add to anyway.
  const expenses = useQuery({ queryKey: queryKeys.expenses(), queryFn: () => fetchExpenses() });
  const categories = useQuery({
    queryKey: queryKeys.expenseCategories,
    queryFn: fetchExpenseCategories,
  });

  function refresh() {
    void queryClient.invalidateQueries({ queryKey: queryKeys.expenses() });
    // The end-of-day panel reads the same money from the other side.
    void queryClient.invalidateQueries({ queryKey: queryKeys.businessDayCurrent });
  }

  const add = useMutation({
    mutationFn: recordExpense,
    onSuccess: () => {
      setError(null);
      refresh();
    },
    onError: (caught) => setError(messageOf(caught)),
  });

  const cancel = useMutation({
    mutationFn: ({ id, reason }: { id: string; reason: string }) => voidExpense(id, { reason }),
    onSuccess: () => {
      setVoidError(null);
      setVoiding(null);
      refresh();
    },
    onError: (caught) => setVoidError(messageOf(caught)),
  });

  const rows = expenses.data ?? [];
  const live = rows.filter((expense) => !expense.voided);
  const total = live.reduce((sum, expense) => sum + expense.amount, 0);
  const fromDrawer = live
    .filter((expense) => expense.paidFromDrawer)
    .reduce((sum, expense) => sum + expense.amount, 0);

  return (
    <div className="mx-auto max-w-3xl">
      <h1 className="text-heading text-text">Expenses</h1>
      <p className="mt-1 text-body text-text-dim">
        What the hall paid out tonight — deliveries, bills, supplies. Anything paid from the
        drawer comes off the cash you are expected to have at close.
      </p>

      {error ? (
        <div className="mt-4">
          <Banner tone="danger">{error}</Banner>
        </div>
      ) : null}
      {expenses.isError ? (
        <div className="mt-4">
          <Banner tone="danger">{messageOf(expenses.error)}</Banner>
        </div>
      ) : null}

      <div className="mt-6">
        <AddExpenseForm
          categories={categories.data ?? []}
          categoriesPending={categories.isPending}
          pending={add.isPending}
          onAdd={(body) => add.mutate(body)}
        />
      </div>

      <Card className="mt-6">
        <div className="flex items-baseline justify-between gap-3">
          <h2 className="text-heading text-text">Tonight</h2>
          <span className="text-label uppercase text-text-dim">
            {live.length} {live.length === 1 ? 'expense' : 'expenses'}
          </span>
        </div>

        {expenses.isPending ? (
          <div className="py-10 text-center">
            <Spinner label="Loading tonight's expenses…" />
          </div>
        ) : rows.length === 0 ? (
          <p className="mt-4 text-body text-text-dim">Nothing paid out yet tonight.</p>
        ) : (
          <>
            <ul className="mt-4 divide-y divide-border">
              {rows.map((expense) => (
                <ExpenseRow key={expense.id} expense={expense} onVoid={() => setVoiding(expense)} />
              ))}
            </ul>

            <dl className="mt-4 space-y-1 border-t border-border pt-3">
              <div className="flex justify-between gap-3">
                <dt className="text-label uppercase text-text-dim">Total tonight</dt>
                <dd className="tabular text-body text-text">{formatMoney(total)}</dd>
              </div>
              {/* The half that changes the drawer, called out separately: it is the figure the
                  count reconciles against, and the other half never touched the till. */}
              <div className="flex justify-between gap-3">
                <dt className="text-label uppercase text-text-dim">Of that, from the drawer</dt>
                <dd className="tabular text-body text-text">{formatMoney(fromDrawer)}</dd>
              </div>
            </dl>
          </>
        )}
      </Card>

      {voiding ? (
        <VoidExpenseModal
          expense={voiding}
          pending={cancel.isPending}
          error={voidError}
          onClose={() => {
            setVoiding(null);
            setVoidError(null);
          }}
          onConfirm={(reason) => cancel.mutate({ id: voiding.id, reason })}
        />
      ) : null}
    </div>
  );
}

/*
 * A voided row stays on the list, struck through, with its reason.
 *
 * Removing it would leave the counter looking at a list that does not contain the ₱850 they
 * remember typing, and the reliable response to that is to type it again.
 */
function ExpenseRow({ expense, onVoid }: { expense: Expense; onVoid: () => void }) {
  return (
    <li className="flex items-start justify-between gap-3 py-3">
      {/* The strike goes on the individual lines, not the row: text-decoration inherits and a
          descendant cannot cancel it, so striking the container would score through the void
          reason too — the one line on a voided row that has to stay readable. */}
      <div className={expense.voided ? 'text-text-dim' : 'text-text'}>
        <div className="text-body">
          <span className={expense.voided ? 'line-through' : undefined}>
            {expense.categoryName ?? 'Expense'}
          </span>
          {!expense.paidFromDrawer ? (
            <span className="ml-2 text-label uppercase text-text-dim">Not from the drawer</span>
          ) : null}
        </div>
        <div className={`text-label text-text-dim ${expense.voided ? 'line-through' : ''}`}>
          {formatTime(expense.incurredAt)}
          {expense.recordedByUsername ? ` · ${expense.recordedByUsername}` : ''}
          {expense.note ? ` · ${expense.note}` : ''}
        </div>
        {expense.voided ? (
          <div className="text-label uppercase text-danger">
            Voided{expense.voidReason ? ` — ${expense.voidReason}` : ''}
          </div>
        ) : null}
      </div>

      <div className="flex shrink-0 items-center gap-3">
        <span
          className={`tabular text-body ${expense.voided ? 'text-text-dim line-through' : 'text-text'}`}
        >
          {formatMoney(expense.amount)}
        </span>
        {/* Away from the amount and absent once it is voided: destructive actions do not sit
            next to the thing the eye lands on. */}
        {!expense.voided ? (
          <Button variant="secondary" onClick={onVoid}>
            Void
          </Button>
        ) : null}
      </div>
    </li>
  );
}

function AddExpenseForm({
  categories,
  categoriesPending,
  pending,
  onAdd,
}: {
  categories: { id: string; name: string }[];
  categoriesPending: boolean;
  pending: boolean;
  onAdd: (body: {
    expenseCategoryId: string;
    amount: number;
    note?: string;
    paidFromDrawer: boolean;
  }) => void;
}) {
  const [amount, setAmount] = useState('');
  const [categoryId, setCategoryId] = useState('');
  const [note, setNote] = useState('');
  // Checked by default: cash out of the till is the ordinary case in this hall, and the
  // exception is the one worth an explicit click.
  const [paidFromDrawer, setPaidFromDrawer] = useState(true);

  const parsed = parseAmount(amount);
  const ready = parsed !== null && parsed > 0 && categoryId !== '';

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    if (!ready) return;
    onAdd({
      expenseCategoryId: categoryId,
      amount: parsed,
      note: note.trim() === '' ? undefined : note.trim(),
      paidFromDrawer,
    });
    setAmount('');
    setNote('');
    setPaidFromDrawer(true);
  }

  return (
    <Card>
      <h2 className="text-heading text-text">Add expense</h2>
      <form onSubmit={handleSubmit} className="mt-4 flex flex-col gap-6">
        <Field
          label="Amount"
          type="text"
          inputMode="decimal"
          autoComplete="off"
          prefix="₱"
          value={amount}
          placeholder="0.00"
          data-autofocus
          onChange={(event) => setAmount(event.target.value)}
          onBlur={() => {
            const typed = parseAmount(amount);
            setAmount(typed === null ? '' : formatAmountDigits(typed));
          }}
        />

        <Select
          label="What for"
          value={categoryId}
          hint={
            categoriesPending
              ? 'Loading categories…'
              : 'The owner adds categories under Set up → Expense categories.'
          }
          onChange={(event) => setCategoryId(event.target.value)}
        >
          <option value="">Choose one</option>
          {categories.map((category) => (
            <option key={category.id} value={category.id}>
              {category.name}
            </option>
          ))}
        </Select>

        <Field
          label="Note (optional)"
          value={note}
          placeholder="Who it was paid to, or what it was for"
          onChange={(event) => setNote(event.target.value)}
        />

        {/* The one field that changes the drawer arithmetic, so it says what it does rather
            than leaving the counter to find out at close. */}
        <label className="flex items-start gap-3 text-body text-text">
          <input
            type="checkbox"
            checked={paidFromDrawer}
            onChange={(event) => setPaidFromDrawer(event.target.checked)}
            className="mt-1 size-5"
          />
          <span>
            Paid from the cash drawer
            <span className="block text-label text-text-dim">
              Leave this on if the money came out of the till tonight. Uncheck it for a bank
              transfer or something the owner paid himself.
            </span>
          </span>
        </label>

        <Button type="submit" pending={pending} disabled={!ready}>
          Record expense
        </Button>
      </form>
    </Card>
  );
}
