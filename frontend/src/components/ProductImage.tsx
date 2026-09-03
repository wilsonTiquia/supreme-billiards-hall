import { useState } from 'react';
import { productImageUrl } from '@/api/endpoints/products';

/**
 * A product's picture, or a typographic stand-in when it has none.
 *
 * Images are optional and always will be — the owner photographs what sells, not the whole
 * catalogue — so the empty case is the designed one, not a fallback. It is a filled surface
 * tile carrying the product's initials, which means a grid where half the tiles have photos
 * and half do not still reads as one deliberate grid. There is no broken-image icon and no
 * empty box: a file missing from the volume collapses to the same stand-in, so it looks like
 * a product that was never photographed rather than like a bug on the floor.
 */

const SIZES = {
  /** POS grid tile — the screen this feature exists for. */
  tile: { box: 'aspect-[4/3] w-full rounded-md', type: 'text-amount' },
  /** Admin catalogue row. */
  thumb: { box: 'size-11 rounded-md', type: 'text-body' },
  /** Admin form, beside the upload control. */
  preview: { box: 'size-28 rounded-lg', type: 'text-display' },
} as const;

/** First letters of up to two words: "San Miguel Pale Pilsen" → "SM". */
function initialsOf(name: string): string {
  const letters = name
    .split(/\s+/)
    .filter((word) => word.length > 0)
    .slice(0, 2)
    .map((word) => word[0]);
  return letters.join('').toUpperCase() || '?';
}

export function ProductImage({
  productId,
  name,
  imageSha256,
  size,
  className = '',
}: {
  productId: string;
  name: string;
  imageSha256: string | null;
  size: keyof typeof SIZES;
  className?: string;
}) {
  const [broken, setBroken] = useState<string | null>(null);

  const { box, type } = SIZES[size];
  const src = productImageUrl(productId, imageSha256);
  const shared = `${box} shrink-0 overflow-hidden border border-border bg-raised ${className}`;

  // Compared against the current checksum rather than held as a boolean, so a replacement
  // upload is tried again instead of inheriting the previous file's failure.
  if (src && broken !== imageSha256) {
    return (
      <img
        src={src}
        alt=""
        loading="lazy"
        onError={() => setBroken(imageSha256)}
        className={`${shared} object-cover`}
      />
    );
  }

  return (
    <div
      aria-hidden
      className={`${shared} flex items-center justify-center ${type} text-text-dim`}
    >
      {initialsOf(name)}
    </div>
  );
}
