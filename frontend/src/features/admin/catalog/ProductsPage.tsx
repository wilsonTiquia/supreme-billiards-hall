import { useMemo, useRef, useState, type ChangeEvent, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  archiveProduct,
  createProduct,
  deleteProductImage,
  fetchProductsAdmin,
  unarchiveProduct,
  updateProduct,
  uploadProductImage,
} from '@/api/endpoints/products';
import { fetchCategories } from '@/api/endpoints/categories';
import { queryKeys } from '@/api/queryKeys';
import { messageOf } from '@/api/errors';
import type { ProductAdmin, ProductRequest } from '@/api/types';
import { AdminPage } from '../AdminPage';
import { Card } from '@/components/Card';
import { Button } from '@/components/Button';
import { Field } from '@/components/Field';
import { Select } from '@/components/Select';
import { Modal } from '@/components/Modal';
import { ProductImage } from '@/components/ProductImage';
import { DeliveryForm } from '../stock/DeliveryForm';
import { useToast } from '@/components/Toast';
import { Banner } from '@/components/Banner';
import { Spinner } from '@/components/Spinner';
import { formatMoney } from '@/lib/money';
import { formatDateTime } from '@/lib/datetime';

/** Selling price less average cost, as a percentage of the selling price. */
function margin(product: ProductAdmin): string {
  if (product.sellingPrice <= 0) return '—';
  return `${(((product.sellingPrice - product.avgCost) / product.sellingPrice) * 100).toFixed(1)}%`;
}

export function ProductsPage() {
  const queryClient = useQueryClient();
  const { notify, dismiss } = useToast();
  const [stocking, setStocking] = useState<ProductAdmin | null>(null);
  const [deliveryError, setDeliveryError] = useState<string | null>(null);
  const [editing, setEditing] = useState<ProductAdmin | null>(null);
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [includeArchived, setIncludeArchived] = useState(false);
  const [term, setTerm] = useState('');
  const [confirmingArchive, setConfirmingArchive] = useState<ProductAdmin | null>(null);

  const products = useQuery({
    queryKey: queryKeys.products({ activeOnly: false, includeArchived }),
    queryFn: () => fetchProductsAdmin({ activeOnly: false, includeArchived }),
  });

  const categories = useQuery({ queryKey: queryKeys.categories, queryFn: fetchCategories });

  function refresh() {
    void queryClient.invalidateQueries({ queryKey: ['products'] });
    void queryClient.invalidateQueries({ queryKey: queryKeys.lowStock });
  }

  const save = useMutation({
    mutationFn: ({ id, body }: { id: string | null; body: ProductRequest }) =>
      id ? updateProduct(id, body) : createProduct(body),
    onSuccess: (_product, { id, body }) => {
      notify(id ? 'Product saved.' : body.openingStock ? 'Product created with opening stock.' : 'Product created.');
      setError(null);
      setEditing(null);
      setCreating(false);
      refresh();
    },
    onError: (caught) => setError(messageOf(caught)),
  });

  const archive = useMutation({
    mutationFn: (id: string) => archiveProduct(id),
    onSuccess: () => {
      setError(null);
      setConfirmingArchive(null);
      refresh();
    },
    onError: (caught) => {
      setConfirmingArchive(null);
      setError(messageOf(caught));
    },
  });

  const restore = useMutation({
    mutationFn: (id: string) => unarchiveProduct(id),
    onSuccess: () => {
      setError(null);
      refresh();
    },
    // A 409 here means the name was reused while this was archived; the message names the fix.
    onError: (caught) => setError(messageOf(caught)),
  });

  /* Filtered here rather than by refetching per keystroke: the catalogue is small enough that
     a local filter answers instantly, and the POS picker already works this way. */
  const rows = useMemo(() => {
    const all = products.data ?? [];
    const needle = term.trim().toLowerCase();
    return needle ? all.filter((product) => product.name.toLowerCase().includes(needle)) : all;
  }, [products.data, term]);

  return (
    <AdminPage
      title="Products"
      intro="Manage your menu, prices and stock on hand."
      error={error ?? (products.isError ? messageOf(products.error) : null)}
    >
      <div className="mb-4 flex flex-wrap items-center gap-3">
        <input
          value={term}
          placeholder="Search products…"
          aria-label="Search products"
          onChange={(event) => setTerm(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === 'Escape') setTerm('');
          }}
          className="hit w-full max-w-sm rounded-lg border border-border bg-raised px-3 text-body text-text placeholder:text-text-dim/60"
        />
        {/* Archiving used to put a product beyond reach of this screen entirely. This is how
            a misclick is found again. */}
        <label className="hit flex cursor-pointer items-center gap-3 text-body text-text">
          <input
            type="checkbox"
            checked={includeArchived}
            onChange={(event) => setIncludeArchived(event.target.checked)}
            className="size-6"
          />
          Include archived
        </label>
        <Button className="ml-auto" onClick={() => { setError(null); setCreating(true); }}>
          New product
        </Button>
      </div>

      <Card className="overflow-x-auto">
        {products.isPending ? (
          <div className="py-10 text-center">
            <Spinner label="Loading products…" />
          </div>
        ) : (
          <table className="block w-full text-left md:table">
            <thead className="hidden md:table-header-group">
              <tr className="border-b border-border text-label uppercase text-text-dim">
                <th className="py-2 w-14"><span className="sr-only">Image</span></th>
                <th className="py-2">Name</th>
                <th className="py-2 pl-3 text-right">Price</th>
                <th className="py-2 pl-3 text-right">Avg cost</th>
                <th className="py-2 pl-3 text-right">Margin</th>
                <th className="py-2 pl-3 text-right">On hand</th>
                <th className="py-2" />
              </tr>
            </thead>
            <tbody className="block md:table-row-group">
              {rows.map((product) => (
                <tr
                  key={product.id}
                  // Dimmed and struck rather than hidden: it has to be recognisable at a
                  // glance as a row that is not on the counter.
                  className={`grid grid-cols-2 gap-x-4 border-b border-border py-4 md:table-row md:py-0 ${product.archivedAt ? 'opacity-55' : ''}`}
                >
                  <td className="hidden py-3 pr-3 md:table-cell">
                    <ProductImage
                      productId={product.id}
                      name={product.name}
                      imageSha256={product.imageSha256}
                      size="thumb"
                    />
                  </td>
                  <td className="col-span-2 py-3 text-heading text-text md:pr-3 md:text-body">
                    <span className={product.archivedAt ? 'line-through' : ''}>{product.name}</span>
                    {product.archivedAt ? (
                      <span className="ml-2 text-label uppercase text-danger">Archived</span>
                    ) : !product.isActive ? (
                      <span className="ml-2 text-label uppercase text-text-dim">Inactive</span>
                    ) : null}
                    {product.archivedAt ? (
                      <div className="text-label text-text-dim">
                        Archived {formatDateTime(product.archivedAt)}
                      </div>
                    ) : null}
                  </td>
                  <td className="tabular py-3 text-body text-amount md:text-right">
                    <span className="block text-label text-text-dim md:hidden">Price</span>
                    {formatMoney(product.sellingPrice)}
                  </td>
                  <td className="tabular py-3 text-body text-text-dim md:pl-3 md:text-right">
                    <span className="block text-label text-text-dim md:hidden">Avg cost</span>
                    {formatMoney(product.avgCost)}
                  </td>
                  <td className="tabular py-3 text-body text-text md:pl-3 md:text-right"><span className="block text-label text-text-dim md:hidden">Margin</span>{margin(product)}</td>
                  <td
                    className={`tabular py-3 text-body md:pl-3 md:text-right ${product.qtyOnHand <= 0 ? 'text-danger' : 'text-text'}`}
                  >
                    <span className="block text-label text-text-dim md:hidden">On hand</span>
                    {product.qtyOnHand}
                  </td>
                  <td className="col-span-2 py-3 md:pl-4 md:text-right">
                    <div className="flex flex-wrap gap-2 md:justify-end">
                      <Button variant="secondary" onClick={() => { dismiss(); setDeliveryError(null); setStocking(product); }}>
                        Add stock
                      </Button>
                      {product.archivedAt ? (
                        <Button
                          variant="secondary"
                          pending={restore.isPending && restore.variables === product.id}
                          onClick={() => restore.mutate(product.id)}
                        >
                          Restore
                        </Button>
                      ) : (
                        <>
                          <Button variant="secondary" onClick={() => { dismiss(); setError(null); setEditing(product); }}>
                            Edit
                          </Button>
                          {/* Confirmed, because this sits one click from Edit and the row
                              then leaves the default view. */}
                          <Button
                            variant="secondary"
                            onClick={() => setConfirmingArchive(product)}
                          >
                            Archive
                          </Button>
                        </>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>

      {stocking ? (
        <Modal title={`Add stock · ${stocking.name}`} onClose={() => setStocking(null)}>
          {deliveryError ? <div className="mb-4"><Banner tone="danger">{deliveryError}</Banner></div> : null}
          <DeliveryForm
            products={products.data ?? []}
            initialProductId={stocking.id}
            onError={(message) => { dismiss(); setDeliveryError(message); }}
            onDone={() => { setStocking(null); refresh(); notify('Delivery recorded. Average costs have been recomputed.'); }}
          />
        </Modal>
      ) : null}

      {confirmingArchive ? (
        <Modal
          title={`Archive ${confirmingArchive.name}?`}
          onClose={() => setConfirmingArchive(null)}
        >
          <p className="text-body text-text">
            <strong>{confirmingArchive.name}</strong> comes off the counter immediately and can
            no longer be added to a bill.
          </p>
          <p className="mt-3 text-body text-text-dim">
            Nothing is deleted — past bills keep it, and its picture is kept too. Tick
            “Include archived” on this screen to find it again and restore it.
          </p>
          <div className="mt-6 flex justify-end gap-3">
            <Button
              type="button"
              variant="secondary"
              data-autofocus
              onClick={() => setConfirmingArchive(null)}
            >
              Cancel
            </Button>
            <Button
              variant="danger"
              pending={archive.isPending}
              onClick={() => archive.mutate(confirmingArchive.id)}
            >
              Archive it
            </Button>
          </div>
        </Modal>
      ) : null}

      {creating || editing ? (
        <ProductForm
          product={editing}
          categories={categories.data ?? []}
          pending={save.isPending}
          error={error}
          onClose={() => {
            setEditing(null);
            setCreating(false);
          }}
          onSave={(body) => save.mutate({ id: editing?.id ?? null, body })}
          onImageChanged={refresh}
        />
      ) : null}
    </AdminPage>
  );
}

function ProductForm({
  product,
  categories,
  pending,
  error,
  onClose,
  onSave,
  onImageChanged,
}: {
  product: ProductAdmin | null;
  categories: { id: string; name: string }[];
  pending: boolean;
  error: string | null;
  onClose: () => void;
  onSave: (body: ProductRequest) => void;
  onImageChanged: () => void;
}) {
  const [name, setName] = useState(product?.name ?? '');
  const [categoryId, setCategoryId] = useState(product?.categoryId ?? '');
  const [sellingPrice, setSellingPrice] = useState(
    product ? String(product.sellingPrice) : '',
  );
  const [openingQuantity, setOpeningQuantity] = useState('');
  const [openingCost, setOpeningCost] = useState('');
  const hasOpeningStock = openingQuantity !== '' || openingCost !== '';
  const [isActive, setIsActive] = useState(product?.isActive ?? true);

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    if (name.trim() === '' || sellingPrice.trim() === '') return;
    if (pending) return;
    // Opening stock is a delivery recorded by the server in the same transaction.
    onSave({
      name: name.trim(),
      categoryId: categoryId === '' ? undefined : categoryId,
      sellingPrice: Number(sellingPrice),
      isActive,
      ...(!product && hasOpeningStock ? {
        openingStock: { quantity: Number(openingQuantity), unitCost: Number(openingCost) },
      } : {}),
    });
  }

  return (
    <Modal title={product ? `Edit ${product.name}` : 'New product'} onClose={onClose}>
      <form onSubmit={handleSubmit} className="flex flex-col gap-3">
        {error ? <Banner tone="danger">{error}</Banner> : null}
        <Field
          label="Name"
          required
          maxLength={100}
          value={name}
          data-autofocus
          onChange={(event) => setName(event.target.value)}
        />
        {/* The upload needs an id to attach to, so it appears once the row exists. Nothing
            is lost by that: a product without a picture is a normal product. */}
        {product ? (
          <ProductImageField product={product} onChanged={onImageChanged} />
        ) : null}
        <Select
          label="Category"
          value={categoryId}
          onChange={(event) => setCategoryId(event.target.value)}
        >
          <option value="">No category</option>
          {categories.map((category) => (
            <option key={category.id} value={category.id}>
              {category.name}
            </option>
          ))}
        </Select>
        <Field
          label="Selling price"
          required
          type="number"
          step="0.01"
          min="0"
          inputMode="decimal"
          value={sellingPrice}
          onChange={(event) => setSellingPrice(event.target.value)}
        />
        {!product ? (
          <fieldset className="rounded-lg border border-border p-4">
            <legend className="px-1 text-body font-semibold text-text">Opening stock</legend>
            <p className="mb-4 text-label text-text-dim">Already on the shelf? Enter both fields, or leave both empty.</p>
            <div className="grid grid-cols-2 gap-3">
              <Field label="Quantity" type="number" min="0.001" max="999999999.999" step="0.001"
                inputMode="decimal" required={hasOpeningStock} value={openingQuantity}
                onChange={(event) => setOpeningQuantity(event.target.value)} />
              <Field label="Unit cost" type="number" min="0" max="99999999.9999" step="0.0001"
                inputMode="decimal" required={hasOpeningStock} value={openingCost}
                onChange={(event) => setOpeningCost(event.target.value)} />
            </div>
          </fieldset>
        ) : null}
        <label className="hit flex cursor-pointer items-center gap-3 text-body text-text">
          <input
            type="checkbox"
            checked={isActive}
            onChange={(event) => setIsActive(event.target.checked)}
            className="size-6"
          />
          Available at the counter
        </label>
        <div className="flex justify-end gap-3">
          <Button type="button" variant="secondary" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" pending={pending}>
            Save
          </Button>
        </div>
      </form>
    </Modal>
  );
}

/**
 * Upload, preview, replace, remove. The picture is what the counter looks for on the POS
 * grid, so this sits directly under the name rather than at the bottom of the form.
 *
 * It saves on its own, not with the form: the route is multipart and separate, and an
 * operator who picks a file expects it to be there whether or not they then press Save.
 */
function ProductImageField({
  product,
  onChanged,
}: {
  product: ProductAdmin;
  onChanged: () => void;
}) {
  const [imageSha256, setImageSha256] = useState(product.imageSha256);
  const [error, setError] = useState<string | null>(null);
  const picker = useRef<HTMLInputElement>(null);

  const upload = useMutation({
    mutationFn: (file: File) => uploadProductImage(product.id, file),
    onSuccess: (image) => {
      setError(null);
      setImageSha256(image.imageSha256);
      onChanged();
    },
    // The server names the real limit and the accepted formats, so show what it said.
    onError: (caught) => setError(messageOf(caught)),
  });

  const remove = useMutation({
    mutationFn: () => deleteProductImage(product.id),
    onSuccess: () => {
      setError(null);
      setImageSha256(null);
      onChanged();
    },
    onError: (caught) => setError(messageOf(caught)),
  });

  function handlePick(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    // Cleared so picking the same file twice after a rejection still fires a change.
    event.target.value = '';
    if (file) upload.mutate(file);
  }

  const busy = upload.isPending || remove.isPending;

  return (
    <div>
      <p className="text-label uppercase text-text-dim">Picture</p>
      <div className="mt-2 flex items-center gap-4">
        <ProductImage
          productId={product.id}
          name={product.name}
          imageSha256={imageSha256}
          size="preview"
        />
        <div className="flex flex-col items-start gap-2">
          <input
            ref={picker}
            type="file"
            accept="image/jpeg,image/png,image/webp"
            onChange={handlePick}
            className="sr-only"
          />
          <Button
            type="button"
            variant="secondary"
            pending={upload.isPending}
            disabled={busy}
            onClick={() => picker.current?.click()}
          >
            {imageSha256 ? 'Replace picture' : 'Add picture'}
          </Button>
          {imageSha256 ? (
            <Button
              type="button"
              variant="secondary"
              pending={remove.isPending}
              disabled={busy}
              onClick={() => remove.mutate()}
            >
              Remove picture
            </Button>
          ) : null}
          <p className="text-label text-text-dim">JPEG, PNG or WebP, up to 2 MB.</p>
        </div>
      </div>
      {error ? <p className="mt-2 text-body text-danger">{error}</p> : null}
    </div>
  );
}
