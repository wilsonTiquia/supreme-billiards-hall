import { useState, type FormEvent } from 'react';
import type { BillLine } from '@/api/types';
import { Modal } from '@/components/Modal';
import { Button } from '@/components/Button';
import { Field } from '@/components/Field';
import { Banner } from '@/components/Banner';
import { formatMoney } from '@/lib/money';

/**
 * A void needs a reason — the server rejects a blank one with a 400, and the reason is what
 * the owner reads later in the audit log. The line is retained and struck through, never
 * removed, so staff can see what happened.
 */
export function VoidLineModal({
  line,
  pending,
  error,
  onClose,
  onConfirm,
}: {
  line: BillLine;
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
    <Modal title="Void this line" onClose={onClose}>
      <form onSubmit={handleSubmit} className="flex flex-col gap-6">
        <div className="rounded-lg border border-border bg-raised p-3">
          <div className="text-body text-text">{line.description}</div>
          <div className="tabular text-label text-text-dim">
            {line.quantity} × {formatMoney(line.unitPrice)} = {formatMoney(line.lineTotal)}
          </div>
        </div>

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
            Void line
          </Button>
        </div>
      </form>
    </Modal>
  );
}
