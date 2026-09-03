import { useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  archiveTable,
  changeTableRate,
  createTable,
  fetchFloor,
  updateTable,
} from '@/api/endpoints/tables';
import { queryKeys } from '@/api/queryKeys';
import { messageOf } from '@/api/errors';
import type { PoolTable, PoolTableRequest } from '@/api/types';
import { AdminPage } from '../AdminPage';
import { Card } from '@/components/Card';
import { Button } from '@/components/Button';
import { Field } from '@/components/Field';
import { Modal } from '@/components/Modal';
import { Spinner } from '@/components/Spinner';
import { Banner } from '@/components/Banner';
import { formatRate } from '@/lib/money';

export function TablesPage() {
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState<PoolTable | null>(null);
  const [repricing, setRepricing] = useState<PoolTable | null>(null);
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const floor = useQuery({ queryKey: queryKeys.floor, queryFn: fetchFloor });

  function refresh() {
    void queryClient.invalidateQueries({ queryKey: queryKeys.floor });
  }

  const save = useMutation({
    mutationFn: ({ id, body }: { id: string | null; body: PoolTableRequest }) =>
      id ? updateTable(id, body) : createTable(body),
    onSuccess: () => {
      setError(null);
      setEditing(null);
      setCreating(false);
      refresh();
    },
    onError: (caught) => setError(messageOf(caught)),
  });

  const reprice = useMutation({
    mutationFn: ({ id, ratePerMinute }: { id: string; ratePerMinute: number }) =>
      changeTableRate(id, { ratePerMinute }),
    onSuccess: () => {
      setError(null);
      setRepricing(null);
      refresh();
    },
    onError: (caught) => setError(messageOf(caught)),
  });

  const archive = useMutation({
    mutationFn: (id: string) => archiveTable(id),
    onSuccess: () => {
      setError(null);
      refresh();
    },
    // Rejected while a session is open on the table. That is guidance, not a fault.
    onError: (caught) => setError(messageOf(caught)),
  });

  const tables = floor.data?.tables ?? [];

  return (
    <AdminPage
      title="Pool tables"
      intro="Changing a rate opens a new rate period. It never edits the old one, so a bill from last month keeps the rate it was charged at."
      error={error ?? (floor.isError ? messageOf(floor.error) : null)}
    >
      <div className="mb-4 flex justify-end">
        <Button onClick={() => setCreating(true)}>New table</Button>
      </div>

      <Card>
        {floor.isPending ? (
          <div className="py-10 text-center">
            <Spinner label="Loading tables…" />
          </div>
        ) : (
          <ul className="divide-y divide-border">
            {tables.map((table) => (
              <li key={table.id} className="flex flex-wrap items-center justify-between gap-3 py-3">
                <div>
                  <div className="text-body text-text">
                    {table.name}
                    {table.session ? (
                      <span className="ml-2 text-label uppercase text-green">In use</span>
                    ) : null}
                    {!table.isActive ? (
                      <span className="ml-2 text-label uppercase text-text-dim">Inactive</span>
                    ) : null}
                  </div>
                  <div className="tabular text-label text-text-dim">
                    {formatRate(table.ratePerMinute)}
                    {table.tableNumber != null ? ` · No. ${table.tableNumber}` : ''}
                  </div>
                </div>
                <div className="flex gap-2">
                  <Button variant="secondary" onClick={() => setRepricing(table)}>
                    Change rate
                  </Button>
                  <Button variant="secondary" onClick={() => setEditing(table)}>
                    Edit
                  </Button>
                  <Button
                    variant="secondary"
                    pending={archive.isPending && archive.variables === table.id}
                    onClick={() => archive.mutate(table.id)}
                  >
                    Archive
                  </Button>
                </div>
              </li>
            ))}
          </ul>
        )}
      </Card>

      {creating || editing ? (
        <TableForm
          table={editing}
          pending={save.isPending}
          onClose={() => {
            setEditing(null);
            setCreating(false);
          }}
          onSave={(body) => save.mutate({ id: editing?.id ?? null, body })}
        />
      ) : null}

      {repricing ? (
        <RateForm
          table={repricing}
          pending={reprice.isPending}
          onClose={() => setRepricing(null)}
          onSave={(ratePerMinute) => reprice.mutate({ id: repricing.id, ratePerMinute })}
        />
      ) : null}
    </AdminPage>
  );
}

function TableForm({
  table,
  pending,
  onClose,
  onSave,
}: {
  table: PoolTable | null;
  pending: boolean;
  onClose: () => void;
  onSave: (body: PoolTableRequest) => void;
}) {
  const [name, setName] = useState(table?.name ?? '');
  const [tableNumber, setTableNumber] = useState(
    table?.tableNumber != null ? String(table.tableNumber) : '',
  );
  const [ratePerMinute, setRatePerMinute] = useState(
    table ? String(table.ratePerMinute) : '',
  );
  const [isActive, setIsActive] = useState(table?.isActive ?? true);

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    if (name.trim() === '' || ratePerMinute.trim() === '') return;
    onSave({
      name: name.trim(),
      tableNumber: tableNumber.trim() === '' ? undefined : Number(tableNumber),
      ratePerMinute: Number(ratePerMinute),
      isActive,
    });
  }

  return (
    <Modal title={table ? `Edit ${table.name}` : 'New table'} onClose={onClose}>
      <form onSubmit={handleSubmit} className="flex flex-col gap-6">
        <Field
          label="Name"
          value={name}
          data-autofocus
          onChange={(event) => setName(event.target.value)}
        />
        <Field
          label="Table number (optional)"
          type="number"
          value={tableNumber}
          onChange={(event) => setTableNumber(event.target.value)}
        />
        <Field
          label="Rate per minute"
          type="number"
          step="0.0001"
          min="0"
          inputMode="decimal"
          value={ratePerMinute}
          hint={
            table
              ? 'Changing this here opens a new rate period, exactly as Change rate does.'
              : 'Required — a table with no rate cannot host a session.'
          }
          onChange={(event) => setRatePerMinute(event.target.value)}
        />
        <label className="hit flex cursor-pointer items-center gap-3 text-body text-text">
          <input
            type="checkbox"
            checked={isActive}
            onChange={(event) => setIsActive(event.target.checked)}
            className="size-6"
          />
          Open for play
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

function RateForm({
  table,
  pending,
  onClose,
  onSave,
}: {
  table: PoolTable;
  pending: boolean;
  onClose: () => void;
  onSave: (ratePerMinute: number) => void;
}) {
  const [rate, setRate] = useState('');

  return (
    <Modal title={`Change the rate on ${table.name}`} onClose={onClose}>
      <form
        onSubmit={(event) => {
          event.preventDefault();
          if (rate.trim() === '') return;
          onSave(Number(rate));
        }}
        className="flex flex-col gap-6"
      >
        <Banner tone="info">
          The current period closes and a new one opens from now. Sessions already billed keep
          the rate they were charged at.
        </Banner>
        <div>
          <div className="text-label uppercase text-text-dim">Current rate</div>
          <div className="tabular text-body text-text">{formatRate(table.ratePerMinute)}</div>
        </div>
        <Field
          label="New rate per minute"
          type="number"
          step="0.0001"
          min="0"
          inputMode="decimal"
          value={rate}
          data-autofocus
          onChange={(event) => setRate(event.target.value)}
        />
        <div className="flex justify-end gap-3">
          <Button type="button" variant="secondary" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" pending={pending} disabled={rate.trim() === ''}>
            Open a new period
          </Button>
        </div>
      </form>
    </Modal>
  );
}
