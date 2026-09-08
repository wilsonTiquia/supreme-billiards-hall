-- What the hall spends, not what it sells.
--
-- The dashboard has always answered "what did the night take out" with cost of goods and the
-- giveaway routes, which is the cost of the things sold and nothing else. Water delivery, the
-- electricity bill, rent, a broom -- none of it appears anywhere, so a night that grossed
-- PHP 12,000 and paid PHP 3,000 for a delivery reads exactly like one that paid nothing. The
-- owner has been holding that figure in his head, which is the thing this system exists to stop.
--
-- Two tables rather than one with a free-text category, for the same reason product_category is
-- a table: the owner adds his own, at runtime, and a breakdown grouped on typed strings would
-- report "Water", "water" and "Water delivery" as three costs.

-- Admin-managed, exactly like product_category. Copied field for field rather than shared with
-- it: an expense category and a product category are different vocabularies that happen to look
-- alike today, and merging them would mean "Rent" appearing in the POS product filter.
CREATE TABLE expense_category (
  id          uuid PRIMARY KEY DEFAULT uuidv7(),
  branch_id   uuid        NOT NULL REFERENCES branch(id),
  name        text        NOT NULL,
  sort_order  integer     NOT NULL DEFAULT 0,
  archived_at timestamptz,
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now(),

  -- Target for the composite FK from expense, the mechanism that makes a cross-branch
  -- reference impossible.
  CONSTRAINT expense_category_branch_id_key UNIQUE (branch_id, id)
);

CREATE UNIQUE INDEX expense_category_name_key
  ON expense_category (branch_id, lower(name)) WHERE archived_at IS NULL;

COMMENT ON TABLE expense_category IS 'Admin-managed grouping for operating expenses. A lookup table rather than an enum because the owner adds his own at runtime, and a breakdown grouped on free text would report "Water" and "water" as two costs.';


-- One thing the hall paid for.
--
-- Deliberately NOT part of cost of goods. Product cost is snapshotted onto bill_line at the
-- moment of sale and belongs to the item sold; this is the cost of being open at all, and the
-- two must not be added together anywhere or the margin on a beer starts including the rent.
CREATE TABLE expense (
  id                  uuid          PRIMARY KEY DEFAULT uuidv7(),
  branch_id           uuid          NOT NULL REFERENCES branch(id),
  -- NOT NULL: "Others" is seeded for the genuinely unclassifiable, and a nullable category
  -- would put a permanent unnamed bucket in the dashboard breakdown.
  expense_category_id uuid          NOT NULL,

  amount              numeric(12,2) NOT NULL,
  note                text,

  -- Whether this money physically left the till tonight. A water delivery paid in cash from
  -- the drawer changes what the drawer should hold at close; rent paid by bank transfer does
  -- not. Both are operating cost, only one is drawer arithmetic, and nothing else in the row
  -- distinguishes them.
  paid_from_drawer    boolean       NOT NULL,

  incurred_at         timestamptz   NOT NULL DEFAULT now(),
  -- Computed by the database, like every other business_date here. An expense recorded at
  -- 02:00 belongs to the night that is still running, not to the calendar day it fell in.
  business_date       date GENERATED ALWAYS AS (business_date_of(incurred_at)) STORED,

  recorded_by         uuid          NOT NULL REFERENCES app_user(id),
  created_at          timestamptz   NOT NULL DEFAULT now(),

  -- Void, not delete -- the same rule bill_line carries. The row is retained and excluded from
  -- every total, so "we recorded 850 for water then took it back" stays visible as what
  -- happened rather than vanishing.
  voided_at           timestamptz,
  voided_by           uuid          REFERENCES app_user(id),
  void_reason         text,

  -- Composite, carrying branch_id: an expense's category must live in the SAME branch.
  CONSTRAINT expense_category_fk
    FOREIGN KEY (branch_id, expense_category_id)
    REFERENCES expense_category (branch_id, id),

  CONSTRAINT expense_amount_chk CHECK (amount > 0),
  CONSTRAINT expense_void_pair_chk CHECK (
    (voided_at IS NULL) = (voided_by IS NULL)
  ),
  -- A void without a stated reason is not an audit trail.
  CONSTRAINT expense_void_reason_chk CHECK (
    voided_at IS NULL OR void_reason IS NOT NULL
  )
);

-- The night's list, and the dashboard's total for a day.
CREATE INDEX expense_business_day_idx ON expense (branch_id, business_date);

COMMENT ON TABLE expense IS 'Operating cost -- what it costs to be open, as opposed to what the goods cost. Never added to cost of goods: doing so would put the rent inside the margin on a beer.';
COMMENT ON COLUMN expense.paid_from_drawer IS 'True when the cash physically left the till. Only these rows reduce the expected drawer total at close; a bank transfer is operating cost but not drawer arithmetic.';
COMMENT ON COLUMN expense.business_date IS 'Generated. An expense recorded at 02:00 belongs to the night still running, exactly as a sale at 02:00 does.';


-- The owner's starting vocabulary. He adds the rest himself from the admin screen, which is the
-- whole reason this is a table.
--
-- SELECT over branch rather than a literal id: V2 seeded product_category against the one
-- hardcoded branch, which would silently leave a second hall with no categories at all.
INSERT INTO expense_category (branch_id, name, sort_order)
SELECT b.id, c.name, c.sort_order
FROM branch b
CROSS JOIN (VALUES
  ('Water',       1),
  ('Electricity', 2),
  ('Rent',        3),
  ('Supplies',    4),
  -- Last on purpose: it is the fallback, and a dropdown that offers it first invites it to
  -- become the only one anybody picks.
  ('Others',      5)
) AS c(name, sort_order);


-- =====================================================================
-- THE DRAWER
-- =====================================================================

-- Cash paid out of the till is cash that is not in the till at close.
--
-- Until now expected_cash was float + takings, which assumes nothing ever leaves the drawer
-- except as change. It does: the water man is paid from it. On a night with a PHP 850 delivery
-- the drawer holds 850 less than expected and the variance reads -850, and a control that is
-- predictably wrong on exactly the nights something happened is not a control.
--
-- Frozen on the row like cash_sales and opening_float, and for the same reason: a
-- reconciliation from March has to still add up in March's terms. Defaults to zero so every
-- row already in the table keeps precisely the variance it had.
ALTER TABLE cash_count
  ADD COLUMN cash_expenses numeric(12,2) NOT NULL DEFAULT 0;

COMMENT ON COLUMN cash_count.cash_expenses IS
  'SUM(expense.amount) WHERE paid_from_drawer AND NOT voided for this business day, frozen at the moment of counting. Money that left the till, so it is subtracted from what the drawer should hold.';

-- Dropped and recreated because a generated expression cannot be altered in place. Same
-- approach V6 took when the float joined the arithmetic.
ALTER TABLE cash_count DROP COLUMN variance;

ALTER TABLE cash_count
  ADD COLUMN variance numeric(12,2)
  GENERATED ALWAYS AS (counted_cash - (cash_sales + opening_float - cash_expenses)) STORED;

COMMENT ON COLUMN cash_count.variance IS
  'counted_cash - (cash_sales + opening_float - cash_expenses). Negative is a shortfall. Still computed by the database so no caller can produce a variance the arithmetic does not support.';

ALTER TABLE cash_count
  ADD CONSTRAINT cash_count_expenses_nonneg_chk CHECK (cash_expenses >= 0);
