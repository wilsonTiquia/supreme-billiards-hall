import { useState, type FormEvent } from 'react';
import { useMutation } from '@tanstack/react-query';
import { recordDelivery } from '@/api/endpoints/stock';
import { messageOf } from '@/api/errors';
import type { ProductAdmin, StockDeliveryRequest } from '@/api/types';
import { Button } from '@/components/Button';
import { Field } from '@/components/Field';
import { ProductPicker } from '@/components/ProductPicker';

export function DeliveryForm({
  products,
  initialProductId = '',
  onError,
  onDone,
}: {
  products: ProductAdmin[];
  initialProductId?: string;
  onError: (message: string) => void;
  onDone: () => void;
}) {
  const [lines, setLines] = useState<{ productId: string; quantity: string; unitCost: string }[]>([
    { productId: initialProductId, quantity: '', unitCost: '' },
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
    if (deliver.isPending || usable.length === 0) return;
    if (lines.some((line) => (line.quantity !== '' || line.unitCost !== '') && !line.productId)) {
      onError('Choose a product for every delivery line.');
      return;
    }
    deliver.mutate({
      lines: usable.map((line) => ({
        productId: line.productId,
        quantity: Number(line.quantity),
        unitCost: Number(line.unitCost),
      })),
    });
  }

  return (
    <>
      <p className="mt-1 text-body text-text-dim">
        Enter the quantity received and the cost of one unit for each product.
      </p>

      <form onSubmit={handleSubmit} className="mt-4 flex flex-col gap-4">
        <div className="flex max-h-[45dvh] flex-col gap-4 overflow-y-auto overscroll-contain pr-2 [scrollbar-gutter:stable]" role="region" aria-label="Delivery lines" tabIndex={0}>
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
                  data-autofocus={initialProductId ? true : undefined}
                  type="number"
                  step="0.001"
                  min="0.001"
                  required={line.productId !== '' || line.unitCost !== ''}
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
                  step="0.0001"
                  min="0"
                  required={line.productId !== '' || line.quantity !== ''}
                  value={line.unitCost}
                  onChange={(event) =>
                    setLines((current) =>
                      current.map((l, i) => (i === index ? { ...l, unitCost: event.target.value } : l)),
                    )
                  }
                />
              </div>
              {lines.length > 1 ? (
                <Button type="button" variant="secondary" className="mt-2" aria-label={`Remove line ${index + 1}`}
                  onClick={() => setLines((current) => current.filter((_, i) => i !== index))}>
                  Remove line
                </Button>
              ) : null}
            </div>
          ))}
        </div>

        <div className="flex flex-wrap justify-between gap-3">
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
    </>
  );
}

