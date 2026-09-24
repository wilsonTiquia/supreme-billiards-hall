-- A product can move categories. Keep the original category non-deletable after its last
-- product moves away, rather than losing the distinction between unused and previously used.
ALTER TABLE product_category ADD COLUMN referenced_at timestamptz;

-- Existing product audit snapshots carry category names. Include the category's earlier names
-- when backfilling; ambiguous reused names are conservatively retained, never hard-deleted.
UPDATE product_category category SET referenced_at = now()
WHERE EXISTS (SELECT 1 FROM product p WHERE p.category_id = category.id)
   OR EXISTS (
       SELECT 1 FROM audit_log product_event
       WHERE product_event.branch_id = category.branch_id AND product_event.entity_table = 'product'
         AND (product_event.before->>'categoryId' = category.id::text
           OR product_event.after->>'categoryId' = category.id::text
           OR product_event.before->>'category' IN (
               SELECT category.name UNION
               SELECT name FROM audit_log category_event
               CROSS JOIN LATERAL (VALUES (category_event.before->>'name'), (category_event.after->>'name')) names(name)
               WHERE category_event.entity_table = 'product_category' AND category_event.entity_id = category.id)
           OR product_event.after->>'category' IN (
               SELECT category.name UNION
               SELECT name FROM audit_log category_event
               CROSS JOIN LATERAL (VALUES (category_event.before->>'name'), (category_event.after->>'name')) names(name)
               WHERE category_event.entity_table = 'product_category' AND category_event.entity_id = category.id))
   );

CREATE FUNCTION remember_product_category_use() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    UPDATE product_category SET referenced_at = now()
    WHERE id = NEW.category_id AND referenced_at IS NULL;
    RETURN NEW;
END;
$$;

CREATE TRIGGER product_category_usage
AFTER INSERT OR UPDATE OF category_id ON product
FOR EACH ROW WHEN (NEW.category_id IS NOT NULL)
EXECUTE FUNCTION remember_product_category_use();
