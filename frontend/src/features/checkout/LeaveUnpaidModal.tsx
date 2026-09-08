import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { fetchBillNotes, leaveBillUnpaid } from '@/api/endpoints/bills';
import { queryKeys } from '@/api/queryKeys';
import { ErrorCode, isApiError, messageOf } from '@/api/errors';
import { formatMoney } from '@/lib/money';
import { formatDateTime } from '@/lib/datetime';
import { Modal } from '@/components/Modal';
import { Button } from '@/components/Button';
import { Banner } from '@/components/Banner';
import { Field } from '@/components/Field';

/** Matches the server's `@Size(max = 280)` and `session_note_body_chk`. */
const MAX_BODY = 280;

/**
 * Recording a sale that nobody paid for.
 *
 * The amount is shown large and stated as owed rather than as a total, because this is the one
 * confirmation in the app where the operator is agreeing to NOT collect money, and the figure
 * is what they will be asked about next month.
 *
 * The note is the point of the whole dialog. A debt with no name against it is money nobody can
 * collect, so the server refuses one — and this asks for it up front rather than letting the
 * operator find out after pressing the button.
 */
export function LeaveUnpaidModal({
  billId,
  billVersion,
  amount,
  onClose,
  onDone,
}: {
  billId: string;
  billVersion: number;
  amount: number;
  onClose: () => void;
  onDone: () => void;
}) {
  const queryClient = useQueryClient();
  const [note, setNote] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [noteRequired, setNoteRequired] = useState(false);

  /* The thread as it stands. Whether a note is required is the SERVER's decision — it counts
     staff notes and refuses without one — but reading the thread here lets the field say which
     case the operator is in before they commit, rather than after a rejected request. */
  const notes = useQuery({
    queryKey: queryKeys.billNotes(billId),
    queryFn: () => fetchBillNotes(billId),
  });

  // SYSTEM notes are the server talking to itself and do not name anybody, which is exactly the
  // rule the server applies. Counting them here would let the field say "optional" on a session
  // the server is about to refuse.
  const staffNotes = (notes.data ?? []).filter((entry) => entry.kind === 'STAFF');
  const alreadyNamed = staffNotes.length > 0;
  const mustName = noteRequired || (notes.isSuccess && !alreadyNamed);

  const leave = useMutation({
    mutationFn: () =>
      leaveBillUnpaid(billId, {
        billVersion,
        ...(note.trim() === '' ? {} : { note: note.trim() }),
      }),
    onSuccess: () => {
      // Both lists move: the bill leaves the floor's "not checked out" strip and joins the
      // debt list. Invalidating the ['bills'] family would also drop the checkout it is
      // standing on, so these are named.
      void queryClient.invalidateQueries({ queryKey: queryKeys.unsettledBills });
      void queryClient.invalidateQueries({ queryKey: queryKeys.unpaidBills });
      void queryClient.invalidateQueries({ queryKey: queryKeys.floor });
      void queryClient.invalidateQueries({ queryKey: queryKeys.billNotes(billId) });
      onDone();
    },
    onError: (caught) => {
      // Coded, so the repair is to mark the field required rather than to print a sentence and
      // leave the operator to work out which control it is about.
      if (isApiError(caught) && caught.code === ErrorCode.SessionNoteRequired) {
        setNoteRequired(true);
        setError(null);
        return;
      }
      setError(messageOf(caught));
    },
  });

  const blocked = mustName && note.trim() === '';

  return (
    <Modal title="Leave this bill unpaid" onClose={onClose}>
      <form
        className="flex flex-col gap-5"
        onSubmit={(event) => {
          event.preventDefault();
          if (blocked || leave.isPending) return;
          leave.mutate();
        }}
      >
        <div className="rounded-lg border border-gold bg-raised p-4">
          <p className="text-label uppercase text-text-dim">Amount left owed</p>
          <p className="tabular mt-1 text-display text-amount">{formatMoney(amount)}</p>
          <p className="mt-2 text-label text-text-dim">
            The bill is finalised and gets a receipt number. It counts in tonight&rsquo;s sales,
            and stays on the unpaid list until it is collected.
          </p>
        </div>

        {alreadyNamed ? (
          <div>
            <p className="text-label uppercase text-text-dim">Already on this session</p>
            <ul className="mt-2 flex flex-col gap-2">
              {staffNotes.map((entry) => (
                <li key={entry.id} className="rounded-lg border border-border bg-raised p-3">
                  <p className="text-body text-text">{entry.body}</p>
                  <p className="mt-1 text-label text-text-dim">
                    {entry.authorUsername} · {formatDateTime(entry.createdAt)}
                  </p>
                </li>
              ))}
            </ul>
          </div>
        ) : null}

        {/* Never pre-filled. The names already on the session are shown above to be read, not
            copied — a pre-filled box gets pressed through without being looked at, and this is
            the field that decides whether the money is collectable. */}
        <Field
          label={alreadyNamed ? 'Add another name (optional)' : 'Who owes this'}
          value={note}
          data-autofocus
          maxLength={MAX_BODY}
          placeholder={alreadyNamed ? 'Anything else worth recording' : 'e.g. Jun'}
          hint={
            alreadyNamed
              ? 'The session already has a name on it, so this can be left blank.'
              : 'Required. An unpaid bill with no name is money nobody can collect.'
          }
          onChange={(event) => setNote(event.target.value)}
        />

        {noteRequired ? (
          <Banner tone="warning">
            Nobody&rsquo;s name is on this bill. Write who owes it before leaving it unpaid.
          </Banner>
        ) : null}
        {error ? <Banner tone="danger">{error}</Banner> : null}

        <div className="flex justify-end gap-3">
          <Button type="button" variant="secondary" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" pending={leave.isPending} disabled={blocked}>
            Leave {formatMoney(amount)} unpaid
          </Button>
        </div>
      </form>
    </Modal>
  );
}
