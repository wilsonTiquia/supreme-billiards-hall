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
import type { PoolTable, PoolTableRateRequest, PoolTableRequest } from '@/api/types';
import { AdminPage } from '../AdminPage';
import { Card } from '@/components/Card';
import { Button } from '@/components/Button';
import { Field } from '@/components/Field';
import { RateModeField, rateBody, type RateMode } from '@/components/RateModeField';
import { Modal } from '@/components/Modal';
import { Spinner } from '@/components/Spinner';
import { Banner } from '@/components/Banner';
import {
  formatEffectiveHourly,
  formatHourlyRate,
  formatMoney,
  formatPreciseRate,
  formatRate,
} from '@/lib/money';

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
    mutationFn: ({ id, body }: { id: string; body: PoolTableRateRequest }) =>
      changeTableRate(id, body),
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
                    {describeRate(table)}
                    {table.tableNumber != null ? ` · No. ${table.tableNumber}` : ''}
                  </div>
                  <RoundingNote table={table} />
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
          onSave={(body) => reprice.mutate({ id: repricing.id, body })}
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
  // A table configured hourly opens in hourly mode. It has to: this form posts the rate back
  // on every save, so opening it in per-minute mode would turn a rename into a silent reprice
  // that dropped the owner's hourly figure.
  const [mode, setMode] = useState<RateMode>(table?.ratePerHour != null ? 'hour' : 'minute');
  const [rate, setRate] = useState(initialRateValue(table));
  const [isActive, setIsActive] = useState(table?.isActive ?? true);

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    if (name.trim() === '' || rate.trim() === '') return;
    onSave({
      name: name.trim(),
      tableNumber: tableNumber.trim() === '' ? undefined : Number(tableNumber),
      ...rateBody(mode, rate),
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
        <RateModeField
          mode={mode}
          onModeChange={(next) => {
            setMode(next);
            setRate('');
          }}
          value={rate}
          onValueChange={setRate}
          minuteLabel="Rate per minute"
          hourLabel="Rate per hour"
          hint={
            table
              ? 'Changing this here opens a new rate period, exactly as Change rate does.'
              : 'Required — a table with no rate cannot host a session.'
          }
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
          <Button type="submit" pending={pending} disabled={rate.trim() === ''}>
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
  onSave: (body: PoolTableRateRequest) => void;
}) {
  const [mode, setMode] = useState<RateMode>(table.ratePerHour != null ? 'hour' : 'minute');
  const [rate, setRate] = useState('');

  return (
    <Modal title={`Change the rate on ${table.name}`} onClose={onClose}>
      <form
        onSubmit={(event) => {
          event.preventDefault();
          if (rate.trim() === '') return;
          onSave(rateBody(mode, rate));
        }}
        className="flex flex-col gap-6"
      >
        <Banner tone="info">
          The current period closes and a new one opens from now. Sessions already billed keep
          the rate they were charged at.
        </Banner>
        <div>
          <div className="text-label uppercase text-text-dim">Current rate</div>
          <div className="tabular text-body text-text">{describeRate(table)}</div>
          <RoundingNote table={table} />
        </div>
        <RateModeField
          mode={mode}
          onModeChange={(next) => {
            setMode(next);
            setRate('');
          }}
          value={rate}
          onValueChange={setRate}
          minuteLabel="New rate per minute"
          hourLabel="New rate per hour"
          autoFocus
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

function initialRateValue(table: PoolTable | null): string {
  if (!table) return '';
  return String(table.ratePerHour ?? table.ratePerMinute);
}

/** The rate as the admin configured it: their hourly figure if they typed one, else per minute. */
function describeRate(table: PoolTable): string {
  return table.ratePerHour != null
    ? formatHourlyRate(table.ratePerHour)
    : formatRate(table.ratePerMinute);
}

/**
 * Says out loud that an hourly figure did not divide by 60 exactly.
 *
 * ₱240/hour is ₱4.0000/min and reconciles, so this renders nothing. ₱200/hour is ₱3.3333/min,
 * which prices an hour at ₱199.998 — a full hour still bills ₱200.00 because the line rounds
 * to centavos, but a long session lands a centavo or two under. The admin is entitled to know
 * that before the first customer queries a receipt, so it is on the screen rather than in a
 * comment. Every figure here came back from the server.
 */
function RoundingNote({ table }: { table: PoolTable }) {
  if (table.ratePerHour == null || table.effectiveRatePerHour == null) return null;
  if (table.effectiveRatePerHour === table.ratePerHour) return null;

  return (
    <p className="tabular mt-1 text-label text-text-dim">
      Stored as {formatPreciseRate(table.ratePerMinute)}, which prices an hour at{' '}
      {formatEffectiveHourly(table.effectiveRatePerHour)}. A full hour still bills{' '}
      {formatMoney(table.ratePerHour)}; longer sessions come out a centavo or two under the
      hourly figure.
    </p>
  );
}
