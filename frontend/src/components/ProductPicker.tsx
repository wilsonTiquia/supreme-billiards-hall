import { useEffect, useId, useMemo, useRef, useState } from 'react';
import { formatMoney } from '@/lib/money';

/**
 * Pick a product by typing.
 *
 * A <select> is fine at ten products and useless at fifty, which is what Thursday's catalogue
 * will be. This narrows as you type, moves with the arrow keys, commits on Enter, and is still
 * a plain click-a-row list for anyone using the mouse alone.
 *
 * Hand-rolled rather than pulled in: the project takes no new dependencies, and a combobox is
 * a text input, a filtered list and three key handlers.
 */
export interface PickableProduct {
  id: string;
  name: string;
  sellingPrice: number;
  qtyOnHand?: number;
}

export function ProductPicker({
  label,
  products,
  value,
  onChange,
  placeholder = 'Type to find a product…',
}: {
  label: string;
  products: PickableProduct[];
  value: string;
  onChange: (productId: string) => void;
  placeholder?: string;
}) {
  const id = useId();
  const [term, setTerm] = useState('');
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  const box = useRef<HTMLDivElement>(null);

  const chosen = products.find((product) => product.id === value) ?? null;

  const matches = useMemo(() => {
    const needle = term.trim().toLowerCase();
    if (!needle) return products;
    return products.filter((product) => product.name.toLowerCase().includes(needle));
  }, [products, term]);

  // Clicking anywhere else commits what is already chosen and closes the list, so the panel
  // never sits open behind the form.
  useEffect(() => {
    if (!open) return;
    const onDown = (event: MouseEvent) => {
      if (box.current && !box.current.contains(event.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', onDown);
    return () => document.removeEventListener('mousedown', onDown);
  }, [open]);

  function commit(product: PickableProduct) {
    onChange(product.id);
    setTerm('');
    setOpen(false);
  }

  return (
    <div className="flex flex-col gap-2" ref={box}>
      <label htmlFor={id} className="text-label uppercase text-text-dim">
        {label}
      </label>

      <div className="relative">
        <input
          id={id}
          role="combobox"
          aria-expanded={open}
          aria-controls={`${id}-list`}
          autoComplete="off"
          value={open ? term : (chosen?.name ?? '')}
          placeholder={chosen ? chosen.name : placeholder}
          onFocus={() => {
            setTerm('');
            setActive(0);
            setOpen(true);
          }}
          onChange={(event) => {
            setTerm(event.target.value);
            setActive(0);
            setOpen(true);
          }}
          onKeyDown={(event) => {
            if (event.key === 'ArrowDown') {
              event.preventDefault();
              setOpen(true);
              setActive((i) => Math.min(i + 1, matches.length - 1));
            } else if (event.key === 'ArrowUp') {
              event.preventDefault();
              setActive((i) => Math.max(i - 1, 0));
            } else if (event.key === 'Enter') {
              if (open && matches[active]) {
                event.preventDefault();
                commit(matches[active]);
              }
            } else if (event.key === 'Escape') {
              setOpen(false);
            }
          }}
          className="hit w-full rounded-lg border border-border bg-raised px-3 text-body text-text placeholder:text-text-dim/60"
        />

        {open ? (
          <ul
            id={`${id}-list`}
            role="listbox"
            className="absolute z-20 mt-1 max-h-72 w-full overflow-y-auto rounded-lg border border-border bg-raised shadow-2xl"
          >
            {matches.length === 0 ? (
              <li className="px-3 py-3 text-body text-text-dim">Nothing matches “{term}”.</li>
            ) : (
              matches.map((product, index) => (
                <li key={product.id} role="option" aria-selected={index === active}>
                  <button
                    type="button"
                    // mousedown, not click: the input's blur would otherwise close the list
                    // before the click landed.
                    onMouseDown={(event) => {
                      event.preventDefault();
                      commit(product);
                    }}
                    onMouseEnter={() => setActive(index)}
                    className={`hit flex w-full items-center justify-between gap-3 px-3 text-left text-body transition ${
                      index === active ? 'bg-surface text-text' : 'text-text-dim hover:text-text'
                    }`}
                  >
                    <span className="min-w-0 truncate">{product.name}</span>
                    <span className="tabular shrink-0 text-label">
                      {formatMoney(product.sellingPrice)}
                      {product.qtyOnHand !== undefined ? ` · ${product.qtyOnHand} on hand` : ''}
                    </span>
                  </button>
                </li>
              ))
            )}
          </ul>
        ) : null}
      </div>
    </div>
  );
}
