import { useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  createVoucherBatch,
  fetchVoucherBatches,
  fetchVouchers,
} from '@/api/endpoints/vouchers';
import { queryKeys } from '@/api/queryKeys';
import { messageOf } from '@/api/errors';
import type { VoucherBatch, VoucherBatchRequest } from '@/api/types';
import { AdminPage } from '../AdminPage';
import { useSetupLifecycle } from '../setup/useSetupLifecycle';
import { Card } from '@/components/Card';
import { Button } from '@/components/Button';
import { Field } from '@/components/Field';
import { Modal } from '@/components/Modal';
import { Banner } from '@/components/Banner';
import { Spinner } from '@/components/Spinner';
import { formatBusinessDate, formatDateTime } from '@/lib/datetime';
import { VoucherCodesDialog, VoucherCodeList } from './VoucherCodes';
import './vouchers.css';

/**
 * The giveaway, from the owner's side.
 *
 * Under RequireAdmin, and that is a security boundary rather than a placement: whoever can read
 * a list of unredeemed codes can spend them. The counter never sees this screen — a cashier
 * redeems a code the customer already holds, which is a different thing entirely.
 *
 * Batch identity leads; the server-supplied counts distinguish liability from history.
 */
export function VouchersPage() {
  const queryClient = useQueryClient();
  const [creating, setCreating] = useState(false);
  const [generated, setGenerated] = useState<VoucherBatch | null>(null);
  const [inspecting, setInspecting] = useState<VoucherBatch | null>(null);
  const [error, setError] = useState<string | null>(null);

  const batches = useQuery({
    queryKey: queryKeys.voucherBatches,
    queryFn: fetchVoucherBatches,
  });

  const lifecycle = useSetupLifecycle('vouchers', refresh);
  function refresh() {
    void queryClient.invalidateQueries({ queryKey: queryKeys.voucherBatches });
    void queryClient.invalidateQueries({ queryKey: queryKeys.setup('vouchers') });
  }

  const create = useMutation({
    mutationFn: (body: VoucherBatchRequest) => createVoucherBatch(body),
    onSuccess: (batch) => {
      setError(null);
      setCreating(false);
      // Straight into the code list, because this is the ONE moment the codes are handed back
      // and the owner has to get them out of the screen and into a post or a printer. Closing
      // this without copying them is recoverable — they are on the batch — but making him go
      // looking is not what you want at the end of creating fifty of them.
      setGenerated(batch);
      refresh();
    },
    onError: (caught) => setError(messageOf(caught)),
  });

  return (
    <AdminPage
      title="Vouchers"
      intro="Free table time, given away as a prize. A voucher covers a number of HOURS at whatever the table charges — play longer and the customer pays the difference, play less and the rest is forfeited. Codes are single use and only work on a session billed at the standard rate."
      error={error ?? (batches.isError ? messageOf(batches.error) : null)}
    >
      <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
        {lifecycle.toggle}
        <Button onClick={() => setCreating(true)}>New batch</Button>
      </div>

      {batches.isPending ? (
        <Card><div className="py-10 text-center"><Spinner label="Loading voucher batches…" /></div></Card>
      ) : batches.isError ? null : (batches.data ?? []).length === 0 ? (
        <Card><p className="py-8 text-center text-body text-text-dim">No vouchers have been generated yet.</p></Card>
      ) : (
        <ul className="voucher-batches" aria-label="Voucher batches">
          {(batches.data ?? []).map(batch => (
            <li key={batch.id}>
              <Card className="voucher-batch">
                <div className="voucher-batch-heading">
                  <div className="min-w-0">
                    <h2 className="text-heading text-text">{batch.quantity} {batch.quantity === 1 ? 'coupon' : 'coupons'}</h2>
                    <p className="text-body text-text-dim">{batch.hoursLabel}</p>
                  </div>
                  <div className="voucher-batch-actions">
                    <Button variant="secondary" onClick={() => setInspecting(batch)}>Codes</Button>
                    {lifecycle.action(batch.id)}
                  </div>
                </div>
                <h3 className="mt-4 break-words text-body font-semibold text-text">{batch.note ?? 'No giveaway note'}</h3>
                <p className="mt-1 break-words text-label text-text-dim">
                  Created by {batch.createdByUsername ?? 'unknown'} on {formatDateTime(batch.createdAt)}
                </p>
                <dl className="voucher-counts">
                  <Count label="Outstanding" value={batch.outstanding} lead />
                  <Count label="Redeemed" value={batch.redeemed} />
                  <Count label="Expired" value={batch.expired} />
                </dl>
                <p className="voucher-expiry text-label text-text-dim">Expires {formatBusinessDate(batch.expiresOn)}</p>
              </Card>
            </li>
          ))}
        </ul>
      )}

      {creating ? (
        <NewBatchForm
          pending={create.isPending}
          onClose={() => setCreating(false)}
          onSave={(body) => create.mutate(body)}
        />
      ) : null}

      {generated ? (
        <GeneratedCodes batch={generated} onClose={() => setGenerated(null)} />
      ) : null}

      {inspecting ? (
        <BatchCodes batch={inspecting} onClose={() => setInspecting(null)} />
      ) : null}
      {lifecycle.panel}
      {lifecycle.dialog}
    </AdminPage>
  );
}

function Count({ label, value, lead }: { label: string; value: number; lead?: boolean }) {
  return (
    <div>
      <dt className="text-label uppercase text-text-dim">{label}</dt>
      <dd className={`tabular ${lead ? 'text-amount text-text' : 'text-amount text-text-dim'}`}>
        {value}
      </dd>
    </div>
  );
}

function NewBatchForm({
  pending,
  onClose,
  onSave,
}: {
  pending: boolean;
  onClose: () => void;
  onSave: (body: VoucherBatchRequest) => void;
}) {
  const [hours, setHours] = useState('2');
  const [quantity, setQuantity] = useState('50');
  const [expiresOn, setExpiresOn] = useState('');
  const [note, setNote] = useState('');

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    if (hours.trim() === '' || quantity.trim() === '' || expiresOn === '') return;
    onSave({
      hours: Number(hours),
      quantity: Number(quantity),
      expiresOn,
      note: note.trim() === '' ? undefined : note.trim(),
    });
  }

  return (
    <Modal title="New voucher batch" onClose={onClose}>
      <form onSubmit={handleSubmit} className="flex flex-col gap-6">
        {/* HOURS, because that is how the prize was advertised. The server converts to minutes
            and refuses anything that is not a whole number of them. */}
        <Field
          label="Hours per voucher"
          type="number"
          min="0.25"
          step="0.25"
          inputMode="decimal"
          value={hours}
          data-autofocus
          hint="What each code is worth — 2 means two hours of table time at whatever that table charges."
          onChange={(event) => setHours(event.target.value)}
        />
        <Field
          label="How many"
          type="number"
          min="1"
          max="500"
          inputMode="numeric"
          value={quantity}
          hint="Up to 500 in one batch. They are generated together and can be reopened from Codes."
          onChange={(event) => setQuantity(event.target.value)}
        />
        <Field
          label="Expires on"
          type="date"
          value={expiresOn}
          hint="Good for the whole of that night — a code expiring on the 31st still works at 2am on the 1st."
          onChange={(event) => setExpiresOn(event.target.value)}
        />
        <Field
          label="What this giveaway is (optional)"
          value={note}
          placeholder="October Facebook draw"
          hint="The only thing that tells one batch's redemptions from another's on the dashboard."
          onChange={(event) => setNote(event.target.value)}
        />
        <div className="flex justify-end gap-3">
          <Button type="button" variant="secondary" onClick={onClose}>
            Cancel
          </Button>
          <Button
            type="submit"
            pending={pending}
            disabled={hours.trim() === '' || quantity.trim() === '' || expiresOn === ''}
          >
            Generate
          </Button>
        </div>
      </form>
    </Modal>
  );
}

/** The codes as generated, laid out to be copied or printed. */
function GeneratedCodes({ batch, onClose }: { batch: VoucherBatch; onClose: () => void }) {
  return (
    <VoucherCodesDialog title={`${batch.quantity} codes generated`} batch={batch} onClose={onClose}>
      <div className="mb-4">
        <Banner tone="warning">
          Each code is single use and expires {formatBusinessDate(batch.expiresOn)}. Anyone who
          can read a code can spend it — treat this list like cash.
        </Banner>
      </div>
      <VoucherCodeList codes={batch.codes ?? []} />
    </VoucherCodesDialog>
  );
}

/** One batch's codes, read back later. Fetched rather than held, so the statuses are current. */
function BatchCodes({ batch, onClose }: { batch: VoucherBatch; onClose: () => void }) {
  const codes = useQuery({
    queryKey: queryKeys.vouchers(batch.id),
    queryFn: () => fetchVouchers(batch.id),
  });

  return (
    <VoucherCodesDialog title={`${batch.quantity} ${batch.quantity === 1 ? 'coupon' : 'coupons'}`} batch={batch} onClose={onClose}>
      {codes.isPending ? (
        <div className="py-8 text-center">
          <Spinner label="Loading codes…" />
        </div>
      ) : codes.isError ? (
        <Banner tone="danger">{messageOf(codes.error)}</Banner>
      ) : (
        <VoucherCodeList codes={codes.data ?? []} />
      )}
    </VoucherCodesDialog>
  );
}
