import { useState, type FormEvent } from 'react';
import { useMutation } from '@tanstack/react-query';
import { changePassword } from '@/api/endpoints/auth';
import { messageOf } from '@/api/errors';
import { Button } from '@/components/Button';
import { Field } from '@/components/Field';
import { Banner } from '@/components/Banner';

/** The server's rule, mirrored here only to avoid a round trip for the obvious case. */
const MIN_LENGTH = 8;

/**
 * The one change-password form, used by both the forced-change screen and the normal account
 * screen. The current password is always required, so the shape is identical in both places;
 * only the surrounding frame differs.
 */
export function ChangePasswordForm({
  onSuccess,
  submitLabel = 'Change password',
}: {
  onSuccess: () => void;
  submitLabel?: string;
}) {
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [confirm, setConfirm] = useState('');
  const [localError, setLocalError] = useState<string | null>(null);

  const change = useMutation({
    mutationFn: () => changePassword({ currentPassword: current, newPassword: next }),
    onSuccess: () => onSuccess(),
  });

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setLocalError(null);
    // Confirmation is a client-only guard; the server never sees it. Catching a mismatch and a
    // too-short password here saves a round trip and a 400 for the two most common slips.
    if (next.length < MIN_LENGTH) {
      setLocalError(`The new password must be at least ${MIN_LENGTH} characters.`);
      return;
    }
    if (next !== confirm) {
      setLocalError('The new password and its confirmation do not match.');
      return;
    }
    change.mutate();
  }

  const error = localError ?? (change.isError ? messageOf(change.error) : null);

  return (
    <form onSubmit={handleSubmit} className="flex flex-col gap-6" noValidate>
      <Field
        label="Current password"
        type="password"
        autoComplete="current-password"
        autoFocus
        value={current}
        onChange={(event) => {
          setCurrent(event.target.value);
          setLocalError(null);
        }}
      />
      <Field
        label="New password"
        type="password"
        autoComplete="new-password"
        hint={`At least ${MIN_LENGTH} characters.`}
        value={next}
        onChange={(event) => {
          setNext(event.target.value);
          setLocalError(null);
        }}
      />
      <Field
        label="Confirm new password"
        type="password"
        autoComplete="new-password"
        value={confirm}
        onChange={(event) => {
          setConfirm(event.target.value);
          setLocalError(null);
        }}
      />

      {error ? <Banner tone="danger">{error}</Banner> : null}

      <div className="flex justify-end">
        <Button
          type="submit"
          pending={change.isPending}
          disabled={!current || !next || !confirm}
        >
          {submitLabel}
        </Button>
      </div>
    </form>
  );
}
