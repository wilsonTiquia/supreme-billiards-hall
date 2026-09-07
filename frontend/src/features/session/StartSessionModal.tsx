import { useMemo, useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { openSession } from '@/api/endpoints/sessions';
import { fetchCustomerTypes } from '@/api/endpoints/customerTypes';
import { queryKeys } from '@/api/queryKeys';
import { messageOf } from '@/api/errors';
import type { OpenSessionRequest, PoolTable } from '@/api/types';
import { Modal } from '@/components/Modal';
import { Button } from '@/components/Button';
import { Select } from '@/components/Select';
import { Field } from '@/components/Field';
import { RateModeField, type RateMode } from '@/components/RateModeField';
import { Banner } from '@/components/Banner';
import { formatHourlyRate, formatRate } from '@/lib/money';

export function StartSessionModal({
  table,
  onClose,
  onStarted,
}: {
  table: PoolTable;
  onClose: () => void;
  onStarted: (sessionId: string) => void;
}) {
  const queryClient = useQueryClient();
  const [error, setError] = useState<string | null>(null);
  const [customerTypeId, setCustomerTypeId] = useState<string>('');
  const [rateOverride, setRateOverride] = useState('');
  const [overrideReason, setOverrideReason] = useState('');
  // Opens in the unit the table itself is configured in: starting an hourly table should put
  // the friend rate in hourly mode, because that is the number the counter has in their head.
  const [rateMode, setRateMode] = useState<RateMode>(
    table.ratePerHour != null ? 'hour' : 'minute',
  );

  const { data: customerTypes = [] } = useQuery({
    queryKey: queryKeys.customerTypes,
    queryFn: fetchCustomerTypes,
    staleTime: 5 * 60_000,
  });

  // The one marked isDefault is what the counter picks nine times out of ten.
  const selectedId = customerTypeId || customerTypes.find((type) => type.isDefault)?.id || '';
  const selected = useMemo(
    () => customerTypes.find((type) => type.id === selectedId),
    [customerTypes, selectedId],
  );
  const allowsOverride = selected?.allowsRateOverride ?? false;

  // The standard rate in whichever unit is being typed. In hourly mode on a table configured
  // per minute there is no typed hourly figure to quote, so this uses the server's
  // effectiveRatePerHour — 60 x the stored rate, computed there, not here.
  function standardLabel(mode: RateMode): string {
    return mode === 'hour'
      ? formatHourlyRate(table.ratePerHour ?? table.effectiveRatePerHour)
      : formatRate(table.ratePerMinute);
  }

  function standardIn(mode: RateMode): string {
    const figure = mode === 'hour' ? (table.ratePerHour ?? table.effectiveRatePerHour) : table.ratePerMinute;
    return figure == null ? '' : String(figure);
  }

  const start = useMutation({
    mutationFn: (body: OpenSessionRequest) => openSession(body),
    onSuccess: (session) => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.floor });
      onStarted(session.id);
    },
    onError: (caught) => {
      // A 409 means another tab opened this table first. The database index decides, so
      // there is nothing to retry — say so plainly and let the floor refresh show the truth.
      setError(messageOf(caught));
      void queryClient.invalidateQueries({ queryKey: queryKeys.floor });
    },
  });

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    if (!selectedId) {
      setError('Choose a customer type.');
      return;
    }

    const body: OpenSessionRequest = { tableId: table.id, customerTypeId: selectedId };
    // Only sent when the chosen type allows it; the server rejects it otherwise. One field or
    // the other, never both — and neither when the box is blank, which means charge the
    // standard rate. Number() rather than a truthiness test, so a zero friend rate (a comped
    // game) is sent as the giveaway it is instead of being dropped as falsy.
    if (allowsOverride && rateOverride.trim() !== '') {
      if (rateMode === 'hour') body.rateOverridePerHour = Number(rateOverride);
      else body.rateOverridePerMinute = Number(rateOverride);
      if (overrideReason.trim() !== '') body.rateOverrideReason = overrideReason.trim();
    }
    start.mutate(body);
  }

  return (
    <Modal title={`Start ${table.name}`} onClose={onClose}>
      <form onSubmit={handleSubmit} className="flex flex-col gap-6">
        <div>
          <div className="text-label uppercase text-text-dim">Table</div>
          <div className="text-body text-text">
            {table.name} ·{' '}
            {table.ratePerHour != null
              ? formatHourlyRate(table.ratePerHour)
              : formatRate(table.ratePerMinute)}
          </div>
        </div>

        <Select
          label="Customer type"
          value={selectedId}
          data-autofocus
          onChange={(event) => {
            setCustomerTypeId(event.target.value);
            setRateOverride('');
            setOverrideReason('');
            setRateMode(table.ratePerHour != null ? 'hour' : 'minute');
          }}
        >
          {customerTypes.map((type) => (
            <option key={type.id} value={type.id}>
              {type.name}
              {type.isDefault ? ' (default)' : ''}
            </option>
          ))}
        </Select>

        {allowsOverride ? (
          <>
            <RateModeField
              mode={rateMode}
              onModeChange={(next) => {
                setRateMode(next);
                setRateOverride('');
              }}
              value={rateOverride}
              onValueChange={setRateOverride}
              legend="Friend rate by"
              minuteLabel="Friend rate per minute"
              hourLabel="Friend rate per hour"
              placeholder={standardIn(rateMode)}
              // The giveaway has to be visible while it is typed, not found later in a report —
              // and it is only visible if it is quoted in the unit the person is typing in. So
              // this follows the TOGGLE, not the table.
              hint={`Standard rate is ${standardLabel(rateMode)}. Leave blank to charge it.`}
            />
            <Field
              label="Reason"
              value={overrideReason}
              placeholder="Who authorised it"
              onChange={(event) => setOverrideReason(event.target.value)}
            />
          </>
        ) : null}

        {error ? <Banner tone="danger">{error}</Banner> : null}

        <div className="flex justify-end gap-3">
          <Button type="button" variant="secondary" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" pending={start.isPending}>
            Start table
          </Button>
        </div>
      </form>
    </Modal>
  );
}
