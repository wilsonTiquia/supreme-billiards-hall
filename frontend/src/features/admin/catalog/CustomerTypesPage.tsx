import { useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  createCustomerType,
  fetchCustomerTypes,
  updateCustomerType,
} from '@/api/endpoints/customerTypes';
import { queryKeys } from '@/api/queryKeys';
import { messageOf } from '@/api/errors';
import type { CustomerType, CustomerTypeRequest } from '@/api/types';
import { AdminPage } from '../AdminPage';
import { useSetupLifecycle } from '../setup/useSetupLifecycle';
import { Card } from '@/components/Card';
import { Button } from '@/components/Button';
import { Field } from '@/components/Field';
import { Modal } from '@/components/Modal';
import { Spinner } from '@/components/Spinner';

export function CustomerTypesPage() {
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState<CustomerType | null>(null);
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const types = useQuery({ queryKey: queryKeys.customerTypes, queryFn: fetchCustomerTypes });

  const lifecycle = useSetupLifecycle('customer-types', refresh);

  function refresh() {
    void queryClient.invalidateQueries({ queryKey: queryKeys.setup('customer-types') });
    void queryClient.invalidateQueries({ queryKey: queryKeys.customerTypes });
  }

  const save = useMutation({
    mutationFn: ({ id, body }: { id: string | null; body: CustomerTypeRequest }) =>
      id ? updateCustomerType(id, body) : createCustomerType(body),
    onSuccess: () => {
      setError(null);
      setEditing(null);
      setCreating(false);
      refresh();
    },
    onError: (caught) => setError(messageOf(caught)),
  });

  return (
    <AdminPage
      title="Customer types"
      intro="The start-session dropdown reads this. Allowing a rate override is what lets the counter charge this type something other than the table's standard rate — and every override is recorded against the session."
      error={error ?? (types.isError ? messageOf(types.error) : null)}
    >
      <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
        {lifecycle.toggle}
        <Button onClick={() => setCreating(true)}>New customer type</Button>
      </div>

      <Card>
        {types.isPending ? (
          <div className="py-10 text-center">
            <Spinner label="Loading customer types…" />
          </div>
        ) : (
          <ul className="divide-y divide-border">
            {(types.data ?? []).map((type) => (
              <li key={type.id} className="flex flex-wrap items-center justify-between gap-3 py-3">
                <div>
                  <div className="text-body text-text">
                    {type.name}
                    {type.isDefault ? (
                      <span className="ml-2 text-label uppercase text-green">Default</span>
                    ) : null}
                  </div>
                  <div className="text-label text-text-dim">
                    {type.allowsRateOverride ? 'Rate override allowed' : 'Standard rate only'}
                  </div>
                </div>
                <div className="flex flex-wrap gap-2">
                  <Button variant="secondary" onClick={() => setEditing(type)}>
                    Edit
                  </Button>
                  {lifecycle.action(type.id)}
                </div>
              </li>
            ))}
          </ul>
        )}
      </Card>

      {creating || editing ? (
        <CustomerTypeForm
          type={editing}
          pending={save.isPending}
          onClose={() => {
            setEditing(null);
            setCreating(false);
          }}
          onSave={(body) => save.mutate({ id: editing?.id ?? null, body })}
        />
      ) : null}
      {lifecycle.panel}
      {lifecycle.dialog}
    </AdminPage>
  );
}

function CustomerTypeForm({
  type,
  pending,
  onClose,
  onSave,
}: {
  type: CustomerType | null;
  pending: boolean;
  onClose: () => void;
  onSave: (body: CustomerTypeRequest) => void;
}) {
  const [name, setName] = useState(type?.name ?? '');
  const [allowsRateOverride, setAllowsRateOverride] = useState(type?.allowsRateOverride ?? false);
  const [isDefault, setIsDefault] = useState(type?.isDefault ?? false);
  const [sortOrder, setSortOrder] = useState(
    type?.sortOrder != null ? String(type.sortOrder) : '',
  );

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    if (name.trim() === '') return;
    onSave({
      name: name.trim(),
      allowsRateOverride,
      isDefault,
      sortOrder: sortOrder.trim() === '' ? undefined : Number(sortOrder),
    });
  }

  return (
    <Modal title={type ? `Edit ${type.name}` : 'New customer type'} onClose={onClose}>
      <form onSubmit={handleSubmit} className="flex flex-col gap-6">
        <Field
          label="Name"
          value={name}
          data-autofocus
          onChange={(event) => setName(event.target.value)}
        />
        <Field
          label="Sort order (optional)"
          type="number"
          value={sortOrder}
          onChange={(event) => setSortOrder(event.target.value)}
        />
        <label className="flex items-center gap-3 text-body text-text">
          <input
            type="checkbox"
            checked={allowsRateOverride}
            onChange={(event) => setAllowsRateOverride(event.target.checked)}
            className="size-5"
          />
          Allow a rate override
        </label>
        <label className="flex items-center gap-3 text-body text-text">
          <input
            type="checkbox"
            checked={isDefault}
            onChange={(event) => setIsDefault(event.target.checked)}
            className="size-5"
          />
          Pre-selected when starting a table
        </label>
        <div className="flex justify-end gap-3">
          <Button type="button" variant="secondary" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" pending={pending}>
            Save
          </Button>
        </div>
      </form>
    </Modal>
  );
}
