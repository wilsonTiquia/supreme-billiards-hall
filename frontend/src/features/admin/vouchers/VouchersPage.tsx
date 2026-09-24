import { useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  createVoucherBatch,
  fetchVoucherBatches,
  fetchVouchers,
} from '@/api/endpoints/vouchers';
import { queryKeys } from '@/api/queryKeys';
import { messageOf } from '@/api/errors';
import type { Voucher, VoucherBatch, VoucherBatchRequest } from '@/api/types';
import { AdminPage } from '../AdminPage';
import { useSetupLifecycle } from '../setup/useSetupLifecycle';
import { Card } from '@/components/Card';
import { Button } from '@/components/Button';
import { Field } from '@/components/Field';
import { Modal } from '@/components/Modal';
import { Banner } from '@/components/Banner';
import { Spinner } from '@/components/Spinner';
import { formatBusinessDate, formatDateTime } from '@/lib/datetime';

/**
 * The giveaway, from the owner's side.
 *
 * Under RequireAdmin, and that is a security boundary rather than a placement: whoever can read
 * a list of unredeemed codes can spend them. The counter never sees this screen — a cashier
 * redeems a code the customer already holds, which is a different thing entirely.
 *
 * The screen answers one question the owner actually asks — how much is still out there — so
 * `outstanding` is the figure the row leads with. Issued is history; outstanding is liability.
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

      <Card>
        {batches.isPending ? (
          <div className="py-10 text-center">
            <Spinner label="Loading voucher batches…" />
          </div>
        ) : (batches.data ?? []).length === 0 ? (
          <p className="py-8 text-center text-body text-text-dim">
            No vouchers have been generated yet.
          </p>
        ) : (
          <ul className="divide-y divide-border">
            {(batches.data ?? []).map((batch) => (
              <li key={batch.id} className="flex flex-wrap items-center justify-between gap-3 py-3">
                <div>
                  <div className="text-body text-text">
                    {batch.quantity} × {batch.hoursLabel}
                    <span className="ml-2 text-label uppercase text-text-dim">
                      expires {formatBusinessDate(batch.expiresOn)}
                    </span>
                  </div>
                  <div className="text-label text-text-dim">
                    {batch.note ?? '— no note —'} · {batch.createdByUsername ?? 'unknown'} ·{' '}
                    {formatDateTime(batch.createdAt)}
                  </div>
                </div>
                <div className="flex flex-wrap items-center gap-3">
                  {/* Outstanding first and largest: it is the only one of the four that is
                      still a liability. The other three are what happened. */}
                  <dl className="flex gap-5 text-right">
                    <Count label="Outstanding" value={batch.outstanding} lead />
                    <Count label="Redeemed" value={batch.redeemed} />
                    <Count label="Expired" value={batch.expired} />
                  </dl>
                  <Button variant="secondary" onClick={() => setInspecting(batch)}>
                    Codes
                  </Button>
                  {lifecycle.action(batch.id)}
                </div>
              </li>
            ))}
          </ul>
        )}
      </Card>

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
      <dd className={`tabular ${lead ? 'text-amount text-text' : 'text-body text-text-dim'}`}>
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
          hint="Up to 500 in one batch. They are generated together and shown once for copying."
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
    <Modal title={`${batch.quantity} codes generated`} onClose={onClose}>
      <div className="flex flex-col gap-4">
        <Banner tone="warning">
          Each code is single use and expires {formatBusinessDate(batch.expiresOn)}. Anyone who
          can read a code can spend it — treat this list like cash.
        </Banner>
        <CodeGrid codes={batch.codes ?? []} />
      </div>
    </Modal>
  );
}

/** One batch's codes, read back later. Fetched rather than held, so the statuses are current. */
function BatchCodes({ batch, onClose }: { batch: VoucherBatch; onClose: () => void }) {
  const codes = useQuery({
    queryKey: queryKeys.vouchers(batch.id),
    queryFn: () => fetchVouchers(batch.id),
  });

  return (
    <Modal title={`${batch.quantity} × ${batch.hoursLabel}`} onClose={onClose}>
      {codes.isPending ? (
        <div className="py-8 text-center">
          <Spinner label="Loading codes…" />
        </div>
      ) : codes.isError ? (
        <Banner tone="danger">{messageOf(codes.error)}</Banner>
      ) : (
        <CodeGrid codes={codes.data ?? []} />
      )}
    </Modal>
  );
}

/**
 * Monospaced and widely tracked, because these get read aloud off a phone in a dark room. A
 * spent code is struck through rather than dropped: the owner checking a batch wants to see
 * which of the fifty came back, not a shorter list.
 */
function CodeGrid({ codes }: { codes: Voucher[] }) {
  if (codes.length === 0) {
    return <p className="py-6 text-center text-body text-text-dim">No codes in this batch.</p>;
  }
  return (
    <ul className="grid max-h-[28rem] grid-cols-2 gap-x-6 gap-y-1 overflow-y-auto md:grid-cols-3">
      {codes.map((voucher) => (
        <li key={voucher.id} className="flex items-baseline justify-between gap-2 py-1">
          <span
            className={`tabular tracking-widest text-body ${
              voucher.status === 'OUTSTANDING' ? 'text-text' : 'text-text-dim line-through'
            }`}
          >
            {voucher.code}
          </span>
          {voucher.status === 'REDEEMED' ? (
            <span className="text-label uppercase text-text-dim">
              {voucher.redeemedReceiptNo ? `#${voucher.redeemedReceiptNo}` : 'used'}
            </span>
          ) : voucher.status === 'EXPIRED' ? (
            <span className="text-label uppercase text-text-dim">expired</span>
          ) : null}
        </li>
      ))}
    </ul>
  );
}
