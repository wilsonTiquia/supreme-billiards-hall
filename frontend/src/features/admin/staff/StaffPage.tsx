import { useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { listUsers, resetUserPassword } from '@/api/endpoints/users';
import { queryKeys } from '@/api/queryKeys';
import { messageOf } from '@/api/errors';
import type { StaffUser } from '@/api/types';
import { AdminPage } from '../AdminPage';
import { Card } from '@/components/Card';
import { Button } from '@/components/Button';
import { Field } from '@/components/Field';
import { Banner } from '@/components/Banner';
import { Modal } from '@/components/Modal';
import { Spinner } from '@/components/Spinner';

const MIN_LENGTH = 8;

/**
 * The owner's view of who can sign in, and the one action on it: hand a staff member a new
 * temporary password. The server forces them to change it on next login, so the owner never
 * holds a working credential for someone else's account — which is what keeps every override in
 * the audit log attributable to a password only that person knows.
 */
export function StaffPage() {
  const [resetting, setResetting] = useState<StaffUser | null>(null);
  const [banner, setBanner] = useState<string | null>(null);

  const users = useQuery({
    queryKey: queryKeys.users,
    queryFn: listUsers,
  });

  return (
    <AdminPage
      title="Staff"
      intro="Who can sign in, and their password status. A reset is always temporary — the person sets their own on next login."
      error={users.isError ? messageOf(users.error) : null}
    >
      {banner ? (
        <div className="mb-4">
          <Banner tone="info">{banner}</Banner>
        </div>
      ) : null}

      {users.isPending ? (
        <div className="py-16 text-center">
          <Spinner label="Loading staff…" />
        </div>
      ) : users.data && users.data.length > 0 ? (
        <Card className="overflow-x-auto p-0">
          <table className="w-full border-collapse text-body">
            <thead>
              <tr className="border-b border-border text-left text-label uppercase text-text-dim">
                <th className="px-4 py-3 font-normal">Name</th>
                <th className="px-4 py-3 font-normal">Username</th>
                <th className="px-4 py-3 font-normal">Role</th>
                <th className="px-4 py-3 font-normal">Password</th>
                <th className="px-4 py-3" />
              </tr>
            </thead>
            <tbody>
              {users.data.map((staff) => (
                <tr key={staff.id} className="border-b border-border last:border-0">
                  <td className="px-4 py-3 text-text">
                    {staff.fullName}
                    {staff.active ? null : (
                      <span className="ml-2 text-label uppercase text-text-dim">inactive</span>
                    )}
                  </td>
                  <td className="px-4 py-3 text-text-dim">{staff.username}</td>
                  <td className="px-4 py-3 text-text-dim">{staff.role}</td>
                  <td className="px-4 py-3">
                    {staff.mustChangePassword ? (
                      <span className="text-amount">Temporary — change pending</span>
                    ) : (
                      <span className="text-text-dim">Set</span>
                    )}
                  </td>
                  <td className="px-4 py-3 text-right">
                    <Button
                      variant="secondary"
                      onClick={() => {
                        setBanner(null);
                        setResetting(staff);
                      }}
                    >
                      Reset password
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      ) : (
        <Card>
          <p className="text-body text-text-dim">No staff in this branch yet.</p>
        </Card>
      )}

      {resetting ? (
        <ResetPasswordModal
          staff={resetting}
          onClose={() => setResetting(null)}
          onDone={(name) => {
            setResetting(null);
            setBanner(`${name}'s password was reset. They must change it on next sign-in.`);
          }}
        />
      ) : null}
    </AdminPage>
  );
}

function ResetPasswordModal({
  staff,
  onClose,
  onDone,
}: {
  staff: StaffUser;
  onClose: () => void;
  onDone: (name: string) => void;
}) {
  const queryClient = useQueryClient();
  const [next, setNext] = useState('');
  const [confirm, setConfirm] = useState('');
  const [localError, setLocalError] = useState<string | null>(null);

  const reset = useMutation({
    mutationFn: () => resetUserPassword(staff.id, { newPassword: next }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.users });
      onDone(staff.fullName);
    },
  });

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setLocalError(null);
    if (next.length < MIN_LENGTH) {
      setLocalError(`The temporary password must be at least ${MIN_LENGTH} characters.`);
      return;
    }
    if (next !== confirm) {
      setLocalError('The password and its confirmation do not match.');
      return;
    }
    reset.mutate();
  }

  const error = localError ?? (reset.isError ? messageOf(reset.error) : null);

  return (
    <Modal title={`Reset ${staff.fullName}'s password`} onClose={onClose}>
      <p className="mb-6 text-body text-text-dim">
        Set a temporary password and hand it to {staff.fullName}. They will be required to
        change it the next time they sign in.
      </p>
      <form onSubmit={handleSubmit} className="flex flex-col gap-6" noValidate>
        <Field
          label="Temporary password"
          type="text"
          autoComplete="off"
          data-autofocus
          hint={`At least ${MIN_LENGTH} characters. It is shown so you can read it out.`}
          value={next}
          onChange={(event) => {
            setNext(event.target.value);
            setLocalError(null);
          }}
        />
        <Field
          label="Confirm password"
          type="text"
          autoComplete="off"
          value={confirm}
          onChange={(event) => {
            setConfirm(event.target.value);
            setLocalError(null);
          }}
        />

        {error ? <Banner tone="danger">{error}</Banner> : null}

        <div className="flex justify-end gap-3">
          <Button type="button" variant="secondary" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" pending={reset.isPending} disabled={!next || !confirm}>
            Reset password
          </Button>
        </div>
      </form>
    </Modal>
  );
}
