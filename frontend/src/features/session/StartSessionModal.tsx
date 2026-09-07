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
import { formatHourlyRate, formatRate, formatRatePair } from '@/lib/money';

type Pricing = 'standard' | 'friend' | 'flat';

const PRICING_CHOICES: { id: Pricing; label: string }[] = [
  { id: 'standard', label: 'Standard rate' },
  { id: 'friend', label: 'Friend rate' },
  { id: 'flat', label: 'Flat rate' },
];

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
  // Standard, friend rate, or a flat tournament fee. Three choices rather than a checkbox,
  // because they are alternatives: a session has exactly one pricing story.
  const [pricing, setPricing] = useState<Pricing>('standard');
  const [rateOverride, setRateOverride] = useState('');
  const [overrideReason, setOverrideReason] = useState('');
  const [flatAmount, setFlatAmount] = useState('');
  const [flatReason, setFlatReason] = useState('');
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

  // Named after the type it is being given on — "Happy Hour rate" — because the owner now has
  // customer types beyond friends, and a field labelled "Friend rate" misnames every one of
  // them. The fallback is reachable only if a type allows an override without having a name.
  const rateName = selected?.name ? `${selected.name} rate` : 'Rate override';

  // The standard rate in whichever unit is being typed. In hourly mode on a table configured
  // per minute there is no typed hourly figure to quote, so this uses the server's
  // effectiveRatePerHour — 60 x the stored rate, computed there, not here.
  function standardLabel(mode: RateMode): string {
    return mode === 'hour'
      ? formatHourlyRate(table.ratePerHour ?? table.effectiveRatePerHour)
      : formatRate(table.ratePerMinute);
  }

  /*
   * The standard rate in BOTH units, HOURLY FIRST — always, whichever unit the table was
   * configured in.
   *
   * The owner thinks in pesos per hour and the meter bills per minute, and this is where
   * someone orients themselves before choosing how to price the session, so it should not make
   * them convert. Ordering by the configured unit was the earlier rule and is wrong: it put the
   * hourly figure first on an hourly table and second on a per-minute one, which on this modal
   * means two different orderings on one screen. The floor card leads hourly for the same
   * reason, and all three sites read off formatRatePair so they cannot drift apart.
   *
   * Both figures come from the server, and the null guard lives in formatRatePair — a table
   * with no current rate shows a single "—" rather than "— · —".
   */
  function standardBothUnits(): string {
    const rates = formatRatePair(table.ratePerMinute, table.ratePerHour ?? table.effectiveRatePerHour);
    return rates.hourly ? `${rates.hourly} · ${rates.perMinute}` : rates.perMinute;
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

    // One pricing story per session, so exactly one of these branches contributes. Number()
    // rather than a truthiness test throughout: a zero friend rate and a zero flat fee are both
    // comps, and dropping them as falsy would silently bill the standard rate instead.
    if (pricing === 'friend' && allowsOverride && rateOverride.trim() !== '') {
      if (rateMode === 'hour') body.rateOverridePerHour = Number(rateOverride);
      else body.rateOverridePerMinute = Number(rateOverride);
      if (overrideReason.trim() !== '') body.rateOverrideReason = overrideReason.trim();
    }
    if (pricing === 'flat') {
      if (flatAmount.trim() === '') {
        setError('Enter the flat amount for this session.');
        return;
      }
      if (flatReason.trim() === '') {
        setError('A flat rate needs a reason — what event is it for?');
        return;
      }
      body.flatAmount = Number(flatAmount);
      body.flatRateReason = flatReason.trim();
    }
    start.mutate(body);
  }

  return (
    <Modal title={`Start ${table.name}`} onClose={onClose}>
      <form onSubmit={handleSubmit} className="flex flex-col gap-6">
        {/*
          The header says the same thing the standard-rate description below now says, word for
          word, and that duplication is deliberate — do not tidy it away.

          The description is gated on `pricing === 'standard'` and disappears the moment someone
          picks Flat rate, which is exactly when they want to know what the meter would have
          charged. This line is the copy that survives a pricing switch. (Friend rate is already
          covered: the RateModeField hint quotes the standard rate in the toggle's unit.)
        */}
        <div>
          <div className="text-label uppercase text-text-dim">Table</div>
          <div className="text-body text-text">
            {table.name} · {standardBothUnits()}
          </div>
        </div>

        <Select
          label="Customer type"
          value={selectedId}
          data-autofocus
          onChange={(event) => {
            setCustomerTypeId(event.target.value);
            // The flat fee is deliberately NOT reset: it belongs to the event, not to who is
            // playing, and changing the customer type mid-form should not silently drop it.
            setRateOverride('');
            setOverrideReason('');
            setRateMode(table.ratePerHour != null ? 'hour' : 'minute');
            if (pricing === 'friend') setPricing('standard');
          }}
        >
          {customerTypes.map((type) => (
            <option key={type.id} value={type.id}>
              {type.name}
              {type.isDefault ? ' (default)' : ''}
            </option>
          ))}
        </Select>

        {/*
          Three alternatives, not a checkbox each. Friend rate is offered only where the
          customer type allows it; the flat fee is offered always, because a tournament is an
          event rather than a kind of customer — gating it would mean creating a "Tournament"
          customer type to run one, which is the thing-to-switch-back this design removes.
        */}
        <div className="flex flex-col gap-3">
          <div className="text-label uppercase text-text-dim">Price this session</div>
          <div className="flex flex-wrap gap-2">
            {PRICING_CHOICES.filter((choice) => choice.id !== 'friend' || allowsOverride).map(
              (choice) => {
                const active = pricing === choice.id;
                return (
                  <button
                    key={choice.id}
                    type="button"
                    aria-pressed={active}
                    onClick={() => {
                      setPricing(choice.id);
                      setError(null);
                    }}
                    className={`hit rounded-lg border px-4 text-body font-semibold transition ${
                      active
                        ? 'border-green bg-green text-ink'
                        : 'border-border bg-raised text-text-dim hover:border-text-dim hover:text-text'
                    }`}
                  >
                    {choice.label}
                  </button>
                );
              },
            )}
          </div>
          {pricing === 'standard' ? (
            <p className="text-label text-text-dim">
              The table&rsquo;s own rate, {standardBothUnits()}.
            </p>
          ) : null}
        </div>

        {pricing === 'flat' ? (
          <>
            <Field
              label="Flat amount for the whole session"
              type="number"
              step="0.01"
              min="0"
              inputMode="decimal"
              prefix="₱"
              value={flatAmount}
              // The charge is the whole story here, so it is stated rather than implied: the
              // timer still runs and the minutes are still recorded, they just do not price it.
              hint="Charged once, however long the session runs. The timer still runs and the minutes are still recorded."
              onChange={(event) => setFlatAmount(event.target.value)}
            />
            <Field
              label="Reason"
              value={flatReason}
              placeholder="Saturday tournament"
              hint="Required. This is the only record of why the table was not on the meter."
              onChange={(event) => setFlatReason(event.target.value)}
            />
          </>
        ) : null}

        {pricing === 'friend' && allowsOverride ? (
          <>
            <RateModeField
              mode={rateMode}
              onModeChange={(next) => {
                setRateMode(next);
                setRateOverride('');
              }}
              value={rateOverride}
              onValueChange={setRateOverride}
              legend={`${rateName} by`}
              minuteLabel={`${rateName} per minute`}
              hourLabel={`${rateName} per hour`}
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
