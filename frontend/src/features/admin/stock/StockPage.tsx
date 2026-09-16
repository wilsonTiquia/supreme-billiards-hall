import { useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { fetchProductsAdmin } from '@/api/endpoints/products';
import {
  fetchLowStock,
  recordComp,
  recordCorrection,
  recordDelivery,
} from '@/api/endpoints/stock';
import { queryKeys } from '@/api/queryKeys';
import { messageOf } from '@/api/errors';
import type { ProductAdmin, StockDeliveryRequest } from '@/api/types';
import { AdminPage } from '../AdminPage';
import { Card } from '@/components/Card';
import { Button } from '@/components/Button';
import { Field } from '@/components/Field';
import { ProductPicker } from '@/components/ProductPicker';
import { useToast } from '@/components/Toast';
import { Spinner } from '@/components/Spinner';

export function StockPage() {
  const queryClient = useQueryClient();
  const [error, setError] = useState<string | null>(null);
  const { notify, dismiss } = useToast();

  const products = useQuery({
    queryKey: queryKeys.products({ activeOnly: false }),
    queryFn: () => fetchProductsAdmin({ activeOnly: false }),
  });

  const lowStock = useQuery({ queryKey: queryKeys.lowStock, queryFn: fetchLowStock });

  // A failure must clear the previous success, or the screen shows both at once and reads as
  // if the failed action also worked.
  function fail(message: string) {
    dismiss();
    setError(message);
  }

  function refresh(message: string) {
    setError(null);
    notify(message);
    void queryClient.invalidateQueries({ queryKey: ['products'] });
    void queryClient.invalidateQueries({ queryKey: queryKeys.lowStock });
  }

  const rows = products.data ?? [];

  return (
    <AdminPage
      title="Stock"
      intro="Stock only ever moves through the ledger, and the ledger is append-only. A correction is a new compensating row, never an edit."
      error={error ?? (products.isError ? messageOf(products.error) : null)}
    >
      {products.isPending ? (
        <div className="py-10 text-center">
          <Spinner label="Loading stock…" />
        </div>
      ) : (
        <div className="grid gap-6 lg:grid-cols-2">
          <DeliveryForm
            products={rows}
            onError={fail}
            onDone={() => refresh('Delivery recorded. Average costs have been recomputed.')}
          />
          <div className="flex flex-col gap-6">
            <CorrectionForm
              products={rows}
              onError={fail}
              onDone={() => refresh('Correction recorded as a compensating movement.')}
            />
            <CompForm
              products={rows}
              onError={fail}
              onDone={() => refresh('Give-away recorded.')}
            />
          </div>
        </div>
      )}

      <Card className="mt-6">
        <h2 className="text-heading text-text">Low or negative stock</h2>
        <p className="mt-1 text-body text-text-dim">
          At or below the branch threshold. Anything negative is swept in here too — selling
          below zero is allowed, so this is where it surfaces.
        </p>
        {lowStock.isPending ? (
          <div className="py-6 text-center">
            <Spinner label="Loading…" />
          </div>
        ) : (lowStock.data ?? []).length === 0 ? (
          <p className="mt-4 text-body text-green">Nothing is running low.</p>
        ) : (
          <ul className="mt-4 divide-y divide-border">
            {(lowStock.data ?? []).map((line) => (
              <li key={line.productId} className="flex items-baseline justify-between gap-3 py-3">
                <span className="text-heading text-text">{line.name}</span>
                <span
                  className={`tabular text-heading ${line.qtyOnHand <= 0 ? 'text-danger' : 'text-text'}`}
                >
                  {line.qtyOnHand} / {line.threshold}
                </span>
              </li>
            ))}
          </ul>
        )}
      </Card>
    </AdminPage>
  );
}

/* ── Deliveries ─────────────────────────────────────────────────────────────── */

function DeliveryForm({
  products,
  onError,
  onDone,
}: {
  products: ProductAdmin[];
  onError: (message: string) => void;
  onDone: () => void;
}) {
  const [lines, setLines] = useState<{ productId: string; quantity: string; unitCost: string }[]>([
    { productId: '', quantity: '', unitCost: '' },
  ]);

  const deliver = useMutation({
    mutationFn: (body: StockDeliveryRequest) => recordDelivery(body),
    onSuccess: () => {
      setLines([{ productId: '', quantity: '', unitCost: '' }]);
      onDone();
    },
    onError: (caught) => onError(messageOf(caught)),
  });

  const usable = lines.filter(
    (line) => line.productId !== '' && line.quantity.trim() !== '' && line.unitCost.trim() !== '',
  );

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    if (usable.length === 0) return;
    deliver.mutate({
      lines: usable.map((line) => ({
        productId: line.productId,
        quantity: Number(line.quantity),
        unitCost: Number(line.unitCost),
      })),
    });
  }

  return (
    <Card>
      <h2 className="text-heading text-text">Receive a delivery</h2>
      <p className="mt-1 text-body text-text-dim">
        Each line's unit cost feeds the moving weighted average, which is what profit is
        measured against.
      </p>

      <form onSubmit={handleSubmit} className="mt-4 flex flex-col gap-4">
        {lines.map((line, index) => (
          <div key={index} className="rounded-lg border border-border p-3">
            <ProductPicker
              label={`Line ${index + 1}`}
              products={products}
              value={line.productId}
              onChange={(productId) =>
                setLines((current) =>
                  current.map((l, i) => (i === index ? { ...l, productId } : l)),
                )
              }
            />
            <div className="mt-3 grid grid-cols-2 gap-3">
              <Field
                label="Quantity"
                type="number"
                step="0.001"
                min="0"
                value={line.quantity}
                onChange={(event) =>
                  setLines((current) =>
                    current.map((l, i) => (i === index ? { ...l, quantity: event.target.value } : l)),
                  )
                }
              />
              <Field
                label="Unit cost"
                type="number"
                step="0.01"
                min="0"
                value={line.unitCost}
                onChange={(event) =>
                  setLines((current) =>
                    current.map((l, i) => (i === index ? { ...l, unitCost: event.target.value } : l)),
                  )
                }
              />
            </div>
          </div>
        ))}

        <div className="flex justify-between gap-3">
          <Button
            type="button"
            variant="secondary"
            onClick={() =>
              setLines((current) => [...current, { productId: '', quantity: '', unitCost: '' }])
            }
          >
            Add a line
          </Button>
          <Button type="submit" pending={deliver.isPending} disabled={usable.length === 0}>
            Record delivery
          </Button>
        </div>
      </form>
    </Card>
  );
}

/* ── Corrections ────────────────────────────────────────────────────────────── */

function CorrectionForm({
  products,
  onError,
  onDone,
}: {
  products: ProductAdmin[];
  onError: (message: string) => void;
  onDone: () => void;
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
    <Card>
      <h2 className="text-heading text-text">Correct a count</h2>
      <p className="mt-1 text-body text-text-dim">
        Enter what is actually on the shelf. The ledger records the difference, never an edit.
      </p>

      <form
        onSubmit={(event) => {
          event.preventDefault();
          if (productId === '' || newQuantity.trim() === '' || note.trim() === '') return;
          correct.mutate();
        }}
        className="mt-4 flex flex-col gap-4"
      >
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
      </form>
    </Card>
  );
}

/* ── Comps ──────────────────────────────────────────────────────────────────── */

function CompForm({
  products,
  onError,
  onDone,
}: {
  products: ProductAdmin[];
  onError: (message: string) => void;
  onDone: () => void;
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
    <Card>
      <h2 className="text-heading text-text">Give away</h2>
      <p className="mt-1 text-body text-text-dim">
        Stock that left without being sold. It costs the hall its cost price, and the dashboard
        shows it as a loss — the same act as Give away on the quick-sale screen.
      </p>

      <form
        onSubmit={(event) => {
          event.preventDefault();
          if (productId === '' || quantity.trim() === '' || note.trim() === '') return;
          comp.mutate();
        }}
        className="mt-4 flex flex-col gap-4"
      >
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
      </form>
    </Card>
  );
}
