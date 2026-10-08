import { useEffect, useId, useLayoutEffect, useRef, useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  createTable,
  fetchFloor,
  updateTable,
} from '@/api/endpoints/tables';
import { queryKeys } from '@/api/queryKeys';
import { messageOf } from '@/api/errors';
import type { PoolTable, PoolTableRequest } from '@/api/types';
import { AdminPage } from '../AdminPage';
import { useSetupLifecycle } from '../setup/useSetupLifecycle';
import { Card } from '@/components/Card';
import { Button } from '@/components/Button';
import { Field } from '@/components/Field';
import { RateModeField, rateBody, type RateMode } from '@/components/RateModeField';
import { Modal } from '@/components/Modal';
import { Spinner } from '@/components/Spinner';
import { ActionMenu } from '@/components/ActionMenu';
import './tables.css';
import {
  formatEffectiveHourly,
  formatHourlyRate,
  formatMoney,
  formatPreciseRate,
} from '@/lib/money';

export function TablesPage() {
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState<PoolTable | null>(null);
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const floor = useQuery({ queryKey: queryKeys.floor, queryFn: fetchFloor });

  const lifecycle = useSetupLifecycle('tables', refresh);

  function refresh() {
    void queryClient.invalidateQueries({ queryKey: queryKeys.setup('tables') });
    void queryClient.invalidateQueries({ queryKey: queryKeys.floor });
    void queryClient.invalidateQueries({ queryKey: ['reports'] });
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

  const tables = floor.data?.tables ?? [];

  return (
    <AdminPage
      title="Pool tables"
      intro="Changing a rate opens a new rate period. It never edits the old one, so a bill from last month keeps the rate it was charged at."
      error={error ?? (floor.isError ? messageOf(floor.error) : null)}
    >
      <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
        {lifecycle.toggle}
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
                <div className="min-w-0 flex-1 break-words">
                  <div className="text-body text-text">
                    {table.name}
                    <span className="ml-2 text-label text-text-dim">{table.isPremium ? '★ Premium' : 'Standard'}</span>
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
                  <RateInfo table={table} />
                </div>
                <ActionMenu label={`Actions for ${table.name}`} items={[
                  { id: 'edit', label: 'Edit', onSelect: () => setEditing(table) },
                  lifecycle.menuItem(table.id, table.session ? 'Close the open session before archiving this table.' : undefined),
                ]} />
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

      {lifecycle.panel}
      {lifecycle.dialog}
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
  const [isPremium, setIsPremium] = useState(table?.isPremium ?? false);

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    if (name.trim() === '' || rate.trim() === '') return;
    onSave({
      name: name.trim(),
      tableNumber: tableNumber.trim() === '' ? undefined : Number(tableNumber),
      ...rateBody(mode, rate),
      isActive,
      isPremium,
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
              ? 'Changing this opens a new rate period. Existing sessions keep their original rate.'
              : 'Required — a table with no rate cannot host a session.'
          }
        />
        <div>
          <label className="hit flex cursor-pointer items-center gap-3 text-body text-text">
            <input type="checkbox" checked={isPremium} onChange={event => setIsPremium(event.target.checked)} className="size-6" />
            Premium table
          </label>
          <p className="text-label text-text-dim">Classification only. Changing it does not change rates.</p>
        </div>
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

function initialRateValue(table: PoolTable | null): string {
  if (!table) return '';
  return String(table.ratePerHour ?? table.ratePerMinute);
}

/** Display the configured hourly amount or its server-calculated hourly equivalent. */
function describeRate(table: PoolTable): string {
  const hourly = table.ratePerHour ?? table.effectiveRatePerHour;
  return hourly == null ? 'Rate unavailable' : formatHourlyRate(hourly);
}

function RateInfo({ table }: { table: PoolTable }) {
  const id = useId();
  const wrapper = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const panel = useRef<HTMLDivElement>(null);
  const pinned = useRef(false);
  const [open, setOpen] = useState(false);

  function close() { pinned.current = false; setOpen(false); }

  useLayoutEffect(() => {
    if (!open) return;
    const popup = panel.current!;
    popup.showPopover();
    const position = () => {
      const rect = trigger.current!.getBoundingClientRect();
      popup.style.maxHeight = `${window.innerHeight - 24}px`;
      const size = popup.getBoundingClientRect();
      popup.style.left = `${Math.max(12, Math.min(rect.left, window.innerWidth - size.width - 12))}px`;
      popup.style.top = `${Math.max(12, Math.min(rect.bottom + size.height <= window.innerHeight - 12 ? rect.bottom : rect.top - size.height, window.innerHeight - size.height - 12))}px`;
    };
    position();
    window.addEventListener('resize', position);
    window.addEventListener('scroll', position, true);
    return () => {
      popup.hidePopover();
      window.removeEventListener('resize', position);
      window.removeEventListener('scroll', position, true);
    };
  }, [open]);

  useEffect(() => {
    if (!open) return;
    const outside = (event: PointerEvent) => {
      if (!wrapper.current?.contains(event.target as Node)) close();
    };
    const escape = (event: KeyboardEvent) => { if (event.key === 'Escape') close(); };
    document.addEventListener('pointerdown', outside);
    document.addEventListener('keydown', escape);
    return () => {
      document.removeEventListener('pointerdown', outside);
      document.removeEventListener('keydown', escape);
    };
  }, [open]);

  return (
    <div ref={wrapper} className="mt-1 w-fit text-label text-text-dim"
      onPointerEnter={event => { if (event.pointerType !== 'touch') setOpen(true); }}
      onPointerLeave={() => { if (!pinned.current && !wrapper.current?.contains(document.activeElement)) setOpen(false); }}
      onBlur={event => { if (!event.currentTarget.contains(event.relatedTarget)) close(); }}>
      <button ref={trigger} type="button" aria-label={`Rate details for ${table.name}`}
        aria-describedby={open ? id : undefined} aria-expanded={open} aria-controls={id}
        className="hit flex cursor-pointer items-center gap-2 rounded-lg px-2 text-text-dim hover:bg-raised"
        onFocus={() => setOpen(true)}
        onClick={() => { if (pinned.current) close(); else { pinned.current = true; setOpen(true); } }}>
        <span aria-hidden>ⓘ</span><span>Rate details</span>
      </button>
      <div ref={panel} id={id} role="tooltip" popover="manual" className="table-rate-help">
        <p className="tabular">Billed by the minute at {formatPreciseRate(table.ratePerMinute)}.</p>
        {table.ratePerHour == null && table.effectiveRatePerHour != null ? (
          <p className="tabular mt-1">Hourly equivalent: {formatEffectiveHourly(table.effectiveRatePerHour)}.</p>
        ) : null}
        <RoundingNote table={table} />
      </div>
    </div>
  );
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
      {formatMoney(table.ratePerHour)}; longer sessions can differ slightly from the hourly
      figure because billing uses the stored per-minute rate.
    </p>
  );
}
