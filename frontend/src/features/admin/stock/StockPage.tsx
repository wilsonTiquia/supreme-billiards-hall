import { useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { fetchProductsAdmin } from '@/api/endpoints/products';
import {
  fetchLowStock,
  recordComp,
  recordCorrection,
} from '@/api/endpoints/stock';
import { queryKeys } from '@/api/queryKeys';
import { messageOf } from '@/api/errors';
import type { ProductAdmin } from '@/api/types';
import { AdminPage } from '../AdminPage';
import { Card } from '@/components/Card';
import { Button } from '@/components/Button';
import { Field } from '@/components/Field';
import { ProductPicker } from '@/components/ProductPicker';
import { Banner } from '@/components/Banner';
import { DeliveryForm } from './DeliveryForm';
import { Spinner } from '@/components/Spinner';

const tasks = ['Delivery', 'Count', 'Giveaway'] as const;
type Task = (typeof tasks)[number];
type Feedback = { error?: string; success?: string };

export function StockPage() {
  const queryClient = useQueryClient();
  const [task, setTask] = useState<Task>('Delivery');
  const [feedback, setFeedback] = useState<Partial<Record<Task, Feedback>>>({});
  const tabs = useRef<(HTMLButtonElement | null)[]>([]);

  const products = useQuery({
    queryKey: queryKeys.products({ activeOnly: false }),
    queryFn: () => fetchProductsAdmin({ activeOnly: false }),
  });
  const lowStock = useQuery({ queryKey: queryKeys.lowStock, queryFn: fetchLowStock });

  function report(task: Task, message: Feedback) {
    setFeedback(current => ({ ...current, [task]: message }));
  }

  function refresh(task: Task, message: string) {
    report(task, { success: message });
    void queryClient.invalidateQueries({ queryKey: ['products'] });
    void queryClient.invalidateQueries({ queryKey: queryKeys.lowStock });
  }

  const rows = products.data ?? [];

  return (
    <AdminPage title="Stock" intro="Receive deliveries, correct shelf counts and record give-aways.">
      <div className="grid items-start gap-6 lg:grid-cols-[minmax(0,7fr)_minmax(0,3fr)]">
        <div className="min-w-0">
          <div role="tablist" aria-label="Stock task" className="grid grid-cols-3 gap-1 rounded-xl border border-border bg-surface p-1">
            {tasks.map((name, index) => (
              <Button
                key={name}
                ref={element => { tabs.current[index] = element; }}
                type="button"
                role="tab"
                id={`stock-tab-${name}`}
                aria-controls={`stock-panel-${name}`}
                aria-selected={task === name}
                tabIndex={task === name ? 0 : -1}
                variant={task === name ? 'primary' : 'secondary'}
                className="min-w-0 px-2"
                onClick={() => setTask(name)}
                onKeyDown={event => {
                  let next: number;
                  if (event.key === 'ArrowRight') next = (index + 1) % tasks.length;
                  else if (event.key === 'ArrowLeft') next = (index + tasks.length - 1) % tasks.length;
                  else if (event.key === 'Home') next = 0;
                  else if (event.key === 'End') next = tasks.length - 1;
                  else return;
                  event.preventDefault();
                  setTask(tasks[next]);
                  tabs.current[next]?.focus();
                }}
              >
                {name}
              </Button>
            ))}
          </div>
          <p className="my-3 text-label text-text-dim">Drafts are kept when switching tasks on this page.</p>
          {/* Keep every form mounted: switching tasks must preserve drafts and pending mutations. */}
          {tasks.map(name => (
            <section key={name} id={`stock-panel-${name}`} role="tabpanel"
              aria-labelledby={`stock-tab-${name}`} hidden={task !== name} tabIndex={0}>
              <Card className="min-w-0">
                {products.isPending ? (
                  <div className="py-10 text-center"><Spinner label="Loading products…" /></div>
                ) : products.isError && !products.data ? (
                  <Banner tone="danger" actions={<Button variant="secondary" pending={products.isFetching} onClick={() => void products.refetch()}>Retry products</Button>}>
                    {messageOf(products.error)}
                  </Banner>
                ) : (
                  <>
                    {name === 'Delivery' ? (
                      <>
                        <h2 className="text-heading text-text">Receive a delivery</h2>
                        <DeliveryForm products={rows}
                          onStart={() => report(name, {})}
                          onError={error => report(name, { error })}
                          onDone={() => refresh(name, 'Delivery recorded. Average costs have been recomputed.')} />
                      </>
                    ) : name === 'Count' ? (
                      <CorrectionForm products={rows}
                        onStart={() => report(name, {})}
                        onError={error => report(name, { error })}
                        onDone={() => refresh(name, 'Correction recorded as a compensating movement.')} />
                    ) : (
                      <CompForm products={rows}
                        onStart={() => report(name, {})}
                        onError={error => report(name, { error })}
                        onDone={() => refresh(name, 'Give-away recorded.')} />
                    )}
                    {feedback[name]?.error ? <div className="mt-4"><Banner tone="danger">{feedback[name]?.error}</Banner></div> : null}
                    {feedback[name]?.success ? <div className="mt-4"><Banner>{feedback[name]?.success}</Banner></div> : null}
                  </>
                )}
              </Card>
            </section>
          ))}
        </div>

        <aside aria-labelledby="stock-low-title" className="min-w-0 lg:sticky lg:top-6">
          <Card>
            <h2 id="stock-low-title" className="text-heading text-text">Low or negative stock</h2>
            <p className="mt-1 text-body text-text-dim">
              At or below the branch threshold, including negative stock. Selling below zero is allowed.
            </p>
            {lowStock.isPending ? (
              <div className="py-6 text-center"><Spinner label="Loading stock…" /></div>
            ) : lowStock.isError ? (
              <div className="mt-4">
                <Banner tone="danger" actions={<Button variant="secondary" pending={lowStock.isFetching} onClick={() => void lowStock.refetch()}>Retry</Button>}>
                  {messageOf(lowStock.error)}
                </Banner>
              </div>
            ) : (lowStock.data ?? []).length === 0 ? (
              <p className="mt-4 text-body text-green">Nothing is running low.</p>
            ) : (
              <ul className="mt-4 divide-y divide-border lg:max-h-[60dvh] lg:overflow-y-auto lg:overscroll-contain">
                {(lowStock.data ?? []).map(line => (
                  <li key={line.productId} className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1 py-3">
                    <span className="min-w-0 break-words text-heading text-text">{line.name}</span>
                    <span className={`tabular text-body ${line.qtyOnHand <= 0 ? 'text-danger' : 'text-text-dim'}`}>
                      <span className="font-semibold">{line.qtyOnHand}</span> on hand · {line.threshold} threshold
                    </span>
                  </li>
                ))}
              </ul>
            )}
          </Card>
        </aside>
      </div>
    </AdminPage>
  );
}

/* ── Corrections ────────────────────────────────────────────────────────────── */

function CorrectionForm({
  products,
  onError,
  onDone,
  onStart,
}: {
  products: ProductAdmin[];
  onError: (message: string) => void;
  onDone: () => void;
  onStart: () => void;
}) {
  const [productId, setProductId] = useState('');
  const [newQuantity, setNewQuantity] = useState('');
  const [note, setNote] = useState('');

  const chosen = products.find((product) => product.id === productId);

  const correct = useMutation({
    mutationFn: () =>
      recordCorrection({ productId, newQuantity: Number(newQuantity), note: note.trim() }),
    onSuccess: () => {
      setNewQuantity('');
      setNote('');
      onDone();
    },
    // Correcting to the figure already held is a 409 by design — there is no movement to
    // record. Said plainly rather than dressed up as a failure.
    onError: (caught) => onError(messageOf(caught)),
  });

  return (
    <>
      <h2 className="text-heading text-text">Correct a count</h2>
      <p className="mt-1 text-body text-text-dim">
        Enter what is actually on the shelf. The ledger records the difference, never an edit.
      </p>

      <form
        onSubmit={(event) => {
          event.preventDefault();
          if (correct.isPending || productId === '' || newQuantity.trim() === '' || note.trim() === '') return;
          onStart();
          correct.mutate();
        }}
        className="mt-4"
      >
        <fieldset disabled={correct.isPending} className="flex min-w-0 flex-col gap-4">
          <ProductPicker label="Product" products={products} value={productId} onChange={setProductId} />
          <Field
            label="Actual quantity"
            type="number"
            step="0.001"
            value={newQuantity}
            hint={chosen ? `System currently shows ${chosen.qtyOnHand}.` : undefined}
            onChange={(event) => setNewQuantity(event.target.value)}
          />
          <Field
            label="Reason"
            value={note}
            placeholder="Required — why the count was wrong"
            onChange={(event) => setNote(event.target.value)}
          />
          <Button
            type="submit"
            pending={correct.isPending}
            disabled={productId === '' || newQuantity.trim() === '' || note.trim() === ''}
          >
            Record correction
          </Button>
        </fieldset>
      </form>
    </>
  );
}

/* ── Comps ──────────────────────────────────────────────────────────────────── */

function CompForm({
  products,
  onError,
  onDone,
  onStart,
}: {
  products: ProductAdmin[];
  onError: (message: string) => void;
  onDone: () => void;
  onStart: () => void;
}) {
  const [productId, setProductId] = useState('');
  const [quantity, setQuantity] = useState('');
  const [note, setNote] = useState('');

  const comp = useMutation({
    mutationFn: () => recordComp({ productId, quantity: Number(quantity), note: note.trim() }),
    onSuccess: () => {
      setQuantity('');
      setNote('');
      onDone();
    },
    onError: (caught) => onError(messageOf(caught)),
  });

  return (
    <>
      <h2 className="text-heading text-text">Give away</h2>
      <p className="mt-1 text-body text-text-dim">
        Stock that left without being sold. It costs the hall its cost price, and the dashboard
        shows it as a loss — the same act as Give away on the quick-sale screen.
      </p>

      <form
        onSubmit={(event) => {
          event.preventDefault();
          if (comp.isPending || productId === '' || quantity.trim() === '' || note.trim() === '') return;
          onStart();
          comp.mutate();
        }}
        className="mt-4"
      >
        <fieldset disabled={comp.isPending} className="flex min-w-0 flex-col gap-4">
          <ProductPicker label="Product" products={products} value={productId} onChange={setProductId} />
          <Field
            label="Quantity"
            type="number"
            step="0.001"
            min="0"
            value={quantity}
            onChange={(event) => setQuantity(event.target.value)}
          />
          <Field
            label="Reason"
            value={note}
            placeholder="Required — who and why"
            onChange={(event) => setNote(event.target.value)}
          />
          <Button
            type="submit"
            pending={comp.isPending}
            disabled={productId === '' || quantity.trim() === '' || note.trim() === ''}
          >
            Record give-away
          </Button>
        </fieldset>
      </form>
    </>
  );
}
