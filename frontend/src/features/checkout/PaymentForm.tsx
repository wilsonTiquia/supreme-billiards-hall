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
  onSubmit: (body: PaymentRequest, photo: File | null) => void;
}) {
  const [method, setMethod] = useState<PaymentMethod>('CASH');
  const [tendered, setTendered] = useState('');
  const [referenceNo, setReferenceNo] = useState('');
  const [photo, setPhoto] = useState<File | null>(null);

  const cashShort = method === 'CASH' && tendered !== '' && Number(tendered) < total;
  const canSubmit =
    method === 'CASH' ? tendered !== '' && !cashShort : referenceNo.trim() !== '';

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    if (!canSubmit || pending) return;

    if (method === 'CASH') {
      // No referenceNo key at all.
      onSubmit({
        method: 'CASH',
        amount: total,
        tendered: Number(tendered),
        idempotencyKey,
        billVersion,
      }, null);
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
    }, photo);
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
              disabled={pending}
              onClick={() => {
                if (option === method) return;
                setMethod(option);
                setPhoto(null);
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
        <div className="grid min-w-0 grid-cols-[minmax(0,1fr)_auto] items-start gap-3">
          <Field
            label="Reference number"
            value={referenceNo}
            disabled={pending}
            data-autofocus
            placeholder="e.g. GC-001"
            onChange={(event) => setReferenceNo(event.target.value)}
          />
          <div className="pt-6">
            <label className={`hit relative inline-flex items-center rounded-lg border border-border bg-raised px-3 text-body text-text focus-within:outline-2 focus-within:outline-offset-2 focus-within:outline-info ${pending ? 'opacity-60' : ''}`}>
              {photo ? 'Change photo' : 'Add photo'}
              <input
                key={method}
                type="file"
                aria-label="Payment photo"
                accept="image/jpeg,image/png,image/webp"
                disabled={pending}
                onChange={(event) => {
                  const file = event.target.files?.[0];
                  if (file) setPhoto(file);
                  event.currentTarget.value = '';
                }}
                className="absolute inset-0 w-full cursor-pointer opacity-0"
              />
            </label>
          </div>
          <div className="col-span-2 min-w-0 text-label text-text-dim">
            {photo ? (
              <div className="flex flex-wrap items-center gap-x-3">
                <span className="min-w-0 [overflow-wrap:anywhere]">{photo.name}</span>
                <button type="button" disabled={pending} className="hit text-info underline" onClick={() => setPhoto(null)}>Remove photo</button>
              </div>
            ) : 'Optional confirmation photo · JPEG, PNG or WebP.'}
          </div>
        </div>
      )}

      <Button type="submit" className="h-14 text-heading" pending={pending} disabled={!canSubmit}>
        {duplicateOverride ? 'Record anyway' : `Take ${formatMoney(total)}`}
      </Button>
    </form>
  );
}
