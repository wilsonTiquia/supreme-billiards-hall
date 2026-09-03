import { useState, type FormEvent } from 'react';
import type { PaymentMethod, PaymentRequest } from '@/api/types';
import { Button } from '@/components/Button';
import { Field } from '@/components/Field';
import { formatMoney } from '@/lib/money';

const METHODS: PaymentMethod[] = ['CASH', 'GCASH', 'MAYA'];

/**
 * Builds the request per method, with the irrelevant keys **absent** rather than blank.
 *
 * This is not stylistic. The server rejects on `referenceNo != null` for cash and
 * `tendered != null` for digital — a null check, not a blank check — so sending
 * `referenceNo: ""` from an untouched input produces a 409 that reads like a server bug at
 * the counter on a Friday night.
 */
export function PaymentForm({
  total,
  billVersion,
  idempotencyKey,
  duplicateOverride,
  pending,
  onSubmit,
}: {
  total: number;
  billVersion: number;
  idempotencyKey: string;
  duplicateOverride: boolean;
  pending: boolean;
  onSubmit: (body: PaymentRequest) => void;
}) {
  const [method, setMethod] = useState<PaymentMethod>('CASH');
  const [tendered, setTendered] = useState('');
  const [referenceNo, setReferenceNo] = useState('');

  const cashShort = method === 'CASH' && tendered !== '' && Number(tendered) < total;
  const canSubmit =
    method === 'CASH' ? tendered !== '' && !cashShort : referenceNo.trim() !== '';

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    if (!canSubmit) return;

    if (method === 'CASH') {
      // No referenceNo key at all.
      onSubmit({
        method: 'CASH',
        amount: total,
        tendered: Number(tendered),
        idempotencyKey,
        billVersion,
      });
      return;
    }

    // No tendered key at all.
    onSubmit({
      method,
      amount: total,
      referenceNo: referenceNo.trim(),
      idempotencyKey,
      billVersion,
      ...(duplicateOverride ? { duplicateOverride: true } : {}),
    });
  }

  return (
    <form onSubmit={handleSubmit} className="flex flex-col gap-6">
      <div>
        <div className="text-label uppercase text-text-dim">Method</div>
        <div className="mt-2 flex gap-3">
          {METHODS.map((option) => (
            <button
              key={option}
              type="button"
              onClick={() => {
                setMethod(option);
                setTendered('');
                setReferenceNo('');
              }}
              className={`h-14 flex-1 rounded-xl border px-4 text-heading font-semibold transition ${
                method === option
                  ? 'border-green bg-green text-ink'
                  : 'border-border bg-raised text-text hover:border-text-dim'
              }`}
            >
              {option === 'CASH' ? 'Cash' : option === 'GCASH' ? 'GCash' : 'Maya'}
            </button>
          ))}
        </div>
      </div>

      {method === 'CASH' ? (
        <Field
          label="Tendered"
          type="number"
          step="0.01"
          min={total}
          inputMode="decimal"
          value={tendered}
          data-autofocus
          placeholder={String(total)}
          error={cashShort ? `Less than the ${formatMoney(total)} due.` : undefined}
          // The change figure is deliberately not previewed here: it comes back on the
          // payment response, computed by the server.
          hint="Change is shown once the payment is recorded."
          onChange={(event) => setTendered(event.target.value)}
        />
      ) : (
        <Field
          label="Reference number"
          value={referenceNo}
          data-autofocus
          placeholder="e.g. GC-001"
          onChange={(event) => setReferenceNo(event.target.value)}
        />
      )}

      <Button type="submit" className="h-14 text-heading" pending={pending} disabled={!canSubmit}>
        {duplicateOverride ? 'Record anyway' : `Take ${formatMoney(total)}`}
      </Button>
    </form>
  );
}
