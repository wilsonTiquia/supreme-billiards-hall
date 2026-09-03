import { useEffect, useRef, useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { fetchSettings, updateSettings } from '@/api/endpoints/settings';
import { queryKeys } from '@/api/queryKeys';
import { messageOf } from '@/api/errors';
import { formatAmountDigits, formatMoney, parseAmount } from '@/lib/money';
import { AdminPage } from '../AdminPage';
import { Card } from '@/components/Card';
import { Button } from '@/components/Button';
import { Field } from '@/components/Field';
import { Banner } from '@/components/Banner';
import { Spinner } from '@/components/Spinner';

/**
 * Configured once, applied every night.
 *
 * The float is here rather than on the close-out because it is a standing fact about how the
 * hall runs, not a nightly decision. Asking the counter for it every night would be asking a
 * question with the same answer three hundred times, and the three hundred-and-first answer
 * would be wrong.
 */
export function SettingsPage() {
  const queryClient = useQueryClient();
  const [float_, setFloat] = useState('');
  const [animation, setAnimation] = useState(false);
  const [saved, setSaved] = useState(false);

  const settings = useQuery({
    queryKey: queryKeys.settings,
    queryFn: fetchSettings,
  });

  /*
   * Seeded from the server exactly once, tracked by a ref rather than by looking at the field.
   *
   * The previous version keyed this on `float_ === ''`, which made the effect fire again the
   * moment the box was emptied — so backspacing to clear it silently refilled it with the old
   * figure under the cursor, and typing a new value was a fight. Whether the field has been
   * seeded is a fact about this component's history, not about what is currently in the box,
   * and a ref is where that belongs.
   */
  const seeded = useRef(false);
  useEffect(() => {
    if (settings.data && !seeded.current) {
      seeded.current = true;
      setFloat(formatAmountDigits(settings.data.standardCashFloat));
      setAnimation(settings.data.checkoutAnimation);
    }
  }, [settings.data]);

  const save = useMutation({
    mutationFn: (body: { standardCashFloat: number; checkoutAnimation: boolean }) =>
      updateSettings(body),
    onSuccess: (result) => {
      setSaved(true);
      // Formatted, like every other path that writes this field. String() here was the one
      // place the box could end up reading "1000" after a save that displayed ₱1,000.00.
      setFloat(formatAmountDigits(result.standardCashFloat));
      setAnimation(result.checkoutAnimation);
      // The close-out reads the standard off the business day, so that has to re-read too.
      void queryClient.invalidateQueries({ queryKey: queryKeys.settings });
      void queryClient.invalidateQueries({ queryKey: queryKeys.businessDayCurrent });
    },
  });

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    const amount = parseAmount(float_);
    if (amount === null) return;
    setSaved(false);
    // Tidied to two places on submit as well as on blur, so a value sent by pressing Return
    // straight from the box lands looking the same as one that was tabbed out of.
    setFloat(formatAmountDigits(amount));
    save.mutate({ standardCashFloat: amount, checkoutAnimation: animation });
  }

  /*
   * Formatting happens HERE, on blur, and nowhere near onChange.
   *
   * Reformatting per keystroke is what makes a money box unusable: "1" becomes "1.00" under the
   * cursor, the caret jumps to the end, and the next digit lands in the wrong place. While the
   * field has focus it holds exactly what was typed; when focus leaves, the figure is parsed
   * once and written back grouped and to two places.
   */
  function handleBlur() {
    const amount = parseAmount(float_);
    setFloat(amount === null ? '' : formatAmountDigits(amount));
  }

  const current = settings.data?.standardCashFloat ?? 0;
  const currentAnimation = settings.data?.checkoutAnimation ?? false;
  const entered = parseAmount(float_);
  const unchanged = entered !== null && entered === current && animation === currentAnimation;

  return (
    <AdminPage
      title="Settings"
      intro="How the hall runs. Changed rarely, and every change is in the audit log."
      error={settings.isError ? messageOf(settings.error) : null}
    >
      {settings.isPending ? (
        <div className="py-16 text-center">
          <Spinner label="Loading settings…" />
        </div>
      ) : (
        <Card className="max-w-xl">
          <h2 className="text-heading text-text">Standard change float</h2>
          <p className="mt-1 text-body text-text-dim">
            What goes into the drawer at open so staff can make change. The close-out defaults to
            this every night and expects the drawer to hold it plus the night&rsquo;s cash
            takings. Set it to zero if the hall keeps no float.
          </p>

          <form onSubmit={handleSubmit} className="mt-6 flex flex-col gap-6">
            {/* text, not number: a number input cannot hold "1,000.00" while it is being
                typed, and its spinner and locale quirks are no help on a figure changed twice
                a year. inputMode still brings up the numeric keypad. */}
            <Field
              label="Float"
              type="text"
              inputMode="decimal"
              autoComplete="off"
              prefix="₱"
              value={float_}
              placeholder="0.00"
              hint={`Currently ${formatMoney(current)}. Nights already counted keep the float they were counted against.`}
              onChange={(event) => {
                setFloat(event.target.value);
                setSaved(false);
              }}
              onBlur={handleBlur}
            />

            <div className="border-t border-border pt-6">
              <h2 className="text-heading text-text">Checkout animation</h2>
              <p className="mt-1 text-body text-text-dim">
                A short sequence that plays over the receipt after a payment. The receipt is
                live underneath it and any click or keypress ends it, so it never holds anyone
                up — but checkout happens forty times on a Saturday, and if it starts to grate
                this is the switch. It takes effect on each till&rsquo;s next refresh.
              </p>
              <label className="hit mt-3 flex items-center gap-3">
                <input
                  type="checkbox"
                  checked={animation}
                  onChange={(event) => {
                    setAnimation(event.target.checked);
                    setSaved(false);
                  }}
                  className="h-6 w-6 rounded border-border"
                />
                <span className="text-body text-text">Play it after a payment</span>
              </label>
            </div>

            {save.isError ? <Banner tone="danger">{messageOf(save.error)}</Banner> : null}
            {saved ? <Banner tone="info">Saved. Tonight&rsquo;s close-out will use it.</Banner> : null}

            <div className="flex justify-end">
              <Button type="submit" pending={save.isPending} disabled={entered === null || unchanged}>
                Save
              </Button>
            </div>
          </form>
        </Card>
      )}
    </AdminPage>
  );
}
