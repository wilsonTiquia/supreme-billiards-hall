-- =====================================================================
-- Supreme Billiard Hall — POS schema
-- ORIGINAL DESIGN BASELINE. NOT the current schema. DO NOT BUILD FROM IT.
-- =====================================================================
--
-- This file is the schema as first designed. It became
-- src/main/resources/db/migration/V1__baseline.sql, and every migration
-- after that one has changed the database without changing this file.
-- It is therefore OUT OF DATE, and knowingly so:
--
--   V3  added cash_count.closed_at / closed_by
--   V4  dropped product.sku and added product.image_*
--
-- and anything after V4 is not listed here either, because this file is no
-- longer maintained.
--
-- CANONICAL IS src/main/resources/db/migration/. The migrations are what
-- Flyway has actually executed and what `ddl-auto=validate` checks the
-- entities against. If this file and a migration disagree, the migration is
-- right and this file is stale -- there is no case in which this one wins.
--
-- It is kept for ONE reason: the design commentary below. The rationale for
-- the snapshotting, the append-only ledgers, the composite foreign keys and
-- the uuidv7 keys is written out here and is not carried in the migrations.
-- Read it to understand WHY the schema is shaped as it is. Read the
-- migrations to find out WHAT is in the database.
--
-- Do not sync this file forward. Two canonical files is the problem.
--
-- Target: PostgreSQL 18
-- Timezone: Asia/Manila (fixed +08:00, no DST since 1978)
-- Business day: 10:00 -> 05:00 the following calendar day
--
-- Design rules applied throughout:
--   1. Nothing meaningful is destroyed by an update. Prices, costs, rates
--      and names are SNAPSHOTTED onto the transaction. Money is never
--      computed by joining to the live catalog.
--   2. Deletes are soft (archived_at) so historical rows never orphan.
--   3. Ledgers (stock_movement, audit_log) are append-only, enforced by
--      trigger, not by convention.
--   4. Every branch-owned row carries branch_id NOT NULL, and child rows
--      reference parents via COMPOSITE foreign keys that include branch_id.
--      A row therefore cannot reference a parent in a different branch --
--      cross-branch leakage fails loudly at the database, not silently.
--   5. UUID primary keys (uuidv7) so that per-branch deployments can be
--      merged into a consolidated reporting database without ID collisions.
-- =====================================================================

BEGIN;

-- ---------------------------------------------------------------------
-- Extensions
-- ---------------------------------------------------------------------

-- btree_gist lets an EXCLUDE constraint mix uuid equality with range
-- overlap, which is how we stop a pool table having two overlapping
-- rate periods.
CREATE EXTENSION IF NOT EXISTS btree_gist;


-- ---------------------------------------------------------------------
-- Business-day derivation
-- ---------------------------------------------------------------------

-- Maps an instant to the business day it belongs to.
--
-- Why the literal '+08:00' interval instead of AT TIME ZONE 'Asia/Manila':
-- the named-zone form of AT TIME ZONE is only STABLE (zone rules can be
-- changed by a tzdata update), and STABLE expressions are rejected in
-- generated columns. The interval form is IMMUTABLE. The Philippines has
-- had a fixed +08:00 offset with no DST since 1978, so the substitution is
-- safe -- but it IS an assumption, and it is the one thing in this schema
-- that would need revisiting if PH ever adopted DST.
--
-- The subtraction of 10 hours shifts the day boundary from midnight to
-- 10:00. Consequence worth knowing: every instant from 00:00 to 09:59
-- maps to the PREVIOUS business day. That is deliberate -- a 02:00 sale
-- and a 05:00 auto-close both belong to the night that is still running.
--
-- CAUTION: changing this function does NOT recompute already-stored
-- generated columns. Altering the business day after go-live requires an
-- explicit backfill of every business_date column.
CREATE OR REPLACE FUNCTION business_date_of(ts timestamptz)
RETURNS date
LANGUAGE sql
IMMUTABLE PARALLEL SAFE STRICT
AS $$
  SELECT ((ts AT TIME ZONE INTERVAL '+08:00') - INTERVAL '10 hours')::date
$$;

COMMENT ON FUNCTION business_date_of(timestamptz) IS
  'Maps an instant to its Asia/Manila business day (10:00-05:00). IMMUTABLE so it can be used in generated columns.';


-- Blocks UPDATE and DELETE on append-only ledger tables.
CREATE OR REPLACE FUNCTION forbid_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  RAISE EXCEPTION
    'Table % is append-only; % is not permitted. Post a compensating row instead.',
    TG_TABLE_NAME, TG_OP
    USING ERRCODE = 'restrict_violation';
END;
$$;


-- ---------------------------------------------------------------------
-- Enumerated types
--
-- Enums are used for closed sets that only change with a code deploy.
-- Sets the ADMIN is expected to manage at runtime (customer types,
-- product categories) are lookup TABLES instead -- an enum would require
-- a migration every time the owner invents a new customer type.
-- ---------------------------------------------------------------------

CREATE TYPE user_role       AS ENUM ('EMPLOYEE', 'ADMIN');
CREATE TYPE session_status  AS ENUM ('OPEN', 'PAUSED', 'CLOSED', 'AUTO_CLOSED', 'VOIDED');
CREATE TYPE bill_status     AS ENUM ('OPEN', 'CLOSED', 'VOIDED', 'MERGED');
CREATE TYPE bill_line_kind  AS ENUM ('TIME', 'PRODUCT');
CREATE TYPE payment_method  AS ENUM ('CASH', 'GCASH', 'MAYA');
CREATE TYPE session_close_kind AS ENUM ('MANUAL', 'AUTO_END_OF_DAY');
CREATE TYPE stock_reason    AS ENUM (
  'SALE',        -- deducted by a sale line
  'SALE_VOID',   -- returned to stock when that line is voided
  'DELIVERY',    -- goods received
  'CORRECTION',  -- manual count correction; note is mandatory
  'STAFF_COMP'   -- consumed by staff or given away without a sale
);


-- =====================================================================
-- ADMIN / IDENTITY
-- =====================================================================

-- One physical hall. Branches are FULLY independent: separate catalog,
-- separate stock, separate pricing, separate tables, separate sales.
CREATE TABLE branch (
  id                uuid PRIMARY KEY DEFAULT uuidv7(),
  code              text        NOT NULL,
  name              text        NOT NULL,
  address           text,
  -- Human-facing receipt numbers are per-branch and sequential. They are
  -- deliberately NOT the primary key: short and readable for customers,
  -- while the uuid stays globally unique for the future rollup.
  -- Allocated with SELECT ... FOR UPDATE inside the checkout transaction.
  next_receipt_no   bigint      NOT NULL DEFAULT 1,
  is_active         boolean     NOT NULL DEFAULT true,
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),

  CONSTRAINT branch_code_key       UNIQUE (code),
  CONSTRAINT branch_receipt_no_chk CHECK (next_receipt_no > 0)
);

COMMENT ON TABLE  branch IS 'A physical billiard hall. All operational data is scoped to exactly one branch.';
COMMENT ON COLUMN branch.next_receipt_no IS 'Per-branch receipt counter, allocated under row lock at checkout.';


-- Staff accounts. Employees are branch-bound; admins are global.
CREATE TABLE app_user (
  id             uuid PRIMARY KEY DEFAULT uuidv7(),
  branch_id      uuid        REFERENCES branch(id),
  username       text        NOT NULL,
  password_hash  text        NOT NULL,
  full_name      text        NOT NULL,
  role           user_role   NOT NULL,
  is_active      boolean     NOT NULL DEFAULT true,
  last_login_at  timestamptz,
  archived_at    timestamptz,
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),

  -- An admin may be global (branch_id NULL). An employee must belong to
  -- exactly one branch -- this is what makes branch scoping enforceable.
  CONSTRAINT app_user_branch_required_for_employee
    CHECK (role = 'ADMIN' OR branch_id IS NOT NULL)
);

-- Case-insensitive unique username. Partial so an archived account frees
-- its name for reuse.
CREATE UNIQUE INDEX app_user_username_key
  ON app_user (lower(username)) WHERE archived_at IS NULL;

COMMENT ON TABLE  app_user IS 'Staff account. Employees see selling prices only; cost and profit are admin-only at the application layer.';
COMMENT ON COLUMN app_user.branch_id IS 'NULL means a global admin who can switch between branches.';


-- Runtime configuration, per branch. Kept as key/value so the owner can
-- change the friend-rate floor or the low-stock threshold without a
-- deploy. Values are jsonb so a setting can grow structure later.
CREATE TABLE branch_setting (
  branch_id   uuid        NOT NULL REFERENCES branch(id) ON DELETE CASCADE,
  key         text        NOT NULL,
  value       jsonb       NOT NULL,
  updated_by  uuid        REFERENCES app_user(id),
  updated_at  timestamptz NOT NULL DEFAULT now(),

  PRIMARY KEY (branch_id, key)
);

COMMENT ON TABLE branch_setting IS
  'Per-branch runtime config. Known keys: rate_floor_per_minute, rate_floor_percent, low_stock_threshold, allow_negative_stock.';


-- Append-only record of who did what. This is the table that answers
-- "who voided that beer", "who set a 0.50/min friend rate", "who
-- corrected stock on the 12th".
CREATE TABLE audit_log (
  id            uuid PRIMARY KEY DEFAULT uuidv7(),
  branch_id     uuid        REFERENCES branch(id),
  actor_id      uuid        REFERENCES app_user(id),
  action        text        NOT NULL,
  entity_table  text        NOT NULL,
  entity_id     uuid,
  before        jsonb,
  after         jsonb,
  note          text,
  occurred_at   timestamptz NOT NULL DEFAULT now(),
  business_date date        GENERATED ALWAYS AS (business_date_of(occurred_at)) STORED
);

CREATE TRIGGER audit_log_append_only
  BEFORE UPDATE OR DELETE ON audit_log
  FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

-- Admin audit screen: newest first, filtered to one branch.
CREATE INDEX audit_log_branch_time_idx ON audit_log (branch_id, occurred_at DESC);
-- "Show me everything that happened to this bill."
CREATE INDEX audit_log_entity_idx      ON audit_log (entity_table, entity_id);
-- "Show me every override this employee made."
CREATE INDEX audit_log_actor_idx       ON audit_log (actor_id, occurred_at DESC);

COMMENT ON TABLE audit_log IS 'Append-only audit trail. UPDATE and DELETE are blocked by trigger.';


-- =====================================================================
-- CATALOG
-- =====================================================================

CREATE TABLE product_category (
  id          uuid PRIMARY KEY DEFAULT uuidv7(),
  branch_id   uuid        NOT NULL REFERENCES branch(id),
  name        text        NOT NULL,
  sort_order  integer     NOT NULL DEFAULT 0,
  archived_at timestamptz,
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now(),

  -- Target for composite FKs from product. This is the mechanism that
  -- makes a cross-branch reference impossible.
  CONSTRAINT product_category_branch_id_key UNIQUE (branch_id, id)
);

CREATE UNIQUE INDEX product_category_name_key
  ON product_category (branch_id, lower(name)) WHERE archived_at IS NULL;

COMMENT ON TABLE product_category IS 'Admin-managed product grouping, used to filter the POS product list. Lookup table rather than an enum because the owner adds categories at runtime.';


-- Sellable item. Soft-deleted only: a hard delete would orphan the
-- product_id reference carried on historical bill lines.
CREATE TABLE product (
  id             uuid PRIMARY KEY DEFAULT uuidv7(),
  branch_id      uuid           NOT NULL REFERENCES branch(id),
  category_id    uuid,
  sku            text,
  name           text           NOT NULL,

  -- Current price list. Historical sales do NOT read these columns --
  -- they read the snapshot on bill_line. Changing a price here can never
  -- rewrite past profit.
  selling_price  numeric(12,2)  NOT NULL,
  avg_cost       numeric(12,4)  NOT NULL DEFAULT 0,

  -- Cached on-hand quantity. stock_movement is the source of truth; this
  -- column exists so the POS product grid is one indexed read instead of
  -- an aggregate over the ledger. Maintained inside the same transaction
  -- as the movement. If the two ever disagree, the ledger is correct.
  qty_on_hand    numeric(12,3)  NOT NULL DEFAULT 0,

  is_active      boolean        NOT NULL DEFAULT true,
  archived_at    timestamptz,
  created_at     timestamptz    NOT NULL DEFAULT now(),
  updated_at     timestamptz    NOT NULL DEFAULT now(),

  CONSTRAINT product_branch_id_key   UNIQUE (branch_id, id),
  CONSTRAINT product_price_chk       CHECK (selling_price >= 0),
  CONSTRAINT product_cost_chk        CHECK (avg_cost >= 0),
  -- Composite FK: a product's category must live in the SAME branch.
  CONSTRAINT product_category_fk
    FOREIGN KEY (branch_id, category_id)
    REFERENCES product_category (branch_id, id)
);

CREATE UNIQUE INDEX product_name_key
  ON product (branch_id, lower(name)) WHERE archived_at IS NULL;
CREATE UNIQUE INDEX product_sku_key
  ON product (branch_id, sku) WHERE sku IS NOT NULL AND archived_at IS NULL;
-- Default POS view: active products of one branch, grouped by category.
CREATE INDEX product_browse_idx
  ON product (branch_id, category_id, name) WHERE archived_at IS NULL AND is_active;
-- Dashboard low-stock and negative-stock tiles.
CREATE INDEX product_low_stock_idx
  ON product (branch_id, qty_on_hand) WHERE archived_at IS NULL;

COMMENT ON COLUMN product.avg_cost    IS 'Moving weighted average cost. Recalculated on each DELIVERY. Snapshotted to bill_line.unit_cost at sale time. Chosen over FIFO layering: adequate for a hall, a fraction of the complexity.';
COMMENT ON COLUMN product.qty_on_hand IS 'Denormalised cache of SUM(stock_movement.quantity_delta). The ledger is authoritative.';


-- Customer classification chosen at session start / quick sale. Drives
-- whether a table rate override is expected, and tags the whole bill so
-- the owner can see what went to friends of the house.
CREATE TABLE customer_type (
  id                     uuid PRIMARY KEY DEFAULT uuidv7(),
  branch_id              uuid        NOT NULL REFERENCES branch(id),
  name                   text        NOT NULL,
  -- true for e.g. "Friend of Owner": the POS prompts for a custom rate.
  allows_rate_override   boolean     NOT NULL DEFAULT false,
  is_default             boolean     NOT NULL DEFAULT false,
  sort_order             integer     NOT NULL DEFAULT 0,
  archived_at            timestamptz,
  created_at             timestamptz NOT NULL DEFAULT now(),
  updated_at             timestamptz NOT NULL DEFAULT now(),

  CONSTRAINT customer_type_branch_id_key UNIQUE (branch_id, id)
);

CREATE UNIQUE INDEX customer_type_name_key
  ON customer_type (branch_id, lower(name)) WHERE archived_at IS NULL;
-- At most one default per branch.
CREATE UNIQUE INDEX customer_type_one_default_key
  ON customer_type (branch_id) WHERE is_default AND archived_at IS NULL;

COMMENT ON TABLE customer_type IS 'Admin-managed list, e.g. Regular / Friend of Owner. Food and drink always charge full price; only the table rate is overridable.';


-- =====================================================================
-- POOL TABLES AND RATES
-- =====================================================================

-- The physical object. Deliberately carries NO session state: no
-- start_time, no end_time, no current_bill. A table is durable equipment;
-- an occupancy is a separate, repeatable event. Putting time on this row
-- would mean each table has exactly one session ever, overwritten on
-- every use, and no history at all.
CREATE TABLE pool_table (
  id           uuid PRIMARY KEY DEFAULT uuidv7(),
  branch_id    uuid        NOT NULL REFERENCES branch(id),
  name         text        NOT NULL,
  table_number integer,
  is_active    boolean     NOT NULL DEFAULT true,
  archived_at  timestamptz,
  created_at   timestamptz NOT NULL DEFAULT now(),
  updated_at   timestamptz NOT NULL DEFAULT now(),

  CONSTRAINT pool_table_branch_id_key UNIQUE (branch_id, id)
);

CREATE UNIQUE INDEX pool_table_name_key
  ON pool_table (branch_id, lower(name)) WHERE archived_at IS NULL;

COMMENT ON TABLE pool_table IS 'Physical table. Holds no session state -- occupancy lives in table_session. "Is table 3 busy?" is a query for an open session.';


-- Dated rate history. Sessions snapshot the rate they were billed at, so
-- this table exists for the CURRENT rate and for answering "what did we
-- charge in March", not for pricing historical sales.
CREATE TABLE pool_table_rate (
  id               uuid PRIMARY KEY DEFAULT uuidv7(),
  branch_id        uuid          NOT NULL REFERENCES branch(id),
  pool_table_id    uuid          NOT NULL,
  rate_per_minute  numeric(10,4) NOT NULL,
  effective_from   timestamptz   NOT NULL,
  effective_to     timestamptz,
  created_by       uuid          REFERENCES app_user(id),
  created_at       timestamptz   NOT NULL DEFAULT now(),

  CONSTRAINT pool_table_rate_positive_chk CHECK (rate_per_minute > 0),
  CONSTRAINT pool_table_rate_period_chk
    CHECK (effective_to IS NULL OR effective_to > effective_from),
  CONSTRAINT pool_table_rate_table_fk
    FOREIGN KEY (branch_id, pool_table_id)
    REFERENCES pool_table (branch_id, id),
  -- One table cannot have two rates in force at the same instant.
  -- Enforced by the database, so a bad admin edit fails loudly rather
  -- than producing an ambiguous rate lookup at 1 AM.
  CONSTRAINT pool_table_rate_no_overlap
    EXCLUDE USING gist (
      pool_table_id WITH =,
      tstzrange(effective_from, effective_to) WITH &&
    )
);

-- Rate lookup when opening a session: the row with no end date.
CREATE INDEX pool_table_rate_current_idx
  ON pool_table_rate (pool_table_id) WHERE effective_to IS NULL;

COMMENT ON COLUMN pool_table_rate.rate_per_minute IS 'numeric(10,4), not numeric(12,2): a per-minute rate needs sub-centavo precision so a rate of PHP 200/hour does not round away.';


-- =====================================================================
-- BILLS
--
-- A bill is created when a session opens (and for a quick sale). Line
-- items attach to the bill immediately, so orders taken mid-session have
-- somewhere to live before checkout.
-- =====================================================================

CREATE TABLE bill (
  id                  uuid PRIMARY KEY DEFAULT uuidv7(),
  branch_id           uuid          NOT NULL REFERENCES branch(id),
  receipt_no          bigint,
  status              bill_status   NOT NULL DEFAULT 'OPEN',
  customer_type_id    uuid,

  -- Set when this bill is absorbed by another at checkout (merge).
  merged_into_bill_id uuid          REFERENCES bill(id),

  opened_by           uuid          NOT NULL REFERENCES app_user(id),
  opened_at           timestamptz   NOT NULL DEFAULT now(),
  closed_by           uuid          REFERENCES app_user(id),
  closed_at           timestamptz,

  voided_by           uuid          REFERENCES app_user(id),
  voided_at           timestamptz,
  void_reason         text,

  -- Totals, finalised at checkout. Denormalised so the dashboard reads
  -- one row per bill instead of aggregating every line every time.
  subtotal_time       numeric(12,2) NOT NULL DEFAULT 0,
  subtotal_items      numeric(12,2) NOT NULL DEFAULT 0,
  total_amount        numeric(12,2) NOT NULL DEFAULT 0,
  total_cost          numeric(12,2) NOT NULL DEFAULT 0,

  -- Optimistic locking. Two counter tabs, or a double-clicked checkout,
  -- would otherwise settle the same bill twice.
  version             integer       NOT NULL DEFAULT 0,

  -- Revenue is recognised when the bill closes; an open bill reports
  -- under the day it opened. Because end-of-day close blocks open
  -- sessions, a bill spanning the 05:00 boundary is a rare, flagged case.
  business_date       date GENERATED ALWAYS AS
                        (business_date_of(COALESCE(closed_at, opened_at))) STORED,

  CONSTRAINT bill_branch_id_key    UNIQUE (branch_id, id),
  CONSTRAINT bill_receipt_no_key   UNIQUE (branch_id, receipt_no),
  CONSTRAINT bill_customer_type_fk
    FOREIGN KEY (branch_id, customer_type_id)
    REFERENCES customer_type (branch_id, id),

  -- A closed bill must record when and by whom, and must carry a receipt
  -- number. An open bill must not.
  CONSTRAINT bill_closed_consistency_chk CHECK (
    (status = 'CLOSED' AND closed_at IS NOT NULL AND closed_by IS NOT NULL
                       AND receipt_no IS NOT NULL)
    OR (status <> 'CLOSED' AND closed_at IS NULL AND closed_by IS NULL)
  ),
  CONSTRAINT bill_voided_consistency_chk CHECK (
    (status = 'VOIDED') = (voided_at IS NOT NULL)
  ),
  CONSTRAINT bill_void_reason_chk CHECK (
    voided_at IS NULL OR void_reason IS NOT NULL
  ),
  CONSTRAINT bill_merged_consistency_chk CHECK (
    (status = 'MERGED') = (merged_into_bill_id IS NOT NULL)
  ),
  CONSTRAINT bill_not_merged_into_self_chk CHECK (
    merged_into_bill_id IS DISTINCT FROM id
  ),
  CONSTRAINT bill_totals_chk CHECK (
    subtotal_time >= 0 AND subtotal_items >= 0 AND total_amount >= 0
  )
);

-- The dashboard's core access path: one branch, one business day.
CREATE INDEX bill_business_day_idx
  ON bill (branch_id, business_date) WHERE status = 'CLOSED';
-- The POS floor view: what is still open right now.
CREATE INDEX bill_open_idx
  ON bill (branch_id, opened_at) WHERE status = 'OPEN';
CREATE INDEX bill_merged_into_idx
  ON bill (merged_into_bill_id) WHERE merged_into_bill_id IS NOT NULL;

COMMENT ON TABLE  bill IS 'The payable. Created when a session opens or a quick sale starts. Merging attaches several sessions to one bill.';
COMMENT ON COLUMN bill.business_date IS 'Generated from closed_at (falling back to opened_at). A 02:00 sale reports under the previous calendar day, per the 10:00-05:00 business day.';
COMMENT ON COLUMN bill.version IS 'Optimistic lock. Checkout must supply the version it read, or the second concurrent checkout is rejected.';


-- =====================================================================
-- SESSIONS
-- =====================================================================

CREATE TABLE table_session (
  id                       uuid PRIMARY KEY DEFAULT uuidv7(),
  branch_id                uuid           NOT NULL REFERENCES branch(id),
  bill_id                  uuid           NOT NULL,
  -- Table the session currently occupies. Transfers move this and open a
  -- new session_segment; the segments remain the billing truth.
  pool_table_id            uuid           NOT NULL,
  customer_type_id         uuid,
  status                   session_status NOT NULL DEFAULT 'OPEN',

  opened_by                uuid           NOT NULL REFERENCES app_user(id),
  opened_at                timestamptz    NOT NULL DEFAULT now(),
  closed_by                uuid           REFERENCES app_user(id),
  closed_at                timestamptz,
  close_kind               session_close_kind,
  -- Set when the end-of-day safety net closed this instead of a human.
  needs_review             boolean        NOT NULL DEFAULT false,

  -- Friend-of-owner rate override, captured with full context so the
  -- foregone revenue is measurable and attributable.
  rate_override_per_minute numeric(10,4),
  standard_rate_per_minute numeric(10,4),
  rate_override_by         uuid           REFERENCES app_user(id),
  -- Populated only when the entered rate was below the configured floor
  -- and an admin authorised it.
  rate_override_approved_by uuid          REFERENCES app_user(id),
  rate_override_reason     text,

  -- Finalised at close. Derived from segments minus pauses; stored so the
  -- receipt and the reports never recompute and never disagree.
  billed_minutes           integer,
  time_amount              numeric(12,2),

  notes                    text,
  created_at               timestamptz    NOT NULL DEFAULT now(),
  updated_at               timestamptz    NOT NULL DEFAULT now(),

  CONSTRAINT table_session_branch_id_key UNIQUE (branch_id, id),
  CONSTRAINT table_session_bill_fk
    FOREIGN KEY (branch_id, bill_id) REFERENCES bill (branch_id, id),
  CONSTRAINT table_session_table_fk
    FOREIGN KEY (branch_id, pool_table_id) REFERENCES pool_table (branch_id, id),
  CONSTRAINT table_session_customer_type_fk
    FOREIGN KEY (branch_id, customer_type_id) REFERENCES customer_type (branch_id, id),

  CONSTRAINT table_session_closed_consistency_chk CHECK (
    (status IN ('OPEN', 'PAUSED') AND closed_at IS NULL AND close_kind IS NULL)
    OR (status IN ('CLOSED', 'AUTO_CLOSED', 'VOIDED') AND closed_at IS NOT NULL)
  ),
  CONSTRAINT table_session_close_order_chk CHECK (
    closed_at IS NULL OR closed_at >= opened_at
  ),
  CONSTRAINT table_session_billed_minutes_chk CHECK (
    billed_minutes IS NULL OR billed_minutes >= 0
  ),
  -- An override must record who made it and what the standard rate was,
  -- otherwise the discount is unmeasurable.
  CONSTRAINT table_session_override_chk CHECK (
    rate_override_per_minute IS NULL
    OR (rate_override_by IS NOT NULL AND standard_rate_per_minute IS NOT NULL)
  ),
  CONSTRAINT table_session_override_positive_chk CHECK (
    rate_override_per_minute IS NULL OR rate_override_per_minute >= 0
  )
);

-- THE constraint that makes the floor view trustworthy: one physical
-- table can host at most one live session. A second attempt to open
-- table 3 fails at the database, not in a race-prone application check.
CREATE UNIQUE INDEX table_session_one_open_per_table_key
  ON table_session (pool_table_id) WHERE status IN ('OPEN', 'PAUSED');

CREATE INDEX table_session_bill_idx   ON table_session (bill_id);
CREATE INDEX table_session_open_idx   ON table_session (branch_id, opened_at)
  WHERE status IN ('OPEN', 'PAUSED');
-- Table utilisation reporting.
CREATE INDEX table_session_table_time_idx
  ON table_session (pool_table_id, opened_at DESC);
-- Dashboard tile: sessions the auto-close touched.
CREATE INDEX table_session_review_idx
  ON table_session (branch_id, opened_at) WHERE needs_review;

COMMENT ON TABLE  table_session IS 'One occupancy of one table. Reconstructable: who opened, who closed, every pause and every transfer.';
COMMENT ON COLUMN table_session.rate_override_approved_by IS
  'Reserved. The owner has ruled that employees may set ANY friend rate with no floor and no approval, so this stays NULL in normal operation. Kept because reinstating an approval step later is then a policy change, not a migration.';


-- The billing truth for time. A session with no transfers has exactly one
-- segment. A transfer closes the current segment and opens another on the
-- new table at that table's rate -- which is how a mid-session move is
-- billed correctly when the two tables charge differently.
CREATE TABLE session_segment (
  id               uuid PRIMARY KEY DEFAULT uuidv7(),
  branch_id        uuid          NOT NULL REFERENCES branch(id),
  session_id       uuid          NOT NULL,
  pool_table_id    uuid          NOT NULL,
  seq              integer       NOT NULL,
  -- Snapshot, not a lookup. Re-pricing a table tomorrow must not change
  -- what this segment cost tonight.
  rate_per_minute  numeric(10,4) NOT NULL,
  started_at       timestamptz   NOT NULL,
  ended_at         timestamptz,
  moved_by         uuid          REFERENCES app_user(id),
  move_reason      text,

  CONSTRAINT session_segment_seq_key UNIQUE (session_id, seq),
  CONSTRAINT session_segment_session_fk
    FOREIGN KEY (branch_id, session_id) REFERENCES table_session (branch_id, id) ON DELETE CASCADE,
  CONSTRAINT session_segment_table_fk
    FOREIGN KEY (branch_id, pool_table_id) REFERENCES pool_table (branch_id, id),
  CONSTRAINT session_segment_period_chk CHECK (ended_at IS NULL OR ended_at > started_at),
  CONSTRAINT session_segment_rate_chk   CHECK (rate_per_minute >= 0)
);

-- A session accrues time on exactly one table at a time.
CREATE UNIQUE INDEX session_segment_one_open_key
  ON session_segment (session_id) WHERE ended_at IS NULL;
CREATE INDEX session_segment_session_idx ON session_segment (session_id, seq);
-- Per-table revenue and utilisation are computed from segments, not from
-- table_session.pool_table_id, so a transferred session is attributed to
-- both tables proportionally.
CREATE INDEX session_segment_table_idx   ON session_segment (pool_table_id, started_at DESC);

COMMENT ON TABLE session_segment IS 'Billable time intervals. One row per table the session occupied. Sum of segments minus pauses = billed minutes.';


-- Unbilled intervals. Covers brownouts, table faults and customers
-- stepping out. Every pause is attributable.
CREATE TABLE session_pause (
  id          uuid PRIMARY KEY DEFAULT uuidv7(),
  branch_id   uuid        NOT NULL REFERENCES branch(id),
  session_id  uuid        NOT NULL,
  paused_at   timestamptz NOT NULL DEFAULT now(),
  resumed_at  timestamptz,
  paused_by   uuid        NOT NULL REFERENCES app_user(id),
  resumed_by  uuid        REFERENCES app_user(id),
  reason      text,

  CONSTRAINT session_pause_session_fk
    FOREIGN KEY (branch_id, session_id) REFERENCES table_session (branch_id, id) ON DELETE CASCADE,
  CONSTRAINT session_pause_period_chk CHECK (resumed_at IS NULL OR resumed_at > paused_at)
);

-- A session cannot be paused twice concurrently.
CREATE UNIQUE INDEX session_pause_one_open_key
  ON session_pause (session_id) WHERE resumed_at IS NULL;
CREATE INDEX session_pause_session_idx ON session_pause (session_id, paused_at);

COMMENT ON TABLE session_pause IS 'Paused time is deducted from billed minutes. Logged with actor so pausing cannot be used quietly to comp a friend.';


-- =====================================================================
-- BILL LINES
-- =====================================================================

CREATE TABLE bill_line (
  id             uuid PRIMARY KEY DEFAULT uuidv7(),
  branch_id      uuid           NOT NULL REFERENCES branch(id),
  bill_id        uuid           NOT NULL,
  line_kind      bill_line_kind NOT NULL,
  seq            integer        NOT NULL,

  -- Reference for reporting only. NEVER joined to compute money.
  product_id     uuid,
  session_id     uuid,

  -- SNAPSHOTS. These three columns are the reason a sale from three
  -- months ago still reports the correct profit. Renaming or re-pricing
  -- a product cannot reach back into this row.
  description    text           NOT NULL,
  unit_price     numeric(12,2)  NOT NULL,
  unit_cost      numeric(12,4)  NOT NULL DEFAULT 0,
  quantity       numeric(12,3)  NOT NULL,

  -- Time lines only.
  billed_minutes integer,

  line_total     numeric(12,2)
                 GENERATED ALWAYS AS (round(quantity * unit_price, 2)) STORED,
  line_cost      numeric(12,2)
                 GENERATED ALWAYS AS (round(quantity * unit_cost, 2)) STORED,

  -- Pre-checkout void. The row is retained and excluded from totals --
  -- the original record stays, nothing is deleted.
  voided_at      timestamptz,
  voided_by      uuid           REFERENCES app_user(id),
  void_reason    text,

  created_by     uuid           NOT NULL REFERENCES app_user(id),
  created_at     timestamptz    NOT NULL DEFAULT now(),

  CONSTRAINT bill_line_branch_id_key UNIQUE (branch_id, id),
  CONSTRAINT bill_line_seq_key       UNIQUE (bill_id, seq),
  CONSTRAINT bill_line_bill_fk
    FOREIGN KEY (branch_id, bill_id) REFERENCES bill (branch_id, id),
  CONSTRAINT bill_line_product_fk
    FOREIGN KEY (branch_id, product_id) REFERENCES product (branch_id, id),
  CONSTRAINT bill_line_session_fk
    FOREIGN KEY (branch_id, session_id) REFERENCES table_session (branch_id, id),

  -- A product line points at a product; a time line points at a session
  -- and records the minutes it charged for.
  CONSTRAINT bill_line_kind_shape_chk CHECK (
    (line_kind = 'PRODUCT' AND product_id IS NOT NULL AND session_id IS NULL
                           AND billed_minutes IS NULL)
    OR (line_kind = 'TIME' AND session_id IS NOT NULL AND product_id IS NULL
                           AND billed_minutes IS NOT NULL)
  ),
  CONSTRAINT bill_line_quantity_chk CHECK (quantity > 0),
  CONSTRAINT bill_line_price_chk    CHECK (unit_price >= 0 AND unit_cost >= 0),
  CONSTRAINT bill_line_void_pair_chk CHECK (
    (voided_at IS NULL) = (voided_by IS NULL)
  ),
  -- A void without a stated reason is not an audit trail.
  CONSTRAINT bill_line_void_reason_chk CHECK (
    voided_at IS NULL OR void_reason IS NOT NULL
  )
);

-- Rendering a bill: all live lines in entry order.
CREATE INDEX bill_line_bill_idx ON bill_line (bill_id, seq) WHERE voided_at IS NULL;
-- Top-selling-items report.
CREATE INDEX bill_line_product_idx
  ON bill_line (product_id, created_at DESC) WHERE voided_at IS NULL AND line_kind = 'PRODUCT';
-- Dashboard void tile.
CREATE INDEX bill_line_voided_idx
  ON bill_line (branch_id, voided_at DESC) WHERE voided_at IS NOT NULL;

COMMENT ON COLUMN bill_line.description IS 'Snapshot of the product name (or "Table 3 - time") at the moment of sale. A later rename never rewrites an old receipt.';
COMMENT ON COLUMN bill_line.unit_cost   IS 'Snapshot of product.avg_cost at sale time. Gross profit = line_total - line_cost, permanently.';


-- Records a checkout-time merge, with enough detail to reverse it.
-- Line items move flat onto the surviving bill and lose their table tag
-- (the owner's choice, for a simple receipt); this snapshot is therefore
-- the ONLY place the pre-merge shape survives, and it is what unmerge
-- reads to restore both sessions.
CREATE TABLE bill_merge_event (
  id                 uuid PRIMARY KEY DEFAULT uuidv7(),
  branch_id          uuid        NOT NULL REFERENCES branch(id),
  surviving_bill_id  uuid        NOT NULL,
  absorbed_bill_id   uuid        NOT NULL,

  merged_by          uuid        NOT NULL REFERENCES app_user(id),
  merged_at          timestamptz NOT NULL DEFAULT now(),
  unmerged_by        uuid        REFERENCES app_user(id),
  unmerged_at        timestamptz,

  -- Full before/after: both bills' totals, their sessions, and every line
  -- that moved with its id, description and value.
  before_snapshot    jsonb       NOT NULL,
  after_snapshot     jsonb       NOT NULL,

  CONSTRAINT bill_merge_distinct_chk
    CHECK (surviving_bill_id <> absorbed_bill_id),
  CONSTRAINT bill_merge_unmerge_pair_chk
    CHECK ((unmerged_at IS NULL) = (unmerged_by IS NULL)),
  CONSTRAINT bill_merge_surviving_fk
    FOREIGN KEY (branch_id, surviving_bill_id) REFERENCES bill (branch_id, id),
  CONSTRAINT bill_merge_absorbed_fk
    FOREIGN KEY (branch_id, absorbed_bill_id) REFERENCES bill (branch_id, id)
);

-- Unmerge looks up the live merge for a bill.
CREATE UNIQUE INDEX bill_merge_active_key
  ON bill_merge_event (absorbed_bill_id) WHERE unmerged_at IS NULL;
CREATE INDEX bill_merge_surviving_idx ON bill_merge_event (surviving_bill_id);

COMMENT ON TABLE bill_merge_event IS 'Checkout-time merge record. Carries the origin data that bill_line deliberately does not, so unmerge can restore both sessions before payment.';


-- =====================================================================
-- PAYMENT AND RECEIPT
-- =====================================================================

CREATE TABLE payment (
  id                  uuid PRIMARY KEY DEFAULT uuidv7(),
  branch_id           uuid           NOT NULL REFERENCES branch(id),
  bill_id             uuid           NOT NULL,
  method              payment_method NOT NULL,
  amount              numeric(12,2)  NOT NULL,

  -- Cash only.
  tendered            numeric(12,2),
  change_given        numeric(12,2),

  -- Digital only.
  reference_no        text,
  -- Set when staff confirmed a duplicate reference warning and proceeded.
  duplicate_override_by uuid         REFERENCES app_user(id),

  -- Payment confirmation photo. Stored on a Docker volume; the database
  -- keeps the path plus a checksum so a backup that loses the volume is
  -- detectable rather than silently empty.
  photo_path          text,
  photo_sha256        text,
  photo_bytes         bigint,

  -- Guards against a double-clicked checkout or a retried request
  -- recording the same payment twice.
  idempotency_key     text           NOT NULL,

  taken_by            uuid           NOT NULL REFERENCES app_user(id),
  taken_at            timestamptz    NOT NULL DEFAULT now(),
  business_date       date GENERATED ALWAYS AS (business_date_of(taken_at)) STORED,

  -- ONE PAYMENT PER BILL. This is the owner's explicit ruling, upheld
  -- after being challenged. Supporting split payments later means
  -- dropping this one constraint -- the table shape already allows many.
  CONSTRAINT payment_one_per_bill_key UNIQUE (bill_id),
  CONSTRAINT payment_idempotency_key  UNIQUE (branch_id, idempotency_key),
  CONSTRAINT payment_bill_fk
    FOREIGN KEY (branch_id, bill_id) REFERENCES bill (branch_id, id),

  CONSTRAINT payment_amount_chk   CHECK (amount > 0),
  -- Cash requires a tendered amount; digital requires a reference number.
  CONSTRAINT payment_cash_shape_chk CHECK (
    (method = 'CASH' AND tendered IS NOT NULL AND reference_no IS NULL)
    OR (method <> 'CASH' AND tendered IS NULL AND change_given IS NULL
                         AND reference_no IS NOT NULL)
  ),
  CONSTRAINT payment_tendered_chk CHECK (tendered IS NULL OR tendered >= amount),
  CONSTRAINT payment_change_chk   CHECK (
    change_given IS NULL OR change_given = tendered - amount
  ),
  CONSTRAINT payment_photo_shape_chk CHECK (
    (photo_path IS NULL) = (photo_sha256 IS NULL)
  )
);

-- Duplicate-reference warning lookup at checkout.
CREATE INDEX payment_reference_idx
  ON payment (branch_id, reference_no) WHERE reference_no IS NOT NULL;
-- Payment-method mix tile.
CREATE INDEX payment_business_day_idx ON payment (branch_id, business_date, method);

COMMENT ON CONSTRAINT payment_one_per_bill_key ON payment IS
  'Owner ruling: one payment method per bill. Note the interaction with merged bills -- a large combined bill must be settled by a single method.';
COMMENT ON COLUMN payment.idempotency_key IS 'Client-generated per checkout attempt. Prevents a retried or double-clicked submit from taking payment twice.';


-- Immutable rendered receipt. Stored rather than regenerated so what the
-- customer was shown can always be reproduced exactly, even if the
-- rendering code or the catalog changes.
CREATE TABLE receipt (
  id          uuid PRIMARY KEY DEFAULT uuidv7(),
  branch_id   uuid        NOT NULL REFERENCES branch(id),
  bill_id     uuid        NOT NULL,
  receipt_no  bigint      NOT NULL,
  issued_at   timestamptz NOT NULL DEFAULT now(),
  payload     jsonb       NOT NULL,

  CONSTRAINT receipt_bill_key    UNIQUE (bill_id),
  CONSTRAINT receipt_no_key      UNIQUE (branch_id, receipt_no),
  CONSTRAINT receipt_bill_fk
    FOREIGN KEY (branch_id, bill_id) REFERENCES bill (branch_id, id)
);

CREATE TRIGGER receipt_append_only
  BEFORE UPDATE OR DELETE ON receipt
  FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

COMMENT ON TABLE receipt IS 'Digital receipt snapshot. Not a BIR official receipt -- the hall issues those separately.';


-- =====================================================================
-- INVENTORY
-- =====================================================================

CREATE TABLE stock_delivery (
  id            uuid PRIMARY KEY DEFAULT uuidv7(),
  branch_id     uuid        NOT NULL REFERENCES branch(id),
  supplier_name text,
  reference     text,
  received_by   uuid        NOT NULL REFERENCES app_user(id),
  received_at   timestamptz NOT NULL DEFAULT now(),
  note          text,

  CONSTRAINT stock_delivery_branch_id_key UNIQUE (branch_id, id)
);

CREATE INDEX stock_delivery_recent_idx ON stock_delivery (branch_id, received_at DESC);

COMMENT ON TABLE stock_delivery IS 'Header for one received shipment. The individual quantities are stock_movement rows referencing it.';


-- The inventory ledger. Append-only and immutable: current stock is the
-- sum of this table, and every change is traceable to its cause, its
-- actor and its moment. This is what replaces the brief's mutable
-- quantity column and its ytd_quantity counter -- quantity sold in ANY
-- period is a filtered SUM here, not one hardcoded window.
CREATE TABLE stock_movement (
  id             uuid PRIMARY KEY DEFAULT uuidv7(),
  branch_id      uuid          NOT NULL REFERENCES branch(id),
  product_id     uuid          NOT NULL,
  reason         stock_reason  NOT NULL,

  -- Negative for outflow, positive for inflow. Never zero.
  quantity_delta numeric(12,3) NOT NULL,
  -- Running balance immediately after this movement, so the ledger can be
  -- audited without replaying it from the beginning.
  qty_after      numeric(12,3) NOT NULL,
  -- Deliveries only: what this stock cost, feeding the moving average.
  unit_cost      numeric(12,4),

  -- Cause. Exactly one of these is set, matching the reason.
  bill_line_id   uuid,
  delivery_id    uuid,

  note           text,
  actor_id       uuid          NOT NULL REFERENCES app_user(id),
  occurred_at    timestamptz   NOT NULL DEFAULT now(),
  business_date  date GENERATED ALWAYS AS (business_date_of(occurred_at)) STORED,

  CONSTRAINT stock_movement_product_fk
    FOREIGN KEY (branch_id, product_id) REFERENCES product (branch_id, id),
  CONSTRAINT stock_movement_bill_line_fk
    FOREIGN KEY (branch_id, bill_line_id) REFERENCES bill_line (branch_id, id),
  CONSTRAINT stock_movement_delivery_fk
    FOREIGN KEY (branch_id, delivery_id) REFERENCES stock_delivery (branch_id, id),

  CONSTRAINT stock_movement_nonzero_chk CHECK (quantity_delta <> 0),
  -- Each reason must carry the evidence appropriate to it.
  CONSTRAINT stock_movement_cause_chk CHECK (
    (reason IN ('SALE', 'SALE_VOID') AND bill_line_id IS NOT NULL AND delivery_id IS NULL)
    OR (reason = 'DELIVERY' AND delivery_id IS NOT NULL AND bill_line_id IS NULL
                            AND unit_cost IS NOT NULL AND quantity_delta > 0)
    -- A correction or a comp with no explanation is not traceable.
    OR (reason IN ('CORRECTION', 'STAFF_COMP') AND bill_line_id IS NULL
                            AND delivery_id IS NULL AND note IS NOT NULL)
  ),
  CONSTRAINT stock_movement_unit_cost_chk CHECK (unit_cost IS NULL OR unit_cost >= 0)
);

CREATE TRIGGER stock_movement_append_only
  BEFORE UPDATE OR DELETE ON stock_movement
  FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

-- Per-product movement history, newest first -- the "why is this count
-- wrong" screen.
CREATE INDEX stock_movement_product_idx ON stock_movement (product_id, occurred_at DESC);
-- Cost-of-goods for a business day.
CREATE INDEX stock_movement_business_day_idx ON stock_movement (branch_id, business_date, reason);
-- Tracing a sale line back to the stock it moved.
CREATE INDEX stock_movement_bill_line_idx
  ON stock_movement (bill_line_id) WHERE bill_line_id IS NOT NULL;

COMMENT ON TABLE stock_movement IS 'Append-only inventory ledger. Authoritative over product.qty_on_hand. UPDATE and DELETE are blocked by trigger.';
COMMENT ON COLUMN stock_movement.qty_after IS 'Balance snapshot after this movement. Lets an auditor verify any single row without replaying the whole ledger.';


-- =====================================================================
-- END-OF-DAY CASH RECONCILIATION
-- =====================================================================

-- The one control that survives from the hall's current paper process:
-- count the drawer, compare it to what the system says should be there.
-- Not full shift management -- one row per branch per business day.
CREATE TABLE cash_count (
  id             uuid PRIMARY KEY DEFAULT uuidv7(),
  branch_id      uuid          NOT NULL REFERENCES branch(id),
  business_date  date          NOT NULL,
  -- SUM(payment.amount) WHERE method = 'CASH' for this business day,
  -- frozen at the moment of counting.
  expected_cash  numeric(12,2) NOT NULL,
  counted_cash   numeric(12,2) NOT NULL,
  variance       numeric(12,2)
                 GENERATED ALWAYS AS (counted_cash - expected_cash) STORED,
  counted_by     uuid          NOT NULL REFERENCES app_user(id),
  counted_at     timestamptz   NOT NULL DEFAULT now(),
  note           text,

  CONSTRAINT cash_count_day_key UNIQUE (branch_id, business_date),
  CONSTRAINT cash_count_nonneg_chk CHECK (expected_cash >= 0 AND counted_cash >= 0)
);

COMMENT ON TABLE cash_count IS 'End-of-day drawer reconciliation. A negative variance is a shortfall; the dashboard surfaces it.';


COMMIT;


-- =====================================================================
-- SEED — minimum data for a working branch
-- =====================================================================

BEGIN;

INSERT INTO branch (id, code, name, address)
VALUES ('01900000-0000-7000-8000-000000000001',
        'MAIN',
        'Supreme Billiard Hall',
        '2nd Floor MCC Building, Russia cor. Rhodesia St., Better Living Subd., Paranaque City');

-- rate_floor_per_minute is deliberately absent: the owner ruled that an
-- employee may set any friend rate without approval. Every override is still
-- recorded on table_session with the actor and the standard rate, and surfaced
-- on the admin dashboard. Detection, not prevention -- see the open-risks list.
INSERT INTO branch_setting (branch_id, key, value) VALUES
  ('01900000-0000-7000-8000-000000000001', 'low_stock_threshold',    '10'::jsonb),
  ('01900000-0000-7000-8000-000000000001', 'allow_negative_stock',   'true'::jsonb),
  ('01900000-0000-7000-8000-000000000001', 'payment_photo_retention','"forever"'::jsonb),
  ('01900000-0000-7000-8000-000000000001', 'payment_photo_visibility','"admin_only"'::jsonb);

-- Password hashes are placeholders; the application sets real bcrypt
-- hashes on first run.
INSERT INTO app_user (id, branch_id, username, password_hash, full_name, role) VALUES
  ('01900000-0000-7000-8000-0000000000a1', NULL,
   'owner', '$2a$10$REPLACE_ON_FIRST_RUN', 'Owner', 'ADMIN'),
  ('01900000-0000-7000-8000-0000000000a2', '01900000-0000-7000-8000-000000000001',
   'counter', '$2a$10$REPLACE_ON_FIRST_RUN', 'Front Counter', 'EMPLOYEE');

INSERT INTO customer_type (branch_id, name, allows_rate_override, is_default, sort_order) VALUES
  ('01900000-0000-7000-8000-000000000001', 'Regular',          false, true,  1),
  ('01900000-0000-7000-8000-000000000001', 'Friend of Owner',  true,  false, 2);

-- Seven tables: four standard at PHP 4.00/min, three premium at PHP 5.00/min.
-- Admins can add, rename, re-rate and archive tables from the UI; this is only
-- the starting set so the hall is usable on first launch.
INSERT INTO pool_table (id, branch_id, name, table_number) VALUES
  ('019a0000-0000-7000-8000-000000000001','01900000-0000-7000-8000-000000000001','Table 1',1),
  ('019a0000-0000-7000-8000-000000000002','01900000-0000-7000-8000-000000000001','Table 2',2),
  ('019a0000-0000-7000-8000-000000000003','01900000-0000-7000-8000-000000000001','Table 3',3),
  ('019a0000-0000-7000-8000-000000000004','01900000-0000-7000-8000-000000000001','Table 4',4),
  ('019a0000-0000-7000-8000-000000000005','01900000-0000-7000-8000-000000000001','Table 5 (Premium)',5),
  ('019a0000-0000-7000-8000-000000000006','01900000-0000-7000-8000-000000000001','Table 6 (Premium)',6),
  ('019a0000-0000-7000-8000-000000000007','01900000-0000-7000-8000-000000000001','Table 7 (Premium)',7);

INSERT INTO pool_table_rate (branch_id, pool_table_id, rate_per_minute, effective_from) VALUES
  ('01900000-0000-7000-8000-000000000001','019a0000-0000-7000-8000-000000000001',4.0000,'2026-01-01'),
  ('01900000-0000-7000-8000-000000000001','019a0000-0000-7000-8000-000000000002',4.0000,'2026-01-01'),
  ('01900000-0000-7000-8000-000000000001','019a0000-0000-7000-8000-000000000003',4.0000,'2026-01-01'),
  ('01900000-0000-7000-8000-000000000001','019a0000-0000-7000-8000-000000000004',4.0000,'2026-01-01'),
  ('01900000-0000-7000-8000-000000000001','019a0000-0000-7000-8000-000000000005',5.0000,'2026-01-01'),
  ('01900000-0000-7000-8000-000000000001','019a0000-0000-7000-8000-000000000006',5.0000,'2026-01-01'),
  ('01900000-0000-7000-8000-000000000001','019a0000-0000-7000-8000-000000000007',5.0000,'2026-01-01');

INSERT INTO product_category (branch_id, name, sort_order) VALUES
  ('01900000-0000-7000-8000-000000000001', 'Beer',       1),
  ('01900000-0000-7000-8000-000000000001', 'Softdrinks', 2),
  ('01900000-0000-7000-8000-000000000001', 'Food',       3),
  ('01900000-0000-7000-8000-000000000001', 'Cigarettes', 4);

COMMIT;
