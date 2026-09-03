import { request } from '../client';
import type {
  Product,
  ProductAdmin,
  ProductImage,
  ProductQuery,
  ProductRequest,
  StockMovement,
  UUID,
} from '../types';

/**
 * Typed against Product, not ProductAdmin: this is the counter's grid, and an employee's
 * response carries no avgCost key at all. Nothing here may read a cost field.
 */
export function fetchProducts(query: ProductQuery = {}): Promise<Product[]> {
  return request<Product[]>('/products', {
    query: { categoryId: query.categoryId, q: query.q, activeOnly: query.activeOnly },
  });
}

/**
 * The admin catalogue. Typed as ProductAdmin because an ADMIN response carries `avgCost` —
 * the employee grid must keep using fetchProducts() and the cost-free Product type.
 */
export function fetchProductsAdmin(query: ProductQuery = {}): Promise<ProductAdmin[]> {
  return request<ProductAdmin[]>('/products', {
    query: {
      categoryId: query.categoryId,
      q: query.q,
      activeOnly: query.activeOnly,
      includeArchived: query.includeArchived,
    },
  });
}

export function createProduct(body: ProductRequest): Promise<ProductAdmin> {
  return request<ProductAdmin>('/products', { method: 'POST', body });
}

export function updateProduct(id: UUID, body: ProductRequest): Promise<ProductAdmin> {
  return request<ProductAdmin>(`/products/${id}`, { method: 'PUT', body });
}

/** Archives. Never a hard delete — historical bill lines reference the row. */
export function archiveProduct(id: UUID): Promise<null> {
  return request<null>(`/products/${id}`, { method: 'DELETE' });
}

/**
 * The way back. ADMIN only.
 *
 * A 409 means a live product has taken the name since — `product_name_key` covers unarchived
 * rows only — and the message says which one to rename. Show it; there is nothing to retry.
 */
export function unarchiveProduct(id: UUID): Promise<ProductAdmin> {
  return request<ProductAdmin>(`/products/${id}/unarchive`, { method: 'POST' });
}

/** The ledger for one product: answers "why is this count wrong". */
export function fetchProductMovements(id: UUID): Promise<StockMovement[]> {
  return request<StockMovement[]>(`/products/${id}/movements`);
}

/* ── Images ───────────────────────────────────────────────────────────────────────────
   The one asset the counter reads. Rendered through an <img src>, not fetched here: the
   route sends an ETag and a day of cache, and a blob fetch would throw both away on a grid
   that asks for every tile on every poll. Same-origin, so the session cookie rides along. */

/**
 * Null in, null out — a product with no picture has no URL, and the caller renders the
 * typographic placeholder instead. `imageSha256` is appended so a replaced image is fetched
 * immediately rather than after the cached copy expires.
 */
export function productImageUrl(id: UUID, imageSha256: string | null): string | null {
  return imageSha256 ? `/api/v1/products/${id}/image?v=${imageSha256}` : null;
}

/** ADMIN. multipart/form-data, field name `file`. Replacing deletes the file it replaces. */
export function uploadProductImage(id: UUID, file: File): Promise<ProductImage> {
  const form = new FormData();
  form.append('file', file);
  return request<ProductImage>(`/products/${id}/image`, { method: 'POST', body: form });
}

/** ADMIN. Removes the file and the columns; the product itself is untouched. */
export function deleteProductImage(id: UUID): Promise<null> {
  return request<null>(`/products/${id}/image`, { method: 'DELETE' });
}
