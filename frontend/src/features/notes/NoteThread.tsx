import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { addSessionNote, fetchSessionNotes } from '@/api/endpoints/sessions';
import { fetchBillNotes } from '@/api/endpoints/bills';
import { queryKeys } from '@/api/queryKeys';
import { messageOf } from '@/api/errors';
import type { SessionNote } from '@/api/types';
import { formatDateTime } from '@/lib/datetime';
import { Banner } from '@/components/Banner';
import { Button } from '@/components/Button';
import { Spinner } from '@/components/Spinner';

/** Matches the server's `@Size(max = 280)` and the `session_note_body_chk` in the database. */
const MAX_BODY = 280;

/** Which thread this is: one session's own, or every session the bill carried. */
type Source = { kind: 'session'; sessionId: string } | { kind: 'bill'; billId: string };

interface NoteThreadProps {
  source: Source;
  /**
   * The session a new note is written against. Omitting it makes the thread read-only — the
   * receipt screen has no session id to write to, and a settled sale is a record to read.
   */
  writeTo?: string;
  title?: string;
}

/**
 * Who was on the table, so an unpaid bill has a name on it.
 *
 * **Append-only, and there is no edit or delete here on purpose.** The server refuses both and
 * the database blocks them by trigger, so a button would only be a lie. A wrong note is
 * corrected by a later one, and both stay in the thread — which is the point: a note about
 * money owed that the person doing the favour can quietly remove is worth nothing.
 *
 * Bodies are free text from staff and are rendered as text. Never introduce
 * `dangerouslySetInnerHTML` anywhere near this component.
 */
export function NoteThread({ source, writeTo, title = 'Who is on this table' }: NoteThreadProps) {
  const queryClient = useQueryClient();
  const [draft, setDraft] = useState('');
  const [error, setError] = useState<string | null>(null);

  const notes = useQuery({
    queryKey:
      source.kind === 'session'
        ? queryKeys.sessionNotes(source.sessionId)
        : queryKeys.billNotes(source.billId),
    queryFn: () =>
      source.kind === 'session'
        ? fetchSessionNotes(source.sessionId)
        : fetchBillNotes(source.billId),
  });

  const add = useMutation({
    mutationFn: (body: string) => addSessionNote(writeTo as string, { body }),
    onSuccess: () => {
      setDraft('');
      setError(null);
      // This thread, and the floor strip that shows the latest note on its card. Named
      // precisely rather than by prefix: invalidating ['bills'] would also throw away the
      // checkout preview the operator may be reading a total from.
      void queryClient.invalidateQueries({ queryKey: queryKeys.sessionNotes(writeTo as string) });
      if (source.kind === 'bill') {
        void queryClient.invalidateQueries({ queryKey: queryKeys.billNotes(source.billId) });
      }
      void queryClient.invalidateQueries({ queryKey: queryKeys.unsettledBills });
    },
    onError: (caught) => setError(messageOf(caught)),
  });

  const trimmed = draft.trim();
  const remaining = MAX_BODY - draft.length;

  return (
    <section>
      <h2 className="text-heading text-text">{title}</h2>

      {notes.isPending ? (
        <div className="py-4">
          <Spinner label="Loading notes…" />
        </div>
      ) : notes.isError ? (
        <div className="mt-3">
          <Banner tone="danger">{messageOf(notes.error)}</Banner>
        </div>
      ) : notes.data.length === 0 ? (
        <p className="mt-2 text-body text-text-dim">
          {writeTo
            ? 'No notes yet. Put the names on while they are playing — that is the only time anyone knows them.'
            : 'No notes were written on this bill.'}
        </p>
      ) : (
        <ul className="mt-3 flex flex-col gap-3">
          {notes.data.map((note) => (
            <NoteRow key={note.id} note={note} />
          ))}
        </ul>
      )}

      {writeTo ? (
        <form
          className="mt-4 flex flex-col gap-2"
          onSubmit={(event) => {
            event.preventDefault();
            if (trimmed === '') return;
            add.mutate(trimmed);
          }}
        >
          <div className="flex gap-2">
            <input
              aria-label="Note"
              value={draft}
              maxLength={MAX_BODY}
              placeholder="Marco + 2"
              onChange={(event) => setDraft(event.target.value)}
              className="hit w-full rounded-lg border border-border bg-raised px-3 text-body text-text placeholder:text-text-dim/60"
            />
            <Button type="submit" pending={add.isPending} disabled={trimmed === ''}>
              Add
            </Button>
          </div>
          {/* Only once it is close enough to matter — a counter on an empty box is noise. */}
          {remaining <= 40 ? (
            <p className="text-label text-text-dim">{remaining} characters left</p>
          ) : (
            <p className="text-label text-text-dim">
              Notes cannot be edited or deleted. Correct one by adding another.
            </p>
          )}
          {error ? <Banner tone="danger">{error}</Banner> : null}
        </form>
      ) : null}
    </section>
  );
}

/**
 * A system note is marked, not merely worded differently. Settlement writes one and the server
 * stamps its kind, so a staff member typing the same sentence still shows as staff — which is
 * the whole reason the kind is on the wire rather than inferred from the text.
 */
function NoteRow({ note }: { note: SessionNote }) {
  const system = note.kind === 'SYSTEM';
  return (
    <li className={`border-l-2 pl-3 ${system ? 'border-info' : 'border-border'}`}>
      <p className="text-body text-text">{note.body}</p>
      <p className="text-label uppercase text-text-dim">
        {system ? 'Recorded by the till' : note.authorUsername} · {formatDateTime(note.createdAt)}
      </p>
    </li>
  );
}
