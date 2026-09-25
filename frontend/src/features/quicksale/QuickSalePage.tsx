import { useMemo, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { quoteQuickSale, recordQuickSale } from '@/api/endpoints/quickSales';
import { recordCompBatch } from '@/api/endpoints/stock';
import { fetchCustomerTypes } from '@/api/endpoints/customerTypes';
import { queryKeys } from '@/api/queryKeys';
import { ErrorCode, isApiError, messageOf } from '@/api/errors';
import type { Payment, PaymentRequest, Product, QuickSaleLineRequest } from '@/api/types';
import { newIdempotencyKey } from '@/lib/idempotency';
import { useScreenTheme } from '@/app/useTheme';
import { formatMoney } from '@/lib/money';
import { Card } from '@/components/Card';
import { Button } from '@/components/Button';
import { Select } from '@/components/Select';
import { Field } from '@/components/Field';
import { Banner } from '@/components/Banner';
import { Spinner } from '@/components/Spinner';
import { ProductImage } from '@/components/ProductImage';
import { ProductGrid } from '@/features/session/ProductGrid';
import { PaymentForm } from '@/features/checkout/PaymentForm';
import { attachSelectedPhoto } from '@/features/checkout/paymentPhoto';

/**
 * A sale with no table — a bottle of water bought on the way past the counter.
 *
 * The whole thing is one server transaction: the bill is created, the stock moves and the
 * payment is taken in a single POST. So unlike the session checkout there is nothing to settle
 * later and nothing left open if the browser dies mid-way; either the sale happened or it did
 * not.
 *
 * The total is quoted by the server, never summed here (frontend/CLAUDE.md §2). Every change
 * to the basket re-quotes, and the amount paid is the quote's own figure — so the browser does
 * no arithmetic on money at any point.
 */
export function QuickSalePage() {
  useScreenTheme('pos');
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  /* Sell or give away. A comp is NOT a sale: it writes no bill, no payment and no receipt,
     and never reaches revenue. It is stock leaving the building for free, recorded as exactly
     that in the append-only ledger, and it surfaces on the dashboard's losses tile. */
  const [mode, setMode] = useState<'SELL' | 'GIVE'>('SELL');

  const [basket, setBasket] = useState<{ product: Product; quantity: number }[]>([]);
  const [customerTypeId, setCustomerTypeId] = useState('');
  const [photoNote, setPhotoNote] = useState<string | null>(null);
  const [settled, setSettled] = useState<Payment | null>(null);
  const [givenAway, setGivenAway] = useState<number | null>(null);
  const [compReason, setCompReason] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [duplicateReference, setDuplicateReference] = useState(false);

  const { data: customerTypes = [] } = useQuery({
    queryKey: queryKeys.customerTypes,
    queryFn: fetchCustomerTypes,
    staleTime: 5 * 60_000,
  });

  // Same convention as the start-session modal: the marked default is what the counter wants
  // nine times out of ten.
  const selectedCustomerTypeId =
    customerTypeId || customerTypes.find((type) => type.isDefault)?.id || '';

  const lines: QuickSaleLineRequest[] = useMemo(
    () => basket.map((entry) => ({ productId: entry.product.id, quantity: entry.quantity })),
    [basket],
  );

  /* The server prices the basket. Keyed on the lines, so adding or removing one re-quotes and
     an amount can never be paid that belongs to a basket the operator has since changed. */
  const quote = useQuery({
    queryKey: queryKeys.quickSaleQuote(lines),
    queryFn: () => quoteQuickSale(lines),
    enabled: mode === 'SELL' && lines.length > 0,
    staleTime: Infinity,
  });

  /* One key per basket, reused on every retry of it — including "Record anyway". A changed
     basket is a genuinely different charge, so it starts a new attempt and a new key. */
  const idempotencyKey = useRef(newIdempotencyKey());
  const keyedFor = useRef<string>('');
  const basketSignature = JSON.stringify(lines);
  if (keyedFor.current !== basketSignature) {
    idempotencyKey.current = newIdempotencyKey();
    keyedFor.current = basketSignature;
  }

  const sell = useMutation({
    mutationFn: ({ body }: { body: PaymentRequest; photo: File | null }) =>
      recordQuickSale({ customerTypeId: selectedCustomerTypeId, lines, payment: body }),
    onSuccess: async (payment, { photo }) => {
      setPhotoNote(await attachSelectedPhoto(payment, photo));
      setError(null);
      setDuplicateReference(false);
      setSettled(payment);
      // The sale moved stock, so anything showing a count is now stale.
      void queryClient.invalidateQueries({ queryKey: ['products'] });
      void queryClient.invalidateQueries({ queryKey: queryKeys.lowStock });
    },
    onError: (caught) => {
      if (isApiError(caught) && caught.code === ErrorCode.DuplicatePaymentReference) {
        setDuplicateReference(true);
        setError(messageOf(caught));
        return;
      }
      /* Anything else — most realistically the amount-mismatch 409 after an admin repriced a
         product mid-basket. Re-quoting is the fix, and the form then reads the new total, so
         the old amount cannot be resent. */
      setError(messageOf(caught));
      void quote.refetch();
    },
  });

  const giveAway = useMutation({
    mutationFn: () =>
      recordCompBatch({
        lines: lines.map((line) => ({ productId: line.productId, quantity: line.quantity })),
        note: compReason.trim(),
      }),
    onSuccess: (movements) => {
      setError(null);
      setGivenAway(movements.length);
      void queryClient.invalidateQueries({ queryKey: ['products'] });
      void queryClient.invalidateQueries({ queryKey: queryKeys.lowStock });
    },
    onError: (caught) => setError(messageOf(caught)),
  });

  function addProduct(product: Product) {
    setBasket((current) => {
      const existing = current.find((entry) => entry.product.id === product.id);
      return existing
        ? current.map((entry) =>
            entry.product.id === product.id ? { ...entry, quantity: entry.quantity + 1 } : entry,
          )
        : [...current, { product, quantity: 1 }];
    });
  }

  function changeQuantity(productId: string, delta: number) {
    setBasket((current) =>
      current
        .map((entry) =>
          entry.product.id === productId
            ? { ...entry, quantity: entry.quantity + delta }
            : entry,
        )
        .filter((entry) => entry.quantity > 0),
    );
  }

  /* ── Given away ──────────────────────────────────────────────────────────── */
  // A confirmation, not a receipt. Nothing was sold, so there is nothing to hand a customer.
  if (givenAway !== null) {
    return (
      <div className="mx-auto max-w-lg">
        <Card>
          <h1 className="text-heading text-text">Given away</h1>
          <p className="mt-3 text-body text-text">
            {givenAway} {givenAway === 1 ? 'product' : 'products'} recorded as a staff comp.
            The stock is out of the count and no money changed hands.
          </p>
          <p className="mt-3 text-body text-text-dim">
            There is no bill and no receipt — this was not a sale. It shows on the dashboard's
            losses at cost, against the reason you gave.
          </p>
          <div className="mt-6 flex gap-3">
            <Button
              onClick={() => {
                setGivenAway(null);
                setBasket([]);
                setCompReason('');
              }}
            >
              Give away something else
            </Button>
            <Button variant="secondary" onClick={() => navigate('/floor')}>
              Back to floor
            </Button>
          </div>
        </Card>
      </div>
    );
  }

  /* ── Settled ─────────────────────────────────────────────────────────────── */
  if (settled) {
    return (
      <div className="mx-auto max-w-lg">
        <Card>
          <h1 className="text-heading text-text">
            {settled.replayed ? 'Already recorded' : 'Sold'}
          </h1>
          <p className="mt-1 text-label uppercase text-text-dim">
            Receipt #{settled.receiptNo} · {settled.method}
          </p>

          <div className="figure-amount mt-4 text-amount">{formatMoney(settled.amount)}</div>

          {settled.method === 'CASH' ? (
            <dl className="mt-4 space-y-1">
              <div className="flex justify-between gap-3">
                <dt className="text-label uppercase text-text-dim">Tendered</dt>
                <dd className="tabular text-body text-text">{formatMoney(settled.tendered)}</dd>
              </div>
              {/* Straight from the response. Never computed here. */}
              <div className="flex justify-between gap-3">
                <dt className="text-label uppercase text-text-dim">Change</dt>
                <dd className="tabular text-amount text-amount">
                  {formatMoney(settled.changeGiven)}
                </dd>
              </div>
            </dl>
          ) : (
            <p className="mt-4 text-body text-text-dim">Reference {settled.referenceNo}</p>
          )}

          {photoNote ? <p role="status" className="mt-4 text-body text-text-dim">{photoNote}</p> : null}
          <div className="mt-6 flex gap-3">
            <Button onClick={() => navigate(`/receipt/${settled.billId}`, { state: { justPaid: settled, photoNote } })}>View receipt</Button>
            <Button variant="secondary" onClick={() => navigate('/floor')}>
              Back to floor
            </Button>
          </div>
        </Card>
      </div>
    );
  }

  /* ── Ringing up ──────────────────────────────────────────────────────────── */
  const total = quote.data?.total ?? null;
  const ready = lines.length > 0 && total !== null && selectedCustomerTypeId !== '';

  return (
    <div className="grid gap-6 lg:h-[calc(100dvh-6.5rem)] lg:grid-cols-[1fr_minmax(24rem,34rem)]">
      <Card className="flex min-h-0 flex-1 flex-col">
        <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
          <h1 className="text-heading text-text">
            {mode === 'SELL' ? 'Quick sale' : 'Give away'}
          </h1>
          <div className="flex gap-2">
            {(['SELL', 'GIVE'] as const).map((option) => (
              <button
                key={option}
                type="button"
                onClick={() => {
                  setMode(option);
                  setError(null);
                }}
                className={`hit rounded-lg border px-4 text-body font-semibold transition ${
                  mode === option
                    ? 'border-green bg-green text-ink'
                    : 'border-border bg-raised text-text hover:border-text-dim'
                }`}
              >
                {option === 'SELL' ? 'Sell' : 'Give away'}
              </button>
            ))}
          </div>
        </div>
        <ProductGrid onAdd={addProduct} pendingProductId={null} />
      </Card>

      <div className="flex min-h-0 flex-col gap-4 overflow-y-auto">
        <Card>
          <h2 className="text-heading text-text">Basket</h2>

          {basket.length === 0 ? (
            <p className="mt-4 text-body text-text-dim">
              Nothing yet. Pick a product on the left — no table needed.
            </p>
          ) : (
            <ul className="mt-4 divide-y divide-border">
              {basket.map((entry) => {
                // The line's own money comes from the quote, never from a sum done here.
                const quoted = quote.data?.lines.find(
                  (line) => line.productId === entry.product.id,
                );
                return (
                  <li key={entry.product.id} className="flex items-center gap-3 py-3">
                    <ProductImage
                      productId={entry.product.id}
                      name={entry.product.name}
                      imageSha256={entry.product.imageSha256}
                      size="thumb"
                    />
                    <div className="min-w-0 flex-1">
                      <div className="truncate text-body text-text">{entry.product.name}</div>
                      <div className="tabular text-label text-text-dim">
                        {mode === 'SELL'
                          ? `${entry.quantity} × ${formatMoney(entry.product.sellingPrice)}`
                          : `${entry.quantity} to give away`}
                      </div>
                    </div>
                    {mode === 'SELL' ? (
                      <div className="tabular text-body text-text">
                        {quoted ? formatMoney(quoted.lineTotal) : '…'}
                      </div>
                    ) : null}
                    <div className="flex gap-1">
                      <Button
                        variant="secondary"
                        aria-label={`One fewer ${entry.product.name}`}
                        onClick={() => changeQuantity(entry.product.id, -1)}
                      >
                        −
                      </Button>
                      <Button
                        variant="secondary"
                        aria-label={`One more ${entry.product.name}`}
                        onClick={() => changeQuantity(entry.product.id, 1)}
                      >
                        +
                      </Button>
                    </div>
                  </li>
                );
              })}
            </ul>
          )}

          {lines.length > 0 && mode === 'SELL' ? (
            <div className="mt-4 flex items-baseline justify-between gap-3 border-t border-border pt-4">
              <span className="text-label uppercase text-text-dim">Total</span>
              <span className="figure-amount text-amount">
                {quote.isPending ? <Spinner label="Pricing…" /> : formatMoney(total)}
              </span>
            </div>
          ) : null}

          {lines.length > 0 && mode === 'GIVE' ? (
            <p className="mt-4 border-t border-border pt-4 text-body text-text-dim">
              Nothing is charged. This leaves the stock count and is recorded against you.
            </p>
          ) : null}
        </Card>

        {quote.isError ? <Banner tone="danger">{messageOf(quote.error)}</Banner> : null}

        {duplicateReference ? (
          <Banner tone="warning">
            {error} Check it is not a double entry, then use “Record anyway”.
          </Banner>
        ) : error ? (
          <Banner tone="danger">{error}</Banner>
        ) : null}

        {mode === 'GIVE' ? (
          <Card>
            <h2 className="mb-4 text-heading text-text">Record the give-away</h2>
            {/* Asked for up front, not raised as a validation error after the fact. The schema
                will not accept a STAFF_COMP without one, and the reason plus the actor are the
                whole control on comps. */}
            <Field
              label="Reason"
              value={compReason}
              placeholder="Who it went to and why"
              hint="Required. It is written onto every line and shown in the audit."
              onChange={(event) => setCompReason(event.target.value)}
            />
            <Button
              className="mt-6 w-full"
              pending={giveAway.isPending}
              disabled={lines.length === 0 || compReason.trim() === ''}
              onClick={() => giveAway.mutate()}
            >
              {lines.length === 0
                ? 'Add something to give away'
                : `Give away ${lines.length} ${lines.length === 1 ? 'product' : 'products'}`}
            </Button>
          </Card>
        ) : (
          <Card>
            <Select
              label="Customer type"
              value={selectedCustomerTypeId}
              onChange={(event) => setCustomerTypeId(event.target.value)}
            >
              {customerTypes.map((type) => (
                <option key={type.id} value={type.id}>
                  {type.name}
                  {type.isDefault ? ' (default)' : ''}
                </option>
              ))}
            </Select>

            <h2 className="mt-6 mb-4 text-heading text-text">Take payment</h2>
            {!ready ? (
              <p className="text-body text-text-dim">
                Add at least one product and choose a customer type.
              </p>
            ) : (
              <PaymentForm
                total={total}
                /* The bill is created and settled inside the one transaction, so there is no
                   earlier version to have read. The server ignores this; it is sent because
                   validation requires the key. */
                billVersion={0}
                idempotencyKey={idempotencyKey.current}
                duplicateOverride={duplicateReference}
                pending={sell.isPending}
                onSubmit={(body, photo) => sell.mutate({ body, photo })}
              />
            )}
          </Card>
        )}

        <Link to="/floor" className="hit inline-flex items-center text-body text-info underline">
          Back to the floor
        </Link>
      </div>
    </div>
  );
}
