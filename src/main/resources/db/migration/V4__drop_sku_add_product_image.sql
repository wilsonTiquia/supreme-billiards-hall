-- SKU is gone: the hall identifies a product by name, and the counter never typed a code.
-- The unique index has to go first, since it is what depends on the column.
DROP INDEX product_sku_key;

ALTER TABLE product DROP COLUMN sku;

-- A picture is faster to find than a line of text in a dark room, so the POS grid shows one
-- per tile. Optional on purpose: half a catalogue with photos must still look deliberate.
--
-- The bytes live on disk under ~/SupremeData/product-images, not in the database, for the same
-- reason as payment photos: a dump stays small enough to take every night. What is stored here
-- is the pointer plus enough to prove the file is the one that was written -- a backup that
-- lost the volume is then detectable rather than silently empty.
ALTER TABLE product
  ADD COLUMN image_path   text,
  ADD COLUMN image_sha256 text,
  ADD COLUMN image_bytes  bigint;

COMMENT ON COLUMN product.image_path   IS 'Absolute path on the app volume. NULL means no image; every screen must render without one.';
COMMENT ON COLUMN product.image_sha256 IS 'SHA-256 of the stored file. Doubles as the ETag and the cache-buster the POS grid appends, so a replaced image is picked up immediately.';
COMMENT ON COLUMN product.image_bytes  IS 'Size of the stored file. Capped at 2 MB on upload.';

-- All three or none: a row with a path and no checksum could not be verified against the volume.
ALTER TABLE product
  ADD CONSTRAINT product_image_together_chk
  CHECK (num_nonnulls(image_path, image_sha256, image_bytes) IN (0, 3));
