import { useState, type FormEvent } from 'react';
import type { Expense } from '@/api/types';
import { Modal } from '@/components/Modal';
import { Button } from '@/components/Button';
import { Field } from '@/components/Field';
import { Banner } from '@/components/Banner';
import { formatMoney } from '@/lib/money';

/**
 * The same shape as VoidLineModal, and for the same reasons: the reason is mandatory — the
 * server rejects a blank one with a 400 — and the row is retained and struck through rather
 * than removed, so the counter can see that they recorded it and took it back.
 */
export function VoidExpenseModal({
  expense,
  pending,
  error,
  onClose,
  onConfirm,
}: {
  expense: Expense;
  pending: boolean;
  error: string | null;
  onClose: () => void;
  onConfirm: (reason: string) => void;
}) {
  const [reason, setReason] = useState('');

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    if (reason.trim() === '') return;
    onConfirm(reason.trim());
  }

  return (
    <Modal title="Void this expense" onClose={onClose}>
      <form onSubmit={handleSubmit} className="flex flex-col gap-6">
        <div className="rounded-lg border border-border bg-raised p-3">
          <div className="text-body text-text">{expense.categoryName ?? 'Expense'}</div>
          <div className="tabular text-label text-text-dim">
            {formatMoney(expense.amount)}
            {expense.paidFromDrawer ? ' · from the drawer' : ' · not from the drawer'}
          </div>
          {expense.note ? (
            <div className="text-label text-text-dim">{expense.note}</div>
          ) : null}
        </div>

        {expense.paidFromDrawer ? (
          <Banner tone="info">
            This was paid from the drawer, so voiding it puts {formatMoney(expense.amount)} back
            into tonight's expected cash.
          </Banner>
        ) : null}

        <Field
          label="Reason"
          value={reason}
          data-autofocus
          placeholder="Why is this being voided"
          hint="Recorded against your name in the audit log."
          onChange={(event) => setReason(event.target.value)}
        />

        {error ? <Banner tone="danger">{error}</Banner> : null}

        <div className="flex justify-end gap-3">
          <Button type="button" variant="secondary" onClick={onClose}>
            Keep it
          </Button>
          <Button type="submit" variant="danger" pending={pending} disabled={reason.trim() === ''}>
            Void expense
          </Button>
        </div>
      </form>
    </Modal>
  );
}
