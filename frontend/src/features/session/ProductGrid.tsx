import { useEffect, useMemo, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { fetchProducts } from '@/api/endpoints/products';
import { fetchCategories } from '@/api/endpoints/categories';
import { queryKeys } from '@/api/queryKeys';
import type { Product } from '@/api/types';
import { formatMoney } from '@/lib/money';
import { ProductImage } from '@/components/ProductImage';
import { Spinner } from '@/components/Spinner';

/**
 * Type-ahead first. The counter works under time pressure with a keyboard, so the search box
 * takes focus on mount and Enter adds the top match without the mouse ever being touched.
 *
 * Filtering is done here rather than by refetching per keystroke: the catalogue is small and
 * a local filter answers instantly, where a round trip per character would not.
 */
export function ProductGrid({
  onAdd,
  pendingProductId,
  compact = false,
}: {
  onAdd: (product: Product) => void;
  pendingProductId: string | null;
  compact?: boolean;
}) {
  const [term, setTerm] = useState('');
  // null is "All", and it is where the screen starts and returns to.
  const [categoryId, setCategoryId] = useState<string | null>(null);
  const search = useRef<HTMLInputElement>(null);

  const { data: products = [], isPending } = useQuery({
    queryKey: queryKeys.products({ activeOnly: true }),
    queryFn: () => fetchProducts({ activeOnly: true }),
    staleTime: 60_000,
  });

  const { data: categories = [] } = useQuery({
    queryKey: queryKeys.categories,
    queryFn: fetchCategories,
    staleTime: 5 * 60_000,
  });

  useEffect(() => {
    search.current?.focus();
  }, []);

  /* The two compose: the category narrows the shelf, the search narrows what is on it. Typing
     inside Beer searches Beer, which is what someone reaching for a category expects. */
  const matches = useMemo(() => {
    const needle = term.trim().toLowerCase();
    return products.filter(
      (product) =>
        (categoryId === null || product.categoryId === categoryId) &&
        (needle === '' || product.name.toLowerCase().includes(needle)),
    );
  }, [products, term, categoryId]);

  // Only categories that actually have something in them: an empty shelf is a button that
  // does nothing, and the counter would still have to press it to find that out.
  const shelves = useMemo(
    () => categories.filter((category) => products.some((p) => p.categoryId === category.id)),
    [categories, products],
  );

  return (
    <div className="flex h-full min-h-0 flex-col">
      <input
        ref={search}
        value={term}
        placeholder="Search products…"
        aria-label="Search products"
        onChange={(event) => setTerm(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === 'Enter' && matches.length > 0) {
            event.preventDefault();
            onAdd(matches[0]);
            setTerm('');
          }
          if (event.key === 'Escape') setTerm('');
        }}
        className="min-h-[3.25rem] w-full shrink-0 rounded-lg border border-border bg-raised px-4 text-body text-text placeholder:text-text-dim/60"
      />

      {/* Big enough to hit without looking. Changing shelf deliberately does NOT move focus:
          the search box keeps it, so the counter can filter and keep typing in one motion. */}
      {shelves.length > 0 ? (
        <div className="mt-3 flex shrink-0 flex-wrap gap-2">
          {[{ id: null, name: 'All' }, ...shelves].map((shelf) => {
            const active = categoryId === shelf.id;
            return (
              <button
                key={shelf.id ?? 'all'}
                type="button"
                aria-pressed={active}
                onClick={() => setCategoryId(shelf.id)}
                className={`hit rounded-lg border px-4 text-body font-semibold transition ${
                  active
                    ? 'border-green bg-green text-ink'
                    : 'border-border bg-raised text-text-dim hover:border-text-dim hover:text-text'
                }`}
              >
                {shelf.name}
              </button>
            );
          })}
        </div>
      ) : null}

      {isPending ? (
        <div className="py-10 text-center">
          <Spinner label="Loading products…" />
        </div>
      ) : matches.length === 0 ? (
        <p className="py-10 text-center text-body text-text-dim">
          {term
            ? `Nothing in this shelf matches “${term}”.`
            : 'Nothing in this category yet.'}
        </p>
      ) : (
        <div className={`mt-4 grid min-h-0 flex-1 auto-rows-min gap-4 overflow-y-auto ${compact ? 'grid-cols-[repeat(auto-fill,minmax(min(100%,160px),1fr))]' : 'grid-cols-[repeat(auto-fill,minmax(220px,1fr))]'}`}>
          {matches.map((product) => (
            <button
              key={product.id}
              type="button"
              disabled={pendingProductId === product.id}
              onClick={() => onAdd(product)}
              className="flex flex-col items-stretch rounded-xl border border-border bg-surface p-4 text-left transition hover:border-green hover:brightness-110 disabled:opacity-60"
            >
              {/* The picture is why this grid exists: it is what the eye lands on first. */}
              <ProductImage
                productId={product.id}
                name={product.name}
                imageSha256={product.imageSha256}
                size="tile"
                className="mb-3"
              />
              <span className="text-body text-text">{product.name}</span>
              <span className="tabular mt-1 text-heading text-amount">
                {formatMoney(product.sellingPrice)}
              </span>
              {/* A negative or thin count is worth seeing before it is sold, but never blocks. */}
              <span
                className={`text-label ${product.qtyOnHand <= 0 ? 'text-danger' : 'text-text-dim'}`}
              >
                {/* "left", not "on hand": the counter is deciding whether to ring something
                    up, and that is the question they are asking. The admin stock screens keep
                    "on hand" — different reader, different register, read at leisure. */}
                {product.qtyOnHand} left
              </span>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
