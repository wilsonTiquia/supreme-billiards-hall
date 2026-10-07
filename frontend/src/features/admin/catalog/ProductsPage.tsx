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
import { fetchSetup } from '@/api/endpoints/setup';
import { ActionMenu } from '@/components/ActionMenu';
import { NewProductImageField } from './NewProductImageField';
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
import { ProductStockForm } from '../stock/ProductStockForm';
import { recordProductStock } from '@/api/endpoints/stock';
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
  const deliveryInFlight = useRef(false);
  const [stocking, setStocking] = useState<ProductAdmin | null>(null);
  const [deliveryError, setDeliveryError] = useState<string | null>(null);
  const [editing, setEditing] = useState<ProductAdmin | null>(null);
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [includeArchived, setIncludeArchived] = useState(false);
  const [term, setTerm] = useState('');
  const [categoryFilter, setCategoryFilter] = useState('all');
  const [imageTask, setImageTask] = useState<{ product: ProductAdmin; file: File } | null>(null);
  const [showImageTask, setShowImageTask] = useState(false);
  const [confirmingArchive, setConfirmingArchive] = useState<ProductAdmin | null>(null);

  const products = useQuery({
    queryKey: queryKeys.products({ activeOnly: false, includeArchived }),
    queryFn: () => fetchProductsAdmin({ activeOnly: false, includeArchived }),
  });

  const categories = useQuery({ queryKey: queryKeys.categories, queryFn: fetchCategories });

  // The ordinary category picker excludes archived categories; existing products keep their links.
  const categorySetup = useQuery({ queryKey: queryKeys.setup('categories'), queryFn: () => fetchSetup('categories') });
  const catalogCategories = useMemo(() => [
    ...(categories.data ?? []),
    ...(categorySetup.data ?? []).filter(c => !categories.data?.some(active => active.id === c.id)),
  ], [categories.data, categorySetup.data]);

  function refresh() {
    void queryClient.invalidateQueries({ queryKey: ['products'] });
    void queryClient.invalidateQueries({ queryKey: queryKeys.lowStock });
  }

  const delivery = useMutation({
    mutationFn: recordProductStock,
    onSuccess: () => {
      setStocking(null);
      refresh();
      notify('Delivery recorded. Average cost has been recomputed.');
    },
    onError: caught => { setDeliveryError(messageOf(caught)); refresh(); },
    onSettled: () => { deliveryInFlight.current = false; },
  });

  const imageUpload = useMutation({
    mutationFn: ({ product, file }: { product: ProductAdmin; file: File }) => uploadProductImage(product.id, file),
    onSuccess: () => {
      setImageTask(null);
      setShowImageTask(false);
      notify('Product picture saved.');
      refresh();
    },
  });

  const save = useMutation({
    mutationFn: ({ id, body }: { id: string | null; body: ProductRequest; file: File | null }) =>
      id ? updateProduct(id, body) : createProduct(body),
    onSuccess: (product, { id, body, file }) => {
      notify(id ? 'Product saved.' : body.openingStock ? 'Product created with opening stock.' : 'Product created.');
      setError(null);
      setEditing(null);
      setCreating(false);
      refresh();
      if (!id && file) {
        const task = { product, file };
        setImageTask(task);
        setShowImageTask(true);
        imageUpload.mutate(task);
      }
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
    return all.filter(product => (!needle || product.name.toLowerCase().includes(needle)) &&
      (categoryFilter === 'all' || (product.categoryId ?? 'uncategorized') === categoryFilter));
  }, [products.data, term, categoryFilter]);
  const groups = useMemo(() => {
    const grouped = new Map<string, ProductAdmin[]>();
    for (const product of rows) {
      const id = product.categoryId ?? 'uncategorized';
      const group = grouped.get(id);
      if (group) group.push(product);
      else grouped.set(id, [product]);
    }
    const order = [...catalogCategories.map(c => c.id), ...grouped.keys()];
    return [...new Set(order)].filter(id => grouped.has(id) && id !== 'uncategorized')
      .concat(grouped.has('uncategorized') ? ['uncategorized'] : [])
      .map(id => ({ id, name: id === 'uncategorized' ? 'Uncategorized' :
        catalogCategories.find(c => c.id === id)?.name ?? 'Category unavailable', products: grouped.get(id)! }));
  }, [rows, catalogCategories]);

  return (
    <AdminPage
      title="Products"
      intro="Manage your menu, prices and stock on hand."
      error={error ?? (!stocking ? deliveryError : null) ?? (products.isError ? messageOf(products.error) : null)}
    >
      {delivery.isPending && !stocking ? <div className="mb-4"><Banner>Recording delivery…</Banner></div> : null}
      {imageTask && !showImageTask ? <div className="mb-4">
        <Banner tone={imageUpload.isError ? 'warning' : 'info'} actions={
          <Button variant="secondary" onClick={() => setShowImageTask(true)}>Review picture</Button>
        }>
          {imageTask.product.name} is saved. {imageUpload.isPending ? 'Uploading picture…' : 'Its picture still needs uploading.'}
        </Banner>
      </div> : null}
      {categories.isError || categorySetup.isError ? <div className="mb-4"><Banner tone="warning">
        Some category names could not be loaded. Products are still shown.
      </Banner></div> : null}
      <div className="mb-4 flex flex-wrap items-end gap-3">
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
        <div className="w-full min-w-0 sm:w-auto sm:max-w-xs">
          <Select label="Filter by category" value={categoryFilter} onChange={event => setCategoryFilter(event.target.value)}>
            <option value="all">All categories</option>
            {catalogCategories.map(category => <option key={category.id} value={category.id}>{category.name}</option>)}
            <option value="uncategorized">Uncategorized</option>
          </Select>
        </div>
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
        <Button className="ml-auto" disabled={save.isPending || Boolean(imageTask)} onClick={() => { setError(null); setCreating(true); }}>
          New product
        </Button>
      </div>

      <div className="space-y-5">
        {products.isPending ? (
          <div className="py-10 text-center">
            <Spinner label="Loading products…" />
          </div>
        ) : rows.length === 0 ? (
          <Card><p className="text-body text-text-dim">{products.isError ? 'Products could not be loaded.' : term || categoryFilter !== 'all' ? 'No products match these filters.' : 'No products yet. Create your first product to get started.'}</p></Card>
        ) : groups.map(group => (
          <section key={group.id} aria-labelledby={`category-${group.id}`}>
            <Card>
              <div className="mb-4 flex items-baseline gap-3">
                <h2 id={`category-${group.id}`} className="min-w-0 break-words text-heading text-text">{group.name}</h2>
                <span className="shrink-0 text-label text-text-dim">{group.products.length} {group.products.length === 1 ? 'product' : 'products'}</span>
              </div>
              <table className="block w-full text-left xl:table xl:table-fixed">
                <caption className="sr-only">{group.name} products</caption>
                <thead className="hidden xl:table-header-group">
                  <tr className="border-b border-border text-label uppercase text-text-dim">
                    <th className="py-2 w-14"><span className="sr-only">Image</span></th>
                    <th className="py-2">Name</th>
                    <th className="w-24 py-2 pl-3 text-right">Price</th>
                    <th className="w-24 py-2 pl-3 text-right">Avg cost</th>
                    <th className="w-24 py-2 pl-3 text-right">Margin</th>
                    <th className="w-24 py-2 pl-3 text-right">On hand</th>
                    <th className="w-52 py-2"><span className="sr-only">Actions</span></th>
                  </tr>
                </thead>
                <tbody className="block xl:table-row-group">
                  {group.products.map((product) => (
                    <tr
                      key={product.id}
                      // Keep archived rows readable; the label and strike-through carry their status.
                      className="grid grid-cols-2 gap-x-4 border-b border-border py-4 xl:table-row xl:py-0"
                    >
                      <td className="hidden py-3 pr-3 xl:table-cell">
                        <ProductImage
                          productId={product.id}
                          name={product.name}
                          imageSha256={product.imageSha256}
                          size="thumb"
                        />
                      </td>
                      <td className="col-span-2 break-words py-3 text-heading text-text xl:pr-3 xl:text-body">
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
                      <td className="tabular py-3 text-body text-amount xl:text-right">
                        <span className="block text-label text-text-dim xl:hidden">Price</span>
                        {formatMoney(product.sellingPrice)}
                      </td>
                      <td className="tabular py-3 text-body text-text-dim xl:pl-3 xl:text-right">
                        <span className="block text-label text-text-dim xl:hidden">Avg cost</span>
                        {formatMoney(product.avgCost)}
                      </td>
                      <td className="tabular py-3 text-body text-text xl:pl-3 xl:text-right"><span className="block text-label text-text-dim xl:hidden">Margin</span>{margin(product)}</td>
                      <td
                        className={`tabular py-3 text-body xl:pl-3 xl:text-right ${product.qtyOnHand <= 0 ? 'text-danger' : 'text-text'}`}
                      >
                        <span className="block text-label text-text-dim xl:hidden">On hand</span>
                        {product.qtyOnHand}
                      </td>
                      <td className="col-span-2 py-3 xl:pl-4 xl:text-right">
                        <div className="flex flex-wrap gap-2 xl:justify-end">
                          <Button variant="secondary" disabled={delivery.isPending} onClick={() => { if (deliveryInFlight.current) return; dismiss(); setDeliveryError(null); setStocking(product); }}>
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
                            <ActionMenu label={`Actions for ${product.name}`} disabled={save.isPending} items={[
                              { id: 'edit', label: 'Edit', onSelect: () => { dismiss(); setError(null); setEditing(product); } },
                              { id: 'archive', label: 'Archive', danger: true, onSelect: () => setConfirmingArchive(product) },
                            ]} />
                          )}
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </Card>
          </section>
        ))}
      </div>

      {stocking ? (
        <Modal title={`Add stock · ${stocking.name}`} onClose={() => setStocking(null)}>
          {deliveryError ? <div className="mb-4"><Banner tone="danger">{deliveryError}</Banner></div> : null}
          <ProductStockForm
            product={stocking}
            pending={delivery.isPending}
            onEdit={() => { setStocking(null); setError(null); setEditing(stocking); }}
            onSubmit={quantity => {
              if (deliveryInFlight.current || stocking.defaultPurchaseCost == null) return;
              deliveryInFlight.current = true;
              setDeliveryError(null);
              delivery.mutate({ productId: stocking.id, quantity, expectedDefaultPurchaseCost: stocking.defaultPurchaseCost });
            }}
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

      {imageTask && showImageTask ? (
        <Modal title={`Picture for ${imageTask.product.name}`} onClose={() => setShowImageTask(false)}>
          <p tabIndex={-1} data-autofocus className="mb-4 text-body text-text">Product saved, including any opening stock. Only the picture is being uploaded.</p>
          <p className="mb-4 break-all text-label text-text-dim">{imageTask.file.name}</p>
          {imageUpload.isError ? <Banner tone="danger">Picture upload failed: {messageOf(imageUpload.error)} Retry uploads to the saved product.</Banner> : null}
          {imageUpload.isPending ? <Spinner label="Uploading picture…" /> : <div className="mt-6 flex flex-wrap justify-end gap-3">
            <Button variant="secondary" onClick={() => { setImageTask(null); setShowImageTask(false); }}>Finish without picture</Button>
            <Button data-autofocus onClick={() => imageUpload.mutate(imageTask)}>Retry picture upload</Button>
          </div>}
          <p className="mt-4 text-label text-text-dim">You can also add or replace the picture later from Edit.</p>
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
          onSave={(body, file) => save.mutate({ id: editing?.id ?? null, body, file })}
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
  onSave: (body: ProductRequest, file: File | null) => void;
  onImageChanged: () => void;
}) {
  const [file, setFile] = useState<File | null>(null);
  const [imageInvalid, setImageInvalid] = useState(false);
  const [name, setName] = useState(product?.name ?? '');
  const [categoryId, setCategoryId] = useState(product?.categoryId ?? '');
  const [sellingPrice, setSellingPrice] = useState(
    product ? String(product.sellingPrice) : '',
  );
  const [defaultCost, setDefaultCost] = useState(product?.defaultPurchaseCost == null ? '' : String(product.defaultPurchaseCost));
  const [confirmZero, setConfirmZero] = useState(false);
  const choosingZero = defaultCost.trim() !== '' && Number(defaultCost) === 0;
  const [openingQuantity, setOpeningQuantity] = useState('');
  const [openingCost, setOpeningCost] = useState('');
  const hasOpeningStock = openingQuantity !== '' || openingCost !== '';
  const [isActive, setIsActive] = useState(product?.isActive ?? true);

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    if (name.trim() === '' || sellingPrice.trim() === '') return;
    if (pending || imageInvalid || (choosingZero && !confirmZero)) return;
    // Opening stock is a delivery recorded by the server in the same transaction.
    onSave({
      name: name.trim(),
      categoryId: categoryId === '' ? undefined : categoryId,
      sellingPrice: Number(sellingPrice),
      defaultPurchaseCost: defaultCost.trim() === '' ? null : Number(defaultCost),
      confirmZeroDefaultCost: choosingZero && confirmZero,
      isActive,
      ...(!product && hasOpeningStock ? {
        openingStock: { quantity: Number(openingQuantity), unitCost: Number(openingCost) },
      } : {}),
    }, file);
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
        {product ? (
          <ProductImageField product={product} onChanged={onImageChanged} />
        ) : <NewProductImageField file={file} disabled={pending} onChange={setFile} onInvalid={setImageInvalid} />}
        <Select
          label="Category"
          value={categoryId}
          onChange={(event) => setCategoryId(event.target.value)}
        >
          <option value="">No category</option>
          {product?.categoryId && !categories.some(category => category.id === product.categoryId) ?
            <option value={product.categoryId}>Current category (unavailable)</option> : null}
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
        <Field label="Default purchase cost" type="number" min="0" max="99999999.9999" step="0.0001"
          inputMode="decimal" value={defaultCost}
          onChange={event => { setDefaultCost(event.target.value); setConfirmZero(false); }} />
        <p className="text-label text-text-dim">For future Add stock deliveries. Leave blank if unknown. Changing this does not revalue current stock or past sales.</p>
        {choosingZero ? <label className="hit flex cursor-pointer items-start gap-3 rounded-lg border border-border p-3 text-body text-text">
          <input type="checkbox" required checked={confirmZero} onChange={event => setConfirmZero(event.target.checked)} className="mt-1 size-6 shrink-0" />
          I confirm this product is genuinely free. Use zero as its default purchase cost.
        </label> : null}
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
          <Button type="submit" pending={pending} disabled={imageInvalid || (choosingZero && !confirmZero)}>
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
            aria-label="Choose product picture"
            tabIndex={-1}
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
