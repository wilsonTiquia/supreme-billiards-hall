import { useState, type FormEvent } from 'react';
import type { ProductAdmin } from '@/api/types';
import { Button } from '@/components/Button';
import { Field } from '@/components/Field';
import { Banner } from '@/components/Banner';

const costFormat = new Intl.NumberFormat('en-PH', {
  style: 'currency', currency: 'PHP', minimumFractionDigits: 2, maximumFractionDigits: 4,
});

/** The catalog shortcut always receives one product. Bulk deliveries keep their own form. */
export function ProductStockForm({ product, pending, onEdit, onSubmit }: {
  product: ProductAdmin;
  pending: boolean;
  onEdit: () => void;
  onSubmit: (quantity: number) => void;
}) {
  const [quantity, setQuantity] = useState('1');
  const cost = product.defaultPurchaseCost;
  const valid = /^(?:\d+)(?:\.\d{1,3})?$/.test(quantity)
    && Number(quantity) >= 0.001 && Number(quantity) <= 999999999.999;

  function step(delta: number) {
    const current = Number(quantity);
    if (!Number.isFinite(current)) return;
    setQuantity(String(Math.min(999999999.999, Math.max(0.001, Math.round((current + delta) * 1000) / 1000))));
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    if (pending || cost == null || !valid) return;
    onSubmit(Number(quantity));
  }

  return <form onSubmit={submit} className="flex flex-col gap-5">
    <p className="text-body text-text-dim">Enter the quantity received for this product.</p>
    <div className="grid grid-cols-[44px_minmax(0,1fr)_44px] items-end gap-3">
      <Button type="button" variant="secondary" aria-label="Decrease quantity" className="px-0"
        disabled={pending || !valid || Number(quantity) <= 0.001} onClick={() => step(-1)}>−</Button>
      <Field label="Quantity" type="number" inputMode="decimal" min="0.001" max="999999999.999"
        step="0.001" required data-autofocus value={quantity} disabled={pending}
        onChange={event => setQuantity(event.target.value)} />
      <Button type="button" variant="secondary" aria-label="Increase quantity" className="px-0"
        disabled={pending || !valid || Number(quantity) >= 999999999.999} onClick={() => step(1)}>+</Button>
    </div>
    <div className="rounded-lg border border-border p-4">
      <p className="text-label uppercase text-text-dim">Default purchase cost per unit</p>
      <p className="mt-1 text-heading tabular text-text">{cost == null ? 'Not set' : costFormat.format(cost)}</p>
      <p className="mt-2 text-label text-text-dim">{cost === 0 ? 'Free stock — explicitly saved by an admin.' : 'Used for this delivery. Change it in Edit product.'}</p>
      <Button type="button" variant="tertiary" className="mt-2" disabled={pending} onClick={onEdit}>Edit product</Button>
    </div>
    {cost == null ? <Banner tone="warning">Set a default purchase cost in Edit product before adding stock. Leave it blank if the cost is unknown.</Banner> : null}
    <Button type="submit" pending={pending} disabled={cost == null || !valid}>Record delivery</Button>
  </form>;
}
