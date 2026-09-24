import { useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  createUser,
  listUsers,
  resetUserPassword,
  updateUser,
} from '@/api/endpoints/users';
import { queryKeys } from '@/api/queryKeys';
import { messageOf } from '@/api/errors';
import type { CreateUserRequest, Role, StaffUser, UpdateUserRequest } from '@/api/types';
import { AdminPage } from '../AdminPage';
import { useSetupLifecycle } from '../setup/useSetupLifecycle';
import { Card } from '@/components/Card';
import { Button } from '@/components/Button';
import { Field } from '@/components/Field';
import { Banner } from '@/components/Banner';
import { Modal } from '@/components/Modal';
import { Select } from '@/components/Select';
import { Spinner } from '@/components/Spinner';

const MIN_LENGTH = 8;

/**
 * The owner's view of who can sign in: adding people, editing them, retiring them, and handing
 * over a temporary password. The server forces every admin-set password to be replaced on next
 * login, so the owner never holds a working credential for someone else's account — which is
 * what keeps every override in the audit log attributable to a password only that person knows.
 *
 * Two things this screen must never let happen, because the server refuses them and a 409 the
 * user could have been warned about is worse than a disabled button: you cannot retire yourself,
 * and you cannot retire the last administrator. Losing every administrator would leave nobody
 * able to clear a login lockout, with only the break-glass procedure in HELP.md to get back in.
 */
export function StaffPage() {
  const queryClient = useQueryClient();
  const [resetting, setResetting] = useState<StaffUser | null>(null);
  const [editing, setEditing] = useState<StaffUser | null>(null);
  const [creating, setCreating] = useState(false);
  const [banner, setBanner] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const users = useQuery({
    queryKey: queryKeys.users,
    queryFn: listUsers,
  });

  const lifecycle = useSetupLifecycle('staff', refresh);

  function refresh() {
    void queryClient.invalidateQueries({ queryKey: queryKeys.setup('staff') });
    void queryClient.invalidateQueries({ queryKey: queryKeys.users });
  }

  return (
    <AdminPage
      title="Staff"
      intro="Who can sign in, what they may do, and their password status. A password you set here is always temporary — the person sets their own the next time they sign in. A forgotten administrator password cannot be reset by another administrator; see HELP.md."
      error={error ?? (users.isError ? messageOf(users.error) : null)}
    >
      <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
        {lifecycle.toggle}
        <Button
          onClick={() => {
            setBanner(null);
            setError(null);
            setCreating(true);
          }}
        >
          New staff
        </Button>
      </div>
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
        <div className="overflow-x-auto rounded-xl border border-border bg-surface">
          <table className="w-full border-collapse text-body">
            <thead className="hidden lg:table-header-group">
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
                <tr key={staff.id} className="grid grid-cols-2 border-b border-border py-3 last:border-0 lg:table-row lg:py-0">
                  <td className="col-span-2 break-words px-4 py-1 text-text lg:py-3">
                    {staff.fullName}
                    {staff.active ? null : (
                      <span className="ml-2 text-label uppercase text-text-dim">inactive</span>
                    )}
                  </td>
                  <td className="break-words px-4 py-1 text-text-dim lg:py-3"><span className="block text-label lg:hidden">Username</span>{staff.username}</td>
                  <td className="px-4 py-1 text-text-dim lg:py-3"><span className="block text-label lg:hidden">Role</span>{staff.role}</td>
                  <td className="col-span-2 px-4 py-1 lg:py-3">
                    <span className="mr-2 text-label text-text-dim lg:hidden">Password:</span>
                    {staff.mustChangePassword ? (
                      <span className="text-amount">Temporary — change pending</span>
                    ) : (
                      <span className="text-text-dim">Set</span>
                    )}
                  </td>
                  <td className="col-span-2 px-4 py-3">
                    <div className="flex flex-wrap gap-2 lg:justify-end">
                      <Button
                        variant="secondary"
                        onClick={() => {
                          setBanner(null);
                          setError(null);
                          setEditing(staff);
                        }}
                      >
                        Edit
                      </Button>
                      <Button
                        variant="secondary"
                        onClick={() => {
                          setBanner(null);
                          setError(null);
                          setResetting(staff);
                        }}
                      >
                        Reset password
                      </Button>
                      {lifecycle.action(staff.id)}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <Card>
          <p className="text-body text-text-dim">Nobody can sign in yet.</p>
        </Card>
      )}

      {creating || editing ? (
        <StaffForm
          staff={editing}
          onClose={() => {
            setCreating(false);
            setEditing(null);
          }}
          onDone={(message) => {
            setCreating(false);
            setEditing(null);
            setBanner(message);
            refresh();
          }}
        />
      ) : null}

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
      {lifecycle.panel}
      {lifecycle.dialog}
    </AdminPage>
  );
}

/**
 * New staff and Edit, in one form.
 *
 * Role is an explicit choice rather than an "is administrator" checkbox: promoting somebody
 * hands them the dashboard, the settings, every giveaway control and the ability to create more
 * administrators, and that is not a thing to tick past. A username cannot be edited — it is what
 * the person types every night — so it appears only when creating.
 */
function StaffForm({
  staff,
  onClose,
  onDone,
}: {
  staff: StaffUser | null;
  onClose: () => void;
  onDone: (message: string) => void;
}) {
  const editing = staff !== null;
  const [username, setUsername] = useState('');
  const [fullName, setFullName] = useState(staff?.fullName ?? '');
  const [role, setRole] = useState<Role>(staff?.role ?? 'EMPLOYEE');
  const [active, setActive] = useState(staff?.active ?? true);
  const [password, setPassword] = useState('');
  const [localError, setLocalError] = useState<string | null>(null);

  const save = useMutation({
    mutationFn: () => {
      if (staff) {
        const body: UpdateUserRequest = { fullName: fullName.trim(), role, isActive: active };
        return updateUser(staff.id, body);
      }
      const body: CreateUserRequest = {
        username: username.trim(),
        fullName: fullName.trim(),
        role,
        temporaryPassword: password,
      };
      return createUser(body);
    },
    onSuccess: (saved) =>
      onDone(
        editing
          ? `${saved.fullName} was updated.`
          : `${saved.fullName} was added. Give them the temporary password — they will be asked to change it when they first sign in.`,
      ),
  });

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setLocalError(null);
    if (!fullName.trim()) {
      setLocalError('A full name is required.');
      return;
    }
    if (!editing) {
      if (!username.trim()) {
        setLocalError('A username is required.');
        return;
      }
      if (password.length < MIN_LENGTH) {
        setLocalError(`The temporary password must be at least ${MIN_LENGTH} characters.`);
        return;
      }
    }
    save.mutate();
  }

  const error = localError ?? (save.isError ? messageOf(save.error) : null);

  return (
    <Modal title={editing ? `Edit ${staff.fullName}` : 'New staff'} onClose={onClose}>
      <form onSubmit={handleSubmit} className="flex flex-col gap-6" noValidate>
        {editing ? null : (
          <Field
            label="Username"
            data-autofocus
            autoComplete="off"
            hint="What they type to sign in. Letters, numbers, dots, dashes and underscores."
            value={username}
            onChange={(event) => {
              setUsername(event.target.value);
              setLocalError(null);
            }}
          />
        )}

        <Field
          label="Full name"
          data-autofocus={editing ? true : undefined}
          value={fullName}
          onChange={(event) => {
            setFullName(event.target.value);
            setLocalError(null);
          }}
        />

        <Select
          label="Role"
          hint="An administrator sees the dashboard and every cost and profit figure, and can add or remove staff."
          value={role}
          onChange={(event) => setRole(event.target.value as Role)}
        >
          <option value="EMPLOYEE">Counter staff</option>
          <option value="ADMIN">Administrator</option>
        </Select>

        {editing ? (
          <Select
            label="Can sign in"
            hint="An inactive account keeps all its history but cannot log in."
            value={active ? 'yes' : 'no'}
            onChange={(event) => setActive(event.target.value === 'yes')}
          >
            <option value="yes">Yes</option>
            <option value="no">No — suspended</option>
          </Select>
        ) : (
          <Field
            label="Temporary password"
            type="text"
            autoComplete="off"
            hint={`At least ${MIN_LENGTH} characters. It is shown so you can read it out — they will be asked to change it when they first sign in.`}
            value={password}
            onChange={(event) => {
              setPassword(event.target.value);
              setLocalError(null);
            }}
          />
        )}

        {error ? <Banner tone="danger">{error}</Banner> : null}

        <div className="flex justify-end gap-3">
          <Button type="button" variant="secondary" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" pending={save.isPending}>
            {editing ? 'Save' : 'Add staff'}
          </Button>
        </div>
      </form>
    </Modal>
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
