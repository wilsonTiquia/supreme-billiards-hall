-- A supplier default for future deliveries, never an inventory revaluation.
-- Existing products stay unset; avg_cost is not evidence of a supplier price.
ALTER TABLE product ADD COLUMN default_purchase_cost numeric(12,4);
ALTER TABLE product ADD CONSTRAINT product_default_purchase_cost_nonnegative
    CHECK (default_purchase_cost >= 0);
